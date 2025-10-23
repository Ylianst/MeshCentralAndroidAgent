package com.meshcentral.agent

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image.Plane
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.core.util.Pair
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.*
import kotlin.concurrent.thread


class ScreenCaptureService : Service() {
    private var mMediaProjection: MediaProjection? = null
    var mImageReader: ImageReader? = null
    var mHandler: Handler? = null
    private var mDisplay: Display? = null
    private var mVirtualDisplay: VirtualDisplay? = null
    private var mDensity = 0
    private var mRotation = 0
    private var mOrientationChangeCallback: ScreenCaptureService.OrientationChangeCallback? = null
    var mWidth = 0
    var mHeight = 0

    // Tile data
    private var tilesWide : Int = 0
    private var tilesHigh : Int = 0
    private var tilesFullWide : Int = 0
    private var tilesFullHigh : Int = 0
    private var tilesRemainingWidth : Int = 0
    private var tilesRemainingHeight : Int = 0
    private var tilesCount : Int = 0
    private var oldcrcs : IntArray? = null
    private var newcrcs : IntArray? = null

    var forceFullFrame = false
    private var notificationBlinkTimer: CountDownTimer? = null
    @Volatile private var projectionActive = false

    fun isProjectionActive(): Boolean = projectionActive


    private inner class ImageAvailableListener : OnImageAvailableListener {

        override fun onImageAvailable(reader: ImageReader) {
            if ((meshAgent == null) && (g_mainActivity != null)) {
                g_mainActivity!!.stopProjection()
                return
            }

            if (mImageReader == null) return

            // Drain available image to keep capture flowing
            try {
                var image = mImageReader!!.acquireLatestImage()
                if (image != null) {
                    processImage(image)
                    image.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    private val activeThreads = java.util.concurrent.atomic.AtomicInteger(0)
    fun processImage(image: android.media.Image) {
        var bitmap: Bitmap? = null
        try {
            val hasTunnels = (meshAgent?.tunnels?.size ?: 0) > 0
            val shouldSend = hasTunnels && (checkDesktopTunnelPushback() < 65535)

            val planes: Array<Plane> = image.getPlanes()
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * mWidth

            // Create the bitmap
            bitmap = Bitmap.createBitmap(mWidth + rowPadding / pixelStride, mHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            // Resize the bitmap if needed
            if (g_desktop_scalingLevel != 1024) {
                val newWidth = (mWidth * g_desktop_scalingLevel) / 1024
                val newHeight = (mHeight * g_desktop_scalingLevel) / 1024
                bitmap = getResizedBitmap(bitmap, newWidth, newHeight)
            }

            // Setup or update the CRC buffer and tile information.
            val wt = (bitmap!!.width / 64)
            val ht = (bitmap.height / 64)
            if ((tilesFullWide != wt) || (tilesFullHigh != ht)) {
                tilesWide = wt;
                tilesHigh = ht;
                tilesFullWide = tilesWide
                tilesFullHigh = tilesHigh
                tilesRemainingWidth = (bitmap.width % 64);
                tilesRemainingHeight = (bitmap.height % 64);
                if (tilesRemainingWidth != 0) { tilesWide++; }
                if (tilesRemainingHeight != 0) { tilesHigh++; }
                tilesCount = (tilesWide * tilesHigh);
                oldcrcs = IntArray(tilesCount);
                newcrcs = IntArray(tilesCount);
            }

            // Compute all tile CRC's
            computeAllCRCs(bitmap);

            // Compute how many tiles have changed
            var changedTiles : Int = 0;
            for (i in 0 until tilesCount) { if (oldcrcs!![i] != newcrcs!![i]) { changedTiles++; } }

            if (changedTiles > 0 && shouldSend) {
                if (forceFullFrame || (changedTiles * 100) >= (tilesCount * 85))
                {
                    val bitmapCopy = bitmap.copy(bitmap.config, false)
                    if (bitmapCopy != null) {
                        activeThreads.incrementAndGet()
                        thread {
                            sendEntireImage(bitmapCopy)
                            bitmapCopy.recycle()
                            val remaining = activeThreads.decrementAndGet()
                        }
                    }
                    for (i in 0 until tilesCount) { oldcrcs!![i] = newcrcs!![i]; }
                    forceFullFrame = false
                }
                else
                {
                    val bitmapCopy = bitmap.copy(bitmap.config, false)

                    // Collect tile data to send
                    data class TileToSend(val x: Int, val y: Int, val w: Int)
                    val tilesToSend = mutableListOf<TileToSend>()

                    var sendx : Int = -1;
                    var sendy : Int = 0;
                    var sendw : Int = 0;
                    for (i in 0 until tilesHigh)
                    {
                        for (j in 0 until tilesWide)
                        {
                            val tileNumber : Int = (i * tilesWide) + j;
                            if (oldcrcs!![tileNumber] != newcrcs!![tileNumber])
                            {
                                oldcrcs!![tileNumber] = newcrcs!![tileNumber];
                                if (sendx == -1) { sendx = j; sendy = i; sendw = 1; } else { sendw += 1; }
                            }
                            else
                            {
                                if (sendx != -1) {
                                    tilesToSend.add(TileToSend(sendx, sendy, sendw))
                                    sendx = -1;
                                }
                            }
                        }
                        if (sendx != -1) {
                            tilesToSend.add(TileToSend(sendx, sendy, sendw))
                            sendx = -1;
                        }
                    }
                    if (sendx != -1) {
                        tilesToSend.add(TileToSend(sendx, sendy, sendw))
                    }
                    if (bitmapCopy != null) {
                        // Send tiles in background thread
                        thread {
                            for (tile in tilesToSend) {
                                sendSubBitmapRow(bitmapCopy, tile.x, tile.y, tile.w)
                            }
                            bitmapCopy.recycle()
                        }
                    }
                }
            }
        } finally {
            bitmap?.recycle()
        }
    }

    private fun sendSubBitmapRow(bm: Bitmap, x : Int, y : Int, w : Int) {
        // Just send the single row of tiles without accessing CRCs
        // The vertical optimization was causing race conditions
        sendSubImage(bm, x * 64, y * 64, w * 64, 64);
    }

    private fun Adler32(n : Int, state: Int) : Int {
        var a = state shr 16;
        var b = state and 0xFFFF;
        a = (a + n) % 65521
        b = (b + a) % 65521
        return (b shl 16) + a
    }

    // Compute all CRC's
    private fun computeAllCRCs(bm: Bitmap) {
        // Clear all CRC's
        for (i in 0 until tilesCount) { newcrcs!![i] = 1 }

        // Compute all of the CRC's
        for (y in 0 until tilesHigh) {
            var h : Int = 64;
            if (((y * 64) + 64) > bm.height) { h = (bm.height - (y * 64)) }
            for (x in 0 until tilesWide) {
                var w : Int = 64;
                if (((x * 64) + 64) > bm.width) { w = (bm.width - (x * 64)) }
                val t = (y * tilesWide) + x
                val pixels = IntArray(w * h)
                bm.getPixels(pixels, 0, w, x * 64, y * 64, w, h)
                for (i in 0 until pixels.size) { newcrcs!![t] = Adler32(pixels[i], newcrcs!![t]) }
            }
        }
    }

    // Send a sub bitmap
    private fun sendSubImage(bm: Bitmap, x: Int, y: Int, w: Int, h :Int) {
        var ww = w;
        var hh = h;
        if (x + w > bm.width) { ww = (bm.width - x) }
        if (y + h > bm.height) { hh = (bm.height - y) }
        // Extract the sub bitmap if needed
        val cropedBitmap: Bitmap = Bitmap.createBitmap(bm, x, y, ww, hh)
        // Write bitmap to a memory and build a jumbo command
        var bytesOut = ByteArrayOutputStream()
        var dos = DataOutputStream(bytesOut)
        dos.writeShort(27) // Jumbo command
        dos.writeShort(8) // Jumbo command size
        dos.writeInt(0) // Next command size (0 for now)
        dos.writeShort(3) // Image command
        dos.writeShort(0) // Image command size, 0 since jumbo is used
        dos.writeShort(x) // X
        dos.writeShort(y) // Y
        if (g_desktop_imageType == 4) { // WebP
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                cropedBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, g_desktop_compressionLevel, dos)
            } else {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    cropedBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, g_desktop_compressionLevel, dos)
                } else {
                    cropedBitmap.compress(Bitmap.CompressFormat.WEBP, g_desktop_compressionLevel, dos)
                }
            }
        } else if (g_desktop_imageType == 2) { // PNG
            cropedBitmap.compress(Bitmap.CompressFormat.PNG, g_desktop_compressionLevel, dos)
        } else { // JPEG (Default)
            cropedBitmap.compress(Bitmap.CompressFormat.JPEG, g_desktop_compressionLevel, dos)
        }
        cropedBitmap.recycle()
        var data = bytesOut.toByteArray()
        var cmdSize : Int = (data.size - 8)
        data[4] = (cmdSize shr 24).toByte()
        data[5] = (cmdSize shr 16).toByte()
        data[6] = (cmdSize shr 8).toByte()
        data[7] = (cmdSize).toByte()
        sendDesktopTunnelData(data.toByteString()) // Send the data to all remote desktop tunnels
    }

    private fun sendEntireImage(bm: Bitmap) {
        // Write bitmap to a memory and build a jumbo command
        var bytesOut = ByteArrayOutputStream()
        var dos = DataOutputStream(bytesOut)
        dos.writeShort(27) // Jumbo command
        dos.writeShort(8) // Jumbo command size
        dos.writeInt(0) // Next command size (0 for now)
        dos.writeShort(3) // Image command
        dos.writeShort(0) // Image command size, 0 since jumbo is used
        dos.writeShort(0) // X
        dos.writeShort(0) // Y
        if (g_desktop_imageType == 4) { // WebP
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                bm.compress(Bitmap.CompressFormat.WEBP_LOSSY, g_desktop_compressionLevel, dos)
            } else {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    bm.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, g_desktop_compressionLevel, dos)
                } else {
                    bm.compress(Bitmap.CompressFormat.WEBP, g_desktop_compressionLevel, dos)
                }
            }
        } else if (g_desktop_imageType == 2) { // PNG
            bm.compress(Bitmap.CompressFormat.PNG, g_desktop_compressionLevel, dos)
        } else { // JPEG (Default)
            bm.compress(Bitmap.CompressFormat.JPEG, g_desktop_compressionLevel, dos)
        }
        var data = bytesOut.toByteArray()
        var cmdSize : Int = (data.size - 8)
        data[4] = (cmdSize shr 24).toByte()
        data[5] = (cmdSize shr 16).toByte()
        data[6] = (cmdSize shr 8).toByte()
        data[7] = (cmdSize).toByte()
        sendDesktopTunnelData(data.toByteString()) // Send the data to all remote desktop tunnels
    }

    // Resize a bitmap
    private fun getResizedBitmap(bm: Bitmap, newWidth: Int, newHeight: Int): Bitmap? {
        val width = bm.width
        val height = bm.height
        val scaleWidth = newWidth.toFloat() / width
        val scaleHeight = newHeight.toFloat() / height
        val matrix = Matrix()
        matrix.postScale(scaleWidth, scaleHeight)
        val resizedBitmap = Bitmap.createBitmap(bm, 0, 0, width, height, matrix, false)
        bm.recycle()
        return resizedBitmap
    }

    private inner class OrientationChangeCallback internal constructor(context: Context?) : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (mDisplay == null) return;
            val rotation = mDisplay!!.rotation
            //println("rotation $rotation")
            if (rotation != mRotation) {
                mRotation = rotation

                var rotationTimer = object: CountDownTimer(200, 200) {
                    override fun onTick(millisUntilFinished: Long) {
                        // Nop
                    }
                    override fun onFinish() {
                        try {
                            // Clean up
                            if (mVirtualDisplay != null) mVirtualDisplay!!.release()
                            if (mImageReader != null) mImageReader!!.setOnImageAvailableListener(null, null)

                            // Re-create virtual display depending on device width / height
                            createVirtualDisplay()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                rotationTimer.start()
            }
        }
    }

    private inner class MediaProjectionStopCallback : MediaProjection.Callback() {
        override fun onStop() {
            //Log.e(ScreenCaptureService.Companion.TAG, "stopping projection.")
            if (mHandler != null) {
                mHandler!!.post {
                    if (mVirtualDisplay != null) mVirtualDisplay!!.release()
                    if (mImageReader != null) mImageReader!!.setOnImageAvailableListener(null, null)
                    if (mOrientationChangeCallback != null) mOrientationChangeCallback!!.disable()
                    mMediaProjection!!.unregisterCallback(this@MediaProjectionStopCallback)
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        // start capture handling thread
        object : Thread() {
            override fun run() {
                Looper.prepare()
                mHandler = Handler()
                Looper.loop()
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (ScreenCaptureService.Companion.isStartCommand(intent)) {
            // Create notification
            val notification: Pair<Int, Notification> = NotificationUtils.getNotification(this)
            startForeground(notification.first!!, notification.second)
            // Start projection
            val resultCode = intent.getIntExtra(ScreenCaptureService.Companion.RESULT_CODE, Activity.RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>(ScreenCaptureService.Companion.DATA)
            startProjection(resultCode, data)
        } else if (ScreenCaptureService.Companion.isStopCommand(intent)) {
            stopProjection()
            stopSelf()
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent?) {
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        if (mMediaProjection == null) {
            try {
                mMediaProjection = mpManager.getMediaProjection(resultCode, data!!)
            } catch (ex: Exception) {
                // Unable to get the projection manager.
                // TODO: Deal with this situation nicely.
            }
            if (mMediaProjection != null) {
                // Display metrics
                mDensity = Resources.getSystem().displayMetrics.densityDpi
                val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    val displayManager = applicationContext.getSystemService(DISPLAY_SERVICE) as DisplayManager
                    mDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                } else {
                    mDisplay = windowManager.defaultDisplay
                }

                // Create virtual display depending on device width / height
                createVirtualDisplay()

                // Register orientation change callback
                mOrientationChangeCallback = this.OrientationChangeCallback(this)
                if (mOrientationChangeCallback!!.canDetectOrientation()) {
                    mOrientationChangeCallback!!.enable()
                }

                g_ScreenCaptureService = this
                updateTunnelDisplaySize()
                sendAgentConsole("Started display sharing")
                projectionActive = true
            }
        }
    }

    private fun sendAgentConsole(r: String) {
        if (meshAgent != null) {
            meshAgent!!.sendConsoleResponse(r, sessionid = null)
        }
    }

    private fun stopProjection() {
        if (!projectionActive) return
        NotificationUtils.stopNotificationBlink()
        if (mHandler != null) {
            mHandler!!.post {
                if (mMediaProjection != null) {
                    try { mMediaProjection?.stop() } catch (_: Exception) {}
                    projectionActive = false
                    mMediaProjection!!.stop()
                    g_ScreenCaptureService = null
                    sendAgentConsole("Stopped display sharing")
                }
            }
        }
    }

    @SuppressLint("WrongConstant")
    private fun createVirtualDisplay() {
        // Get width and height
        mWidth = Resources.getSystem().displayMetrics.widthPixels
        mHeight = Resources.getSystem().displayMetrics.heightPixels

        sendAgentConsole("Screen: $mWidth x $mHeight")
        updateTunnelDisplaySize()

        // Start capture reader
        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 5)
        // Register media projection stop callback
        mMediaProjection!!.registerCallback(this.MediaProjectionStopCallback(), mHandler)
        mVirtualDisplay = mMediaProjection!!.createVirtualDisplay(ScreenCaptureService.Companion.SCREENCAP_NAME, mWidth, mHeight,
                mDensity, ScreenCaptureService.Companion.virtualDisplayFlags, mImageReader!!.surface, null, mHandler)

        mImageReader!!.setOnImageAvailableListener(this.ImageAvailableListener(), mHandler)
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val RESULT_CODE = "RESULT_CODE"
        private const val DATA = "DATA"
        private const val ACTION = "ACTION"
        private const val START = "START"
        private const val STOP = "STOP"
        private const val SCREENCAP_NAME = "screencap"
        fun getStartIntent(context: Context?, resultCode: Int, data: Intent?): Intent {
            val intent = Intent(context, ScreenCaptureService::class.java)
            intent.putExtra(ScreenCaptureService.Companion.ACTION, ScreenCaptureService.Companion.START)
            intent.putExtra(ScreenCaptureService.Companion.RESULT_CODE, resultCode)
            intent.putExtra(ScreenCaptureService.Companion.DATA, data)
            return intent
        }

        fun getStopIntent(context: Context?): Intent {
            val intent = Intent(context, ScreenCaptureService::class.java)
            intent.putExtra(ScreenCaptureService.Companion.ACTION, ScreenCaptureService.Companion.STOP)
            return intent
        }

        private fun isStartCommand(intent: Intent): Boolean {
            return (intent.hasExtra(ScreenCaptureService.Companion.RESULT_CODE) && intent.hasExtra(ScreenCaptureService.Companion.DATA)
                    && intent.hasExtra(ScreenCaptureService.Companion.ACTION) && intent.getStringExtra(ScreenCaptureService.Companion.ACTION) == ScreenCaptureService.Companion.START)
        }

        private fun isStopCommand(intent: Intent): Boolean {
            return intent.hasExtra(ScreenCaptureService.Companion.ACTION) && intent.getStringExtra(ScreenCaptureService.Companion.ACTION) == ScreenCaptureService.Companion.STOP
        }

        private val virtualDisplayFlags: Int
            private get() = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
    }

    fun updateTunnelDisplaySize() {
        if (meshAgent == null) return;
        for (t in meshAgent!!.tunnels) {
            if ((t.state == 2) && (t.usage == 2)) { // If this is a connected desktop tunnel...
                t.updateDesktopDisplaySize() // Send updated screen size
            }
        }
    }

    fun checkNoMoreDesktopTunnels() {
        if (meshAgent == null) return;
        var desktopTunnelCloud = 0
        for (t in meshAgent!!.tunnels) {
            // If this is a connected desktop tunnel, count it
            if ((t.state == 2) && (t.usage == 2)) { desktopTunnelCloud++ }
        }
        if (desktopTunnelCloud == 0) {
            // If there are no more desktop tunnels, stop projection
            if (!g_autoConsent) {
                g_mainActivity!!.stopProjection()
            } else { // reset the tilesFullWide and tilesFullHigh so on next connect it will send the whole image rather than changed tiles
                tilesFullWide = 0
                tilesFullHigh = 0
            }
        }
    }

    // Get the maximum outbound queue size of all remote desktop sockets
    fun checkDesktopTunnelPushback() : Long {
        if (meshAgent == null) return -1;
        var maxQueueSize : Long = 0
        for (t in meshAgent!!.tunnels) {
            // If this is a connected desktop tunnel, count it
            if ((t.state == 2) && (t.usage == 2) && (t._webSocket != null)) {
                var qs : Long? = t._webSocket?.queueSize()
                if ((qs != null) && (qs > maxQueueSize)) { maxQueueSize = qs }
            }
        }
        return maxQueueSize
    }

    // Send data to all remote desktop sockets
    fun sendDesktopTunnelData(data: ByteString) {
        if (meshAgent == null) return;
        for (t in meshAgent!!.tunnels) {
            // If this is a connected desktop tunnel, send the data
            if ((t.state == 2) && (t.usage == 2) && (t._webSocket != null)) {
                t._webSocket!!.send(data)
            }
        }
    }



    fun requestImmediateFrame() {
        forceFullFrame = true
        NotificationUtils.startNotificationBlink(this)

        mHandler?.post {
            try {
                // First, try to get any existing image
                val existingImage = mImageReader?.acquireLatestImage()
                if (existingImage != null) {
                    processImage(existingImage)
                    existingImage.close()
                    return@post
                }
                // No existing image - force VirtualDisplay to generate a new frame
                // Request a new frame by briefly pausing and resuming the virtual display
                // This forces Android to render a fresh frame
                mVirtualDisplay?.surface?.let { surface ->
                    // The virtual display should automatically generate frames
                    // Set a small delay to allow the system to render
                    mHandler?.postDelayed({
                        val newImage = mImageReader?.acquireLatestImage()
                        if (newImage != null) {
                            processImage(newImage)
                            newImage.close()
                        } else {
                            println("No frame available after delay")
                        }
                    }, 100) // Wait 100ms for frame to be rendered
                }
            } catch (e: Exception) {
                println("requestImmediateFrame - Failed to request frame: ${e.message}")
            }
        }
    }

    fun onTunnelDisconnected() {
        // Only reset state in autoconsent mode where service persists between connections
        if (!g_autoConsent) {
            println("Tunnel disconnected (normal mode), no state reset needed")
            return
        }

        println("Tunnel disconnected (autoconsent mode), resetting state for next connection")
        forceFullFrame = false

        // Reset tile state to force full frame on next connect
        tilesFullWide = 0
        tilesFullHigh = 0

        // Clear CRCs to ensure fresh comparison
        if (oldcrcs != null) {
            for (i in 0 until oldcrcs!!.size) {
                oldcrcs!![i] = 0
            }
        }
    }
}