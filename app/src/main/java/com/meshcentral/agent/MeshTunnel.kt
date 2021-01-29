package com.meshcentral.agent

import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.CountDownTimer
import android.provider.MediaStore
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

class MeshTunnel(parent: MeshAgent, url: String, serverData: JSONObject) : WebSocketListener() {
    private var parent : MeshAgent = parent
    private var url:String = url
    private var serverData: JSONObject = serverData
    private var _webSocket: WebSocket? = null
    private var serverTlsCertHash: ByteArray? = null
    private var connectionTimer: CountDownTimer? = null
    private var state: Int = 0
    private var usage: Int = 0
    private var tunnelOptions : JSONObject? = null
    private var fileInputStream : InputStream? = null

    init {
        //println("MeshTunnel Init: ${serverData.toString()}")
        var serverTlsCertHashHex = serverData.optString("servertlshash")
        if (serverTlsCertHashHex != null) { serverTlsCertHash = parent.hexToByteArray(
            serverTlsCertHashHex
        ) }
        //var tunnelUsage = serverData.getInt("usage")
        //var tunnelUser = serverData.getString("username")

        //println("Starting tunnel: $url")
        //println("Tunnel usage: $tunnelUsage")
        //println("Tunnel user: $tunnelUser")
        startSocket()
    }

    fun Stop() {
        //println("MeshTunnel Stop")
        stopSocket()
    }

