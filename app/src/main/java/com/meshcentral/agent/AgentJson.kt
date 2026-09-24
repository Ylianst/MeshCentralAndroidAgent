package com.meshcentral.agent

import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONObject

internal fun encodeAgentJson(json: JSONObject): ByteString {
    // MeshCentral parses control packets as Latin-1. JSON escapes also work with UTF-8 readers.
    val serialized = json.toString()
    return buildString(serialized.length) {
        for (char in serialized) {
            if (char.code > 0x7f) {
                append("\\u")
                append(char.code.toString(16).padStart(4, '0'))
            } else {
                append(char)
            }
        }
    }.encodeUtf8()
}
