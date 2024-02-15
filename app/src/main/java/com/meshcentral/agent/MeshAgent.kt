package com.meshcentral.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Base64
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.random.Random


class MeshUserInfo(userid: String, realname: String?, image: Bitmap?) {
    val userid: String = userid
    val realname: String? = realname
    val image: Bitmap? = image
    init {
        println("MeshUserInfo: $userid, $realname")
    }
}

class MeshAgent(parent: MainActivity, host: String, certHash: String, devGroupId: String) : WebSocketListener() {
    val parent : MainActivity = parent
    val host : String = host
    val serverCertHash: String = certHash
    val devGroupId: String = devGroupId
    var state : Int = 0 // 0 = Disconnected, 1 = Connecting, 2 = Authenticating, 3 = Connected
    var nonce : ByteArray? = null
    var serverNonce: ByteArray? = null
    var serverTlsCertHash: ByteArray? = null
    var serverTitle : String? = null
    var serverSubTitle : String? = null
    var serverImage : Bitmap? = null
    private var _webSocket: WebSocket? = null
    private var connectionState: Int = 0
    private var connectionTimer: CountDownTimer? = null
    private var lastBattState : JSONObject? = null
    private var lastNetInfo : String? = null
    var tunnels : ArrayList<MeshTunnel> = ArrayList()
    var userinfo : HashMap<String, MeshUserInfo> = HashMap() // UserID -> MeshUserInfo

    init {
        //println("MeshAgent Constructor: ${host}, ${certHash}, $devGroupId")
    }

    fun Start() {
        //println("MeshAgent Start")
        UpdateState(1) // Switch to connecting
        startSocket()
    }

    fun Stop() {
        //println("MeshAgent Stop")
        stopSocket()
        UpdateState(0) // Switch to disconnected
    }

    fun UpdateState(newState: Int) {
        if (newState != state) {
            state = newState
            parent.agentStateChanged()
        }
    }

    fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }

    private fun getUnsafeOkHttpClient(): OkHttpClient {
        // Create a trust manager that does not validate certificate chains
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                serverTlsCertHash = MessageDigest.getInstance("SHA-384").digest(chain?.get(0)?.encoded)
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
            .hostnameVerifier(hostnameVerifier = HostnameVerifier { _, _ -> true })
            .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            .build()
    }


    fun startSocket() {
        _webSocket = getUnsafeOkHttpClient().newWebSocket(
                Request.Builder().url("wss://$host/agent.ashx").build(),
                this
        )
        //socketOkHttpClient.dispatcher.executorService.shutdown()
    }

    fun stopSocket() {
        // Disconnect and clear the control web socket
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
        // Clear all relay tunnels, create a mutable list since the list may change when calling Stop()
        var tunnelsClone : MutableList<MeshTunnel> = tunnels.toMutableList()
        for (t in tunnelsClone) { t.Stop() }
        tunnels.clear()
        // Update the state to disconnected
        UpdateState(0) // Switch to disconnected
    }

    companion object {
        const val NORMAL_CLOSURE_STATUS = 1000
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        //println("onOpen")
        UpdateState(2) // Switch to connected
        nonce = Random.Default.nextBytes(48)

        // Start authenticate the mesh agent by sending a auth nonce & server TLS cert hash.
        // Send 384 bits SHA384 hash of TLS cert public key + 384 bits nonce
        var header = ByteArray(2)
        header[1] = 1
        webSocket.send(header.plus(serverTlsCertHash!!).plus(nonce!!).toByteString()); // Command 1, hash + nonce
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        //println("onMessage: $text")
    }

    override fun onMessage(webSocket: WebSocket, msg: ByteString) {
        try {
            //println("onBinaryMessage: ${msg.size}, ${msg.toByteArray().toHex()}")
            if (msg.size < 2) return;
            if ((connectionState == 3) && (msg[0].toInt() == 123)) {
                // If we are authenticated, process JSON data
                processAgentData(String(msg.toByteArray(), Charsets.UTF_8))
                return
            }

            var cmd : Int = (msg[0].toInt() shl 8) + msg[1].toInt()
            //println("Cmd $cmd, Size: ${msg.size}")
            when (cmd) {
                1 -> {
                    // Server authentication request
                    if (msg.size != 98) return;
                    var serverCertHash = msg.substring(2, 50).toByteArray()
                    if (!serverCertHash.contentEquals(serverTlsCertHash!!)) {
                        println("Server Hash Mismatch, given=${serverCertHash.toHex()}, computed=${serverTlsCertHash?.toHex()}")
                        stopSocket()
                        return
                    }
                    serverNonce = msg.substring(50).toByteArray()

                    // Hash the server cert hash, server nonce and client nonce and sign the result
                    val sig = Signature.getInstance("SHA384withRSA")
                    sig.initSign(agentCertificateKey)
                    sig.update(msg.substring(2).toByteArray().plus(nonce!!))
                    val signature = sig.sign()

                    // Construct the response [2, sideOfCert, Cert, Signature]
                    var header = ByteArray(2)
                    header[1] = 2
                    var certLen = agentCertificate!!.encoded.size
                    var agentCertLenBytes = ByteArray(2)
                    agentCertLenBytes[0] = (certLen shr 8).toByte()
                    agentCertLenBytes[1] = (certLen and 0xFF).toByte()

                    // Send the response
                    webSocket.send(header.plus(agentCertLenBytes).plus(agentCertificate!!.encoded).plus(signature).toByteString())
                }
                2 -> {
                    // Server agent certificate
                    var xcertLen: Int = (msg[2].toUByte().toInt() shl 8) + msg[3].toUByte().toInt()
                    var xcertBytes = msg.substring(4, 4 + xcertLen)
                    var xagentCertificate = CertificateFactory.getInstance("X509").generateCertificate(
                            ByteArrayInputStream(xcertBytes.toByteArray())
                    ) as X509Certificate

                    // The private key DER encoding contains the private key type, we don't want
                    // that when hashing the private so we remove the first 24 bytes.
                    var pkey = xagentCertificate.publicKey as RSAPublicKey
                    var serverid = MessageDigest.getInstance("SHA-384").digest(pkey.encoded.toByteString().substring(24).toByteArray())
                    var serveridb64 = Base64.encodeToString(serverid, Base64.NO_WRAP)
                    serveridb64 = serveridb64.replace('/', '$').replace('+', '@')
                    // If invalid server certificate, disconnect
                    if (serveridb64.compareTo(serverCertHash) != 0) {
                        println("Invalid Server Certificate Hash"); stopSocket(); return
                    }

                    // Verify server signature
                    var signBlock: ByteArray? = serverTlsCertHash!!.plus(nonce!!).plus(serverNonce!!)
                    val sig = Signature.getInstance("SHA384withRSA")
                    sig.initVerify(xagentCertificate)
                    sig.update(signBlock)
                    if (!sig.verify(msg.substring(4 + xcertLen).toByteArray())) {
                        println("Invalid Server Signature"); stopSocket(); return
                    }

                    // Everything is ok, server is valid.
                    connectionState = connectionState or 1

                    //println("Host: ${android.os.Build.HOST}")
                    var agentid = 14;           // This of agent (14, Android in this case)
                    var agentver = 0            // Agent version (TODO)
                    var platfromType = 3;       // This is the icon: 1 = Desktop, 2 = Laptop, 3 = Mobile, 4 = Server, 5 = Disk, 6 = Router
                    var capabilities = 12;      // Capabilities of the agent (bitmask): 1 = Desktop, 2 = Terminal, 4 = Files, 8 = Console, 16 = JavaScript
                    var deviceName: String? = null;
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S) {
                       deviceName = Settings.Secure.getString(parent.contentResolver, "bluetooth_name");
                    }
                    if (deviceName == null) {
                        deviceName = Settings.Global.getString(parent.contentResolver, Settings.Global.DEVICE_NAME) ?: "UNKNOWN_DEVICE_NAME";
                    }
                    val deviceNameUtf = deviceName.toByteArray(Charsets.UTF_8)
                    //println("DeviceName: ${deviceName}")

                    var devGroupIdBytes: ByteArray = Base64.decode(devGroupId.replace('$', '/').replace('@', '+'), Base64.DEFAULT)

                    // Command 3: infover, agentid, agentversion, platformtype, meshid, capabilities, computername
                    var bytesOut = ByteArrayOutputStream()
                    DataOutputStream(bytesOut).use { dos ->
                        with(dos) {
                            writeShort(3)
                            writeInt(1)
                            writeInt(agentid)
                            writeInt(agentver)
                            writeInt(platfromType)
                            write(devGroupIdBytes)
                            writeInt(capabilities)
                            writeShort(deviceNameUtf.size)
                            write(deviceNameUtf)
                        }
                    }
                    webSocket.send(bytesOut.toByteArray().toByteString())
                    if (connectionState == 3) connectHandler()
                }
                4 -> {
                    // Server confirmed authentication, we are allowed to send commands to the server
                    connectionState = connectionState or 2
                    if (connectionState == 3) connectHandler()
                }
                else -> {
                    // Unknown command, ignore it.

                }
            }
        }
        catch (e: Exception) {
            println("Exception: ${e.toString()}")
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        //println("onClosing")
        stopSocket()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        println("onFailure ${t.toString()},  ${response.toString()}")
        stopSocket()
    }

    private fun connectHandler() {
        //println("Connected and verified")
        UpdateState(3) // Switch to connected and verified
        startConnectionTimer()
        sendCoreInfo()
        sendNetworkUpdate(false)
        sendServerImageRequest()

        // Send battery state
        if (_webSocket != null) { _webSocket?.send(getSysBatteryInfo().toString().toByteArray().toByteString()) }
    }

    // Cause some data to be sent over the websocket control channel every 2 minutes to keep it open
    private fun startConnectionTimer() {
        parent.runOnUiThread {
            connectionTimer = object: CountDownTimer(120000000, 120000) {
                override fun onTick(millisUntilFinished: Long) {
                    if (sendNetworkUpdate(false) == false) { // See if we need to update network information
                        if (_webSocket != null) {
                            _webSocket?.send(ByteArray(1).toByteString()) // If not, sent a single zero byte
                        }
                    }
                }
                override fun onFinish() { startConnectionTimer() }
            }
            connectionTimer?.start()
        }
    }

    private fun processAgentData(jsonStr: String) {
        //println("JSON: $jsonStr")
        try {
            val json = JSONObject(jsonStr)
            var action = json.getString("action")
            //println("action: $action")
            when (action) {
                "ping" -> {
                    // Return a pong
                    val r = JSONObject()
                    r.put("action", "pong")
                    if (_webSocket != null) {
                        _webSocket?.send(r.toString().toByteArray().toByteString())
                    }
                }
                "pong" -> {
                    // Nop
                }
                "sysinfo" -> {
                    //println("SysInfo")
                    val s = JSONObject()
                    s.put("mobile", getSysBuildInfo())
                    var identifiers = JSONObject()
                    identifiers.put("storage_devices", getStorageInfo())
                    s.put("identifiers", identifiers)
                    val t = JSONObject()
                    t.put("hardware", s)
                    var hash1 = MessageDigest.getInstance("SHA-384").digest(t.toString().toByteArray()).toHex()
                    var hash2: String? = null
                    if (json.isNull("hash") == false) {
                        hash2 = json.getString("hash")
                    }
                    if ((hash2 == null) || (hash1.contentEquals(hash2) == false)) {
                        t.put("hash", hash1)
                        val r = JSONObject()
                        r.put("action", "sysinfo")
                        r.put("data", t)
                        //println(r.toString())
                        if (_webSocket != null) {
                            _webSocket?.send(r.toString().toByteArray().toByteString())
                        }
                    }
                }
                "netinfo" -> {
                    sendNetworkUpdate(true)
                }
                "openUrl" -> {
                    /*
                    if (visibleScreen != 2) { // Device is busy in QR code scanner
                        // Open the URL
                        var xurl = json.optString("url")
                        //println("Opening: $xurl")
                        if ((xurl != null) && (parent.openUrl(xurl))) {
                            // Event to the server
                            var eventArgs = JSONArray()
                            eventArgs.put(xurl)
                            logServerEventEx(20, eventArgs, "Opening: ${xurl}", json);
                        }
                    }
                    */

                    var xurl = json.optString("url")
                    if (xurl != null) {
                        var getintent: Intent = Intent(Intent.ACTION_VIEW, Uri.parse(xurl));
                        parent.startActivity(getintent);
                    }
                }
                "msg" -> {
                    var msgtype = json.getString("type")
                    when (msgtype) {
                        "console" -> {
                            processConsoleMessage(json.getString("value"), json.getString("sessionid"), json)
                        }
                        "tunnel" -> {
                            /*
                            {"action":"msg",
                            "type":"tunnel",
                            "value":"*\/meshrelay.ashx?...",
                            "usage":5,
                            "servertlshash":"97eaf674eab131d3775f12cfa9c978d185a0e9caaf3a854bf0eb4ff94c2d6c53ca3dc456da149002804666fbbdae2fc9",
                            "soptions":{
                                "consentTitle":"MeshCentral",
                                "consentMsgDesktop":"{0} ({1}) requesting remote desktop access. Grant access?",
                                "consentMsgTerminal":"{0} ({1}) requesting remote terminal access. Grant access?",
                                "consentMsgFiles":"{0} ({1}) requesting remote files access. Grant access?",
                                "notifyTitle":"MeshCentral",
                                "notifyMsgDesktop":"{0} ({1}) started a remote desktop session.",
                                "notifyMsgTerminal":"{0} ({1}) started a remote terminal session.",
                                "notifyMsgFiles":"{0} ({1}) started a remote files session."},
                                "userid":"user//admin","perMessageDeflate":true,
                                "sessionid":"user//admin/1fc5a4e5ab144a721b852118de898e5f1af5b791",
                            "rights":4294967295,
                            "consent":0,
                            "username":"admin",
                            "remoteaddr":"192.168.2.182",
                            "privacybartext":"This is a test: {0} ({1})"}
                            */

                            var url = json.getString("value")
                            if (url.startsWith("*/")) {
                                var hostdns: String = host
                                var i = host.indexOf('/') // If the hostname includes an extra domain, remove it.
                                if (i > 0) {
                                    hostdns = host.substring(0, i); }
                                url = "wss://$hostdns" + url.substring(1)
                            }
                            val tunnel = MeshTunnel(this, url, json)
                            tunnels.add(tunnel)
                            tunnel.Start()
                        }
                        else -> {
                            // Unknown message type, ignore it.
                            println("Unhandled msg type: $msgtype")
                        }
                    }
                }
                "coredump" -> {
                    // Nop
                }
                "getcoredump" -> {
                    // Nop
                }
                "getUserImage" -> {
                    // User real name and optional image
                    var xuserid: String? = json.optString("userid")
                    var xrealname: String? = json.optString("realname")
                    var ximage: String? = json.optString("image")
                    var xuserImage: Bitmap? = null

                    if ((ximage != null) && (!ximage.startsWith("data:image/jpeg;base64,"))) {
                        ximage = null; }

                    if (ximage != null) {
                        try {
                            val imageBytes = android.util.Base64.decode(ximage.substring(23), 0)
                            xuserImage = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

                            // Round the image edges
                            val imageRounded = Bitmap.createBitmap(xuserImage.getWidth(), xuserImage.getHeight(), xuserImage.getConfig())
                            val canvas = Canvas(imageRounded)
                            val mpaint = Paint()
                            mpaint.setAntiAlias(true)
                            mpaint.setShader(BitmapShader(xuserImage, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
                            canvas.drawRoundRect(RectF(0F, 0F, xuserImage.getWidth().toFloat(), xuserImage.getHeight().toFloat()), 32F, 32F, mpaint) // Round Image Corner 100 100 100 100
                            xuserImage.recycle()
                            xuserImage = imageRounded
                        } catch (ex: java.lang.Exception) { }
                    }

                    if ((xuserid != null) && (xrealname != null)) {
                        var xuserinfo: MeshUserInfo = MeshUserInfo(xuserid, xrealname, xuserImage)
                        userinfo[xuserid] = xuserinfo
                    }

                    // Notify of user information change
                    parent.refreshInfo()
                }
                "getServerImage" -> {
                    // Server title and image
                    serverTitle = json.optString("title")
                    serverSubTitle = json.optString("subtitle")
                    var ximage: String? = json.optString("image")
                    if ((ximage != null) && (!ximage.startsWith("data:image/jpeg;base64,"))) { ximage = null; }

                    // Decode the image
                    if (ximage != null) {
                        try {
                            val imageBytes = android.util.Base64.decode(ximage.substring(23), 0)
                            serverImage =
                                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        } catch (ex: java.lang.Exception) { }
                    }

                    // Notify of user information change
                    if ((serverTitle != null) || (serverImage != null)) {
                        parent.refreshInfo()
                    }
                }
                else -> {
                    // Unknown command, ignore it.
                    println("Unhandled action: $action")
                }
            }
        }
        catch (e: Exception) {
            println("processAgentData Exception: ${e.toString()}")
        }
    }

    // Send the latest core information to the server
    fun sendCoreInfo() {
        val r = JSONObject()
        r.put("action", "coreinfo")
        r.put("value", "Android Agent v${BuildConfig.VERSION_NAME}")
        r.put("caps", 13) // Capability bitmask: 1 = Desktop, 2 = Terminal, 4 = Files, 8 = Console, 16 = JavaScript, 32 = Temporary Agent, 64 = Recovery Agent
        if (pushMessagingToken != null) { r.put("pmt", pushMessagingToken) }
        if (_webSocket != null) { _webSocket?.send(r.toString().toByteArray().toByteString()) }
    }

    // Send 2FA authentication URL and approval/reject back
    fun send2faAuth(url: Uri, approved: Boolean) {
        val r = JSONObject()
        r.put("action", "2faauth")
        r.put("url", url.toString())
        r.put("approved", approved)
        if (_webSocket != null) { _webSocket?.send(r.toString().toByteArray().toByteString()) }
    }

    // Request user image and real name if needed
    fun sendUserImageRequest(userid: String) {
        if (userinfo.containsKey(userid)) {
            parent.refreshInfo()
            return
        } else {
            userinfo[userid] = MeshUserInfo(userid, null, null)
            val r = JSONObject()
            r.put("action", "getUserImage")
            r.put("userid", userid)
            if (_webSocket != null) {
                _webSocket?.send(r.toString().toByteArray().toByteString())
            }
        }
    }

    // Request user image and real name if needed
    fun sendServerImageRequest() {
        val r = JSONObject()
        r.put("action", "getServerImage")
        r.put("agent", "android")
        if (_webSocket != null) {
            _webSocket?.send(r.toString().toByteArray().toByteString())
        }
    }

    fun removeTunnel(tunnel: MeshTunnel) {
        tunnels.remove(tunnel)
        parent.refreshInfo()
    }

    fun sendNetworkUpdate(force: Boolean) : Boolean {
        var netinfo = getNetInfo();
        if ((force == false) && (lastNetInfo != null)) {
            var netinfostr = netinfo.toString()
            if (lastNetInfo.equals(netinfostr)) return false
            lastNetInfo = netinfostr
        }
        if (force == true) { lastNetInfo = netinfo.toString() }
        lastNetInfo = netinfo.toString()
        val r = JSONObject()
        r.put("action", "netinfo")
        r.put("netif2", netinfo)
        if (_webSocket != null) {_webSocket?.send(r.toString().toByteArray().toByteString()); return true }
        return false
    }

    private fun getSysBuildInfo() : JSONObject {
        var r = JSONObject()
        r.put("board", android.os.Build.BOARD)
        r.put("bootloader", android.os.Build.BOOTLOADER)
        r.put("brand", android.os.Build.BRAND)
        r.put("device", android.os.Build.DEVICE)
        r.put("display", android.os.Build.DISPLAY)
        //r.put("fingerprint", android.os.Build.FINGERPRINT)
        r.put("androidapi", android.os.Build.VERSION.SDK_INT)
        r.put("androidrelease", android.os.Build.VERSION.RELEASE)
        r.put("host", android.os.Build.HOST)
        r.put("id", android.os.Build.ID)
        r.put("hardware", android.os.Build.HARDWARE)
        r.put("model", android.os.Build.MODEL)
        r.put("product", android.os.Build.PRODUCT)
        //r.put("supported_32_bit_abis", android.os.Build.SUPPORTED_32_BIT_ABIS)
        //r.put("supported_64_bit_abis", android.os.Build.SUPPORTED_64_BIT_ABIS)
        //r.put("supported_abis", android.os.Build.SUPPORTED_ABIS)
        r.put("tags", android.os.Build.TAGS)
        r.put("type", android.os.Build.TYPE)
        r.put("user", android.os.Build.USER)
        r.put("radioVersion", android.os.Build.getRadioVersion())
        return r;
    }

    private fun getStorageInfo() : JSONArray {
        var r = JSONArray()
        val internalStat = StatFs(Environment.getDataDirectory().path)
        val totalSpace = internalStat.blockCountLong * internalStat.blockSizeLong
        var onboard = JSONObject()
        onboard.put("Size", totalSpace)
        onboard.put("Caption", "Onboard Storage")
        onboard.put("Model", "Onboard Storage")
        r.put(onboard)
        return r
    }

    private fun getNetInfo() : JSONObject {
        var r = JSONObject()
        for (n in NetworkInterface.getNetworkInterfaces()) {
            var s = JSONArray()
            var count = 0
            for (j in n.interfaceAddresses) {
                var x = JSONObject()
                x.put("address", j.address.hostAddress)
                if (n.hardwareAddress != null) {
                    var mac = n.hardwareAddress.toHex().toUpperCase()
                    x.put("mac", mac.substring(0, 2) + ":" + mac.substring(2, 4) + ":" + mac.substring(4, 6) + ":" + mac.substring(6, 8) + ":" + mac.substring(8, 10) + ":" + mac.substring(10, 12))
                }
                if (n.isUp) {
                    x.put("status", "up")
                } else {
                    x.put("status", "down")
                }
                if (j.address.hostAddress.indexOf(':') >= 0) {
                    x.put("family", "IPv6")
                } else {
                    x.put("family", "IPv4")
                }
                x.put("index", n.index)
                s.put(x)
                count = count + 1
            }
            if (count > 0) { r.put(n.displayName, s) }
        }
        return r
    }

    fun batteryStateChanged(intent: Intent) {
        // Get the battery status, if it did not chance, don't send anything to the server
        var battState : JSONObject? = getSysBatteryInfo();
        if (battState != null) {
            if ((lastBattState != null) &&
                    ((lastBattState?.getInt("level") == battState.getInt("level"))
                            && (lastBattState?.getString("state")?.compareTo(battState.getString("state")) == 0))) return

            // Battery state changed, send update to the server
            lastBattState = battState
            if (_webSocket != null) { _webSocket?.send(battState.toString().toByteArray().toByteString()) }
        }
    }

    private fun getSysBatteryInfo() : JSONObject? {
        try {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                parent.applicationContext.registerReceiver(null, ifilter)
            }
            val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL

            val batteryPct: Float? = batteryStatus?.let { intent ->
                val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                level * 100 / scale.toFloat()
            }

            var r = JSONObject()
            r.put("action", "battery")
            if (isCharging) {
                r.put("state", "ac")
            } else {
                r.put("state", "dc")
            }
            r.put("level", batteryPct)
            return r;
        }
        catch (e: Exception) { }
        return null
    }

    private fun parseArgString(s: String) : List<String> {
        var r = ArrayList<String>()
        var acc : String = ""
        var q = false
        for (i in 0..(s.length - 1)) {
            var c = s[i]
            if ((c == ' ') && (q == false)) {
                if (acc.length > 0) { r.add(acc); acc = ""; }
            } else {
                if (c == '"') { q = !q; } else { acc += c; }
            }
        }
        if (acc.length > 0) { r.add(acc); }
        return r.toList()
    }

    private fun processConsoleMessage(cmdLine: String, sessionid: String, jsoncmd: JSONObject) {
        //println("Console: $cmdLine")

        // Parse the incoming console command
        var splitCmd = parseArgString(cmdLine)
        var cmd = splitCmd[0]
        var r : String? = null
        if (cmd == "") return

        // Log the incoming console command to the server
        var eventArgs = JSONArray()
        eventArgs.put(cmdLine)
        logServerEventEx(17, eventArgs, "Processing console command: $cmdLine", jsoncmd);

        when (cmd) {
            "help" -> {
                // Return the list of available console commands
                r = "Available commands: alert, battery, dial, flash, netinfo, openurl, openbrowser,\r\n  serverlog, sysinfo, storageinfo, toast, uiclose, uistate, vibrate"
            }
            "alert" -> {
                // Display alert message
                if (splitCmd.size < 2) {
                    r = "Usage:\r\n  alert \"Message\" \"Title\"";
                } else if (splitCmd.size == 2) {
                    // Event to the server
                    var eventArgs = JSONArray()
                    eventArgs.put("Alert")
                    eventArgs.put(splitCmd[1])
                    logServerEventEx(18, eventArgs, "Displaying message box, title=" + splitCmd[2] + ", message=" + splitCmd[1], jsoncmd);

                    // Show the alert
                    parent.showAlertMessage("Alert", splitCmd[1])
                    r = "Ok";
                } else if (splitCmd.size > 2) {
                    // Event to the server
                    var eventArgs = JSONArray()
                    eventArgs.put(splitCmd[2])
                    eventArgs.put(splitCmd[1])
                    logServerEventEx(18, eventArgs, "Displaying message box, title=" + splitCmd[2] + ", message=" + splitCmd[1], jsoncmd);

                    // Show the alert
                    parent.showAlertMessage(splitCmd[2], splitCmd[1])
                    r = "Ok";
                }
            }
            "toast" -> {
                // Display toast message
                if (splitCmd.size < 2) {
                    r = "Usage:\r\n  toast \"Message\"";
                } else if (splitCmd.size >= 2) {
                    parent.showToastMessage(splitCmd[1])

                    // Event to the server
                    var eventArgs = JSONArray()
                    eventArgs.put("None")
                    eventArgs.put(splitCmd[1])
                    logServerEventEx(26, eventArgs, "Displaying toast message, title=None, message=${splitCmd[1]}", jsoncmd);
                    r = "Ok";
                }
            }
            "dial" -> {
                if (splitCmd.size < 2) {
                    r = "Usage:\r\n  dial [phonenumber]";
                } else if (splitCmd.size >= 2) {
                    var getintent: Intent = Intent(Intent.ACTION_VIEW, Uri.parse("tel:${splitCmd[1]}"));
                    parent.startActivity(getintent);
                    r = "ok"
                }
            }
            "sysinfo" -> {
                // System info
                r = getSysBuildInfo().toString(2)
            }
            "storageinfo" -> {
                // Storage info
                r = getStorageInfo().toString(2)
            }
            "netinfo" -> {
                // Network info
                r = getNetInfo().toString(2)
            }
            "battery" -> {
                // Battery info
                var battState: JSONObject? = getSysBatteryInfo();
                if (battState == null) {
                    r = "No battery"
                } else {
                    r = battState.toString(2)
                }
            }
            "openbrowser" -> {
                // Open a URL
                if (splitCmd.size < 2) {
                    r = "Usage:\r\n  openbrowser \"url\"";
                } else if (splitCmd.size >= 2) {
                    if (splitCmd[1].startsWith("https://") || splitCmd[1].startsWith("http://")) {
                        if (visibleScreen == 2) {
                            r = "Device is busy in QR code scanner"
                        } else {
                            // Open the URL
                            try {
                                var getintent: Intent = Intent(Intent.ACTION_VIEW, Uri.parse(splitCmd[1]));
                                parent.startActivity(getintent);
                                r = "Ok"
                            } catch (ex: Exception) {
                                r = "Error opening: ${splitCmd[1]}"
                            }
                        }
                    } else {
                        r = "Url must start with http:// or https://"
                    }
                }
            }
            "openurl" -> {
                // Open a URL in the agent application
                if (splitCmd.size < 2) {
                    r = "Usage:\r\n  openurl \"url\"";
                } else if (splitCmd.size >= 2) {
                    if (splitCmd[1].startsWith("https://") || splitCmd[1].startsWith("http://")) {
                        if (visibleScreen == 2) {
                            r = "Device is busy in QR code scanner"
                        } else {
                            // Open the URL
                            if (parent.openUrl(splitCmd[1])) {
                                r = "Ok";

                                // Event to the server
                                var eventArgs = JSONArray()
                                eventArgs.put(splitCmd[1])
                                logServerEventEx(20, eventArgs, "Opening: ${splitCmd[1]}", jsoncmd);
                            } else {
                                r = "Busy";
                            }
                        }
                    } else {
                        r = "Url must start with http:// or https://"
                    }
                }
            }
            "uiclose" -> {
                if (visibleScreen == 1) {
                    r = "Application is at main screen"
                } else {
                    parent.returnToMainScreen()
                    r = "ok"
                }
            }
            "uistate" -> {
                if (visibleScreen == 1) {
                    r = "Application is at main screen"
                } else if (visibleScreen == 2) {
                    r = "Application is at QR code scanner screen"
                } else if (visibleScreen == 3) {
                    r = "Application is at browser screen: ${pageUrl}"
                } else {
                    r = "Application is in unknown screen ${visibleScreen}"
                }
            }
            "serverlog" -> {
                // Log an event to the server
                if (splitCmd.size < 2) {
                    r = "Usage:\r\n  serverlog \"event\"";
                } else if (splitCmd.size >= 2) {
                    logServerEvent(splitCmd[1], jsoncmd)
                    r = "ok"
                }
            }
            "kvmstart" -> {
                // Start remote desktop
                if (g_ScreenCaptureService == null) {
                    parent.startProjection()
                    r = "ok"
                } else {
                    r = "Already started"
                }
            }
            "kvmstop" -> {
                // Stop remote desktop
                if (g_ScreenCaptureService != null) {
                    parent.stopProjection()
                    r = "ok"
                } else {
                    r = "Already stopped"
                }
            }
            "vibrate" -> {
                // Vibrate the device
                if (splitCmd.size < 2) {
                    r = "Usage:\r\n  vibrate [milliseconds]";
                } else if (splitCmd.size >= 2) {
                    var t: Long = 0
                    try {
                        t = splitCmd[1].toLong()
                    } catch (e: Exception) {
                    }
                    if ((t > 0) && (t <= 10000)) {
                        val v = parent.getApplicationContext()
                                .getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                        if (v == null) {
                            r = "Not supported"
                        } else {
                            // Vibrate for 500 milliseconds
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                v.vibrate(
                                        VibrationEffect.createOneShot(
                                                t,
                                                VibrationEffect.DEFAULT_AMPLITUDE
                                        )
                                )
                            } else {
                                v.vibrate(t)
                            }
                            r = "ok"
                        }
                    } else {
                        r = "Value must be between 1 and 10000"
                    }
                }
            }
            "flash" -> {
                if (splitCmd.size < 2) {
                    r = "Usage:\r\n  flash [milliseconds]";
                } else if (splitCmd.size >= 2) {
                    var isFlashAvailable = parent.getApplicationContext().getPackageManager()
                            .hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT);
                    if (!isFlashAvailable) {
                        r = "Flash not available"
                    } else {
                        var t: Long = 0
                        try {
                            t = splitCmd[1].toLong()
                        } catch (e: Exception) {
                        }
                        if ((t > 0) && (t <= 10000)) {
                            var mCameraManager = parent.getApplicationContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
                            try {
                                var mCameraId = mCameraManager.getCameraIdList()[0];
                                mCameraManager.setTorchMode(mCameraId, true);
                                thread {
                                    Thread.sleep(t)
                                    mCameraManager.setTorchMode(mCameraId, false);
                                }
                                r = "ok"
                            } catch (e: CameraAccessException) {
                                r = "Flash error"
                            }
                        } else {
                            r = "Value must be between 1 and 10000"
                        }
                    }
                }
            }
            else -> {
                // Unknown console command
                r = "Unknown command \"$cmd\", type \"help\" for command list."
            }
        }

        if (r != null) sendConsoleResponse(r, sessionid)
    }

    fun sendConsoleResponse(r: String, sessionid: String?) {
        val json = JSONObject()
        json.put("action", "msg")
        json.put("type", "console")
        json.put("value", r)
        if (sessionid != null) { json.put("sessionid", sessionid) }
        if (_webSocket != null) { _webSocket?.send(json.toString().toByteArray().toByteString()) }
    }

    fun hexToByteArray(hex: String) : ByteArray {
        val HEX_CHARS = "0123456789abcdef"
        val len = hex.length
        val result = ByteArray(len / 2)
        (0 until len step 2).forEach { i ->
            result[i.shr(1)] = HEX_CHARS.indexOf(hex[i]).shl(4).or(HEX_CHARS.indexOf(hex[i + 1])).toByte()
        }
        return result
    }

    fun logServerEvent(msg: String, jsoncmd: JSONObject?) {
        val json = JSONObject()
        json.put("action", "log")
        json.put("msg", msg)
        if (jsoncmd != null) {
            if (!jsoncmd.isNull("userid")) { json.put("userid", jsoncmd.optString("userid")) }
            if (!jsoncmd.isNull("username")) { json.put("username", jsoncmd.optString("username")) }
            if (!jsoncmd.isNull("sessionid")) { json.put("sessionid", jsoncmd.optString("sessionid")) }
            if (!jsoncmd.isNull("remoteaddr")) { json.put("remoteaddr", jsoncmd.optString("remoteaddr")) }
            if (!jsoncmd.isNull("soptions")) {
                var soptions : JSONObject = jsoncmd.getJSONObject("soptions")
                if (!soptions.isNull("userid")) { json.put("userid", soptions.optString("userid")) }
                if (!soptions.isNull("sessionid")) { json.put("sessionid", soptions.optString("sessionid")) }
            }
        }
        if (_webSocket != null) { _webSocket?.send(json.toString().toByteArray().toByteString()) }
    }

    fun logServerEventEx(id: Int, args: JSONArray?, msg: String, jsoncmd: JSONObject?) {
        val json = JSONObject()
        json.put("action", "log")
        json.put("msgid", id)
        if (args != null) { json.put("msgArgs", args) }
        json.put("msg", msg)
        if (jsoncmd != null) {
            if (!jsoncmd.isNull("userid")) { json.put("userid", jsoncmd.optString("userid")) }
            if (!jsoncmd.isNull("username")) { json.put("username", jsoncmd.optString("username")) }
            if (!jsoncmd.isNull("sessionid")) { json.put("sessionid", jsoncmd.optString("sessionid")) }
            if (!jsoncmd.isNull("remoteaddr")) { json.put("remoteaddr", jsoncmd.optString("remoteaddr")) }
            if (!jsoncmd.isNull("soptions")) {
                var soptions : JSONObject = jsoncmd.getJSONObject("soptions")
                if (!soptions.isNull("userid")) { json.put("userid", soptions.optString("userid")) }
                if (!soptions.isNull("sessionid")) { json.put("sessionid", soptions.optString("sessionid")) }
            }
        }
        if (_webSocket != null) { _webSocket?.send(json.toString().toByteArray().toByteString()) }
    }

}
