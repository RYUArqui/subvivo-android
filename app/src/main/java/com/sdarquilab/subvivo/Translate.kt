package com.sdarquilab.subvivo

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 번역(원어 → 한국어). 데스크톱판 translate.py 이식. */
interface Translator {
    val name: String
    fun translate(text: String, src: String?, history: List<Pair<String, String>>): String
    fun translateBatch(lines: List<String>, src: String?, check: () -> Unit = {}): List<String>

    companion object {
        fun make(p: Prefs): Translator = when (p.translator) {
            "claude" -> ClaudeTranslator(p.claudeKey, p.claudeModel, p.notes)
            "none" -> NoTranslator()
            else -> GoogleTranslator()
        }
    }
}

class NoTranslator : Translator {
    override val name = "none"
    override fun translate(text: String, src: String?, history: List<Pair<String, String>>) = ""
    override fun translateBatch(lines: List<String>, src: String?, check: () -> Unit) = lines.map { "" }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

private fun httpGet(url: String, timeoutMs: Int): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = timeoutMs
    c.readTimeout = timeoutMs
    c.setRequestProperty(
        "User-Agent",
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Mobile Safari/537.36",
    )
    try {
        val code = c.responseCode
        if (code !in 200..299) throw IOException("HTTP $code")
        return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        c.disconnect()
    }
}

/** 구글 번역 (무료, 비공식 경로 3개를 차례로 시도: 막히면 다음 경로, 성공한 경로를 계속 씀) */
class GoogleTranslator(private val timeoutMs: Int = 8000, private val target: String = "ko") : Translator {
    override val name = "google"
    private val order = mutableListOf("gtx", "dict", "mymemory")

    private fun gtx(text: String, src: String?): String {
        val body = httpGet(
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=${src ?: "auto"}&tl=$target&dt=t&q=${enc(text)}",
            timeoutMs,
        )
        val parts = JSONArray(body).getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONArray(i) ?: continue
            if (!p.isNull(0)) sb.append(p.optString(0, ""))
        }
        return sb.toString()
    }

    private fun dict(text: String, src: String?): String {
        val body = httpGet(
            "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=${src ?: "auto"}&tl=$target&q=${enc(text)}",
            timeoutMs,
        )
        return when (val v = JSONTokener(body).nextValue()) {
            is JSONObject -> {
                val s = v.optJSONArray("sentences") ?: return ""
                val sb = StringBuilder()
                for (i in 0 until s.length()) sb.append(s.optJSONObject(i)?.optString("trans", "") ?: "")
                sb.toString()
            }
            is JSONArray -> {
                if (v.length() == 0) "" else when (val first = v.get(0)) {
                    is String -> first
                    is JSONArray -> first.optString(0, "")
                    else -> ""
                }
            }
            else -> ""
        }
    }

    private fun mymemory(text: String, src: String?): String {
        val body = httpGet(
            "https://api.mymemory.translated.net/get?q=${enc(text.take(480))}&langpair=${src ?: "Autodetect"}|$target",
            timeoutMs + 2000,
        )
        val o = JSONObject(body)
        val status = o.opt("responseStatus")?.toString()?.toIntOrNull() ?: 200
        if (status != 200) throw IOException(o.optString("responseDetails", "오류").take(120))
        return o.getJSONObject("responseData").optString("translatedText", "")
    }

    @Synchronized
    private fun call(text: String, src: String?): String {
        val errors = ArrayList<String>()
        for (ep in order.toList()) {
            try {
                val out = when (ep) {
                    "gtx" -> gtx(text, src)
                    "dict" -> dict(text, src)
                    else -> mymemory(text, src)
                }.trim()
                if (out.isNotEmpty()) {
                    if (order[0] != ep) {
                        order.remove(ep)
                        order.add(0, ep)
                    }
                    return out
                }
                errors.add("$ep: 빈 응답")
            } catch (e: Exception) {
                errors.add("$ep: ${e.javaClass.simpleName} ${e.message?.take(100) ?: ""}")
            }
        }
        throw IOException(errors.joinToString(" / "))
    }

    override fun translate(text: String, src: String?, history: List<Pair<String, String>>) = call(text, src)

    override fun translateBatch(lines: List<String>, src: String?, check: () -> Unit): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            check()
            val chunk = lines.subList(i, minOf(i + 25, lines.size))
            var parts = call(chunk.joinToString("\n"), src).split("\n").map { it.trim() }
            if (parts.size != chunk.size) parts = chunk.map { check(); call(it, src) } // 줄이 합쳐졌으면 한 줄씩
            out.addAll(parts)
            i += 25
        }
        return out
    }
}

