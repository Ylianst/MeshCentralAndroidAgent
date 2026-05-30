package com.meshcentral.agent

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.firebase.messaging.FirebaseMessaging
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.spongycastle.asn1.x500.X500Name
import org.spongycastle.cert.X509v3CertificateBuilder
import org.spongycastle.cert.jcajce.JcaX509CertificateConverter
import org.spongycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.spongycastle.jce.provider.BouncyCastleProvider
import org.spongycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random
import kotlin.math.absoluteValue

interface AgentHost {
    val contentResolver: ContentResolver
    fun getApplicationContext(): Context
    fun runOnHostThread(action: () -> Unit)
    fun agentStateChanged()
    fun refreshInfo()
    fun startProjection()
    fun stopProjection()
    fun showAlertMessage(title: String, msg: String)
    fun showToastMessage(msg: String)
    fun openUrl(xpageUrl: String): Boolean
    fun returnToMainScreen()
    fun startActivity(intent: Intent)
    fun launchIntentSenderForResult(
        intentSender: IntentSender,
        requestCode: Int,
        fillInIntent: Intent?,
        flagsMask: Int,
        flagsValues: Int,
        extraFlags: Int,
        options: Bundle?
    ): Boolean
}

interface RemoteDesktopProvider {
    val isRunning: Boolean
    val width: Int
    val height: Int
    fun requestFullFrame()
    fun handleMouseCommand(msg: ByteString): Boolean = false
    fun handleTouchCommand(msg: ByteString): Boolean = false
    fun handleKeyCommand(cmd: Int, msg: ByteString): Boolean = false
}

