package com.meshcentral.agent

import android.graphics.Bitmap
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class DesktopFrameEncoder {
    private var tilesWide: Int = 0
    private var tilesHigh: Int = 0
    private var frameWidth: Int = 0
    private var frameHeight: Int = 0
    private var tilesCount: Int = 0
    private var oldcrcs: IntArray? = null
    private var newcrcs: IntArray? = null
    // Written from the tunnel/main thread, read on the capture thread.
    @Volatile private var forceFullFrame = true

    fun requestFullFrame() {
        forceFullFrame = true
    }

    fun encode(bitmap: Bitmap, sink: (ByteString) -> Unit): Boolean {
        if (frameWidth != bitmap.width || frameHeight != bitmap.height || oldcrcs == null || newcrcs == null) {
            frameWidth = bitmap.width
            frameHeight = bitmap.height
            tilesWide = (bitmap.width + 63) / 64
            tilesHigh = (bitmap.height + 63) / 64
            tilesCount = tilesWide * tilesHigh
            oldcrcs = IntArray(tilesCount)
            newcrcs = IntArray(tilesCount)
            forceFullFrame = true
        }

        computeAllCRCs(bitmap)
        var changedTiles = 0
        for (i in 0 until tilesCount) {
            if (forceFullFrame || oldcrcs!![i] != newcrcs!![i]) changedTiles++
        }
        if (changedTiles == 0) return false

        if (forceFullFrame || ((changedTiles * 100) >= (tilesCount * 85))) {
            sink(buildImageCommand(bitmap, 0, 0, bitmap.width, bitmap.height))
            for (i in 0 until tilesCount) oldcrcs!![i] = newcrcs!![i]
            forceFullFrame = false
            return true
        }

        var sendx = -1
        var sendy = 0
        var sendw = 0
        for (i in 0 until tilesHigh) {
            for (j in 0 until tilesWide) {
                val tileNumber = (i * tilesWide) + j
                if (oldcrcs!![tileNumber] != newcrcs!![tileNumber]) {
                    oldcrcs!![tileNumber] = newcrcs!![tileNumber]
                    if (sendx == -1) {
                        sendx = j
                        sendy = i
                        sendw = 1
                    } else {
                        sendw += 1
                    }
                } else if (sendx != -1) {
                    sendSubBitmapRow(bitmap, sendx, sendy, sendw, sink)
                    sendx = -1
                }
            }
            if (sendx != -1) {
                sendSubBitmapRow(bitmap, sendx, sendy, sendw, sink)
                sendx = -1
            }
        }
        if (sendx != -1) {
            sendSubBitmapRow(bitmap, sendx, sendy, sendw, sink)
        }
        forceFullFrame = false
        return true
    }

    private fun sendSubBitmapRow(bitmap: Bitmap, x: Int, y: Int, w: Int, sink: (ByteString) -> Unit) {
        var h = y + 1
        var exit = false
        while (h < tilesHigh) {
            for (xx in x until (x + w)) {
                val tileNumber = (h * tilesWide) + xx
                if (oldcrcs!![tileNumber] == newcrcs!![tileNumber]) {
                    exit = true
                    break
                }
            }
            if (!exit) {
                for (xx in x until (x + w)) {
                    val tileNumber = (h * tilesWide) + xx
                    oldcrcs!![tileNumber] = newcrcs!![tileNumber]
                }
            } else {
                break
            }
            h++
        }
        h -= y
        sink(buildImageCommand(bitmap, x * 64, y * 64, w * 64, h * 64))
    }

    private fun computeAllCRCs(bitmap: Bitmap) {
        for (i in 0 until tilesCount) newcrcs!![i] = 1
        for (y in 0 until tilesHigh) {
            var h = 64
            if (((y * 64) + 64) > bitmap.height) h = bitmap.height - (y * 64)
            for (x in 0 until tilesWide) {
                var w = 64
                if (((x * 64) + 64) > bitmap.width) w = bitmap.width - (x * 64)
                val t = (y * tilesWide) + x
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, x * 64, y * 64, w, h)
                for (pixel in pixels) newcrcs!![t] = adler32(pixel, newcrcs!![t])
            }
        }
    }

    private fun buildImageCommand(bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int): ByteString {
        var ww = w
        var hh = h
        if (x + w > bitmap.width) ww = bitmap.width - x
        if (y + h > bitmap.height) hh = bitmap.height - y
        val croppedBitmap = if (x == 0 && y == 0 && ww == bitmap.width && hh == bitmap.height) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, x, y, ww, hh)
        }

        val bytesOut = ByteArrayOutputStream()
        val dos = DataOutputStream(bytesOut)
        dos.writeShort(27)
        dos.writeShort(8)
        dos.writeInt(0)
        dos.writeShort(3)
        dos.writeShort(0)
        dos.writeShort(x)
        dos.writeShort(y)
        when (g_desktop_imageType) {
            4 -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    croppedBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, g_desktop_compressionLevel, dos)
                } else {
                    @Suppress("DEPRECATION")
                    croppedBitmap.compress(Bitmap.CompressFormat.WEBP, g_desktop_compressionLevel, dos)
                }
            }
            2 -> croppedBitmap.compress(Bitmap.CompressFormat.PNG, g_desktop_compressionLevel, dos)
            else -> croppedBitmap.compress(Bitmap.CompressFormat.JPEG, g_desktop_compressionLevel, dos)
        }
        if (croppedBitmap !== bitmap) croppedBitmap.recycle()

        val data = bytesOut.toByteArray()
        val cmdSize = data.size - 8
        data[4] = (cmdSize shr 24).toByte()
        data[5] = (cmdSize shr 16).toByte()
        data[6] = (cmdSize shr 8).toByte()
        data[7] = cmdSize.toByte()
        return data.toByteString()
    }

    private fun adler32(n: Int, state: Int): Int {
        var a = state shr 16
        var b = state and 0xFFFF
        a = (a + n) % 65521
        b = (b + a) % 65521
        return (b shl 16) + a
    }
}
