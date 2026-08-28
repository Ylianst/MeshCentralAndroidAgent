package com.meshcentral.agent

//import com.google.firebase.iid.FirebaseInstanceId
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.security.PrivateKey
import java.security.cert.X509Certificate


// You can hardcode a server connection string into this application by setting this string.
// Make sure to replace all $ with \$ if your link string contains the $ character
// Once set, the resulting APK will be hard coded and users can't unset this value.
val hardCodedServerLink : String? = null
//val hardCodedServerLink : String? = "mc://central.mesh.meshcentral.com,2ZNi1e2Lrqi\$nnQ7NLJCJWNwxGD9ZstiNzxs\$LIE1tcHQD45bPDvbcKzpC9zUTX9,7b4b43cdad850135f36ab31124b52e47c167fba055ce800267a4dc89fe0e581c"

// User interface values
var g_mainActivity : MainActivity? = null
var mainFragment : MainFragment? = null
var scannerFragment : ScannerFragment? = null
@SuppressLint("StaticFieldLeak")
var webFragment : WebViewFragment? = null
var authFragment : AuthFragment? = null
var settingsFragment: SettingsFragment? = null
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
var g_autoConnect : Boolean = true
var g_autoConsent : Boolean = false
var g_sessionNotification : Boolean = false
var g_userDisconnect : Boolean = false // Indicate user initiated disconnection

// Remote desktop values
var g_ScreenCaptureService : ScreenCaptureService? = null
var g_remoteDesktopProvider : RemoteDesktopProvider? = null
var g_desktop_imageType : Int = 1
var g_desktop_compressionLevel : Int = 40
var g_desktop_scalingLevel : Int = 1024
var g_desktop_frameRateLimiter : Int = 100

// Two-factor authentication values
var g_auth_url : Uri? = null

