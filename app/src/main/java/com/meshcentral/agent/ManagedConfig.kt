package com.meshcentral.agent

// Settings pushed by a device management (MDM/EMM) system through Android managed
// configurations, see res/xml/app_restrictions.xml. A null value means "not managed".
internal data class ManagedConfig(
    val serverLink: String?,
    val autoConnect: Boolean?,
    val autoConsent: Boolean?,
    val lockServer: Boolean
)

internal const val MANAGED_SERVER_LINK = "server_link"
internal const val MANAGED_AUTO_CONNECT = "auto_connect"
internal const val MANAGED_AUTO_CONSENT = "auto_consent"
internal const val MANAGED_LOCK_SERVER = "lock_server"

// Values come from the application restrictions bundle. Keys the administrator did not
// set are missing, invalid server links are ignored.
internal fun parseManagedConfig(values: Map<String, Any?>): ManagedConfig {
    val link = (values[MANAGED_SERVER_LINK] as? String)?.trim()
    return ManagedConfig(
        serverLink = link?.takeIf { it.isNotEmpty() && isMeshServerLinkValid(it) },
        autoConnect = values[MANAGED_AUTO_CONNECT] as? Boolean,
        autoConsent = values[MANAGED_AUTO_CONSENT] as? Boolean,
        lockServer = (values[MANAGED_LOCK_SERVER] as? Boolean) ?: false
    )
}
