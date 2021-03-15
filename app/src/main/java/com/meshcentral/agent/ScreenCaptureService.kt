package com.meshcentral.agent

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
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

                    // Write bitmap to a memory and built a jumbo command
                    var bytesOut = ByteArrayOutputStream()
                    var dos = DataOutputStream(bytesOut)
                    dos.writeShort(27) // Jumbo command
                    dos.writeShort(8) // Jumbo command size
                    dos.writeInt(0) // Next command size (0 for now)
                    dos.writeShort(3) // Image command
                    dos.writeShort(0) // Image command size, 0 since jumbo is used
                    dos.writeShort(0) // X
                    dos.writeShort(0) // Y
                    bitmap!!.compress(Bitmap.CompressFormat.JPEG, g_desktop_compressionLevel, dos)
                    var data = bytesOut.toByteArray()
                    var cmdSize : Int = (data.size - 8)
                    data[4] = (cmdSize shr 24).toByte()
                    data[5] = (cmdSize shr 16).toByte()
                    data[6] = (cmdSize shr 8).toByte()
                    data[7] = (cmdSize).toByte()
                    sendDesktopTunnelData(data.toByteString()) // Send the data to all remote desktop tunnels
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (bitmap != null) {
                bitmap!!.recycle()
            }
            if (image != null) {
                image!!.close()
            }
        }
    }

    private inner class OrientationChangeCallback internal constructor(context: Context?) : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (mDisplay == null) return;
            val rotation = mDisplay!!.rotation
            println("rotation $rotation")
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
                rotationTimer?.start()
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

                // Register media projection stop callback
                mMediaProjection!!.registerCallback(this.MediaProjectionStopCallback(), mHandler)
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