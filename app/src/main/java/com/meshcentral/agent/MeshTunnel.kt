package com.meshcentral.agent

import android.annotation.SuppressLint
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
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
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.collections.ArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread
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
    @Volatile var _webSocket: WebSocket? = null
    @Volatile var state: Int = 0 // 0 = Disconnected, 1 = Connecting, 2 = Connected
    var usage: Int = 0 // 2 = Desktop, 5 = Files, 10 = File transfer
    private var tunnelOptions : JSONObject? = null
    private var lastDirRequest : JSONObject? = null
    private var fileUpload : UploadSink? = null
    private var fileUploadName : String? = null
    private var fileUploadReqId : Int = 0
    private var fileUploadSize : Int = 0
    var userid : String? = null
    var guestname : String? = null
    var sessionUserName : String? = null // UserID + GuestName in Base64 if this is a shared session.
    var sessionUserName2 : String? = null // UserID/GuestName
    // Server-side consent policy for this session, a bitmask from the tunnel command.
    val consentFlags: Int = serverData.optInt("consent", 0)
    private val authorization = SessionAuthorization()
    val consentId: String = UUID.randomUUID().toString()
    val isConsentPending: Boolean get() = state == 2 && authorization.isPending
    val isSessionAuthorized: Boolean get() = state == 2 && authorization.isApproved
    val isDesktopAuthorized: Boolean get() = usage == 2 && isSessionAuthorized
    private val inputPermitted = desktopInputPermitted(
        serverData.optLong("rights", 0), serverData.optBoolean("desktopviewonly", false)
    )
    @Volatile private var desktopSettings: ByteString? = null
    private val fileExecutor by lazy { Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "MeshFiles") } }
    private val fileCommands by lazy { ConsentCommandQueue(fileExecutor) }

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
        if (!userid.isNullOrEmpty()) parent.sendUserImageRequest(userid!!)
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

        return MeshHttp.base.newBuilder()
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
        val socket = _webSocket
        _webSocket = null
        try { socket?.close(NORMAL_CLOSURE_STATUS, null) } catch (ex: Exception) { }
        if (usage == 5) {
            fileCommands.close {
                fileUpload?.discard()
                fileUpload = null
                closeBlockDownload()
            }
            fileExecutor.shutdown()
        }
        parent.parent.runOnHostThread {
            connectionTimer?.cancel()
            connectionTimer = null
        }
        parent.removeTunnel(this)
        AgentController.sessionClosed(this)
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
        // Console message ids the web UI translates itself (agentConsoleMessages in the views).
        const val MSGID_CONSENT_PENDING = 1
        const val MSGID_CONSENT_DENIED = 2
        const val CONSENT_PENDING_MESSAGE = "Waiting for user to grant access..."
        // Consent bitmask bits, as defined by the MeshCentral server.
        const val CONSENT_DESKTOP_NOTIFY = 1
        const val CONSENT_FILES_NOTIFY = 4
        const val CONSENT_DESKTOP_PROMPT = 8
        const val CONSENT_FILES_PROMPT = 32
        private const val DEFAULT_CONSENT_TIMEOUT_S = 30
        // Block payload the web UI expects (16 KB minus the 4-byte header).
        private const val DOWNLOAD_BLOCK_SIZE = 16380
    }

    // The app setting forces a prompt; with automatic consent on, the server's flags still
    // decide, as they do for the other agents.
    fun consentPromptRequired(): Boolean {
        val promptBit = if (usage == 5) CONSENT_FILES_PROMPT else CONSENT_DESKTOP_PROMPT
        return !g_autoConsent || (consentFlags and promptBit) != 0
    }

    fun consentNotifyRequested(): Boolean {
        val notifyBit = if (usage == 5) CONSENT_FILES_NOTIFY else CONSENT_DESKTOP_NOTIFY
        return (consentFlags and notifyBit) != 0
    }

    fun consentTimeoutMs(): Long {
        val seconds = serverData.optJSONObject("soptions")?.optInt("consentTimeout", 0) ?: 0
        return (if (seconds > 0) seconds else DEFAULT_CONSENT_TIMEOUT_S) * 1000L
    }

    fun consentAutoAcceptOnTimeout(): Boolean {
        return serverData.optJSONObject("soptions")?.optBoolean("consentAutoAccept", false) ?: false
    }

    fun remoteUserName(context: Context): String {
        val realname = serverData.optString("realname")
        if (realname.isNotEmpty()) return realname
        val username = serverData.optString("username")
        if (username.isNotEmpty()) return username
        val guest = guestname
        if (!guest.isNullOrEmpty()) return guest
        return context.getString(R.string.remote_user)
    }

    fun consentMessage(context: Context): String {
        val serverKey = if (usage == 5) "consentMsgFiles" else "consentMsgDesktop"
        val defaultRes = if (usage == 5) R.string.files_consent_message else R.string.unattended_consent_message
        return serverText(serverKey) ?: context.getString(defaultRes, remoteUserName(context))
    }

    // Server-configured text uses {0} for the operator's real name and {1} for the account name.
    private fun serverText(key: String): String? {
        val template = serverData.optJSONObject("soptions")?.optString(key, "") ?: ""
        if (template.isEmpty()) return null
        val username = serverData.optString("username")
        val realname = serverData.optString("realname").ifEmpty { username }
        return template.replace("{0}", realname).replace("{1}", username)
    }

    // Toast for the server's notify flag, shown once the session actually starts.
    fun notifySessionStart() {
        if (!consentNotifyRequested()) return
        val context = parent.parent.getApplicationContext()
        val serverKey = if (usage == 5) "notifyMsgFiles" else "notifyMsgDesktop"
        val defaultRes = if (usage == 5) R.string.files_session_started else R.string.desktop_session_started
        parent.parent.showToastMessage(serverText(serverKey) ?: context.getString(defaultRes, remoteUserName(context)))
    }

    fun logSessionEvent(id: Int, msg: String) {
        parent.logServerEventEx(id, null, "$msg (${serverData.optString("remoteaddr")})", serverData)
    }

    private fun startSession() {
        if (isConsentPending) {
            sendConsoleMessage(CONSENT_PENDING_MESSAGE, MSGID_CONSENT_PENDING)
            AgentController.requestSessionConsent(this)
        } else {
            sessionAuthorized(false)
        }
    }

    fun approveConsent() {
        if (authorization.approve()) sessionAuthorized(true)
    }

    private fun sessionAuthorized(prompted: Boolean) {
        if (!isSessionAuthorized) return
        val files = usage == 5
        val id = if (prompted) {
            if (files) 40 else 30
        } else if (files) {
            if (consentNotifyRequested()) 42 else 43
        } else {
            if (consentNotifyRequested()) 35 else 36
        }
        logSessionEvent(id, "Started remote ${if (files) "files" else "desktop"}" +
            if (prompted) " after local user accepted" else " with automatic consent")
        notifySessionStart()
        if (files) {
            sendConsoleMessage(null)
            fileCommands.approve()
        } else {
            AgentController.desktopSessionApproved(this)
        }
    }

    fun denyConsent() {
        if (!isConsentPending) return
        logSessionEvent(if (usage == 5) 41 else 34, "Remote session consent denied")
        sendConsoleMessage("denied", MSGID_CONSENT_DENIED)
        Stop()
    }

    fun applyDesktopSettings() {
        if (!isDesktopAuthorized) return
        desktopSettings?.let { processAuthorizedDesktopCmd(5, it.size, it) }
    }
    // Overlay text on this session's viewer. With msgid the web UI shows its own translated string,
    // otherwise the text as given. A null msg clears the overlay; timeoutSeconds lets the viewer
    // clear it by itself.
    fun sendConsoleMessage(msg: String?, msgid: Int? = null, timeoutSeconds: Int? = null) {
        val json = JSONObject()
        json.put("type", "console")
        json.put("msg", msg ?: JSONObject.NULL)
        if (msg == null) {
            json.put("msgid", 0)
        } else if (msgid != null) {
            json.put("msgid", msgid)
        }
        if (timeoutSeconds != null) json.put("timeout", timeoutSeconds)
        sendCtrlResponse(json)
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        //println("Tunnel-onOpen")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (authorization.isClosed) return
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
                val xusage = text.toIntOrNull() ?: run { println("Invalid usage $text"); stopSocket(); return }
                if (((xusage < 1) || (xusage > 5)) && (xusage != 10)) {
                    println("Invalid usage $text"); stopSocket(); return
                }
                val allowedUsages = serverData.optJSONObject("soptions")?.optJSONArray("usages")?.let { arr ->
                    List(arr.length()) { arr.getInt(it) }
                }
                if (!isTunnelUsageAllowed(allowedUsages, xusage)) {
                    println("Unexpected usage $text, allowed $allowedUsages");
                    stopSocket(); return
                }
                usage = xusage; // 2 = Desktop, 5 = Files, 10 = File transfer
                if (usage == 2 || usage == 5) authorization.begin(consentPromptRequired())
                state = 2

                AgentController.refreshInfo()

                // Start the connection time except if this is a file transfer
                if (usage != 10) {
                    //println("Connected usage $usage")
                    startConnectionTimer()
                    if (usage == 2 || usage == 5) startSession()
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
        if (state != 2 || msg.size == 0) return
        if (usage == 5) {
            if (!fileCommands.submit { processFilePacket(msg) }) stopSocket()
            return
        }
        if (usage != 2 || msg.size < 4) return
        val cmd = ((msg[0].toInt() and 0xff) shl 8) or (msg[1].toInt() and 0xff)
        val size = ((msg[2].toInt() and 0xff) shl 8) or (msg[3].toInt() and 0xff)
        if (size != msg.size) return
        if (cmd == 5) desktopSettings = msg
        if (!isDesktopAuthorized || (cmd in setOf(1, 2, 15, 85) && !inputPermitted)) return
        parent.parent.runOnHostThread {
            if (!isDesktopAuthorized) return@runOnHostThread
            try {
                processAuthorizedDesktopCmd(cmd, size, msg)
            } catch (ex: Exception) {
                println("Desktop input failed: $ex")
            }
        }
    }

    private fun processFilePacket(msg: ByteString) {
        if (!isSessionAuthorized || (msg.size == 1 && msg[0].toInt() == 0)) return
        try {
            if (msg[0].toInt() == 123) {
                processTunnelData(msg.utf8())
            } else if (fileUpload != null) {
                // If this is file upload data, process it here
                val stream = fileUpload?.stream ?: return
                if (msg[0].toInt() == 0) {
                    // If data starts with zero, skip the first byte. This is used to escape binary file data from JSON.
                    fileUploadSize += (msg.size - 1);
                    var buf = msg.toByteArray()
                    try {
                        stream.write(buf, 1, buf.size - 1)
                    } catch (ex : Exception) {
                        uploadError("write failed, ${ex.message}")
                        return
                    }
                } else {
                    // If data does not start with zero, save as-is.
                    fileUploadSize += msg.size;
                    try {
                        stream.write(msg.toByteArray())
                    } catch (ex : Exception) {
                        uploadError("write failed, ${ex.message}")
                        return
                    }
                }

                // Ask for more data
                val json = JSONObject()
                json.put("action", "uploadack")
                json.put("reqid", fileUploadReqId)
                if (_webSocket != null) { _webSocket?.send(json.toString().toByteArray().toByteString()) }
            }
        } catch (ex: Exception) {
            println("Tunnel-Exception: $ex")
        }
    }

    private fun processAuthorizedDesktopCmd(cmd : Int, cmdsize: Int, msg: ByteString) {
        when (cmd) {
            1 -> { // Legacy key input
                AgentController.handleDesktopKeyCommand(cmd, msg)
            }
            2 -> { // Mouse input
                AgentController.handleDesktopMouseCommand(msg)
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
                AgentController.requestDesktopRefresh()
                println("Desktop Refresh")
            }
            8 -> { // Pause
                // Nop
            }
            85 -> { // Unicode key input
                AgentController.handleDesktopKeyCommand(cmd, msg)
            }
            15 -> { // Touch input
                AgentController.handleDesktopTouchCommand(msg)
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
        val provider = AgentController.activeRemoteDesktopProvider()
        if (!isDesktopAuthorized || provider == null || _webSocket == null) return

        // Get the display size
        var mWidth : Int = provider.width
        var mHeight : Int = provider.height

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
        _webSocket?.send(bytesOut.toByteArray().toByteString())
    }

    // Cause some data to be sent over the websocket control channel every 2 minutes to keep it open
    private fun startConnectionTimer() {
        parent.parent.runOnHostThread {
            if (authorization.isClosed) return@runOnHostThread
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

    private fun uploadError(reason: String?) {
        val json = JSONObject()
        json.put("action", "uploaderror")
        json.put("reqid", fileUploadReqId)
        if (_webSocket != null) { _webSocket?.send(json.toString().toByteArray().toByteString()) }
        fileUpload?.discard()
        fileUpload = null
        // The viewer only closes its dialog on uploaderror, so say why on the files overlay.
        sendConsoleMessage("Upload of \"$fileUploadName\" failed: ${reason ?: "unknown error"}", timeoutSeconds = 20)
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
            "download" -> handleDownloadCommand(json)
            "upload" -> {
                // {"action":"upload","reqid":0,"path":"Images","name":"00000000.JPG","size":1180231,"append":false}
                val path = json.getString("path")
                val name = json.getString("name")
                val reqid = json.getInt("reqid")
                val append = json.optBoolean("append", false)

                // Close previous upload
                fileUpload?.discard()
                fileUpload = null

                // Setup
                fileUploadName = name
                fileUploadReqId = reqid
                fileUploadSize = 0
                fileUpload = try {
                    UploadSink.open(parent.parent.contentResolver, path, name, append)
                } catch (ex: UploadException) {
                    uploadError(ex.message)
                    return
                } catch (ex: Exception) {
                    uploadError("cannot create \"$name\", ${ex.message}")
                    return
                }

                // Send response
                val respJson = JSONObject()
                respJson.put("action", "uploadstart")
                respJson.put("reqid", reqid)
                if (_webSocket != null) { _webSocket?.send(respJson.toString().toByteArray().toByteString()) }
            }
            "uploaddone" -> {
                val upload = fileUpload ?: return
                fileUpload = null
                try {
                    upload.finish()
                } catch (ex: Exception) {
                    upload.discard()
                    uploadError("cannot save \"$fileUploadName\", ${ex.message}")
                    return
                }

                // Send response
                val respJson = JSONObject()
                respJson.put("action", "uploaddone")
                respJson.put("reqid", fileUploadReqId)
                if (_webSocket != null) { _webSocket?.send(respJson.toString().toByteArray().toByteString()) }

                // Event the server
                var eventArgs = JSONArray()
                eventArgs.put(fileUploadName)
                eventArgs.put(fileUploadSize)
                parent.logServerEventEx(105, eventArgs, "Upload: \"${fileUploadName}\", Size: $fileUploadSize", serverData);
            }
            "uploadcancel" -> {
                val upload = fileUpload ?: return
                fileUpload = null
                upload.discard()
                val respJson = JSONObject()
                respJson.put("action", "uploadcancel")
                respJson.put("reqid", fileUploadReqId)
                if (_webSocket != null) { _webSocket?.send(respJson.toString().toByteArray().toByteString()) }
            }
            "uploadhash" -> {
                // The viewer compares this with its local file to skip an identical upload. Without
                // a reply its upload dialog waits forever.
                val path = json.getString("path")
                val name = json.getString("name")
                val hash = hashExistingUpload(parent.parent.contentResolver, path, name)
                val respJson = JSONObject()
                respJson.put("action", "uploadhash")
                respJson.put("reqid", json.get("reqid"))
                respJson.put("path", path)
                respJson.put("name", name)
                respJson.put("tag", json.optJSONObject("tag") ?: JSONObject())
                respJson.put("hash", hash ?: JSONObject.NULL)
                if (_webSocket != null) { _webSocket?.send(respJson.toString().toByteArray().toByteString()) }
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
            listIndexedFolder(parent.parent.contentResolver, dir)?.let { return it }
            val listOfFiles = directory.listFiles()
            for (file in listOfFiles.orEmpty()) {
                var f : JSONObject = JSONObject()
                f.put("n", file.name)
                if (file.isDirectory) f.put("t", 2)
                else f.put("t", 3)
                //f.put("t", 3)
                f.put("s", file.length())
                // The web UI treats numeric dates as seconds.
                f.put("d", file.lastModified() / 1000)
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
                f.put("s", cursor.getLong(sizeColumn))
                f.put("d", cursor.getLong(dateModified))
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
                        val launched = parent.parent.launchIntentSenderForResult(
                            intentSender,
                            activityCode,
                            null,
                            0,
                            0,
                            0,
                            null
                        )
                        if (!launched) {
                            pendingActivities.remove(pad)
                            fileDeleteResponse(req, false)
                        }
                    } else {
                        fileDeleteResponse(req, false) // Send fail
                    }
                }
            }
        }
    }

    fun deleteFileEx(pad: PendingActivityData) {
        fileCommands.submit {
            if (!isSessionAuthorized) return@submit
            try {
                parent.parent.contentResolver.delete(pad.url, pad.where, arrayOf(pad.args))
                fileDeleteResponse(pad.req, true)
            } catch (ex: Exception) {
                fileDeleteResponse(pad.req, false)
            }
        }
    }

    // Serves a file to the server's devicefile.ashx download over this usage-10 tunnel.
    fun startFileTransfer(filename: String) {
        val name = filename.substringAfterLast('/')
        val source = openSharedFile(parent.parent.contentResolver, filename.substringBeforeLast('/'), name)
        if (source == null) {
            parent.sendFilesMessage("Download of \"$name\" failed: the file is missing or Android does not let the agent read it", userid)
            stopSocket()
            return
        }
        val ws = _webSocket
        if (ws == null) {
            source.stream.close()
            stopSocket()
            return
        }

        val eventArgs = JSONArray()
        eventArgs.put(source.name)
        eventArgs.put(source.size)
        parent.logServerEventEx(106, eventArgs, "Download: ${source.name}, Size: ${source.size}", serverData)
        val okJson = JSONObject()
        okJson.put("op", "ok")
        okJson.put("size", source.size)
        ws.send(okJson.toString())

        // Stream on our own thread so the socket's reader keeps handling control frames and a
        // close from the other side ends the transfer instead of waiting for it to finish.
        thread(name = "MeshFileSend") {
            try {
                source.stream.use { stream ->
                    val buf = ByteArray(65535)
                    while (_webSocket === ws) {
                        val len = stream.read(buf, 0, buf.size)
                        if (len <= 0) break
                        ws.send(buf.toByteString(0, len))
                        while ((_webSocket === ws) && (ws.queueSize() > 655350)) Thread.sleep(100)
                    }
                }
            } catch (ex: Exception) {
                println("Tunnel-Exception: $ex")
            }
            stopSocket()
        }
    }

    // The viewer's file editor pulls files over the files session in blocks, each prefixed with a
    // 4-byte flag word whose low bit marks the last block.
    private var blockDownload: SharedFile? = null
    private var blockDownloadId: String? = null

    private fun handleDownloadCommand(json: JSONObject) {
        val id = json.opt("id")
        when (json.optString("sub")) {
            "start" -> {
                closeBlockDownload()
                val path = json.optString("path")
                val file = openSharedFile(parent.parent.contentResolver, path.substringBeforeLast('/'), path.substringAfterLast('/'))
                val reply = JSONObject()
                reply.put("action", "download")
                reply.put("id", id)
                if (file == null) {
                    reply.put("sub", "cancel")
                } else {
                    blockDownload = file
                    blockDownloadId = id?.toString()
                    reply.put("sub", "start")
                }
                if (_webSocket != null) { _webSocket?.send(reply.toString().toByteArray().toByteString()) }
            }
            "startack", "ack" -> {
                val file = blockDownload ?: return
                if (id?.toString() != blockDownloadId) return
                val buf = ByteArray(4 + DOWNLOAD_BLOCK_SIZE)
                var len = 0
                try {
                    while (len < DOWNLOAD_BLOCK_SIZE) {
                        val read = file.stream.read(buf, 4 + len, DOWNLOAD_BLOCK_SIZE - len)
                        if (read <= 0) break
                        len += read
                    }
                } catch (ex: Exception) {
                    closeBlockDownload()
                    val reply = JSONObject()
                    reply.put("action", "download")
                    reply.put("sub", "cancel")
                    reply.put("id", id)
                    if (_webSocket != null) { _webSocket?.send(reply.toString().toByteArray().toByteString()) }
                    return
                }
                val last = len < DOWNLOAD_BLOCK_SIZE
                buf[0] = 1
                buf[1] = 0
                buf[2] = 0
                buf[3] = if (last) 1 else 0
                if (_webSocket != null) { _webSocket?.send(buf.toByteString(0, 4 + len)) }
                if (last) closeBlockDownload()
            }
            "cancel" -> closeBlockDownload()
        }
    }

    private fun closeBlockDownload() {
        try { blockDownload?.stream?.close() } catch (ex: Exception) { }
        blockDownload = null
        blockDownloadId = null
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
