package com.meshcentral.agent

import android.annotation.SuppressLint
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.CountDownTimer
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
//import org.webrtc.PeerConnectionFactory
import java.io.*
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.collections.ArrayList
import kotlin.math.absoluteValue
import kotlin.random.Random


class PendingActivityData(tunnel: MeshTunnel, id: Int, url: Uri, where: String, args: String, req: JSONObject) {
    var tunnel : MeshTunnel = tunnel
    var id : Int = id
    var url : Uri = url
    var where : String = where
    var args : String = args
    var req : JSONObject = req
}

@SuppressLint("CustomX509TrustManager")
class MeshTunnel(parent: MeshAgent, url: String, serverData: JSONObject) : WebSocketListener() {
    private var parent : MeshAgent = parent
    private var url:String = url
    private var serverData: JSONObject = serverData
    private var serverTlsCertHash: ByteArray? = null
    private var connectionTimer: CountDownTimer? = null
    var _webSocket: WebSocket? = null
    var state: Int = 0 // 0 = Disconnected, 1 = Connecting, 2 = Connected
    var usage: Int = 0 // 2 = Desktop, 5 = Files, 10 = File transfer
    private var tunnelOptions : JSONObject? = null
    private var lastDirRequest : JSONObject? = null
    private var fileUpload : OutputStream? = null
    private var fileUploadName : String? = null
    private var fileUploadReqId : Int = 0
    private var fileUploadSize : Int = 0
    var userid : String? = null
    var guestname : String? = null
    var sessionUserName : String? = null // UserID + GuestName in Base64 if this is a shared session.
    var sessionUserName2 : String? = null // UserID/GuestName

    init { }

    fun Start() {
        //println("MeshTunnel Init: ${serverData.toString()}")
        var serverTlsCertHashHex = serverData.optString("servertlshash")
        serverTlsCertHash = parent.hexToByteArray(serverTlsCertHashHex)
        //var tunnelUsage = serverData.getInt("usage")
        //var tunnelUser = serverData.getString("username")

        // Set the userid and request more data about this user
        guestname = serverData.optString("guestname")
        userid = serverData.optString("userid")
        if (userid != null) parent.sendUserImageRequest(userid!!)
        sessionUserName = userid
        sessionUserName2 = userid
        if ((userid != "") && (guestname != "")) {
            sessionUserName = userid + "/guest:" + Base64.encodeToString(guestname!!.toByteArray(), Base64.NO_WRAP)
            sessionUserName2 = "$userid/$guestname"
        }

        //println("Starting tunnel: $url")
        //println("Tunnel usage: $tunnelUsage")
        //println("Tunnel user: $tunnelUser")
        //println("Tunnel userid: $userid")
        //println("Tunnel sessionUserName: $sessionUserName")
        //println("Tunnel sessionUserName2: $sessionUserName2")
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
                val certificate = chain?.firstOrNull() ?: throw CertificateException("Server sent no TLS certificate")
                val hash = MessageDigest.getInstance("SHA-384").digest(certificate.encoded).toHex()
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
                .hostnameVerifier ( hostnameVerifier = HostnameVerifier{ _, _ -> true })
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

        // Check if there are no more remote desktop tunnels
        if ((usage == 2) && (g_ScreenCaptureService != null)) {
            g_ScreenCaptureService!!.checkNoMoreDesktopTunnels()
        }
    }

    fun sendCtrlResponse(values: JSONObject?) {
        val json = JSONObject()
        json.put("ctrlChannel", "102938")
        values?.let {
            for (key in it.keys()) {
                json.put(key, it.get(key))
            }
        }
        if (_webSocket != null) { _webSocket?.send(json.toString()) }
    }