object AgentController : AgentHost {
    private lateinit var appContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())
    private var initialized = false
    private var activity: MainActivity? = null
    private var service: AgentForegroundService? = null
    private var retryRunnable: Runnable? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var projectionRetryRunnable: Runnable? = null
    private var projectionRetryCount = 0
    private val MAX_PROJECTION_RETRIES = 12

    val enterpriseEnforced: Boolean
        get() = BuildConfig.ENTERPRISE_ENFORCED

    override val contentResolver: ContentResolver
        get() = appContext.contentResolver

    override fun getApplicationContext(): Context = appContext.applicationContext

    fun init(context: Context) {
        ensureCryptoProvider()
        appContext = context.applicationContext
        loadServerLink()
        loadSettings()
        loadFirebaseToken()
        if (!initialized) {
            initialized = true
            registerBatteryReceiver()
        }
    }

    fun attachActivity(mainActivity: MainActivity) {
        init(mainActivity.applicationContext)
        activity = mainActivity
        g_mainActivity = mainActivity
        refreshInfo()
    }

    fun detachActivity(mainActivity: MainActivity) {
        if (activity === mainActivity) {
            activity = null
            g_mainActivity = null
        }
    }

    fun attachService(agentService: AgentForegroundService) {
        init(agentService.applicationContext)
        service = agentService
    }

    fun detachService(agentService: AgentForegroundService) {
        if (service === agentService) {
            service = null
        }
    }

    fun hasServerLink(): Boolean {
        loadServerLink()
        return serverLink != null
    }

    fun shouldAutoStart(): Boolean {
        loadServerLink()
        loadSettings()
        return serverLink != null && (enterpriseEnforced || g_autoConnect)
    }

    fun setMeshServerLink(x: String?) {
        if ((serverLink == x) || (hardCodedServerLink != null)) return
        if (meshAgent != null) {
            meshAgent?.Stop()
            meshAgent = null
        }
        serverLink = x
        val sharedPreferences = appContext.getSharedPreferences("meshagent", Context.MODE_PRIVATE)
        sharedPreferences.edit().putString("qrmsh", x).apply()

        if (x != null) {
            PreferenceManager.getDefaultSharedPreferences(appContext)
                .edit()
                .putBoolean("pref_autoconnect", true)
                .apply()
            g_autoConnect = true
            AgentForegroundService.start(appContext)
            requestBatteryOptimizationExemption()
        } else {
            stopProjection()
            service?.stopSelf()
        }

        g_userDisconnect = false
        refreshInfo()
        if (g_autoConnect || enterpriseEnforced) {
            toggleAgentConnection(false)
        }
    }

    fun settingsChanged() {
        loadSettings()
        if (!enterpriseEnforced && !g_autoConnect) {
            stopRetryTimer()
            refreshInfo()
            service?.updateNotification()
            return
        }
        if ((meshAgent == null) && !g_userDisconnect && hasServerLink()) {
            AgentForegroundService.start(appContext)
            toggleAgentConnection(false)
        }
        refreshInfo()
        service?.updateNotification()
    }

    fun toggleAgentConnection(userInitiated: Boolean) {
        loadServerLink()
        loadSettings()
        if ((meshAgent == null) && (serverLink != null)) {
            ensureIdentity()
            if (!userInitiated) {
                g_userDisconnect = false
                startAgent()
            } else {
                if (g_autoConnect || enterpriseEnforced) {
                    if (g_userDisconnect) {
                        g_userDisconnect = false
                        startAgent()
                    } else {
                        g_userDisconnect = true
                        stopRetryTimer()
                    }
                } else {
                    g_userDisconnect = true
                    startAgent()
                }
            }
        } else if (meshAgent != null) {
            if (userInitiated && !enterpriseEnforced) {
                g_userDisconnect = true
            }
            stopProjection()
            meshAgent?.Stop()
            meshAgent = null
            stopRetryTimer()
        }
        refreshInfo()
        service?.updateNotification()
    }

    private fun startAgent() {
        val host = getServerHost() ?: return
        val hash = getServerHash() ?: return
        val group = getDevGroup() ?: return
        meshAgent = MeshAgent(this, host, hash, group)
        meshAgent?.Start()
    }

    override fun agentStateChanged() {
        runOnHostThread {
            if ((meshAgent != null) && (meshAgent?.state == 0)) {
                meshAgent = null
            }
            if (((meshAgent != null) && (meshAgent?.state != 0)) || g_userDisconnect || (!g_autoConnect && !enterpriseEnforced)) {
                stopRetryTimer()
            } else if ((meshAgent == null) && !g_userDisconnect && (g_autoConnect || enterpriseEnforced) && retryRunnable == null) {
                startRetryTimer()
            }
            refreshInfo()
            service?.updateNotification()
        }
    }

    override fun refreshInfo() {
        runOnHostThread {
            mainFragment?.refreshInfo()
            activity?.invalidateOptionsMenu()
            service?.updateNotification()
        }
    }

    override fun runOnHostThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post { action() }
        }
    }

    override fun startProjection() {
        if (meshAgent == null || meshAgent?.state != 3) return
        if (isRemoteDesktopRunning()) return
        val accessibility = MeshAccessibilityService.instance
        if (accessibility != null) {
            cancelProjectionRetry()
            if (accessibility.startDesktop()) return
        } else if (isAccessibilityServiceEnabled() && waitForAccessibilityProjection()) {
            // Unattended access is granted but the accessibility service has not rebound yet (common
            // right after an app update). Wait for it to connect instead of reporting setup as missing.
            return
        }
        cancelProjectionRetry()

        val mainActivity = activity
        if (mainActivity != null) {
            if (isAccessibilityServiceEnabled()) {
                // Accessibility is granted but not currently usable (e.g. pre-Android 11, or it
                // failed to bind); legacy screen capture is the only remaining option.
                mainActivity.startMediaProjectionPrompt()
            } else {
                // Don't auto-pop Android's screen-capture consent. Offer Accessibility setup first
                // and make legacy capture an explicit opt-in choice.
                mainActivity.promptScreenShareChoice()
            }
            return
        }

        sendDesktopMessage("Remote desktop requires Accessibility unattended access or an open app screen for Android capture consent.")
        showToastMessage("Enable unattended access in settings to share the screen in the background.")
        showRuntimeNotification(
            appContext.getString(R.string.unattended_access_required),
            appContext.getString(R.string.open_app_to_finish_unattended_setup),
            null
        )
    }

    override fun stopProjection() {
        val provider = g_remoteDesktopProvider
        if (provider is MeshAccessibilityService) {
            provider.stopDesktop()
        }
        if (g_ScreenCaptureService != null) {
            appContext.startService(ScreenCaptureService.getStopIntent(appContext))
        }
    }

    fun stopScreenSharingByUser() {
        val agent = meshAgent
        if (agent != null) {
            // Snapshot with filter() so closing tunnels (which mutates the list) is safe to iterate.
            val desktopTunnels = agent.tunnels.filter { (it.state == 2) && (it.usage == 2) }
            for (t in desktopTunnels) t.Stop()
        }
        if (isRemoteDesktopRunning()) stopProjection()
        refreshInfo()
    }

    fun isRemoteDesktopRunning(): Boolean {
        return g_remoteDesktopProvider?.isRunning == true || g_ScreenCaptureService != null
    }

    fun hasActiveDesktopTunnel(): Boolean {
        val agent = meshAgent ?: return false
        return agent.tunnels.any { (it.state == 2) && (it.usage == 2) }
    }

    // Display names of the remote users with an active session (desktop or files), de-duplicated.
    // File-transfer sub-tunnels (usage 10) are ignored so downloads don't flicker the notification.
    fun activeSessionUsers(): List<String> {
        val agent = meshAgent ?: return emptyList()
        val names = LinkedHashSet<String>()
        for (t in agent.tunnels.toList()) {
            if (t.state != 2 || t.usage == 10) continue
            val sessionUser = t.sessionUserName2
            if (sessionUser.isNullOrEmpty()) continue
            names.add(friendlySessionName(agent, sessionUser))
        }
        return names.toList()
    }

    private fun friendlySessionName(agent: MeshAgent, sessionUserName2: String): String {
        return try {
            val parts = sessionUserName2.split("/")
            if (parts.size >= 3) {
                val userid = parts[0] + "/" + parts[1] + "/" + parts[2]
                val guest = if (parts.size >= 4) " - " + parts[3] else ""
                (agent.userinfo[userid]?.realname ?: parts[2]) + guest
            } else {
                sessionUserName2
            }
        } catch (ex: Exception) {
            sessionUserName2
        }
    }

    // True if our accessibility service is enabled in Android settings. This is the durable signal
    // for "unattended access is granted"; MeshAccessibilityService.instance is only set while the
    // service is actively bound, which is transiently null after an app update or process restart.
    fun isAccessibilityServiceEnabled(): Boolean {
        if (!::appContext.isInitialized) return false
        val enabledServices = try {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (ex: Exception) {
            null
        } ?: return false
        val component = ComponentName(appContext, MeshAccessibilityService::class.java)
        val flat = component.flattenToString()
        val flatShort = component.flattenToShortString()
        return enabledServices.split(':').any { it.equals(flat, ignoreCase = true) || it.equals(flatShort, ignoreCase = true) }
    }

    // Schedule another startProjection() attempt while we wait for the accessibility service to
    // rebind. Returns false once the bounded number of attempts is exhausted so the caller can fall
    // back to its normal handling. Bounded to roughly six seconds.
    private fun waitForAccessibilityProjection(): Boolean {
        if (projectionRetryCount >= MAX_PROJECTION_RETRIES) return false
        projectionRetryCount++
        projectionRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            projectionRetryRunnable = null
            startProjection()
        }
        projectionRetryRunnable = runnable
        mainHandler.postDelayed(runnable, 500)
        return true
    }

    private fun cancelProjectionRetry() {
        projectionRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        projectionRetryRunnable = null
        projectionRetryCount = 0
    }

    fun activeRemoteDesktopProvider(): RemoteDesktopProvider? {
        return g_remoteDesktopProvider ?: g_ScreenCaptureService
    }

    fun requestDesktopRefresh() {
        activeRemoteDesktopProvider()?.requestFullFrame()
    }

    fun isRetrying(): Boolean {
        return retryRunnable != null
    }

    fun checkNoMoreDesktopTunnels() {
        val agent = meshAgent ?: return
        val activeDesktopTunnels = agent.tunnels.count { (it.state == 2) && (it.usage == 2) }
        if (activeDesktopTunnels == 0) {
            stopProjection()
            refreshInfo()
        }
    }

    fun sendDesktopTunnelData(data: ByteString) {
        val agent = meshAgent ?: return
        for (t in agent.tunnels) {
            if ((t.state == 2) && (t.usage == 2) && (t._webSocket != null)) {
                t._webSocket!!.send(data)
            }
        }
    }

    fun sendDesktopMessage(message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        val data = ByteArray(4 + bytes.size)
        data[1] = 17
        data[2] = ((data.size shr 8) and 0xFF).toByte()
        data[3] = (data.size and 0xFF).toByte()
        bytes.copyInto(data, 4)
        sendDesktopTunnelData(data.toByteString())
    }

    fun handleDesktopMouseCommand(msg: ByteString): Boolean {
        val provider = activeInputProvider()
        if (provider != null && provider.handleMouseCommand(msg)) return true
        sendDesktopMessage("Remote input requires Accessibility unattended access.")
        return false
    }

    fun handleDesktopTouchCommand(msg: ByteString): Boolean {
        val provider = activeInputProvider()
        if (provider != null && provider.handleTouchCommand(msg)) return true
        sendDesktopMessage("Remote touch input requires Accessibility unattended access.")
        return false
    }

    fun handleDesktopKeyCommand(cmd: Int, msg: ByteString): Boolean {
        val provider = activeInputProvider()
        if (provider != null && provider.handleKeyCommand(cmd, msg)) return true
        sendDesktopMessage("Remote keyboard input is limited on Android and requires Accessibility unattended access.")
        return false
    }

    private fun activeInputProvider(): RemoteDesktopProvider? {
        val activeProvider = activeRemoteDesktopProvider()
        if (activeProvider is MeshAccessibilityService) return activeProvider
        return MeshAccessibilityService.instance ?: activeProvider
    }

    override fun showAlertMessage(title: String, msg: String) {
        val mainActivity = activity
        if (mainActivity != null) {
            mainActivity.showAlertMessage(title, msg)
        } else {
            showRuntimeNotification(title, msg, null)
        }
    }

    override fun showToastMessage(msg: String) {
        runOnHostThread {
            val toast = Toast.makeText(appContext, msg, Toast.LENGTH_LONG)
            toast.setGravity(Gravity.CENTER, 0, 300)
            toast.show()
        }
    }

    override fun openUrl(xpageUrl: String): Boolean {
        val mainActivity = activity
        if (mainActivity != null) {
            return mainActivity.openUrlInApp(xpageUrl)
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(xpageUrl))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            true
        } catch (ex: Exception) {
            false
        }
    }

    override fun returnToMainScreen() {
        activity?.returnToMainScreen()
    }

    override fun startActivity(intent: Intent) {
        val mainActivity = activity
        if (mainActivity != null) {
            mainActivity.startActivity(intent)
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        }
    }

    override fun launchIntentSenderForResult(
        intentSender: IntentSender,
        requestCode: Int,
        fillInIntent: Intent?,
        flagsMask: Int,
        flagsValues: Int,
        extraFlags: Int,
        options: Bundle?
    ): Boolean {
        val mainActivity = activity ?: return false
        return try {
            mainActivity.startIntentSenderForResult(
                intentSender,
                requestCode,
                fillInIntent,
                flagsMask,
                flagsValues,
                extraFlags,
                options
            )
            true
        } catch (ex: Exception) {
            false
        }
    }

    fun requestBatteryOptimizationExemption() {
        if (!::appContext.isInitialized) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(appContext.packageName)) return
        val mainActivity = activity ?: return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:${appContext.packageName}")
            mainActivity.startActivity(intent)
        } catch (ex: Exception) {
            try {
                mainActivity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        if (!::appContext.isInitialized) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
    }

    fun areNotificationsEnabled(): Boolean {
        if (!::appContext.isInitialized) return false
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    fun showRuntimeNotification(title: String?, body: String?, url: String?) {
        val mainActivity = activity
        if (mainActivity != null) {
            mainActivity.showNotification(title, body, url)
        } else {
            AgentForegroundService.showOneShotNotification(appContext, title, body, url)
        }
    }

    private fun loadServerLink() {
        serverLink = if (hardCodedServerLink != null) {
            hardCodedServerLink
        } else {
            appContext.getSharedPreferences("meshagent", Context.MODE_PRIVATE).getString("qrmsh", null)
        }
    }

    private fun loadSettings() {
        val pm: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(appContext)
        g_autoConnect = enterpriseEnforced || pm.getBoolean("pref_autoconnect", false)
        g_autoConsent = enterpriseEnforced || pm.getBoolean("pref_autoconsent", false)
        g_sessionNotification = pm.getBoolean("pref_session_notification", false)
        if (enterpriseEnforced) {
            pm.edit()
                .putBoolean("pref_autoconnect", true)
                .putBoolean("pref_autoconsent", true)
                .apply()
        }
    }

    private fun loadFirebaseToken() {
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { tokenString ->
                pushMessagingToken = tokenString
                meshAgent?.sendCoreInfo()
            }
        } catch (_: Exception) {
        }
    }

    private fun registerBatteryReceiver() {
        if (batteryReceiver != null) return
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                meshAgent?.batteryStateChanged(intent)
            }
        }
        val intentFilter = IntentFilter()
        intentFilter.addAction(Intent.ACTION_POWER_CONNECTED)
        intentFilter.addAction(Intent.ACTION_POWER_DISCONNECTED)
        intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(batteryReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(batteryReceiver, intentFilter)
        }
    }

    private fun startRetryTimer() {
        if (retryRunnable != null) return
        retryRunnable = object : Runnable {
            override fun run() {
                if ((meshAgent == null) && !g_userDisconnect && (g_autoConnect || enterpriseEnforced)) {
                    toggleAgentConnection(false)
                }
                mainHandler.postDelayed(this, 10000)
            }
        }
        mainHandler.postDelayed(retryRunnable!!, 10000)
    }

    private fun stopRetryTimer() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
    }

    private fun ensureIdentity() {
        if (agentCertificate != null && agentCertificateKey != null) return
        val sharedPreferences = appContext.getSharedPreferences("meshagent", Context.MODE_PRIVATE)
        val certb64: String? = sharedPreferences.getString("agentCert", null)
        val keyb64: String? = sharedPreferences.getString("agentKey", null)
        if ((certb64 == null) || (keyb64 == null)) {
            val keyGen = KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(2048, SecureRandom())
            val keypair = keyGen.generateKeyPair()

            var serial = BigInteger("12345678")
            try {
                serial = BigInteger.valueOf(Random().nextInt().toLong().absoluteValue)
            } catch (_: Exception) {
            }

            val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
                X500Name("CN=android.agent.meshcentral.com"),
                serial,
                Date(System.currentTimeMillis() - 86400000L * 365),
                Date(253402300799000L),
                X500Name("CN=android.agent.meshcentral.com"),
                keypair.public
            )
            agentCertificate = JcaX509CertificateConverter().setProvider("SC").getCertificate(
                builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keypair.private))
            )
            agentCertificateKey = keypair.private
            sharedPreferences.edit()
                .putString("agentCert", Base64.encodeToString(agentCertificate?.encoded, Base64.DEFAULT))
                .putString("agentKey", Base64.encodeToString(agentCertificateKey?.encoded, Base64.DEFAULT))
                .apply()
        } else {
            agentCertificate = CertificateFactory.getInstance("X509").generateCertificate(
                ByteArrayInputStream(Base64.decode(certb64, Base64.DEFAULT))
            ) as X509Certificate
            val keySpec = PKCS8EncodedKeySpec(Base64.decode(keyb64, Base64.DEFAULT))
            agentCertificateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec)
        }
    }

    private fun ensureCryptoProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    fun getServerHost(): String? {
        val link = serverLink ?: return null
        val x: List<String> = link.split(',')
        val serverHost = x[0]
        return serverHost.substring(5)
    }

    fun getServerHash(): String? {
        val link = serverLink ?: return null
        val x: List<String> = link.split(',')
        return x.getOrNull(1)
    }

    fun getDevGroup(): String? {
        val link = serverLink ?: return null
        val x: List<String> = link.split(',')
        return x.getOrNull(2)
    }
}
