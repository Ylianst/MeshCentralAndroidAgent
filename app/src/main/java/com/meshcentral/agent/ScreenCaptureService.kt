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


class ScreenCaptureService : Service() {
    private var mMediaProjection: MediaProjection? = null
    private var mImageReader: ImageReader? = null
    private var mHandler: Handler? = null
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

    private inner class ImageAvailableListener : OnImageAvailableListener {

        override fun onImageAvailable(reader: ImageReader) {
            if ((meshAgent == null) && (g_mainActivity != null)) {
                g_mainActivity!!.stopProjection()
                return
            }

            var bitmap: Bitmap? = null
            var image: android.media.Image? = null
            if (mImageReader == null) return

            try {
                image = mImageReader!!.acquireLatestImage()
                // Skip this image if null or websocket push-back is high
                if ((image != null) && (checkDesktopTunnelPushback() < 65535)) {
                    val planes: Array<Plane> = image.getPlanes()
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * mWidth

                    // Create the bitmap
                    bitmap = Bitmap.createBitmap(mWidth + rowPadding / pixelStride, mHeight, Bitmap.Config.ARGB_8888)
                    bitmap!!.copyPixelsFromBuffer(buffer)

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
                        oldcrcs = IntArray(tilesCount); // 64 x 64 tiles
                        newcrcs = IntArray(tilesCount); // 64 x 64 tiles
                        //println("New tile count: $tilesCount")
                    }

                    // Compute all tile CRC's
                    computeAllCRCs(bitmap);

                    // Compute how many tiles have changed
                    var changedTiles : Int = 0;
                    for (i in 0 until tilesCount) { if (oldcrcs!![i] != newcrcs!![i]) { changedTiles++; } }
                    if (changedTiles > 0) {
                        // If 85% of the all tiles have changed, send the entire screen
                        if ((changedTiles * 100) >= (tilesCount * 85))
                        {
                            sendEntireImage(bitmap)
                            for (i in 0 until tilesCount) { oldcrcs!![i] = newcrcs!![i]; }
                        }
                        else
                        {
                            // Send all changed tiles
                            // This version has horizontal & vertical optimization, JPEG as wide as possible then as high as possible
                            var sendx : Int = -1;
                            var sendy : Int = 0;
                            var sendw : Int = 0;
                            for (i in 0 until tilesHigh)
                            {
                                for (j in 0 until tilesWide)
                                {
                                    var tileNumber : Int = (i * tilesWide) + j;
                                    if (oldcrcs!![tileNumber] != newcrcs!![tileNumber])
                                    {
                                        oldcrcs!![tileNumber] = newcrcs!![tileNumber];
                                        if (sendx == -1) { sendx = j; sendy = i; sendw = 1; } else { sendw += 1; }
                                    }
                                    else
                                    {
                                        if (sendx != -1) { sendSubBitmapRow(bitmap, sendx, sendy, sendw); sendx = -1; }
                                    }
                                }
                                if (sendx != -1) { sendSubBitmapRow(bitmap, sendx, sendy, sendw); sendx = -1; }
                            }
                            if (sendx != -1) { sendSubBitmapRow(bitmap, sendx, sendy, sendw); sendx = -1; }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (bitmap != null) { bitmap.recycle() }
            if (image != null) { image.close() }
        }
    }

    private fun sendSubBitmapRow(bm: Bitmap, x : Int, y : Int, w : Int) {
        var h : Int = (y + 1)
        var exit : Boolean = false
        while (h < tilesHigh) {
            // Check if the row is all different
            for (xx in x until (x + w)) {
                val tileNumber = (h * tilesWide) + xx;
                if (oldcrcs!![tileNumber] == newcrcs!![tileNumber]) { exit = true; break; }
            }
            // If all different set the CRC's to the same, otherwise exit.
            if (!exit) {
                for (xx in x until (x + w)) {
                    var tileNumber : Int = (h * tilesWide) + xx;
                    oldcrcs!![tileNumber] = newcrcs!![tileNumber];
                }
            } else break;
            h++
        }
        h -= y
        sendSubImage(bm, x * 64, y * 64, w * 64, h * 64);
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
                cropedBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, g_desktop_compressionLevel, dos)
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
                bm.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, g_desktop_compressionLevel, dos)
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
            }
        }
    }

    private fun sendAgentConsole(r: String) {
        if (meshAgent != null) {
            meshAgent!!.sendConsoleResponse(r, sessionid = null)
        }
    }

    private fun stopProjection() {
        if (mHandler != null) {
            mHandler!!.post {
                if (mMediaProjection != null) {
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
        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2)
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
        private get() = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
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
        if ((desktopTunnelCloud == 0) && (g_mainActivity != null)) {
            // If there are no more desktop tunnels, stop projection
            g_mainActivity!!.stopProjection()
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
}