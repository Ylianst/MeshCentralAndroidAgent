package com.meshcentral.agent

import android.annotation.SuppressLint
import android.app.AlertDialog
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
import java.util.concurrent.Executors
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

    private val authorization = SessionAuthorization()
    private val fileExecutor = Executors.newSingleThreadExecutor()
    private val fileCommands = ConsentCommandQueue(fileExecutor, onError = { stopSocket() })
    private var consentDialog: AlertDialog? = null
    private var consentTimer: CountDownTimer? = null
    private var desktopSettings: ByteString? = null
    val isSessionAuthorized: Boolean get() = state == 2 && authorization.isApproved
    val isDesktopAuthorized: Boolean get() = usage == 2 && isSessionAuthorized

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
        if (!authorization.close()) return
        state = 0
        parent.parent.runOnUiThread {
            consentTimer?.cancel()
            consentDialog?.dismiss()
            consentDialog = null
        }
        fileCommands.close {
            try { fileUpload?.close() } catch (ex: Exception) { }
            fileUpload = null
            fileExecutor.shutdown()
        }
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
        //println("Tunnel-onOpen")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (authorization.isClosed) return
        if (state == 0) {
            if ((text == "c") || (text == "cr")) { state = 1 }
            return
        }
        if (state != 1) return
        try {
            if (text.startsWith('{')) {
                val json = JSONObject(text)
                if (json.optString("type") == "options") { tunnelOptions = json }
                return
            }
            val xusage = text.toIntOrNull() ?: run { stopSocket(); return }
            val options = serverData.optJSONObject("soptions")
            val allowedUsages = if (options?.has("usages") == true) {
                val list = options.optJSONArray("usages") ?: run { stopSocket(); return }
                List(list.length()) { list.getInt(it) }
            } else null
            if (!isTunnelUsageAllowed(allowedUsages, xusage) || !tunnelRightsAllow(serverData.optLong("rights", 0), xusage)) {
                stopSocket(); return
            }
            usage = xusage
            val promptBit = if (usage == 2) 8 else 32
            authorization.begin(!g_autoConsent || (serverData.optInt("consent", 0) and promptBit) != 0)
            state = 2
            if (usage != 10) startConnectionTimer()
            if (authorization.isPending) requestConsent() else sessionAuthorized()
        } catch (ex: Exception) { stopSocket() }
    }

    private fun requestConsent() {
        sendCtrlResponse(JSONObject().put("type", "console").put("msg", "Waiting for user to grant access...").put("msgid", 1))
        parent.parent.runOnUiThread {
            if (!authorization.isPending) return@runOnUiThread
            val activity = parent.parent
            if (activity.isFinishing || activity.isDestroyed) { stopSocket(); return@runOnUiThread }
            val options = serverData.optJSONObject("soptions")
            val username = serverData.optString("username")
            val realname = serverData.optString("realname").ifEmpty { username }
            val key = if (usage == 2) "consentMsgDesktop" else "consentMsgFiles"
            val message = options?.optString(key, "").orEmpty().ifEmpty {
                activity.getString(if (usage == 2) R.string.desktop_consent_message else R.string.files_consent_message, realname)
            }.replace("{0}", realname).replace("{1}", username)
            try {
                consentDialog = AlertDialog.Builder(activity)
                    .setTitle(options?.optString("consentTitle", "MeshCentral") ?: "MeshCentral")
                    .setMessage(message)
                    .setPositiveButton(R.string.accept) { _, _ -> approveConsent() }
                    .setNegativeButton(R.string.reject) { _, _ -> denyConsent() }
                    .setOnCancelListener { denyConsent() }
                    .show()
                val seconds = (options?.optInt("consentTimeout", 30) ?: 30).takeIf { it > 0 } ?: 30
                consentTimer = object : CountDownTimer(seconds.toLong() * 1000, 1000) {
                    override fun onTick(millisUntilFinished: Long) { }
                    override fun onFinish() {
                        if (options?.optBoolean("consentAutoAccept", false) == true) approveConsent() else denyConsent()
                    }
                }.start()
            } catch (ex: Exception) { stopSocket() }
        }
    }

    fun approveConsent() {
        if (!authorization.approve()) return
        parent.parent.runOnUiThread {
            consentTimer?.cancel()
            consentDialog?.dismiss()
            consentDialog = null
        }
        sessionAuthorized()
    }

    fun denyConsent() {
        if (!authorization.isPending) return
        sendCtrlResponse(JSONObject().put("type", "console").put("msg", "denied").put("msgid", 2))
        stopSocket()
    }

    private fun sessionAuthorized() {
        if (!isSessionAuthorized) return
        sendCtrlResponse(JSONObject().put("type", "console").put("msg", JSONObject.NULL).put("msgid", 0))
        if (usage == 2) {
            parent.parent.runOnUiThread {
                if (!isDesktopAuthorized) return@runOnUiThread
                if (g_ScreenCaptureService == null) parent.parent.startProjection() else updateDesktopDisplaySize()
                desktopSettings?.let { processBinaryDesktopCmd(5, it.size, it) }
            }
        } else {
            fileCommands.approve()
            if (usage == 10) {
                val filename = tunnelOptions?.optString("file")
                if (filename.isNullOrEmpty()) { stopSocket(); return }
                if (!fileCommands.submit { if (isSessionAuthorized) startFileTransfer(filename) }) stopSocket()
            }
        }
    }

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun onMessage(webSocket: WebSocket, msg: ByteString) {
        if (state != 2 || msg.size == 0) return
        if (usage == 5) {
            if (msg.size > 1048576 || !fileCommands.submit { processFilePacket(msg) }) stopSocket()
            return
        }
        if (usage != 2 || msg.size < 4) return
        val cmd = ((msg[0].toInt() and 0xff) shl 8) or (msg[1].toInt() and 0xff)
        val size = ((msg[2].toInt() and 0xff) shl 8) or (msg[3].toInt() and 0xff)
        if (size != msg.size) return
        if (cmd == 5) desktopSettings = msg
        if (isDesktopAuthorized) processBinaryDesktopCmd(cmd, size, msg)
    }

    private fun processFilePacket(msg: ByteString) {
        if (usage != 5 || !isSessionAuthorized) return
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
        if (!isDesktopAuthorized || (g_ScreenCaptureService == null) || (_webSocket == null)) return
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
            if (authorization.isClosed) return@runOnUiThread
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
        if (usage != 5 || !isSessionAuthorized) return
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
        if (usage != 5 || !isSessionAuthorized) return
        try {
            parent.parent.contentResolver.delete(pad.url, pad.where, arrayOf(pad.args))
            fileDeleteResponse(pad.req, true) // Send success
        } catch (ex: Exception) {
            fileDeleteResponse(pad.req, false) // Send fail
        }
    }

    fun startFileTransfer(filename: String) {
        if (usage != 10 || !isSessionAuthorized) return
        var filenameSplit = filename.split('/')
        //println("startFileTransfer: $filenameSplit")

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
        val mediaUri = uri ?: run { stopSocket(); return }
        if (filenameSplit[0].startsWith("Sdcard")){
            val file = resolveSdcardPath(Environment.getExternalStorageDirectory(), filename)
                ?: run { stopSocket(); return }
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
                _webSocket?.send(okJson.toString())
                val contentUrl = Uri.fromFile(file)
                try {
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
                } catch (e: FileNotFoundException) {
                }
            } else {
                // file does not exist
            }
        } else {
            if (filenameSplit.size != 2 || !isSafeFileName(filenameSplit[1])) {
                stopSocket()
                return
            }
                parent.parent.contentResolver.query(
                    mediaUri,
                    projection,
                    null,
                    null,
                    null
                )?.use { cursor ->
                val idColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val titleColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    var name = cursor.getString(titleColumn)
                    if (name == filenameSplit[1]) {
                        var contentUrl: Uri = ContentUris.withAppendedId(mediaUri, cursor.getLong(idColumn))
                        var fileSize = cursor.getInt(sizeColumn)

                        // Event to the server
                        var eventArgs = JSONArray()
                        eventArgs.put(filename)
                        eventArgs.put(fileSize)
                        parent.logServerEventEx(106, eventArgs, "Download: ${filename}, Size: $fileSize", serverData);
                        val okJson = JSONObject()
                        okJson.put("op", "ok")
                        okJson.put("size", fileSize)
                        _webSocket?.send(okJson.toString())

                        // Serve the file
                        parent.parent.getContentResolver().openInputStream(contentUrl).use { stream ->
                            // Perform operation on stream
                            var buf = ByteArray(65535)
                            var len : Int
                            while (true) {
                                len = stream!!.read(buf, 0, 65535)
                                if (len <= 0) {
                                    stopSocket()
                                    break
                                } // Stream is done
                                if (_webSocket == null) {
                                    stopSocket()
                                    break
                                } // Web socket closed
                                _webSocket?.send(buf.toByteString(0, len))
                                if (_webSocket?.queueSize()!! > 655350) {
                                    Thread.sleep(100)
                                }
                            }
                        }
                        return;
                    }
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

    fun fileDeleteResponse(req: JSONObject, success: Boolean) {
        if (usage != 5 || !isSessionAuthorized) return
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
