package com.meshcentral.agent

import java.io.File

internal fun isMeshServerLinkValid(value: String): Boolean {
    if (!value.startsWith("mc://")) return false

    val parts = value.split(',')
    return parts.size >= 3 &&
        parts[0].length >= 8 &&
        parts[0].contains('.') &&
        parts[1].length >= 3 &&
        parts[2].length >= 3
}

internal fun isTunnelUsageAllowed(expectedUsage: Int?, actualUsage: Int): Boolean {
    return expectedUsage == null || expectedUsage == actualUsage
}

internal fun isSafeFileName(name: String): Boolean {
    return name.isNotEmpty() &&
        name != "." &&
        name != ".." &&
        !name.contains('/') &&
        !name.contains('\\') &&
        !name.contains('\u0000')
}

internal fun resolveSdcardPath(root: File, virtualPath: String): File? {
    if (virtualPath != "Sdcard" && !virtualPath.startsWith("Sdcard/")) return null
    if (virtualPath.contains('\\') || virtualPath.contains('\u0000')) return null

    val canonicalRoot = root.canonicalFile
    val relativePath = virtualPath.removePrefix("Sdcard").removePrefix("/")
    val candidate = File(canonicalRoot, relativePath).canonicalFile
    val rootPrefix = canonicalRoot.path + File.separator
    return candidate.takeIf { it == canonicalRoot || it.path.startsWith(rootPrefix) }
}

internal fun resolveSdcardChild(root: File, virtualDirectory: String, name: String): File? {
    if (!isSafeFileName(name)) return null
    val directory = resolveSdcardPath(root, virtualDirectory) ?: return null
    val candidate = File(directory, name).canonicalFile
    return candidate.takeIf { it.parentFile == directory.canonicalFile }
}