    companion object {
        const val NORMAL_CLOSURE_STATUS = 1000
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        println("Tunnel-onOpen")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        println("Tunnel-onMessage: $text")
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
                val serverExpectedUsage = if (serverData.has("usage")) serverData.getInt("usage") else null
                if (!isTunnelUsageAllowed(serverExpectedUsage, xusage)) {
                    println("Unexpected usage $text != $serverExpectedUsage");
                    stopSocket(); return
                }
                usage = xusage; // 2 = Desktop, 5 = Files, 10 = File transfer
                state = 2

                // Start the connection time except if this is a file transfer
                if (usage != 10) {
                    //println("Connected usage $usage")
                    startConnectionTimer()
                    if (usage == 2) {
                        // If this is a remote desktop usage...
                        if (!g_autoConsent && g_ScreenCaptureService == null) {
                            // asking for consent
                            if (meshAgent?.tunnels?.getOrNull(0) != null) {
                                val json = JSONObject()
                                json.put("type", "console")
                                json.put("msg", "Waiting for user to grant access...")
                                json.put("msgid", 1)
                                meshAgent!!.tunnels[0].sendCtrlResponse(json)
                            }
                        }
                        if (g_ScreenCaptureService == null) {
                            // Request media projection
                            parent.parent.startProjection()
                        } else {
                            if (meshAgent?.tunnels?.getOrNull(0) != null) {
                                val json = JSONObject()
                                json.put("type", "console")
                                json.put("msg", null)
                                json.put("msgid", 0)
                                meshAgent!!.tunnels[0].sendCtrlResponse(json)
                            }
                            // Send the display size
                            updateDesktopDisplaySize()
                        }
                    }
                } else {
                    // This is a file transfer
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

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun onMessage(webSocket: WebSocket, msg: ByteString) {
        //println("Tunnel-onBinaryMessage: ${msg.size}, ${msg.toByteArray().toHex()}")
        if ((state != 2) || (msg.size < 2)) return;
        try {
            if (msg[0].toInt() == 123) {
                // If we are authenticated, process JSON data
                processTunnelData(String(msg.toByteArray(), Charsets.UTF_8))
            } else if (fileUpload != null) {
                // If this is file upload data, process it here
                if (msg[0].toInt() == 0) {
                    // If data starts with zero, skip the first byte. This is used to escape binary file data from JSON.
                    fileUploadSize += (msg.size - 1);
                    var buf = msg.toByteArray()
                    try {
                        fileUpload?.write(buf, 1, buf.size - 1)
                    } catch (ex : Exception) {
                        // Report a problem
                        uploadError()
                        return
                    }
                } else {
                    // If data does not start with zero, save as-is.
                    fileUploadSize += msg.size;
                    try {
                        fileUpload?.write(msg.toByteArray())
                    } catch (ex : Exception) {
                        // Report a problem
                        uploadError()
                        return
                    }
                }

                // Ask for more data
                val json = JSONObject()
                json.put("action", "uploadack")
                json.put("reqid", fileUploadReqId)
                if (_webSocket != null) { _webSocket?.send(json.toString().toByteArray().toByteString()) }
            } else {
                if (msg.size < 2) return
                var cmd : Int = (msg[0].toInt() shl 8) + msg[1].toInt()
                var cmdsize : Int = (msg[2].toInt() shl 8) + msg[3].toInt()
                if (cmdsize != msg.size) return
                //println("Cmd $cmd, Size: ${msg.size}, Hex: ${msg.toByteArray().toHex()}")
                if (usage == 2) processBinaryDesktopCmd(cmd, cmdsize, msg) // Remote desktop
            }
        }
        catch (e: Exception) {
            println("Tunnel-Exception: ${e.toString()}")
        }
    }

    private fun processBinaryDesktopCmd(cmd : Int, cmdsize: Int, msg: ByteString) {
        when (cmd) {
            1 -> { // Legacy key input
                // Nop
            }
            2 -> { // Mouse input
                // Nop
            }
            5 -> { // Remote Desktop Settings
                if (cmdsize < 6) return
                g_desktop_imageType = msg[4].toInt() // 1 = JPEG, 2 = PNG, 3 = TIFF, 4 = WebP. TIFF is not support on Android.
                g_desktop_compressionLevel = msg[5].toInt() // Value from 1 to 100
                if (cmdsize >= 8) { g_desktop_scalingLevel = (msg[6].toInt() shl 8).absoluteValue + msg[7].toInt().absoluteValue } // 1024 = 100%
                if (cmdsize >= 10) { g_desktop_frameRateLimiter = (msg[8].toInt() shl 8).absoluteValue + msg[9].toInt().absoluteValue }
                println("Desktop Settings, type=$g_desktop_imageType, comp=$g_desktop_compressionLevel, scale=$g_desktop_scalingLevel, rate=$g_desktop_frameRateLimiter")
                updateDesktopDisplaySize()
            }
            6 -> { // Refresh
                // Nop
                println("Desktop Refresh")
            }
            8 -> { // Pause
                // Nop
            }
            85 -> { // Unicode key input
                // Nop
            }
            87 -> { // Input Lock
                // Nop
            }
            else -> {
                println("Unknown desktop binary command: $cmd, Size: ${msg.size}, Hex: ${msg.toByteArray().toHex()}")
            }
        }
    }

    fun updateDesktopDisplaySize() {
        if ((g_ScreenCaptureService == null) || (_webSocket == null)) return
        //println("updateDesktopDisplaySize: ${g_ScreenCaptureService!!.mWidth} x ${g_ScreenCaptureService!!.mHeight}")

        // Get the display size
        var mWidth : Int = g_ScreenCaptureService!!.mWidth
        var mHeight : Int = g_ScreenCaptureService!!.mHeight

        // Scale the display if needed
        if (g_desktop_scalingLevel != 1024) {
            mWidth = (mWidth * g_desktop_scalingLevel) / 1024
            mHeight = (mHeight * g_desktop_scalingLevel) / 1024
        }

        // Send the display size command
        var bytesOut = ByteArrayOutputStream()
        DataOutputStream(bytesOut).use { dos ->
            with(dos) {
                writeShort(7) // Screen size command
                writeShort(8) // Screen size command size
                writeShort(mWidth) // Width
                writeShort(mHeight) // Height
            }
        }
        _webSocket!!.send(bytesOut.toByteArray().toByteString())
    }

    // Cause some data to be sent over the websocket control channel every 2 minutes to keep it open
    private fun startConnectionTimer() {
        parent.parent.runOnUiThread {
            connectionTimer = object: CountDownTimer(120000000, 120000) {
                override fun onTick(millisUntilFinished: Long) {
                    if (_webSocket != null) {
                        _webSocket?.send(ByteArray(1).toByteString()) // If not, send a single zero byte
                    }
                }
                override fun onFinish() { startConnectionTimer() }
            }
            connectionTimer?.start()
        }
    }

    private fun uploadError() {
        val json = JSONObject()
        json.put("action", "uploaderror")
        json.put("reqid", fileUploadReqId)
        if (_webSocket != null) { _webSocket?.send(json.toString().toByteArray().toByteString()) }
        try { fileUpload?.close() } catch (ex : Exception) {}
        fileUpload = null
        return
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
                    var r: JSONArray = JSONArray()
                    r.put(JSONObject("{n:\"Sdcard\",t:2}"))
                    r.put(JSONObject("{n:\"Images\",t:2}"))
                    r.put(JSONObject("{n:\"Audio\",t:2}"))
                    r.put(JSONObject("{n:\"Videos\",t:2}"))
                    //r.put(JSONObject("{n:\"Documents\",t:2}"))
                    json.put("dir", r)
                } else {
                    lastDirRequest = json; // Bit of a hack, but use this to refresh after a file delete
                    json.put("dir", getFolder(path))
                }
                if (_webSocket != null) {
                    _webSocket?.send(json.toString().toByteArray(Charsets.UTF_8).toByteString())
                }
            }
            "rm" -> {
                val path = json.getString("path")
                val filenames = json.getJSONArray("delfiles")
                deleteFile(path, filenames, json)
            }
            "upload" -> {
                // {"action":"upload","reqid":0,"path":"Images","name":"00000000.JPG","size":1180231}
                val path = json.getString("path")
                val name = json.getString("name")
                //val size = json.getInt("size")
                val reqid = json.getInt("reqid")

                if (!isSafeFileName(name)) {
                    uploadError()
                    return
                }

                // Close previous upload
                if (fileUpload != null) {
                    fileUpload?.close()
                    fileUpload = null;
                }

                // Setup
                fileUploadName = name
                fileUploadReqId = reqid
                fileUploadSize = 0

                if (path.startsWith("Sdcard")) {
                    val file = resolveSdcardChild(Environment.getExternalStorageDirectory(), path, name)
                    if (file == null) {
                        uploadError()
                        return
                    }
                    try {
                        fileUpload = FileOutputStream(file)
                    } catch (e: Exception) {
                        uploadError()
                        return
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val resolver: ContentResolver = parent.parent.getContentResolver()
                        val contentValues = ContentValues()
                        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        val (mimeType, relativePath, externalUri) = when {
                            name.lowercase().endsWith(".jpg") || name.lowercase().endsWith(".jpeg") -> Triple("image/jpg", Environment.DIRECTORY_PICTURES, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                            name.lowercase().endsWith(".png") -> Triple("image/png", Environment.DIRECTORY_PICTURES, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                            name.lowercase().endsWith(".bmp") -> Triple("image/bmp", Environment.DIRECTORY_PICTURES, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                            name.lowercase().endsWith(".mp4") -> Triple("video/mp4", Environment.DIRECTORY_MOVIES, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                            name.lowercase().endsWith(".mp3") -> Triple("audio/mpeg3", Environment.DIRECTORY_MUSIC, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                            name.lowercase().endsWith(".ogg") -> Triple("audio/ogg", Environment.DIRECTORY_MUSIC, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                            else -> {
                                println("Unsupported file type: $name")
                                Triple(null, null, null)
                            }
                        }
                        if (mimeType != null && relativePath != null && externalUri != null) {
                            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                            contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                            val fileUri = resolver.insert(externalUri, contentValues)
                            try {
                                fileUpload = resolver.openOutputStream(fileUri!!)
                            } catch (e: Exception) {
                                uploadError()
                                return
                            }
                        } else {
                            uploadError()
                            return
                        }
                    } else {
                        val fileExtension = name.lowercase().substringAfterLast('.')
                        val fileDir: String = when (fileExtension) {
                            "jpg", "jpeg", "png" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString()
                            "mp4", "mkv" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).toString()
                            "mp3", "wav" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).toString()
                            else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString()
                        }
                        val file = File(fileDir, name)
                        try {
                            fileUpload = FileOutputStream(file)
                        } catch (e: Exception) {
                            uploadError()
                            return
                        }
                    }
                }

                // Send response
                val respJson = JSONObject()
                respJson.put("action", "uploadstart")
                respJson.put("reqid", reqid)
                if (_webSocket != null) { _webSocket?.send(respJson.toString().toByteArray().toByteString()) }
            }
            "uploaddone" -> {
                if (fileUpload == null) return;
                fileUpload?.close()
                fileUpload = null;

                // Send response
                val respJson = JSONObject()
                respJson.put("action", "uploaddone")
                respJson.put("reqid", fileUploadReqId)
                if (_webSocket != null) { _webSocket?.send(respJson.toString().toByteArray().toByteString()) }

                // Event the server
                var eventArgs = JSONArray()
                eventArgs.put(fileUploadName)
                eventArgs.put(fileUploadSize)
                parent.logServerEventEx(105, eventArgs, "Upload: \"${fileUploadName}}\", Size: $fileUploadSize", serverData);
            }
            else -> {
                // Unknown command, ignore it.
                println("Unhandled action: $action, $jsonStr")
            }
        }
    }

    // https://developer.android.com/training/data-storage/shared/media
    fun getFolder(dir: String) : JSONArray {
        val r : JSONArray = JSONArray()
        val projection = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE
        )
        var uri : Uri? = null;
        if (dir.startsWith("Sdcard")) { uri = Uri.fromFile(Environment.getExternalStorageDirectory()) }
        if (dir.equals("Images")) { uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI }
        if (dir.equals("Audio")) { uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI }
        if (dir.equals("Videos")) { uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI }
        //if (dir == "Documents") { uri = MediaStore.Files. }
        val mediaUri = uri ?: return r
        if (dir.startsWith("Sdcard")) {
            val directory = resolveSdcardPath(Environment.getExternalStorageDirectory(), dir) ?: return r
            val listOfFiles = directory.listFiles()
            for (file in listOfFiles.orEmpty()) {
                var f : JSONObject = JSONObject()
                f.put("n", file.name)
                if (file.isDirectory) f.put("t", 2)
                else f.put("t", 3)
                //f.put("t", 3)
                f.put("s", file.length())
                f.put("d", file.lastModified())
                r.put(f)
            }
        } else {
        parent.parent.contentResolver.query(
            mediaUri,
                projection,
                null,
                null,
                null
        )?.use { cursor ->
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
        }
        return r;
    }

    @Suppress("DEPRECATION")
    fun deleteFile(path: String, filenames: JSONArray, req: JSONObject) {
        var fileArray:ArrayList<String> = ArrayList<String>()
        for (i in 0 until filenames.length()) { fileArray.add(filenames.getString(i)) }

        val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE
        )
        var uri : Uri? = null;
        if (path.startsWith("Sdcard")) { uri = Uri.fromFile(Environment.getExternalStorageDirectory()) }
        if (path.equals("Images")) { uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI }
        if (path.equals("Audio")) { uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI }
        if (path.equals("Videos")) { uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI }
        //if (filenameSplit[0] == "Documents") { uri = MediaStore.Files. }
        val mediaUri = uri ?: return

        if (path.startsWith("Sdcard")) {
            try {
                for (i in 0 until filenames.length())
                {
                    val file = resolveSdcardChild(
                        Environment.getExternalStorageDirectory(),
                        path,
                        filenames.getString(i)
                    )
                    if (file == null) {
                        fileDeleteResponse(req, false)
                        continue
                    }
                    if (file.exists()) {
                        if(file.delete()){
                            fileDeleteResponse(req, true) // Send success
                        } else {
                            fileDeleteResponse(req, false) // Send failure
                        }
                    } else {
                        fileDeleteResponse(req, false) // Send failure, file not found
                    }
                }
            } catch (securityException: SecurityException) {
                fileDeleteResponse(req, false) // Send failure
            }
        } else {
            val matchingFiles = mutableMapOf<String, Pair<String, Uri>>()
            parent.parent.contentResolver.query(
                mediaUri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val idColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val titleColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(titleColumn)
                    if (fileArray.contains(name)) {
                        val id = cursor.getString(idColumn)
                        val contentUrl = ContentUris.withAppendedId(mediaUri, cursor.getLong(idColumn))
                        matchingFiles[name] = Pair(id, contentUrl)
                    }
                }
            }
            for (i in 0 until filenames.length()) {
                val filename = filenames.getString(i)
                val matchingFile = matchingFiles[filename]
                if (matchingFile == null) {
                    fileDeleteResponse(req, false)
                    continue
                }
                try {
                    parent.parent.contentResolver.delete(matchingFile.second,null,null)
                    fileDeleteResponse(req, true) // Send success
                } catch (securityException: SecurityException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val recoverableSecurityException =
                            securityException as? RecoverableSecurityException
                                ?: throw securityException

                        // Save the activity
                        val activityCode = Random.nextInt() and 0xFFFF
                        val pad = PendingActivityData(this, activityCode, matchingFile.second, "${MediaStore.Images.Media._ID} = ?", matchingFile.first, req)
                        pendingActivities.add(pad)

                        // Launch the activity
                        val intentSender = recoverableSecurityException.userAction.actionIntent.intentSender
                        parent.parent.startIntentSenderForResult(
                            intentSender,
                            activityCode,
                            null,
                            0,
                            0,
                            0,
                            null
                        )
                    } else {
                        fileDeleteResponse(req, false) // Send fail
                    }
                }
            }
        }
    }

