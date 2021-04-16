package com.meshcentral.agent

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.iid.FirebaseInstanceId
import org.spongycastle.asn1.x500.X500Name
import org.spongycastle.cert.X509v3CertificateBuilder
import org.spongycastle.cert.jcajce.JcaX509CertificateConverter
import org.spongycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.spongycastle.jce.provider.BouncyCastleProvider
import org.spongycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*
import kotlin.collections.ArrayList

// User interface values
var g_mainActivity : MainActivity? = null
var mainFragment : MainFragment? = null
var scannerFragment : ScannerFragment? = null
var webFragment : WebViewFragment? = null
var authFragment : AuthFragment? = null
var visibleScreen : Int = 1

// Server connection values
var serverLink : String? = null
var meshAgent : MeshAgent? = null
var agentCertificate : X509Certificate? = null
var agentCertificateKey : PrivateKey? = null
var pageUrl : String? = null
var cameraPresent : Boolean = false
var pendingActivities : ArrayList<PendingActivityData> = ArrayList<PendingActivityData>()
var pushMessagingToken : String? = null

// Remote desktop values
var g_ScreenCaptureService : ScreenCaptureService? = null
var g_desktop_imageType : Int = 1
var g_desktop_compressionLevel : Int = 40
var g_desktop_scalingLevel : Int = 1024
var g_desktop_frameRateLimiter : Int = 100

// Two-factor authentication values
var g_auth_url : Uri? = null

class MainActivity : AppCompatActivity() {
    var alert : AlertDialog? = null
    lateinit var notificationChannel: NotificationChannel
    lateinit var notificationManager: NotificationManager
    lateinit var builder: Notification.Builder

