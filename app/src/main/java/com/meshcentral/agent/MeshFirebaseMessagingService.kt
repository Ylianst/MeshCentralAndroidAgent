package com.meshcentral.agent

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread


class MeshFirebaseMessagingService : FirebaseMessagingService() {
    private val msgId = AtomicInteger()

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        pushMessagingToken = token
        //println("messagingToken: $token")

        // If we are connected to the server, update the push messaging token now.
        if (meshAgent != null) { meshAgent?.sendCoreInfo() }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        println("onMessageReceived-from: ${remoteMessage.from}")
        println("onMessageReceived-data: ${remoteMessage.data}")
        println("serverLink: $serverLink")

        super.onMessageReceived(remoteMessage)

        // If the server link is not set, see if we can load it
        if (serverLink == null) {
            val sharedPreferences = getSharedPreferences("meshagent", Context.MODE_PRIVATE)
            serverLink = sharedPreferences?.getString("qrmsh", null)
        }

        // The data is empty or the server is not set, bad notification.
        if ((remoteMessage.data == null) || (remoteMessage.data["shash"] == null) || (serverLink == null) || (remoteMessage.data["shash"]!!.length < 12)) return;

        // Check the server's agent hash against the notification.
        var x : List<String> = serverLink!!.split(',')
        if (!x[1].startsWith(remoteMessage.data["shash"]!!)) return;

        // Get the notification URL if one is present
        var url : String? = null
        if ((remoteMessage.data["url"] != null)) {
            url = remoteMessage.data["url"];
        }

        if ((url != null) && (url.startsWith("2fa://"))) {
            // Move to user authentication
            if (g_mainActivity != null) {
                g_mainActivity?.runOnUiThread {
                    g_auth_url = Uri.parse(url)
                    if (meshAgent == null) {
                        g_mainActivity?.toggleAgentConnection(false);
                    } else {
                        // Switch to 2FA auth screen
                        if (mainFragment != null) {
                            mainFragment?.moveToAuthPage()
                        }
                    }
                }
            }
        } else if (remoteMessage.notification != null) {
            if (g_mainActivity != null) {
                println("Showing notification with URL: $url");
                g_mainActivity?.showNotification(remoteMessage.notification?.title, remoteMessage.notification?.body, url)
            }
        } else if (remoteMessage.data != null) {
            var cmd : String? = remoteMessage.data["con"]
            var session : String? = remoteMessage.data["s"]
            var relayId : String? = remoteMessage.data["r"]
            if ((cmd != null) && (session != null)) { processConsoleMessage(cmd, session, relayId, remoteMessage.from!!) }
        }
    }

    fun sendMessage(to:String, cmd:String, session:String, relayId: String?) {
        println("sendMessage: $to, $cmd, $session")
        val fm = FirebaseMessaging.getInstance()

        var m = RemoteMessage.Builder("${to}@gcm.googleapis.com")
        m.setMessageId(msgId.incrementAndGet().toString())
        m.addData("con", cmd)
        m.addData("s", session)
        if (relayId != null) { m.addData("r", relayId) }
        fm.send(m.build())
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

    private fun processConsoleMessage(cmdLine: String, session: String, relayId: String?, to: String) {
        //println("Console: $cmdLine")

        // Parse the incoming console command
        var splitCmd = parseArgString(cmdLine)
        var cmd = splitCmd[0]
        var r: String? = null
        if (cmd == "") return

        when (cmd) {
            "help" -> {
                // Return the list of available console commands
                r = "Available commands: flash, netinfo, sysinfo, vibrate"
            }
            "sysinfo" -> {
                // System info
                r = getSysBuildInfo().toString(2)
            }
            "netinfo" -> {
                // Network info
                r = getNetInfo().toString(2)
            }
            "vibrate" -> {
                // Vibrate the device
                if (splitCmd.size < 2) {
                    r = "Usage:\r\n  vibrate [milliseconds]";
                } else if (g_mainActivity == null) {
                    r = "No main activity";
                } else if (splitCmd.size >= 2) {
                    var t : Long = 0
                    try { t = splitCmd[1].toLong() } catch (e : Exception) {}
                    if ((t > 0) && (t <= 10000)) {
                        val v = g_mainActivity!!.getApplicationContext()
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
                } else if (g_mainActivity == null) {
                    r = "No main activity";
                } else if (splitCmd.size >= 2) {
                    var isFlashAvailable = g_mainActivity!!.getApplicationContext().getPackageManager()
                            .hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT);
                    if (!isFlashAvailable) {
                        r = "Flash not available"
                    } else {
                        var t : Long = 0
                        try { t = splitCmd[1].toLong() } catch (e : Exception) {}
                        if ((t > 0) && (t <= 10000)) {
                            var mCameraManager = g_mainActivity!!.getApplicationContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
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

        if (r != null) { sendMessage(to, r, session, relayId) }
    }

    private fun getSysBuildInfo() : JSONObject {
        var r = JSONObject()
        r.put("board", android.os.Build.BOARD)
        r.put("bootloader", android.os.Build.BOOTLOADER)
        r.put("brand", android.os.Build.BRAND)
        r.put("device", android.os.Build.DEVICE)
        r.put("display", android.os.Build.DISPLAY)
        //r.put("fingerprint", android.os.Build.FINGERPRINT)
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

    private fun getNetInfo() : JSONObject {
        var r = JSONObject()
        for (n in NetworkInterface.getNetworkInterfaces()) {
            if (n.hardwareAddress != null) {
                var s = JSONArray()
                var count = 0
                for (j in n.interfaceAddresses) {
                    var mac = n.hardwareAddress.toHex().toUpperCase()
                    var mac2 = mac.substring(0,2) + ":" + mac.substring(2,4) + ":" + mac.substring(4,6) + ":" + mac.substring(6,8) + ":" + mac.substring(8,10) + ":" + mac.substring(10, 12)
                    var x = JSONObject()
                    x.put("address", j.address.hostAddress)
                    x.put("mac", mac2)
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
        }
        return r
    }

    fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }
}