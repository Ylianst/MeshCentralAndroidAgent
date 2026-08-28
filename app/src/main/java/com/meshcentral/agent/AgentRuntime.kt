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
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.preference.PreferenceManager
import com.google.firebase.messaging.FirebaseMessaging
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.security.auth.x500.X500Principal

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
    private const val TAG = "AgentController"
    private const val INITIAL_RETRY_DELAY_MS = 10_000L
    private const val MAX_RETRY_DELAY_MS = 300_000L
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AGENT_KEY_ALIAS = "meshcentral-agent-identity"
    private const val ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L
    private const val CERTIFICATE_LIFETIME_MILLIS = 20L * 365L * ONE_DAY_MILLIS
    private lateinit var appContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())
    private var initialized = false
    private var activity: MainActivity? = null
    private var service: AgentForegroundService? = null
    private var retryRunnable: Runnable? = null
    private var retryDelayMs = INITIAL_RETRY_DELAY_MS
    private var retryAttemptInProgress = false
    private var batteryReceiver: BroadcastReceiver? = null
    private var settingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var handlingSettingsChange = false
    private var projectionRetryRunnable: Runnable? = null
    private var projectionRetryCount = 0
    private val MAX_PROJECTION_RETRIES = 12

    val enterpriseEnforced: Boolean
        get() = BuildConfig.ENTERPRISE_ENFORCED

    override val contentResolver: ContentResolver
        get() = appContext.contentResolver

    override fun getApplicationContext(): Context = appContext.applicationContext

    fun init(context: Context) {
        appContext = context.applicationContext
        loadServerLink()
        loadSettings()
        loadFirebaseToken()
        if (!initialized) {
            initialized = true
            registerBatteryReceiver()
            registerSettingsListener()
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
        return serverLink != null && (enterpriseEnforced || (g_autoConnect && !g_userDisconnect))
    }

    fun shouldKeepForegroundServiceRunning(): Boolean {
        loadServerLink()
        loadSettings()
        return meshAgent != null || shouldAutoStart() || hasActiveDesktopTunnel()
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
        } else {
            stopProjection()
            service?.stopSelf()
        }

        setUserDisconnected(false)
        refreshInfo()
        if (g_autoConnect || enterpriseEnforced) {
            toggleAgentConnection(false)
        }
    }

    fun settingsChanged() {
        loadSettings()
        if (!enterpriseEnforced && !g_autoConnect) {
            setUserDisconnected(false)
            stopRetryTimer()
            refreshInfo()
            service?.updateNotification()
            stopServiceIfIdle()
            return
        }
        if ((meshAgent == null) && !g_userDisconnect && hasServerLink()) {
            AgentForegroundService.start(appContext)
            toggleAgentConnection(false)
        }
        refreshInfo()
        service?.updateNotification()
        stopServiceIfIdle()
    }

    fun toggleAgentConnection(userInitiated: Boolean) {
        loadServerLink()
        loadSettings()
        if ((meshAgent == null) && (serverLink != null)) {
            if (!ensureIdentity()) return
            if (!userInitiated) {
                setUserDisconnected(false)
                if (!retryAttemptInProgress) resetRetryBackoff()
                startAgent()
            } else {
                if (g_autoConnect || enterpriseEnforced) {
                    if (g_userDisconnect) {
                        setUserDisconnected(false)
                        resetRetryBackoff()
                        startAgent()
                    } else {
                        setUserDisconnected(true)
                        stopRetryTimer()
                    }
                } else {
                    setUserDisconnected(true)
                    resetRetryBackoff()
                    startAgent()
                }
            }
        } else if (meshAgent != null) {
            if (userInitiated && !enterpriseEnforced) {
                setUserDisconnected(true)
            }
            stopProjection()
            meshAgent?.Stop()
            meshAgent = null
            stopRetryTimer()
        }
        refreshInfo()
        service?.updateNotification()
        stopServiceIfIdle()
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
            if (meshAgent?.state == 3) {
                resetRetryBackoff()
            }
            if (((meshAgent != null) && (meshAgent?.state != 0)) || g_userDisconnect || (!g_autoConnect && !enterpriseEnforced)) {
                stopRetryTimer()
            } else if ((meshAgent == null) && !g_userDisconnect && (g_autoConnect || enterpriseEnforced) && retryRunnable == null) {
                startRetryTimer()
            }
            refreshInfo()
            service?.updateNotification()
            stopServiceIfIdle()
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

    // May run on the tunnel's OkHttp thread; dialogs and activities must start on the main thread, or a
    // dialog built off it throws "Can't create handler ... Looper.prepare()".
    override fun startProjection() {
        runOnHostThread { startProjectionOnHostThread() }
    }

    private fun startProjectionOnHostThread() {
        if (meshAgent == null || meshAgent?.state != 3) return
        if (!hasActiveDesktopTunnel()) return
        if (isRemoteDesktopRunning()) return
        val accessibility = MeshAccessibilityService.instance
        if (accessibility != null) {
            cancelProjectionRetry()
            if (g_autoConsent) {
                if (accessibility.startDesktop()) return
            } else {
                // Automatic Consent off: require explicit approval before capturing.
                val mainActivity = activity
                val resumed = mainActivity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
                if (mainActivity != null && resumed) {
                    mainActivity.promptUnattendedConsent()
                } else {
                    // No foreground activity to host a dialog, so ask via the notification.
                    sendDesktopMessage("Waiting for the device user to approve screen sharing.")
                    AgentForegroundService.showConsentNotification(appContext)
                }
                return
            }
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

    fun confirmUnattendedConsent() {
        if (::appContext.isInitialized) AgentForegroundService.cancelConsentNotification(appContext)
        if (meshAgent?.state != 3 || !hasActiveDesktopTunnel() || isRemoteDesktopRunning()) return
        runOnHostThread { MeshAccessibilityService.instance?.startDesktop() }
    }

    fun denyUnattendedConsent() {
        val tunnel = activeDesktopTunnel() ?: return
        val json = JSONObject()
        json.put("type", "console")
        json.put("msg", "denied")
        json.put("msgid", 2)
        tunnel.sendCtrlResponse(json)
        tunnel.Stop()
    }

    override fun stopProjection() {
        if (::appContext.isInitialized) AgentForegroundService.cancelConsentNotification(appContext)
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

    // The connected desktop tunnel, used to route consent responses to the viewer that opened it.
    fun activeDesktopTunnel(): MeshTunnel? {
        return meshAgent?.tunnels?.toList()?.firstOrNull { (it.state == 2) && (it.usage == 2) }
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
        for (t in agent.tunnels.toList()) {
            if ((t.state == 2) && (t.usage == 2)) {
                t._webSocket?.send(data)
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
        g_userDisconnect = !enterpriseEnforced && pm.getBoolean("pref_user_disconnect", false)
        // Only write if a value differs, or the change listener loops on enterprise builds.
        if (enterpriseEnforced &&
            (!pm.getBoolean("pref_autoconnect", false) ||
                !pm.getBoolean("pref_autoconsent", false) ||
                pm.getBoolean("pref_user_disconnect", false))) {
            pm.edit()
                .putBoolean("pref_autoconnect", true)
                .putBoolean("pref_autoconsent", true)
                .putBoolean("pref_user_disconnect", false)
                .apply()
        }
    }

    // Apply setting toggles immediately, not only when the Settings screen closes. pref_user_disconnect
    // is internal state written in code, so it's deliberately not watched.
    private fun registerSettingsListener() {
        if (settingsListener != null) return
        val pm = PreferenceManager.getDefaultSharedPreferences(appContext)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (handlingSettingsChange) return@OnSharedPreferenceChangeListener
            when (key) {
                "pref_autoconnect", "pref_autoconsent", "pref_session_notification" -> {
                    handlingSettingsChange = true
                    try {
                        settingsChanged()
                    } finally {
                        handlingSettingsChange = false
                    }
                }
            }
        }
        settingsListener = listener
        pm.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun loadFirebaseToken() {
        if (pushMessagingToken != null) return
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
                retryRunnable = null
                if ((meshAgent == null) && !g_userDisconnect && (g_autoConnect || enterpriseEnforced)) {
                    retryAttemptInProgress = true
                    try {
                        toggleAgentConnection(false)
                    } finally {
                        retryAttemptInProgress = false
                    }
                }
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                if ((meshAgent == null) && !g_userDisconnect && (g_autoConnect || enterpriseEnforced)) {
                    startRetryTimer()
                }
            }
        }
        mainHandler.postDelayed(retryRunnable!!, retryDelayMs)
    }

    private fun stopRetryTimer() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
    }

    private fun resetRetryBackoff() {
        retryDelayMs = INITIAL_RETRY_DELAY_MS
    }

    private fun setUserDisconnected(disconnected: Boolean) {
        g_userDisconnect = disconnected && !enterpriseEnforced
        PreferenceManager.getDefaultSharedPreferences(appContext)
            .edit()
            .putBoolean("pref_user_disconnect", g_userDisconnect)
            .apply()
    }

    private fun stopServiceIfIdle() {
        if (!shouldKeepForegroundServiceRunning()) {
            service?.stopSelf()
        }
    }

    private fun ensureIdentity(): Boolean {
        if ((agentCertificate != null) && (agentCertificateKey != null)) return true

        val sharedPreferences = appContext.getSharedPreferences("meshagent", Context.MODE_PRIVATE)
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(AGENT_KEY_ALIAS)) {
                val certb64 = sharedPreferences.getString("agentCert", null)
                val keyb64 = sharedPreferences.getString("agentKey", null)
                if ((certb64 != null) && (keyb64 != null)) {
                    val certificate = CertificateFactory.getInstance("X509").generateCertificate(
                        ByteArrayInputStream(Base64.decode(certb64, Base64.DEFAULT))
                    ) as X509Certificate
                    val keySpec = PKCS8EncodedKeySpec(Base64.decode(keyb64, Base64.DEFAULT))
                    val privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec)
                    val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
                        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .build()
                    keyStore.setEntry(
                        AGENT_KEY_ALIAS,
                        KeyStore.PrivateKeyEntry(privateKey, arrayOf(certificate)),
                        protection
                    )
                } else {
                    generateAgentIdentity()
                }
            }

            agentCertificate = keyStore.getCertificate(AGENT_KEY_ALIAS) as X509Certificate
            agentCertificateKey = keyStore.getKey(AGENT_KEY_ALIAS, null) as PrivateKey
            sharedPreferences.edit().remove("agentCert").remove("agentKey").apply()
            true
        } catch (error: Exception) {
            Log.e(TAG, "Unable to load or create agent identity", error)
            agentCertificate = null
            agentCertificateKey = null
            showAlertMessage(
                appContext.getString(R.string.agent_identity_error_title),
                appContext.getString(R.string.agent_identity_error_message)
            )
            false
        }
    }

    private fun generateAgentIdentity() {
        val keyGen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        val now = System.currentTimeMillis()
        keyGen.initialize(
            KeyGenParameterSpec.Builder(AGENT_KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=android.agent.meshcentral.com"))
                .setCertificateSerialNumber(BigInteger(63, SecureRandom()).max(BigInteger.ONE))
                .setCertificateNotBefore(Date(now - ONE_DAY_MILLIS))
                .setCertificateNotAfter(Date(now + CERTIFICATE_LIFETIME_MILLIS))
                .build()
        )
        keyGen.generateKeyPair()
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