class MainActivity : AppCompatActivity() {
    var alert : AlertDialog? = null
    // Set when the user taps "Later" on the unattended setup prompt; suppresses it for this session
    // only, so it returns on the next launch/resume while items are still missing.
    private var unattendedPromptDismissed = false
    lateinit var notificationChannel: NotificationChannel
    lateinit var notificationManager: NotificationManager
    lateinit var builder: Notification.Builder
    private var pendingConnectionUserInitiated: Boolean? = null
    private var localNetworkPermissionRequested = false

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            ContextCompat.startForegroundService(this, ScreenCaptureService.getStartIntent(this, result.resultCode, result.data))
            AgentController.activeDesktopTunnel()?.sendCtrlResponse(JSONObject().apply {
                put("type", "console")
                put("msg", null)
                put("msgid", 0)
            })
        } else {
            AgentController.activeDesktopTunnel()?.let { tunnel ->
                tunnel.sendCtrlResponse(JSONObject().apply {
                    put("type", "console")
                    put("msg", "denied")
                    put("msgid", 2)
                })
                tunnel.Stop()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentController.attachActivity(this)
        setContentView(R.layout.activity_main)

        //var toolbar = g_mainActivity?.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(findViewById(R.id.toolbar))

        // Setup notification manager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Check if this device has a camera
        cameraPresent = applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

        //val fcmId = FirebaseInstallations.getInstance().id
        val fcmToken = FirebaseMessaging.getInstance().token
        fcmToken.addOnSuccessListener(this) { tokenString ->
            pushMessagingToken = tokenString
        }

        /*
        FirebaseInstanceId.getInstance().instanceId.addOnSuccessListener(this
        ) { instanceIdResult ->
            pushMessagingToken = instanceIdResult.token
            //println("messagingToken: $pushMessagingToken")
        }
        */

        handleIntentUrl(intent)

        // Activate the settings
        settingsChanged()
        if (serverLink != null) {
            requestAllPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        if (serverLink != null) {
            window.decorView.post { showUnattendedSetupPromptIfNeeded(false) }
            // Retry a session that connected while backgrounded and is waiting to prompt for consent.
            if (AgentController.hasActiveDesktopTunnel() && !AgentController.isRemoteDesktopRunning()) {
                AgentController.startProjection()
            }
        }
        invalidateOptionsMenu()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentUrl(intent)
    }

    private fun handleIntentUrl(intent: Intent?) {
        val intentUrl: String = intent?.getStringExtra("url") ?: return
        intent.removeExtra("url")
        if (intentUrl.lowercase().startsWith("2fa://")) {
            if (serverLink != null) {
                g_auth_url = Uri.parse(intentUrl)
                if (meshAgent == null) {
                    toggleAgentConnection(false)
                } else {
                    if (mainFragment != null) {
                        mainFragment?.moveToAuthPage()
                    }
                }
            }
        } else if (intentUrl.lowercase().startsWith("http://") || intentUrl.lowercase().startsWith("https://")) {
            val getintent = Intent(Intent.ACTION_VIEW, Uri.parse(intentUrl))
            startActivity(getintent)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        var item1 = menu.findItem(R.id.action_setup_server);
        item1.isVisible = (visibleScreen == 1) && (hardCodedServerLink == null);
        item1.isEnabled = cameraPresent;
        var item2 = menu.findItem(R.id.action_clear_server);
        item2.isVisible = (visibleScreen == 1) && (serverLink != null) && (hardCodedServerLink == null);
        var item3 = menu.findItem(R.id.action_close);
        item3.isVisible = (visibleScreen != 1);
        var item4 = menu.findItem(R.id.action_sharescreen);
        item4.isVisible = false
        var item5 = menu.findItem(R.id.action_stopscreensharing);
        item5.isVisible = AgentController.isRemoteDesktopRunning()
        var item6 = menu.findItem(R.id.action_manual_setup_server);
        item6.isVisible = (visibleScreen == 1) && (serverLink == null) && (hardCodedServerLink == null)
        var item7 = menu.findItem(R.id.action_testAuth);
        item7.isVisible = false //(visibleScreen == 1) && (serverLink != null);
        var item8 = menu.findItem(R.id.action_settings);
        item8.isVisible = (visibleScreen == 1);
        var itemCheckSetup = menu.findItem(R.id.action_check_setup);
        itemCheckSetup.isVisible = (visibleScreen == 1) && (serverLink != null);
        var item9 = menu.findItem(R.id.action_enablepushauthentication);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            item9.isVisible = (notificationManager.areNotificationsEnabled() == false)
        } else {
            item9.isVisible = false
        }
        return true
    }

    @SuppressLint("InlinedApi")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.

        if ((item.itemId == R.id.action_setup_server) && (hardCodedServerLink == null)) {
            // Move to QR code reader if a camera is present
            if ((mainFragment != null) && cameraPresent) mainFragment?.moveToScanner()
        }

        if ((item.itemId == R.id.action_clear_server) && (hardCodedServerLink == null)) {
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
            AgentController.stopScreenSharingByUser()
        }

        if (item.itemId == R.id.action_check_setup) {
            showUnattendedSetupPromptIfNeeded(true)
        }

        if ((item.itemId == R.id.action_manual_setup_server) && (hardCodedServerLink == null)) {
            // Manually setup the server pairing
            promptForServerLink()
        }

        if (item.itemId == R.id.action_testAuth) {
            // Move to authentication screen
            if (mainFragment != null) mainFragment?.moveToAuthPage()
        }

        if (item.itemId == R.id.action_settings) {
            // Move to settings screen
            if (mainFragment != null) mainFragment?.moveToSettingsPage()
        }

        if (item.itemId == R.id.action_enablepushauthentication) {
            // Ask to Enable Push Notifications for Push Authentication
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            }
            startActivity(intent)
        }

        return when(item.itemId) {
            R.id.action_setup_server -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        AgentController.detachActivity(this)
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
        AgentController.setMeshServerLink(x)
        if (x != null) {
            requestAllPermissions()
            window.decorView.post { showUnattendedSetupPromptIfNeeded(true) }
        }
    }

    // Open a URL in the web view fragment
    fun openUrlInApp(xpageUrl: String) : Boolean {
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
            } else if (visibleScreen == 4) {
                if (authFragment != null) authFragment?.exit()
            } else if (visibleScreen == 5) {
                if (settingsFragment != null) settingsFragment?.exit()
            }
        }
    }

    fun confirmServerClear() {
        if (hardCodedServerLink != null) return
        if (alert != null) {
            alert?.dismiss()
            alert = null
        }
        val builder = AlertDialog.Builder(this)
        builder.setTitle("MeshCentral Server")
        builder.setMessage(getString(R.string.clear_server_setup))
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
            if (isFinishing || isDestroyed) return@runOnUiThread
            val builder = AlertDialog.Builder(this)
            builder.setTitle(title)
            builder.setMessage(msg)
            builder.setPositiveButton(android.R.string.ok) { _, _ -> run {} }
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

    fun isAgentDisconnected() : Boolean {
        return (meshAgent == null)
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf<String>()

        // Check and add notification permission if necessary
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_DENIED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Check and add external storage permissions if necessary
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_DENIED) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_DENIED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_DENIED) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        }

        // Request all permissions at once if there are any to request
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_ALL_PERMISSIONS)
        }
    }

    private fun showUnattendedSetupPromptIfNeeded(force: Boolean) {
        if (serverLink == null || isFinishing || isDestroyed) return
        val missingItems = ArrayList<String>()
        if (!AgentController.isAccessibilityServiceEnabled()) {
            missingItems.add(getString(R.string.missing_accessibility))
        }
        if (!AgentController.isIgnoringBatteryOptimizations()) {
            missingItems.add(getString(R.string.missing_battery))
        }
        if (!AgentController.areNotificationsEnabled()) {
            missingItems.add(getString(R.string.missing_notifications))
        }
        if (missingItems.isEmpty()) {
            if (force) showToastMessage(getString(R.string.unattended_setup_complete))
            return
        }

        // A manual re-open always shows the prompt and clears any earlier dismissal; an automatic
        // check stays hidden if the user already dismissed it this session.
        if (force) {
            unattendedPromptDismissed = false
        } else if (unattendedPromptDismissed) {
            return
        }

        if (alert != null) {
            alert?.dismiss()
            alert = null
        }
        val missingText = missingItems.joinToString(separator = "\n") { "- $it" }
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.unattended_setup_title))
            .setMessage(getString(R.string.unattended_setup_message, BuildConfig.VERSION_NAME, missingText))
            .setPositiveButton(R.string.open_accessibility_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton(R.string.open_app_settings) { _, _ ->
                mainFragment?.moveToSettingsPage()
            }
            .setNegativeButton(R.string.later) { dialog, _ ->
                unattendedPromptDismissed = true
                dialog.dismiss()
            }
        alert = builder.show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCAL_NETWORK_PERMISSION) {
            val pendingConnection = pendingConnectionUserInitiated
            pendingConnectionUserInitiated = null
            if (pendingConnection != null && meshAgent == null && serverLink != null) {
                AgentForegroundService.start(this)
                AgentController.toggleAgentConnection(pendingConnection)
                mainFragment?.refreshInfo()
            }
        }
    }

    private fun hasLocalNetworkPermission(): Boolean {
        return Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_LOCAL_NETWORK
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun toggleAgentConnection(userInitiated : Boolean) {
        requestAllPermissions()
        if ((meshAgent == null) && (serverLink != null)) {
            if (!hasLocalNetworkPermission() && pendingConnectionUserInitiated != null) return
            if (!hasLocalNetworkPermission() && (!localNetworkPermissionRequested || userInitiated)) {
                pendingConnectionUserInitiated = userInitiated
                localNetworkPermissionRequested = true
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK),
                    REQUEST_LOCAL_NETWORK_PERMISSION
                )
                return
            }
        }
        AgentForegroundService.start(this)
        AgentController.toggleAgentConnection(userInitiated)
    }

    fun showNotification(title: String?, body: String?, url: String?) {
        //println("showNotification: $title, $body")

        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (url != null) { intent.putExtra("url", url); }
        val pendingIntent: PendingIntent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pendingIntent =
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            pendingIntent =
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

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
        } else {
            builder = Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_message)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
        }

        // Add notification
        try {
            notificationManager.notify(0, builder.build())
        } catch (_: SecurityException) {
        }
    }

    // Show alert asking for server pairing link
    fun promptForServerLink() {
        if (hardCodedServerLink != null) return
        val builder: AlertDialog.Builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.server_pairing_link))

        // Set up the input
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        builder.setView(input)

        // Set up the buttons
        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            var link = input.text.toString()
            println("LINK: $link")
            if (isMeshServerLinkValid(link)) {
                setMeshServerLink(link)
            } else {
                indicateInvalidLink()
            }
        }
        builder.setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    // Show alert that server pairing link is invalid
    fun indicateInvalidLink() {
        val builder: AlertDialog.Builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.invalid_server_pairing_link))

        // Set up the buttons
        builder.setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    fun startProjection() {
        AgentController.startProjection()
    }

    fun startMediaProjectionPrompt() {
        if (AgentController.isRemoteDesktopRunning() || (meshAgent == null) || (meshAgent!!.state != 3)) return
        val mProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mProjectionManager.createScreenCaptureIntent())
    }

    fun promptScreenShareChoice() {
        if (AgentController.isRemoteDesktopRunning() || (meshAgent == null) || (meshAgent!!.state != 3)) return
        if (isFinishing || isDestroyed) return
        if (alert != null) {
            alert?.dismiss()
            alert = null
        }
        alert = AlertDialog.Builder(this)
            .setTitle(R.string.share_screen_choice_title)
            .setMessage(R.string.share_screen_choice_message)
            .setPositiveButton(R.string.open_accessibility_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton(R.string.share_screen_once) { _, _ ->
                startMediaProjectionPrompt()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                sendDesktopConsentDenied()
                dialog.dismiss()
            }
            .show()
    }

    // Per-connection consent prompt shown when Automatic Consent is off.
    fun promptUnattendedConsent() {
        if (AgentController.isRemoteDesktopRunning() || (meshAgent == null) || (meshAgent!!.state != 3)) return
        if (isFinishing || isDestroyed) return
        if (alert != null) {
            alert?.dismiss()
            alert = null
        }
        alert = AlertDialog.Builder(this)
            .setTitle(R.string.share_screen_choice_title)
            .setMessage(R.string.unattended_consent_message)
            .setPositiveButton(R.string.share_screen_once) { _, _ ->
                AgentController.confirmUnattendedConsent()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                sendDesktopConsentDenied()
                dialog.dismiss()
            }
            .show()
    }

    private fun sendDesktopConsentDenied() {
        AgentController.denyUnattendedConsent()
    }

    fun stopProjection() {
        AgentController.stopProjection()
    }

    fun settingsChanged() {
        AgentController.settingsChanged()
    }

    companion object {
        const val REQUEST_ALL_PERMISSIONS = 1
        const val REQUEST_LOCAL_NETWORK_PERMISSION = 2
    }
}
