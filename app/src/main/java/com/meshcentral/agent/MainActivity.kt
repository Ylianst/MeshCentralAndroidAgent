package com.meshcentral.agent

import SelfSignedCertificate
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.iid.FirebaseInstanceId
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

var g_mainActivity:MainActivity? = null
var mainFragment:MainFragment? = null
var scannerFragment:ScannerFragment? = null
var webFragment:WebViewFragment? = null
var visibleScreen:Int = 1
var serverLink:String? = null
var meshAgent:MeshAgent? = null
var agentCertificate:X509Certificate? = null
var agentCertificateKey:PrivateKey? = null
var pageUrl:String? = null
var cameraPresent : Boolean = false
var pendingActivities : ArrayList<PendingActivityData> = ArrayList<PendingActivityData>()
var pushMessagingToken : String? = null

class MainActivity : AppCompatActivity() {
    var alert : AlertDialog? = null
    lateinit var notificationChannel: NotificationChannel
    lateinit var notificationManager: NotificationManager
    lateinit var builder: Notification.Builder

    override fun onCreate(savedInstanceState: Bundle?) {
        g_mainActivity = this
        val sharedPreferences = getSharedPreferences("meshagent", Context.MODE_PRIVATE)
        serverLink = sharedPreferences?.getString("qrmsh", null)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        // Setup notification manager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Register to get battery events
        val intentFilter = IntentFilter()
        intentFilter.addAction(Intent.ACTION_POWER_CONNECTED)
        intentFilter.addAction(Intent.ACTION_POWER_DISCONNECTED)
        intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryInfoReceiver, intentFilter)

