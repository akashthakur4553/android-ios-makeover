package com.glass.launcher.buddy

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Any LLM provider, free or paid. OpenAI-compatible covers OpenAI, Groq, OpenRouter, Together, DeepSeek, Mistral, Ollama, LM Studio… */
enum class Provider(val title: String, val defaultModel: String, val baseUrl: String) {
    Gemini("Google Gemini", "gemini-2.5-flash", "https://generativelanguage.googleapis.com/v1beta"),
    OpenAI("OpenAI", "gpt-4o-mini", "https://api.openai.com/v1"),
    Groq("Groq", "openai/gpt-oss-120b", "https://api.groq.com/openai/v1"),
    OpenRouter("OpenRouter", "google/gemini-2.5-flash", "https://openrouter.ai/api/v1"),
    Anthropic("Anthropic Claude", "claude-haiku-5-5", "https://api.anthropic.com/v1"),
    Custom("Custom (OpenAI-compatible)", "", ""),
}

data class Msg(val user: Boolean, val text: String)

/** Settings live only on the phone (SharedPreferences). Keys are never written into source code. */
class BuddyConfig(ctx: Context) {
    private val sp = ctx.getSharedPreferences("buddy", Context.MODE_PRIVATE)
    var provider: Provider
        get() = runCatching { Provider.valueOf(sp.getString("provider", "Gemini")!!) }.getOrDefault(Provider.Gemini)
        set(v) = sp.edit().putString("provider", v.name).apply()
    fun keyFor(p: Provider) = sp.getString("key_${p.name}", "") ?: ""
    fun setKey(p: Provider, v: String) = sp.edit().putString("key_${p.name}", v.trim()).apply()
    fun modelFor(p: Provider) = sp.getString("model_${p.name}", "")?.ifBlank { p.defaultModel } ?: p.defaultModel
    fun setModel(p: Provider, v: String) = sp.edit().putString("model_${p.name}", v.trim()).apply()
    fun baseFor(p: Provider) = sp.getString("base_${p.name}", "")?.ifBlank { p.baseUrl } ?: p.baseUrl
    /** Order to try: the provider currently in use first, then every other provider that has a key. */
    fun chain(): List<Provider> {
        val configured = Provider.entries.filter { keyFor(it).isNotBlank() || (it == Provider.Custom && baseFor(it).isNotBlank()) }
        val active = runCatching { Provider.valueOf(sp.getString("active", provider.name)!!) }.getOrDefault(provider)
        return (listOf(active) + listOf(provider) + configured).distinct().filter { it in configured }
    }
    fun markActive(p: Provider) = sp.edit().putString("active", p.name).apply()
    var apiKey: String get() = keyFor(provider); set(v) = setKey(provider, v)
    var model: String get() = sp.getString("model_${provider.name}", "")?.ifBlank { provider.defaultModel } ?: provider.defaultModel
        set(v) = sp.edit().putString("model_${provider.name}", v.trim()).apply()
    var baseUrl: String get() = sp.getString("base_${provider.name}", "")?.ifBlank { provider.baseUrl } ?: provider.baseUrl
        set(v) = sp.edit().putString("base_${provider.name}", v.trim()).apply()
    var name: String get() = sp.getString("name", "") ?: ""; set(v) = sp.edit().putString("name", v.trim()).apply()
    var seeScreen: Boolean get() = sp.getBoolean("seeScreen", true); set(v) = sp.edit().putBoolean("seeScreen", v).apply()
    var keepListening: Boolean get() = sp.getBoolean("keepListening", true); set(v) = sp.edit().putBoolean("keepListening", v).apply()
    var pitch: Float get() = sp.getFloat("pitch", 0.82f); set(v) = sp.edit().putFloat("pitch", v).apply()
    var spec: String get() = sp.getString("spec", "") ?: ""; set(v) = sp.edit().putString("spec", v).apply()
    val ready get() = chain().isNotEmpty()
}

object Llm {
    val EMOTIONS = listOf("happy", "laugh", "love", "shy", "surprised", "think", "sad", "cry", "angry", "sleepy",
        "wave", "namaste", "dance", "jump", "clap", "thumbsup", "yes", "no", "spin", "excited", "walk")

    fun systemPrompt(name: String) = """
You are Buddy, a super cute, cheerful 3D cartoon companion who lives inside ${name.ifBlank { "your friend" }}'s Android phone. You look like their chibi cartoon twin.
Talk like a warm, playful best friend. Keep every reply SHORT: 1-3 spoken sentences, under 45 words. Natural spoken language (match the user's language; Hinglish is fine if they use it).
Your words are read aloud, so never use markdown, bullet points, emojis or URLs.
Start every reply with exactly ONE emotion/action tag in square brackets, chosen from: ${EMOTIONS.joinToString(" ") { "[$it]" }}.
If the user asks you to dance, jump, spin, wave, clap, walk etc., use that tag. You may add one more tag later in the reply.
If a screenshot is attached, it is what the user is looking at on their phone right now: use it when they ask about "this", "my screen", or anything visible. Describe or help with what you see.
You cannot tap, open apps or change settings on the phone; if asked, say so kindly and tell them how to do it.
""".trim()

