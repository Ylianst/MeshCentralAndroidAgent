package com.meshcentral.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentJsonTest {
    private fun roundTrip(message: JSONObject): JSONObject {
        val packet = encodeAgentJson(message)
        // MeshCentral reads agent packets with Buffer.toString('binary') before JSON.parse.
        val binaryString = String(packet.toByteArray(), Charsets.ISO_8859_1)
        val decoded = JSONObject(binaryString)
        assertTrue(packet.toByteArray().all { it.toInt() in 0..127 })
        assertEquals(message.toString(), JSONObject(packet.utf8()).toString())
        return decoded
    }

    @Test
    fun preservesReportedClipboardText() {
        val text = "Ça coûte 5€"
        val message = JSONObject().put("action", "msg").put("type", "getclip")
            .put("sessionid", "user//tester/session").put("tag", 1).put("data", text)

        val decoded = roundTrip(message)

        assertEquals(text, decoded.getString("data"))
        assertEquals("getclip", decoded.getString("type"))
        assertEquals("user//tester/session", decoded.getString("sessionid"))
        assertEquals(1, decoded.getInt("tag"))
    }

    @Test
    fun preservesEmojiAndMultipleScripts() {
        val text = "😀 👩🏽‍💻 中文 العربية Ελληνικά e\u0301"
        assertEquals(text, roundTrip(JSONObject().put("data", text)).getString("data"))
    }

    @Test
    fun preservesJsonEscapesAndEmptyClipboard() {
        for (text in listOf("", "plain ASCII", "\"quotes\" \\u00e9 \\path\r\n\t\u0000")) {
            val message = JSONObject().put("data", text)
            assertEquals(message.toString(), encodeAgentJson(message).utf8())
            assertEquals(text, roundTrip(message).getString("data"))
        }
    }

    @Test
    fun preservesUnicodeKeysAndNestedSoftwareMetadata() {
        val message = JSONObject().put("action", "software")
            .put("sessionid", "user//André/session")
            .put("software", JSONArray().put(JSONObject().put("name", "日本語")))
            .put("clé", "résumé")

        val decoded = roundTrip(message)

        assertEquals("user//André/session", decoded.getString("sessionid"))
        assertEquals("日本語", decoded.getJSONArray("software").getJSONObject(0).getString("name"))
        assertEquals("résumé", decoded.getString("clé"))
    }
}