        // Check if this device has a camera
        cameraPresent = applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)

        // Setup push notifications
        println("Asking for token")
        FirebaseInstanceId.getInstance().instanceId.addOnSuccessListener(this
        ) { instanceIdResult ->
            pushMessagingToken = instanceIdResult.token
            println("messagingToken: $pushMessagingToken")
        }

        // See if we there open by a notification with a URL
        if (intent.getStringExtra("url") != null) {
            var getintent : Intent = Intent(Intent.ACTION_VIEW, Uri.parse(intent.getStringExtra("url")));
            startActivity(getintent);
        }
    }

    private fun sendConsoleMessage(msg: String) {
        if (meshAgent != null) { meshAgent?.sendConsoleResponse(msg, null) }
    }

    private val batteryInfoReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (meshAgent != null) { meshAgent?.batteryStateChanged(intent) }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        var item1 = menu.findItem(R.id.action_setup_server);
        item1.isVisible = (visibleScreen == 1);
        item1.isEnabled = cameraPresent;
        var item2 = menu.findItem(R.id.action_clear_server);
        item2.isVisible = (visibleScreen == 1) && (serverLink != null);
        var item3 = menu.findItem(R.id.action_close);
        item3.isVisible = (visibleScreen != 1);
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.

        if (item.itemId == R.id.action_setup_server) {
            // Move to QR code reader if a camera is present
            if ((mainFragment != null) && cameraPresent) mainFragment?.moveToScanner()
        }

        if (item.itemId == R.id.action_clear_server) {
            // Remove the server
            confirmServerClear()
        }

        if (item.itemId == R.id.action_close) {
            // Close
            returnToMainScreen()
        }

        return when(item.itemId) {
            R.id.action_setup_server -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        g_mainActivity = null
        if (alert != null) {
            alert?.dismiss()
            alert = null
        }
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        println("onActivityResult, requestCode: $requestCode, resultCode: $resultCode, data: ${data.toString()}")
        super.onActivityResult(requestCode, resultCode, data)

        var pad : PendingActivityData? = null
        for (b in pendingActivities) { if (b.id == requestCode) { pad = b } }

        if (pad != null) {
            if (resultCode == Activity.RESULT_OK) {
                println("Approved: ${pad.url}, ${pad.where}, ${pad.args}")
                pad.tunnel.deleteFileEx(pad)
            } else {
                println("Denied: ${pad.url}, ${pad.where}, ${pad.args}")
                pad.tunnel.deleteFileEx(pad)
            }
            pendingActivities.remove(pad)
        }

    }

    fun setMeshServerLink(x: String?) {
        serverLink = x
        val sharedPreferences = getSharedPreferences("meshagent", Context.MODE_PRIVATE)
        sharedPreferences.edit().putString("qrmsh", x).apply()
        mainFragment?.refreshInfo()
    }

    // Open a URL in the web view fragment
    fun openUrl(xpageUrl: String) : Boolean {
        if (visibleScreen == 2) return false
        pageUrl = xpageUrl;
        if (visibleScreen == 1) {
            if (mainFragment != null) mainFragment?.moveToWebPage(xpageUrl)
        } else {
            this.runOnUiThread {
                if (webFragment != null) webFragment?.navigate(xpageUrl)
            }
        }
        return true
    }

    fun returnToMainScreen() {
        this.runOnUiThread {
            if (visibleScreen == 2) {
                if (scannerFragment != null) scannerFragment?.exit()
            } else if (visibleScreen == 3) {
                if (webFragment != null) webFragment?.exit()
            }
        }
    }

    fun refreshInfo() {
        this.runOnUiThread {
            if ((meshAgent != null) && (meshAgent?.state == 0)) {
                meshAgent = null
            }
            mainFragment?.refreshInfo()
        }
    }

    fun confirmServerClear() {
        if (alert != null) {
            alert?.dismiss()
            alert = null
        }
        val builder = AlertDialog.Builder(this)
        builder.setTitle("MeshCentral Server")
        builder.setMessage("Clear server setup?")
        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            this.setMeshServerLink(null)
        }
        builder.setNeutralButton(android.R.string.cancel) { _, _ -> }
        alert = builder.show()
    }

    fun showAlertMessage(title: String, msg: String) {
        if (alert != null) {
            alert?.dismiss()
            alert = null
        }
        this.runOnUiThread {
            val builder = AlertDialog.Builder(this)
            builder.setTitle(title)
            builder.setMessage(msg)
            builder.setPositiveButton(android.R.string.ok) { _, _ -> {} }
            alert = builder.show()
        }
    }

    fun showToastMessage(msg: String) {
        this.runOnUiThread {
            var toast = Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG)
            toast?.setGravity(Gravity.CENTER, 0, 300)
            toast?.show()
        }
    }

    fun getServerHost() : String? {
        if (serverLink == null) return null
        var x : List<String> = serverLink!!.split(',')
        var serverHost = x[0]
        return serverHost.substring(5)
    }

    fun getServerHash() : String? {
        if (serverLink == null) return null
        var x : List<String> = serverLink!!.split(',')
        return x[1]
    }

    fun getDevGroup() : String? {
        if (serverLink == null) return null
        var x : List<String> = serverLink!!.split(',')
        return x[2]
    }

    fun isAgentDisconnected() : Boolean {
        return (meshAgent == null)
    }

    fun toggleAgentConnection() {
        println("toggleAgentConnection")
        if ((meshAgent == null) && (serverLink != null)) {
            // Create and connect the agent
            if (agentCertificate == null) {
                val sharedPreferences = getSharedPreferences("meshagent", Context.MODE_PRIVATE)
                var certb64 = sharedPreferences?.getString("agentCert", null)
                var keyb64 = sharedPreferences?.getString("agentKey", null)
                if ((certb64 == null) || (keyb64 == null)) {
                    println("Generating new certificates...")
                    var ssc = SelfSignedCertificate(BuildConfig.APPLICATION_ID)
                    agentCertificate = ssc.cert()
                    agentCertificateKey = ssc.key()
                    sharedPreferences?.edit()?.putString("agentCert", ssc.certb64)?.apply()
                    sharedPreferences?.edit()?.putString("agentKey", ssc.keyb64)?.apply()
                } else {
                    //println("Loading certificates...")
                    agentCertificate = CertificateFactory.getInstance("X509").generateCertificate(
                            ByteArrayInputStream(Base64.decode(certb64, Base64.DEFAULT))
                    ) as X509Certificate
                    val keySpec = PKCS8EncodedKeySpec(Base64.decode(keyb64, Base64.DEFAULT))
                    agentCertificateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec)
                }
                //println("Cert: ${agentCertificate.toString()}")
                //println("XKey: ${agentCertificateKey.toString()}")
            }

            meshAgent = MeshAgent(this, getServerHost()!!, getServerHash()!!, getDevGroup()!!)
            meshAgent?.Start()
        } else if (meshAgent != null) {
            // Stop the agent
            meshAgent?.Stop()
            meshAgent = null
        }
        mainFragment?.refreshInfo()
    }

    fun showNotification(title: String?, body: String?, url: String?) {
        println("showNotification: $title, $body")

        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (url != null) { intent.putExtra("url", url!!); }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationChannel = NotificationChannel(getString(R.string.default_notification_channel_id), "MeshCentral Agent Channel", NotificationManager.IMPORTANCE_DEFAULT)
            notificationChannel.lightColor = Color.BLUE
            notificationChannel.enableVibration(true)
            notificationManager.createNotificationChannel(notificationChannel)
            builder = Notification.Builder(this, getString(com.meshcentral.agent.R.string.default_notification_channel_id))
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setSmallIcon(R.mipmap.ic_launcher)
                //.setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
                .setContentIntent(pendingIntent)
        }

        // Add notification
        notificationManager.notify(0, builder.build())
    }
}