    init {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

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
        //println("Asking for token")
        FirebaseInstanceId.getInstance().instanceId.addOnSuccessListener(this
        ) { instanceIdResult ->
            pushMessagingToken = instanceIdResult.token
            //println("messagingToken: $pushMessagingToken")
        }

        // See if we there open by a notification with a URL
        var intentUrl : String? = intent.getStringExtra("url")
        //println("Main Activity Create URL: $intentUrl")
        if (intentUrl != null) {
            intent.removeExtra("url")
            if (intentUrl.toLowerCase().startsWith("2fa://")) {
                // if there is no server link, ignore this
                if (serverLink != null) {
                    // This activity was created by a 2FA message
                    g_auth_url = Uri.parse(intentUrl)
                    // If not connected, connect to the server now.
                    if (meshAgent == null) {
                        toggleAgentConnection();
                    } else {
                        // Switch to 2FA auth screen
                        if (mainFragment != null) {
                            mainFragment?.moveToAuthPage()
                        }
                    }

                }
            } else if (intentUrl.toLowerCase().startsWith("http://") || intentUrl.toLowerCase().startsWith("https://")) {
                // Open an HTTP or HTTPS URL.
                var getintent: Intent = Intent(Intent.ACTION_VIEW, Uri.parse(intentUrl));
                startActivity(getintent);
            }
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
        var item4 = menu.findItem(R.id.action_sharescreen);
        item4.isVisible = false // (g_ScreenCaptureService == null) && (meshAgent != null) && (meshAgent!!.state == 3)
        var item5 = menu.findItem(R.id.action_stopscreensharing);
        item5.isVisible = (g_ScreenCaptureService != null)
        var item6 = menu.findItem(R.id.action_manual_setup_server);
        item6.isVisible = (visibleScreen == 1) && (serverLink == null)
        var item7 = menu.findItem(R.id.action_testAuth);
        item7.isVisible = false //(visibleScreen == 1) && (serverLink != null);
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

        if (item.itemId == R.id.action_sharescreen) {
            // Start projection
            startProjection()
        }

        if (item.itemId == R.id.action_stopscreensharing) {
            // Stop projection
            stopProjection()
        }

        if (item.itemId == R.id.action_manual_setup_server) {
            // Manually setup the server pairing
            promptForServerLink()
        }

        if (item.itemId == R.id.action_testAuth) {
            // Move to authentication screen
            if (mainFragment != null) mainFragment?.moveToAuthPage()
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

        if (requestCode == MainActivity.Companion.REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startService(com.meshcentral.agent.ScreenCaptureService.getStartIntent(this, resultCode, data))
                return
            }
        }

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
        if (serverLink == x) return
        if (meshAgent != null) { // Stop the agent
            meshAgent?.Stop()
            meshAgent = null
        }
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
            }else if (visibleScreen == 4) {
                if (authFragment != null) authFragment?.exit()
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
        //println("toggleAgentConnection")
        if ((meshAgent == null) && (serverLink != null)) {
            // Create and connect the agent
            if (agentCertificate == null) {
                val sharedPreferences = getSharedPreferences("meshagent", Context.MODE_PRIVATE)
                var certb64 : String? = sharedPreferences?.getString("agentCert", null)
                var keyb64 : String? = sharedPreferences?.getString("agentKey", null)
                if ((certb64 == null) || (keyb64 == null)) {
                    //println("Generating new certificates...")

                    // Generate an RSA key pair
                    val keyGen = KeyPairGenerator.getInstance("RSA")
                    keyGen.initialize(2048, SecureRandom())
                    val keypair = keyGen.generateKeyPair()

                    // Create self signed certificate
                    val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
                            X500Name("CN=android.agent.meshcentral.com"), // issuer authority
                            BigInteger.valueOf(Random().nextInt().toLong()), // serial number of certificate
                            Date(System.currentTimeMillis() - 86400000L * 365), // start of validity
                            Date(253402300799000L), // end of certificate validity
                            X500Name("CN=android.agent.meshcentral.com"), // subject name of certificate
                            keypair.public) // public key of certificate
                    agentCertificate = JcaX509CertificateConverter().setProvider("SC").getCertificate(builder
                            .build(JcaContentSignerBuilder("SHA256withRSA").build(keypair.private))) // Private key of signing authority , here it is self signed
                    agentCertificateKey = keypair.private

                    // Save the certificate and key
                    sharedPreferences?.edit()?.putString("agentCert", Base64.encodeToString(agentCertificate?.encoded, Base64.DEFAULT))?.apply()
                    sharedPreferences?.edit()?.putString("agentKey", Base64.encodeToString(agentCertificateKey?.encoded, Base64.DEFAULT))?.apply()
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
            stopProjection()
            meshAgent?.Stop()
            meshAgent = null
        }
        mainFragment?.refreshInfo()
    }

    fun showNotification(title: String?, body: String?, url: String?) {
        //println("showNotification: $title, $body")

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
                .setSmallIcon(R.drawable.ic_message)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                //.setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
                .setContentIntent(pendingIntent)
        }

        // Add notification
        notificationManager.notify(0, builder.build())
    }

    fun isMshStringValid(x: String):Boolean {
        if (x.startsWith("mc://") == false)  return false
        var xs = x.split(',')
        if (xs.count() < 3) return false
        if (xs[0].length < 8) return false
        if (xs[1].length < 3) return false
        if (xs[2].length < 3) return false
        if (xs[0].indexOf('.') == -1) return false
        return true
    }

    // Show alert asking for server pairing link
    fun promptForServerLink() {
        val builder: AlertDialog.Builder = AlertDialog.Builder(this)
        builder.setTitle("Server Pairing Link")

        // Set up the input
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        builder.setView(input)

        // Set up the buttons
        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            var link = input.text.toString()
            println("LINK: $link")
            if (isMshStringValid(link)) {
                setMeshServerLink(link)
            } else {
                indicateInvalidLink()
            }
        }
        builder.setNegativeButton(android.R.string.cancel) { dialog, which -> dialog.cancel() }
        builder.show()
    }

    // Show alert that server pairing link is invalid
    fun indicateInvalidLink() {
        val builder: AlertDialog.Builder = AlertDialog.Builder(this)
        builder.setTitle("Invalid Server Pairing Link")

        // Set up the buttons
        builder.setPositiveButton(android.R.string.ok) { dialog, which -> dialog.cancel() }
        builder.show()
    }

    // Start screen sharing
    fun startProjection() {
        if ((g_ScreenCaptureService != null) || (meshAgent == null) || (meshAgent!!.state != 3)) return
        if (meshAgent != null) {
            meshAgent!!.sendConsoleResponse("Asking for display consent", sessionid = null)
        }
        val mProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mProjectionManager.createScreenCaptureIntent(), MainActivity.Companion.REQUEST_CODE)
    }

    // Stop screen sharing
    fun stopProjection() {
        if (g_ScreenCaptureService == null) return
        startService(com.meshcentral.agent.ScreenCaptureService.getStopIntent(this))
    }

    companion object {
        private const val REQUEST_CODE = 100
    }
}