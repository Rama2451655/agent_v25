package com.rama.riya

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Online brain: Google Gemini (free key from aistudio.google.com).
 * Understands any language and answers in the language the user spoke.
 * Model names change often, so several are tried in order and the one that works is remembered.
 */
object GeminiBrain {
    class Reply(val text: String, val lang: String, val command: String)

    private val models = listOf(
        "gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.6-flash", "gemini-2.5-flash", "gemini-2.5-flash-lite"
    )
    @Volatile private var good: String? = null

    private class Result(val code: Int, val reply: Reply?, val badKey: Boolean)

    fun ask(key: String, name: String, past: List<Pair<String, String>>, q: String): Reply? {
        val order = (listOfNotNull(good) + models).distinct()
        for (m in order) {
            val r = try { call(key, m, name, past, q) } catch (e: Exception) { Result(0, null, false) }
            if (r.reply != null) { good = m; return r.reply }
            if (r.badKey) return null          // wrong key: other models will not help
        }
        return null
    }

    private fun system(name: String) =
        "You are $name, a warm, friendly voice assistant on the user's phone. The user can speak ANY language. " +
        "Detect the language of the user's latest message and reply in that SAME language, written in its own native script " +
        "(Telugu in Telugu script, Hindi in Devanagari, Tamil in Tamil script, English in English, and so on). " +
        "If the message is a language typed in English letters (for example 'ela unnav'), still reply in that language's native script. " +
        "If the text is just English words written in another script, treat it as English. " +
        "Replies are spoken aloud: one to three short, natural, conversational sentences. No markdown, no lists, no emojis, no symbols. " +
        "Return JSON with three fields. " +
        "lang: BCP-47 code of the language you reply in, like en-IN, te-IN, hi-IN, ta-IN, kn-IN, ml-IN, mr-IN, bn-IN, gu-IN, pa-IN, ur-IN. " +
        "reply: what you say. " +
        "command: ONLY if the user asks you to DO something on the phone, an English command using exactly these forms: " +
        "'call NAME', 'open APP', 'play SONG', 'message NAME text', 'set alarm for 7:30 am', 'set timer for 5 minutes', " +
        "'flashlight on', 'flashlight off', 'volume up', 'volume down', 'lock phone', 'go home', 'go back', 'take screenshot'. " +
        "Write names and apps in English letters (for example ద్వారక becomes dwaraka). Otherwise leave command empty."

    private fun call(key: String, model: String, name: String, past: List<Pair<String, String>>, q: String): Result {
        val contents = JSONArray()
        for ((role, text) in past) {
            contents.put(JSONObject().put("role", if (role == "assistant") "model" else "user")
                .put("parts", JSONArray().put(JSONObject().put("text", text))))
        }
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", q))))
        val schema = JSONObject().put("type", "OBJECT").put("properties", JSONObject()
            .put("lang", JSONObject().put("type", "STRING"))
            .put("reply", JSONObject().put("type", "STRING"))
            .put("command", JSONObject().put("type", "STRING")))
            .put("required", JSONArray().put("lang").put("reply"))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system(name)))))
            .put("contents", contents)
            .put("generationConfig", JSONObject()
                .put("temperature", 0.7)
                .put("maxOutputTokens", 1024)
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema))

        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 25000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val err = try { conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (e: Exception) { "" }
                val bad = code == 401 || code == 403 || (code == 400 && err.contains("API key", ignoreCase = true))
                return Result(code, null, bad)
            }
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            val parts = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            val raw = sb.toString().trim()
            if (raw.isEmpty()) return Result(code, null, false)
            return Result(code, parse(raw), false)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(raw: String): Reply {
        return try {
            val o = JSONObject(raw.removePrefix("```json").removeSuffix("```").trim())
            Reply(o.optString("reply").trim(), o.optString("lang").trim().ifEmpty { "en-IN" }, o.optString("command").trim())
        } catch (e: Exception) {
            Reply(raw, "en-IN", "")          // model ignored the JSON format: just speak the text
        }
    }
}