/** Claude 맥락 번역 (인물 관계·존댓말 유지, API 유료) */
class ClaudeTranslator(private val key: String, private val model: String, private val notes: String) : Translator {
    override val name = "claude"

    init {
        if (key.isBlank()) throw IllegalArgumentException("Claude API 키가 비어 있습니다. 설정에 키를 넣거나 구글 번역을 고르세요.")
    }

    private fun system(src: String?, batch: Boolean): String {
        var s = """너는 영화·드라마 전문 한국어 자막 번역가다. ${Langs.name(src)} 대사를 한국어 자막으로 옮긴다.

규칙:
1. 화면에 띄울 자막이다. 자연스러운 한국어 구어체로, 한 줄 20자 안팎·최대 두 줄로 짧게.
2. 앞 대사의 흐름을 보고 인물 사이의 존댓말·반말, 호칭을 일관되게 유지한다.
3. 인명·지명은 [영화 메모]의 표기를 따른다. 없으면 원어 발음대로 한글 표기한다.
4. 원문은 음성 인식 결과라 잘못 들린 단어가 있을 수 있다. 문맥상 명백할 때만 바로잡고, 없는 내용을 지어내지 않는다.
5. 대사가 중간에 끊겨 있으면 끊긴 대로 옮긴다.
6. 번역문만 출력한다. 설명, 따옴표, 원문, 번호를 붙이지 않는다."""
        if (batch) {
            s += "\n\n[출력 형식]\n입력은 \"번호| 대사\" 줄들이다. 같은 번호를 붙여 \"번호| 번역\" 형식으로, 입력 줄 수와 똑같이 출력한다. 다른 말은 쓰지 않는다."
        }
        return s
    }

    private fun userMessage(text: String, history: List<Pair<String, String>>): String {
        val parts = ArrayList<String>()
        if (notes.isNotBlank()) parts.add("[영화 메모]\n${notes.trim()}")
        if (history.isNotEmpty()) {
            parts.add("[직전 대사 (원문 → 자막)]\n" + history.takeLast(6).joinToString("\n") { "- ${it.first}  →  ${it.second}" })
        }
        parts.add("[이번 대사]\n$text")
        return parts.joinToString("\n\n")
    }

    private fun ask(system: String, user: String, maxTokens: Int): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
            .toString()
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-api-key", key)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("Claude API 오류 (HTTP $code) ${resp.take(200)}")
            val content = JSONObject(resp).optJSONArray("content") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val b = content.optJSONObject(i) ?: continue
                if (b.optString("type") == "text") sb.append(b.optString("text"))
            }
            return sb.toString().trim()
        } finally {
            c.disconnect()
        }
    }

    override fun translate(text: String, src: String?, history: List<Pair<String, String>>): String =
        cleanOutput(ask(system(src, false), userMessage(text, history), 300))

    override fun translateBatch(lines: List<String>, src: String?, check: () -> Unit): List<String> {
        val results = ArrayList<String>()
        var prev: List<Pair<String, String>> = emptyList()
        var i = 0
        while (i < lines.size) {
            check()
            val chunk = lines.subList(i, minOf(i + 30, lines.size))
            val numbered = chunk.mapIndexed { n, t -> "${n + 1}| $t" }.joinToString("\n")
            val raw = ask(system(src, true), userMessage(numbered, prev), 120 + 90 * chunk.size)
            val parsed = parseNumbered(raw, chunk.size).toMutableList()
            for (n in parsed.indices) if (parsed[n] == null) parsed[n] = translate(chunk[n], src, prev)
            val done = parsed.map { it ?: "" }
            results.addAll(done)
            prev = chunk.zip(done).takeLast(6)
            i += 30
        }
        return results
    }

    companion object {
        fun parseNumbered(raw: String, n: Int): List<String?> {
            val found = HashMap<Int, String>()
            val re = Regex("^\\s*(\\d+)\\s*[|｜.:)]\\s*(.*)$")
            for (line in raw.lines()) {
                val m = re.find(line) ?: continue
                val idx = m.groupValues[1].toInt() - 1
                if (idx in 0 until n && idx !in found) found[idx] = cleanOutput(m.groupValues[2])
            }
            return (0 until n).map { found[it] }
        }

        fun cleanOutput(s: String): String {
            var t = s.trim()
            if (t.length >= 2 && t.first() in "\"'“「『" && t.last() in "\"'”」』") t = t.substring(1, t.length - 1).trim()
            return t
        }
    }
}