    private fun getUnsafeOkHttpClient(): OkHttpClient {
        // Create a trust manager that does not validate certificate chains
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?
            ) {
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                var hash =
                    MessageDigest.getInstance("SHA-384").digest(chain?.get(0)?.encoded).toHex()
                if ((serverTlsCertHash != null) && (hash.equals(serverTlsCertHash?.toHex()))) return
                if (hash.equals(parent.serverTlsCertHash?.toHex())) return
                println("Got Bad Tunnel TlsHash: ${hash}")
                throw CertificateException()
            }

            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        })

        // Install the special trust manager that records the certificate hash of the server
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        val sslSocketFactory = sslContext.socketFactory

        return OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.MINUTES)
                .writeTimeout(60, TimeUnit.MINUTES)
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                .build()
    }


    fun startSocket() {
        _webSocket = getUnsafeOkHttpClient().newWebSocket(
            Request.Builder().url(url).build(),
            this
        )
    }

    fun stopSocket() {
        // Disconnect and clean the relay socket
        if (_webSocket != null) {
            try {
                _webSocket?.close(NORMAL_CLOSURE_STATUS, null)
                _webSocket = null
            } catch (ex: Exception) { }
        }
        // Clear the connection timer
        if (connectionTimer != null) {
            connectionTimer?.cancel()
            connectionTimer = null
        }
        // Remove the tunnel from the parent's list
        parent.removeTunnel(this) // Notify the parent that this tunnel is done
    }

    companion object {
        const val NORMAL_CLOSURE_STATUS = 1000
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        //println("Tunnel-onOpen")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        //println("Tunnnel-onMessage: $text")
        if (state == 0) {
            if ((text == "c") || (text == "cr")) { state = 1; }
            return
        }
        else if (state == 1) {
            // {"type":"options","file":"Images/1104105516.JPG"}
            if (text.startsWith('{')) {
                var json = JSONObject(text)
                var type = json.optString("type")
                if (type == "options") { tunnelOptions = json }
            } else {
                var xusage = text.toInt()
                if (((xusage < 1) || (xusage > 5)) && (xusage != 10)) {
                    println("Invalid usage $text"); stopSocket(); return
                }
                var serverExpectedUsage = serverData.optInt("usage")
                if ((serverExpectedUsage != null) && (serverExpectedUsage != xusage) && (serverExpectedUsage == null)) {
                    println("Unexpected usage $text != $serverExpectedUsage");
                    stopSocket(); return
                }
                usage = xusage;
                state = 2

                // Start the connection time except if this is a file transfer
                if (usage != 10) {
                    //println("Connected usage $usage")
                    startConnectionTimer()
                } else {
                    if (tunnelOptions == null) {
                        println("No file transfer options");
                        stopSocket();
                    } else {
                        var filename = tunnelOptions?.optString("file")
                        if (filename == null) {
                            println("No file transfer name");
                            stopSocket();
                        } else {
                            //println("File transfer usage")
                            startFileTransfer(filename)
                        }
                    }
                }
            }
        }
    }

    override fun onMessage(webSocket: WebSocket, msg: ByteString) {
        //println("Tunnel-onBinaryMessage: ${msg.size}, ${msg.toByteArray().toHex()}")
        if ((state != 2) || (msg.size < 2)) return;
        try {
            if (msg[0].toInt() == 123) {
                // If we are authenticated, process JSON data
                processTunnelData(String(msg.toByteArray(), Charsets.UTF_8))
                return
            }
        }
        catch (e: Exception) {
            println("Tunnel-Exception: ${e.toString()}")
        }
    }

    // Cause some data to be sent over the websocket control channel every 2 minutes to keep it open
    private fun startConnectionTimer() {
        parent.parent.runOnUiThread {
            connectionTimer = object: CountDownTimer(120000000, 120000) {
                override fun onTick(millisUntilFinished: Long) {
                    if (_webSocket != null) {
                        _webSocket?.send(ByteArray(1).toByteString()) // If not, sent a single zero byte
                    }
                }
                override fun onFinish() { startConnectionTimer() }
            }
            connectionTimer?.start()
        }
    }

    private fun processTunnelData(jsonStr: String) {
        //println("JSON: $jsonStr")
        val json = JSONObject(jsonStr)
        var action = json.getString("action")
        //println("action: $action")
        when (action) {
            "ls" -> {
                val path = json.getString("path")
                if (path == "") {
                    var r : JSONArray = JSONArray()
                    r.put(JSONObject("{n:\"Images\",t:2}"))
                    r.put(JSONObject("{n:\"Audio\",t:2}"))
                    r.put(JSONObject("{n:\"Videos\",t:2}"))
                    //r.put(JSONObject("{n:\"Documents\",t:2}"))
                    json.put("dir", r)
                } else {
                    json.put("dir", getFolder(path))
                }
                //println(json.toString())
                if (_webSocket != null) {_webSocket?.send(json.toString().toByteArray(Charsets.UTF_8).toByteString()) }
            }
            else -> {
                // Unknown command, ignore it.
                println("Unhandled action: $action, $jsonStr")
            }
        }
    }

    // https://developer.android.com/training/data-storage/shared/media
    fun getFolder(dir:String) : JSONArray {
        val r : JSONArray = JSONArray()
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE
        )
        var uri : Uri? = null;
        if (dir.equals("Images")) { uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI }
        if (dir.equals("Audio")) { uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI }
        if (dir.equals("Videos")) { uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI }
        //if (dir == "Documents") { uri = MediaStore.Files. }
        if (uri == null) { return r }

        val cursor: Cursor? = parent.parent.getContentResolver().query(
            uri,
            projection,
            null,
            null,
            null
        )
        if (cursor != null) {
            val titleColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateModified: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val sizeColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            //val typeColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (cursor.moveToNext()) {
                var f : JSONObject = JSONObject()
                f.put("n", cursor.getString(titleColumn))
                f.put("t", 3)
                f.put("s", cursor.getInt(sizeColumn))
                f.put("d", cursor.getInt(dateModified))
                r.put(f)
                //println("${cursor.getString(titleColumn)}, ${cursor.getString(typeColumn)}")
            }
        }
        return r;
    }

    fun startFileTransfer(filename : String) {
        var filenameSplit = filename.split('/')
        if (filenameSplit.count() != 2) { stopSocket(); return }
        //println("startFileTransfer: $filenameSplit")

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE
        )
        var uri : Uri? = null;
        if (filenameSplit[0].equals("Images")) { uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI }
        if (filenameSplit[0].equals("Audio")) { uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI }
        if (filenameSplit[0].equals("Videos")) { uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI }
        //if (filenameSplit[0] == "Documents") { uri = MediaStore.Files. }
        if (uri == null) { stopSocket(); return }

        val cursor: Cursor? = parent.parent.getContentResolver().query(
            uri,
            projection,
            null,
            null,
            null
        )
        if (cursor != null) {
            val idColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val titleColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                var name = cursor.getString(titleColumn)
                if (name == filenameSplit[1]) {
                    var contentUrl: Uri = ContentUris.withAppendedId(uri, cursor.getLong(idColumn))
                    var fileSize = cursor.getInt(sizeColumn)

                    // Event to the server
                    var eventArgs = JSONArray()
                    eventArgs.put(filename)
                    eventArgs.put(fileSize)
                    parent.logServerEventEx(106, eventArgs, "Download: ${filename}, Size: $fileSize", serverData);

                    // Serve the file
                    parent.parent.getContentResolver().openInputStream(contentUrl).use { stream ->
                        // Perform operation on stream
                        var buf = ByteArray(65535)
                        var len : Int
                        while (true) {
                            len = stream!!.read(buf, 0, 65535)
                            if (len <= 0) { stopSocket(); break; } // Stream is done
                            if (_webSocket == null) { stopSocket(); break; } // Web socket closed
                            _webSocket?.send(buf.toByteString(0, len))
                            if (_webSocket?.queueSize()!! > 655350) { Thread.sleep(100)}
                        }
                    }
                    return;
                }
            }
        }
        stopSocket()
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        //println("Tunnel-onClosing")
        stopSocket()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        println("Tunnel-onFailure ${t.toString()},  ${response.toString()}")
        stopSocket()
    }

    fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }
}