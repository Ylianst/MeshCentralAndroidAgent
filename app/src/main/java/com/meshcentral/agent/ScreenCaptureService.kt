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
import android.os.HandlerThread
import android.os.IBinder
import android.view.Display
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.core.util.Pair
import okio.ByteString
import kotlin.math.max


class ScreenCaptureService : Service(), RemoteDesktopProvider {
    private var mMediaProjection: MediaProjection? = null
    private var mImageReader: ImageReader? = null
    private var mHandler: Handler? = null
    private var mHandlerThread: HandlerThread? = null
    private var mDisplay: Display? = null
    private var mVirtualDisplay: VirtualDisplay? = null
    private var mProjectionCallback: MediaProjection.Callback? = null
    private var mDensity = 0
    private var mRotation = 0
    private var mOrientationChangeCallback: ScreenCaptureService.OrientationChangeCallback? = null
    var mWidth = 0
    var mHeight = 0
    private var forceFullFrame = false
    private val encoder = DesktopFrameEncoder()
    private var lastFrameBitmap: Bitmap? = null

    override val isRunning: Boolean
        get() = mMediaProjection != null

    override val width: Int
        get() = mWidth

    override val height: Int
        get() = mHeight

    private inner class ImageAvailableListener : OnImageAvailableListener {

        override fun onImageAvailable(reader: ImageReader) {
            if (meshAgent == null) {
                AgentController.stopProjection()
                return
            }
            val imageReader = mImageReader ?: return
            var image: android.media.Image? = null
            try {
                image = imageReader.acquireLatestImage()
                if (image != null) processImage(image)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                image?.close()
            }
        }
    }

    private fun processImage(image: android.media.Image) {
        if ((checkDesktopTunnelPushback() >= 65535) || (meshAgent?.tunnels?.getOrNull(0) == null)) return

        val planes: Array<Plane> = image.getPlanes()
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * mWidth

        var bitmap = Bitmap.createBitmap(mWidth + rowPadding / pixelStride, mHeight, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)

        if (g_desktop_scalingLevel != 1024 && g_desktop_scalingLevel > 0) {
            val newWidth = max(1, (mWidth * g_desktop_scalingLevel) / 1024)
            val newHeight = max(1, (mHeight * g_desktop_scalingLevel) / 1024)
            bitmap = getResizedBitmap(bitmap, newWidth, newHeight) ?: bitmap
        }

        if (forceFullFrame) {
            encoder.requestFullFrame()
            forceFullFrame = false
        }
        encoder.encode(bitmap) { AgentController.sendDesktopTunnelData(it) }

        if (lastFrameBitmap !== bitmap) {
            lastFrameBitmap?.recycle()
            lastFrameBitmap = bitmap
        }
    }

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
            mHandler?.post {
                releaseDisplayResources()
                mOrientationChangeCallback?.disable()
                mOrientationChangeCallback = null
                mMediaProjection?.unregisterCallback(this@MediaProjectionStopCallback)
                mProjectionCallback = null
                mMediaProjection = null
                if (g_ScreenCaptureService === this@ScreenCaptureService) {
                    g_ScreenCaptureService = null
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        mHandlerThread = HandlerThread("ScreenCaptureService")
        mHandlerThread!!.start()
        mHandler = Handler(mHandlerThread!!.looper)
    }

    override fun onDestroy() {
        mProjectionCallback?.let { mMediaProjection?.unregisterCallback(it) }
        mProjectionCallback = null
        mOrientationChangeCallback?.disable()
        mOrientationChangeCallback = null
        releaseDisplayResources()
        val mediaProjection = mMediaProjection
        mMediaProjection = null
        mediaProjection?.stop()
        if (g_ScreenCaptureService === this) g_ScreenCaptureService = null
        mHandler?.removeCallbacksAndMessages(null)
        mHandler = null
        mHandlerThread?.quitSafely()
        mHandlerThread = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (ScreenCaptureService.Companion.isStartCommand(intent)) {
            // Create notification
            val notification: Pair<Int, Notification> = NotificationUtils.getNotification(this)
            startForeground(notification.first!!, notification.second)
            // Start projection
            val resultCode = intent.getIntExtra(ScreenCaptureService.Companion.RESULT_CODE, Activity.RESULT_CANCELED)
            val data = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(ScreenCaptureService.Companion.DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(ScreenCaptureService.Companion.DATA)
            }
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
                    @Suppress("DEPRECATION")
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
                g_remoteDesktopProvider = this
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
                    if (g_remoteDesktopProvider === this) {
                        g_remoteDesktopProvider = null
                    }
                    lastFrameBitmap?.recycle()
                    lastFrameBitmap = null
                    sendAgentConsole("Stopped display sharing")
                    // The globals are cleared asynchronously on this handler thread, so refresh the
                    // UI now that capture has actually stopped (hides the "Stop Screen Sharing" item).
                    AgentController.refreshInfo()
                }
            }
        }
    }

    private fun releaseDisplayResources() {
        mImageReader?.setOnImageAvailableListener(null, null)
        mImageReader?.close()
        mImageReader = null
        mVirtualDisplay?.release()
        mVirtualDisplay = null
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
        if (mProjectionCallback == null) {
            mProjectionCallback = this.MediaProjectionStopCallback()
            mMediaProjection!!.registerCallback(mProjectionCallback!!, mHandler)
        }
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
            get() = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
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
        AgentController.checkNoMoreDesktopTunnels()
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
        AgentController.sendDesktopTunnelData(data)
    }

    override fun requestFullFrame() {
        val handler = mHandler
        if (handler == null) {
            forceFullFrame = true
            return
        }
        handler.post {
            forceFullFrame = true
            encoder.requestFullFrame()
            pushCurrentFrame()
        }
    }

    // Push a full frame of the current screen immediately, instead of waiting for the next on-screen
    // change to trigger onImageAvailable. This is what makes the first image appear right away on a
    // (re)connect / refresh while the app is in the background on a static screen.
    private fun pushCurrentFrame() {
        val imageReader = mImageReader ?: return
        if (meshAgent?.tunnels?.getOrNull(0) == null) return

        val image = try { imageReader.acquireLatestImage() } catch (e: Exception) { null }
        if (image != null) {
            try { processImage(image) } finally { image.close() }
            return
        }

        val cached = lastFrameBitmap
        if (cached != null && !cached.isRecycled) {
            encoder.requestFullFrame()
            forceFullFrame = false
            encoder.encode(cached) { AgentController.sendDesktopTunnelData(it) }
            return
        }

        recreateVirtualDisplay()
    }

    private fun recreateVirtualDisplay() {
        try {
            mVirtualDisplay?.release()
            mVirtualDisplay = null
            mImageReader?.setOnImageAvailableListener(null, null)
            mImageReader = null
            if (mMediaProjection != null) createVirtualDisplay()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