    fun deleteFileEx(pad: PendingActivityData) {
        try {
            parent.parent.contentResolver.delete(pad.url, pad.where, arrayOf(pad.args))
            fileDeleteResponse(pad.req, true) // Send success
        } catch (ex: Exception) {
            fileDeleteResponse(pad.req, false) // Send fail
        }
    }

    fun startFileTransfer(filename: String) {
        var filenameSplit = filename.split('/')
        println("startFileTransfer: filename=$filename, split=$filenameSplit")

        val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE
        )
        var uri : Uri? = null;
        if (filenameSplit[0].startsWith("Sdcard")) { uri = Uri.fromFile(Environment.getExternalStorageDirectory()) }
        if (filenameSplit[0].equals("Images")) { uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI }
        if (filenameSplit[0].equals("Audio")) { uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI }
        if (filenameSplit[0].equals("Videos")) { uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI }
        //if (filenameSplit[0] == "Documents") { uri = MediaStore.Files. }
        println("startFileTransfer: root=${filenameSplit[0]}, uri=$uri")
        val mediaUri = uri ?: run {
            println("startFileTransfer: no uri for root=${filenameSplit[0]}, stopping")
            stopSocket()
            return
        }
        if (filenameSplit[0].startsWith("Sdcard")){
            val externalStorageDirectory = Environment.getExternalStorageDirectory()
            println("startFileTransfer: resolving Sdcard path, root=${externalStorageDirectory.absolutePath}, filename=$filename")
            val file = resolveSdcardPath(Environment.getExternalStorageDirectory(), filename)
                ?: run {
                    println("startFileTransfer: failed to resolve Sdcard path, filename=$filename")
                    stopSocket()
                    return
                }
            println("startFileTransfer: resolved Sdcard path=${file.absolutePath}, exists=${file.exists()}, canRead=${file.canRead()}, isFile=${file.isFile}, length=${file.length()}")
            if (file.exists()) {
                val fileName = file.name
                val fileSize = file.length()
                var eventArgs = JSONArray()
                eventArgs.put(fileName)
                eventArgs.put(fileSize)
                parent.logServerEventEx(106, eventArgs, "Download: ${fileName}, Size: $fileSize", serverData);
                val okJson = JSONObject()
                okJson.put("op", "ok")
                okJson.put("size", fileSize)
                val okSendResult = _webSocket?.send(okJson.toString())
                println("startFileTransfer: sent Sdcard ok message, size=$fileSize, sendResult=$okSendResult")
                val contentUrl = Uri.fromFile(file)
                println("startFileTransfer: opening Sdcard input stream, uri=$contentUrl")
                try {
                    // Serve the file
                    parent.parent.getContentResolver().openInputStream(contentUrl).use { stream ->
                            println("startFileTransfer: Sdcard stream opened, streamNull=${stream == null}")
                            // Perform operation on stream
                            var buf = ByteArray(65535)
                            var len : Int
                            var totalBytes: Long = 0
                            while (true) {
                                len = stream!!.read(buf, 0, 65535)
                                if (len <= 0) {
                                    println("startFileTransfer: Sdcard stream finished, totalBytes=$totalBytes")
                                    stopSocket()
                                    break
                                } // Stream is done
                                if (_webSocket == null) {
                                    println("startFileTransfer: websocket closed while sending Sdcard file, totalBytes=$totalBytes")
                                    stopSocket()
                                    break
                                } // Web socket closed
                                val sendResult = _webSocket?.send(buf.toByteString(0, len))
                                totalBytes += len
                                println("startFileTransfer: sent Sdcard chunk, len=$len, totalBytes=$totalBytes, sendResult=$sendResult, queueSize=${_webSocket?.queueSize()}")
                                if (_webSocket?.queueSize()!! > 655350) {
                                    println("startFileTransfer: websocket queue high, sleeping, queueSize=${_webSocket?.queueSize()}")
                                    Thread.sleep(100)
                                }
                            }
                        }
                    return;
                } catch (e: FileNotFoundException) {
                    println("startFileTransfer: Sdcard FileNotFoundException: ${e.message}")
                } catch (e: Exception) {
                    println("startFileTransfer: Sdcard exception: ${e.javaClass.simpleName}: ${e.message}")
                }
            } else {
                println("startFileTransfer: Sdcard file does not exist, path=${file.absolutePath}")
            }
        } else {
            if (filenameSplit.size != 2 || !isSafeFileName(filenameSplit[1])) {
                println("startFileTransfer: invalid MediaStore filename, split=$filenameSplit")
                stopSocket()
                return
            }
                println("startFileTransfer: querying MediaStore, uri=$mediaUri, target=${filenameSplit[1]}")
                var foundFile = false
                parent.parent.contentResolver.query(
                    mediaUri,
                    projection,
                    null,
                    null,
                    null
                )?.use { cursor ->
                println("startFileTransfer: MediaStore query returned, count=${cursor.count}")
                val idColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val titleColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    var name = cursor.getString(titleColumn)
                    println("startFileTransfer: MediaStore row name=$name")
                    if (name == filenameSplit[1]) {
                        foundFile = true
                        var contentUrl: Uri = ContentUris.withAppendedId(mediaUri, cursor.getLong(idColumn))
                        var fileSize = cursor.getInt(sizeColumn)
                        println("startFileTransfer: MediaStore match, uri=$contentUrl, size=$fileSize")

                        // Event to the server
                        var eventArgs = JSONArray()
                        eventArgs.put(filename)
                        eventArgs.put(fileSize)
                        parent.logServerEventEx(106, eventArgs, "Download: ${filename}, Size: $fileSize", serverData);
                        val okJson = JSONObject()
                        okJson.put("op", "ok")
                        okJson.put("size", fileSize)
                        val okSendResult = _webSocket?.send(okJson.toString())
                        println("startFileTransfer: sent MediaStore ok message, size=$fileSize, sendResult=$okSendResult")

                        // Serve the file
                        println("startFileTransfer: opening MediaStore input stream, uri=$contentUrl")
                        parent.parent.getContentResolver().openInputStream(contentUrl).use { stream ->
                            println("startFileTransfer: MediaStore stream opened, streamNull=${stream == null}")
                            // Perform operation on stream
                            var buf = ByteArray(65535)
                            var len : Int
                            var totalBytes: Long = 0
                            while (true) {
                                len = stream!!.read(buf, 0, 65535)
                                if (len <= 0) {
                                    println("startFileTransfer: MediaStore stream finished, totalBytes=$totalBytes")
                                    stopSocket()
                                    break
                                } // Stream is done
                                if (_webSocket == null) {
                                    println("startFileTransfer: websocket closed while sending MediaStore file, totalBytes=$totalBytes")
                                    stopSocket()
                                    break
                                } // Web socket closed
                                val sendResult = _webSocket?.send(buf.toByteString(0, len))
                                totalBytes += len
                                println("startFileTransfer: sent MediaStore chunk, len=$len, totalBytes=$totalBytes, sendResult=$sendResult, queueSize=${_webSocket?.queueSize()}")
                                if (_webSocket?.queueSize()!! > 655350) {
                                    println("startFileTransfer: websocket queue high, sleeping, queueSize=${_webSocket?.queueSize()}")
                                    Thread.sleep(100)
                                }
                            }
                        }
                        return;
                    }
                }
            }
            if (!foundFile) {
                println("startFileTransfer: MediaStore file not found, target=${filenameSplit[1]}")
            }
        }
        println("startFileTransfer: stopping without transfer, filename=$filename")
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

    fun fileDeleteResponse(req: JSONObject, success: Boolean) {
        val json = JSONObject()
        json.put("action", "rm")
        json.put("reqid", req.getString("reqid"))
        json.put("success", success)
        if (_webSocket != null) { _webSocket?.send(json.toString().toByteArray().toByteString()) }

        // Event to the server
        val path = req.getString("path")
        val filenames = req.getJSONArray("delfiles")
        if (filenames.length() == 1) {
            var eventArgs = JSONArray()
            eventArgs.put(path + '/' + filenames[0])
            parent.logServerEventEx(45, eventArgs, "Delete: \"${path}/${filenames[0]}\"", serverData);
        }

        if (success && (lastDirRequest != null)) {
            val dirPath = lastDirRequest?.getString("path")
            if ((dirPath != null) && (dirPath != "")) {
                lastDirRequest?.put("dir", getFolder(dirPath))
                if (_webSocket != null) {_webSocket?.send(lastDirRequest?.toString()!!.toByteArray(Charsets.UTF_8).toByteString()) }
            }
        }
    }

    // WebRTC setup
    /*
    private fun initializePeerConnectionFactory() {
        //Initialize PeerConnectionFactory globals.
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(parent.parent).createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        //Create a new PeerConnectionFactory instance - using Hardware encoder and decoder.
        val options = PeerConnectionFactory.Options()
        //val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(rootEglBase?.eglBaseContext,  /* enableIntelVp8Encoder */true,  /* enableH264HighProfile */true)
        //val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(rootEglBase?.eglBaseContext)
        val factory = PeerConnectionFactory.builder()
                .setOptions(options)
                //.setVideoEncoderFactory(defaultVideoEncoderFactory)
                //.setVideoDecoderFactory(defaultVideoDecoderFactory)
                .createPeerConnectionFactory()

        //factory.createPeerConnection()
    }
    */

}