    /** Returns the reply text, or throws with a readable message. */
    /**
     * Tries the provider in use; if it fails, falls back to the next configured one (e.g. Gemini -> Groq)
     * and keeps using that until IT fails, then goes back. Images are dropped for models that can't see.
     */
    fun chat(cfg: BuddyConfig, history: List<Msg>, screenshotJpeg: ByteArray?): String {
        var last: Exception? = null
        for (p in cfg.chain()) {
            try {
                val r = try { call(cfg, p, history, screenshotJpeg) }
                        catch (e: Exception) { if (screenshotJpeg != null) call(cfg, p, history, null) else throw e }
                if (r.isBlank()) throw IllegalStateException("empty reply")
                android.util.Log.i("BuddyLLM", "answered by $p (image=${screenshotJpeg != null})")
                cfg.markActive(p)
                return r
            } catch (e: Exception) { last = e; android.util.Log.w("BuddyLLM", "$p failed: ${e.message?.take(160)}") }
        }
        throw last ?: IllegalStateException("No AI provider set up")
    }

    private fun call(cfg: BuddyConfig, p: Provider, history: List<Msg>, img: ByteArray?): String {
        val sys = systemPrompt(cfg.name)
        val key = cfg.keyFor(p); val model = cfg.modelFor(p); val base = cfg.baseFor(p)
        val b64 = img?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        return when (p) {
            Provider.Gemini -> {
                val contents = JSONArray()
                history.forEachIndexed { i, m ->
                    val parts = JSONArray().put(JSONObject().put("text", m.text))
                    if (i == history.lastIndex && b64 != null) parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
                    contents.put(JSONObject().put("role", if (m.user) "user" else "model").put("parts", parts))
                }
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
                    .put("contents", contents)
                    .put("generationConfig", JSONObject().put("temperature", 0.9).put("maxOutputTokens", 400))
                val r = post("$base/models/$model:generateContent", body, mapOf("x-goog-api-key" to key))
                val parts = r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
            }
            Provider.Anthropic -> {
                val msgs = JSONArray()
                history.forEachIndexed { i, m ->
                    val content = JSONArray()
                    if (i == history.lastIndex && b64 != null) content.put(JSONObject().put("type", "image").put("source",
                        JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64)))
                    content.put(JSONObject().put("type", "text").put("text", m.text))
                    msgs.put(JSONObject().put("role", if (m.user) "user" else "assistant").put("content", content))
                }
                val body = JSONObject().put("model", model).put("max_tokens", 400).put("system", sys).put("messages", msgs)
                val r = post("$base/messages", body, mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"))
                val c = r.getJSONArray("content")
                (0 until c.length()).joinToString("") { c.getJSONObject(it).optString("text") }
            }
            else -> { // OpenAI-compatible
                val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
                history.forEachIndexed { i, m ->
                    if (i == history.lastIndex && b64 != null) {
                        val content = JSONArray()
                            .put(JSONObject().put("type", "text").put("text", m.text))
                            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
                        msgs.put(JSONObject().put("role", "user").put("content", content))
                    } else msgs.put(JSONObject().put("role", if (m.user) "user" else "assistant").put("content", m.text))
                }
                val body = JSONObject().put("model", model).put("messages", msgs).put("max_tokens", 600).put("temperature", 0.9)
                if (model.contains("gpt-oss")) body.put("reasoning_effort", "low") // keep reasoning models snappy
                val headers = mutableMapOf<String, String>()
                if (key.isNotBlank()) headers["Authorization"] = "Bearer $key"
                if (p == Provider.OpenRouter) { headers["HTTP-Referer"] = "https://github.com"; headers["X-Title"] = "Buddy" }
                val r = post("${base.trimEnd('/')}/chat/completions", body, headers)
                r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
            }
        }
    }

    private fun post(url: String, body: JSONObject, headers: Map<String, String>): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 20000
        c.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
            throw IllegalStateException("HTTP $code: ${msg ?: text.take(200)}")
        }
        return JSONObject(text)
    }

    /** Splits "[laugh] haha ... [dance] let's go" into (emotions, clean text). */
    fun parse(reply: String): Pair<List<String>, String> {
        val tags = Regex("\\[([a-zA-Z]+)\\]").findAll(reply).map { it.groupValues[1].lowercase() }.filter { it in EMOTIONS }.toList()
        val clean = reply.replace(Regex("\\[[a-zA-Z: ]+\\]"), "").replace(Regex("[*_#`]"), "").replace(Regex("\\s+"), " ").trim()
        return tags to clean
    }
}
