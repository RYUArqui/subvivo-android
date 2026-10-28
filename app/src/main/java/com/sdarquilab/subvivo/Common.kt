package com.sdarquilab.subvivo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// ───────────────────────────────────────────────────────── 언어
object Langs {
    val LIST = listOf(
        "ja" to "일본어", "es" to "스페인어", "ca" to "카탈루냐어", "pt" to "포르투갈어",
        "gl" to "갈리시아어", "eu" to "바스크어", "en" to "영어", "fr" to "프랑스어",
        "it" to "이탈리아어", "de" to "독일어", "zh" to "중국어", "ko" to "한국어",
    )
    val SENSE_VOICE = setOf("ja", "zh", "en", "ko", "yue")

    fun name(code: String?): String {
        if (code.isNullOrBlank()) return "알 수 없음"
        return LIST.firstOrNull { it.first == code }?.second ?: code
    }
}

// ───────────────────────────────────────────────────────── 모델
data class ModelSpec(
    val id: String,
    val label: String,
    val kind: String, // whisper | sensevoice | speaker
    val files: List<String>,
    val approxMb: Int,
)

object Models {
    /** 이 앱 저장소의 'models' 릴리스 (GitHub Actions가 sherpa-onnx 공개 모델을 옮겨 둔 곳) */
    const val BASE = "https://github.com/RYUArqui/subvivo-android/releases/download/models/"

    private fun whisper(size: String, label: String, mb: Int) = ModelSpec(
        "whisper-$size", label, "whisper",
        listOf("whisper-$size-encoder.int8.onnx", "whisper-$size-decoder.int8.onnx", "whisper-$size-tokens.txt"), mb,
    )

    val ASR = listOf(
        ModelSpec(
            "sense-voice", "빠르고 정확 · SenseVoice (일·중·영·한 전용)", "sensevoice",
            listOf("sense-voice-model.int8.onnx", "sense-voice-tokens.txt"), 230,
        ),
        whisper("tiny", "가벼움 · Whisper tiny (모든 언어, 정확도 낮음)", 100),
        whisper("base", "균형 · Whisper base (모든 언어)", 155),
        whisper("small", "정확 · Whisper small (모든 언어, 느림)", 360),
    )
    val SPEAKER = ModelSpec("speaker", "목소리 구분 모델", "speaker", listOf("wespeaker_en_voxceleb_resnet34.onnx"), 26)

    fun byId(id: String): ModelSpec = ASR.firstOrNull { it.id == id } ?: ASR[2]
    fun dir(ctx: Context): File = File(ctx.filesDir, "models").apply { mkdirs() }
    fun file(ctx: Context, name: String): File = File(dir(ctx), name)
    fun ready(ctx: Context, spec: ModelSpec): Boolean = spec.files.all { file(ctx, it).length() > 0 }

    /** 모자란 파일만 내려받는다. 끊기면 이어받기. */
    fun download(ctx: Context, spec: ModelSpec, progress: (String) -> Unit, cancelled: () -> Boolean = { false }) {
        val todo = spec.files.filter { file(ctx, it).length() == 0L }
        for ((i, name) in todo.withIndex()) {
            val out = file(ctx, name)
            val tmp = File(out.path + ".part")
            var have = if (tmp.exists()) tmp.length() else 0L
            val conn = URL(BASE + name).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code == 416) { // 이미 다 받음
                tmp.renameTo(out); conn.disconnect(); continue
            }
            if (code !in 200..299) throw IOException("모델 내려받기 실패 (HTTP $code, $name)")
            if (code == 200) have = 0L // 서버가 이어받기를 안 받아 줌 → 처음부터
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong + have else -1L
            var got = have
            var lastShown = -1L
            conn.inputStream.use { inp ->
                FileOutputStream(tmp, have > 0).use { o ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (cancelled()) throw IOException("내려받기를 취소했습니다.")
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        got += n
                        val mb = got / (1 shl 20)
                        if (mb != lastShown) {
                            lastShown = mb
                            val pct = if (total > 0) " ${100 * got / total}%" else ""
                            val tot = if (total > 0) " / ${total / (1 shl 20)}MB" else ""
                            progress("${spec.label} 받는 중 (${i + 1}/${todo.size})$pct — ${mb}MB$tot")
                        }
                    }
                }
            }
            conn.disconnect()
            if (total > 0 && got < total) throw IOException("내려받기가 중간에 끊겼습니다. 다시 시도하면 이어서 받습니다.")
            if (!tmp.renameTo(out)) throw IOException("파일을 저장하지 못했습니다: $name")
        }
    }
}

// ───────────────────────────────────────────────────────── 설정
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("subvivo", Context.MODE_PRIVATE)
    private fun s(k: String, d: String): String = p.getString(k, d) ?: d
    private fun b(k: String, d: Boolean): Boolean = p.getBoolean(k, d)
    private fun i(k: String, d: Int): Int = p.getInt(k, d)
    private fun put(k: String, v: Any?) {
        val e = p.edit()
        when (v) {
            is String -> e.putString(k, v)
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            null -> e.remove(k)
        }
        e.apply()
    }

    var model: String
        get() = s("model", "whisper-base")
        set(v) = put("model", v)
    var liveLang: String
        get() = s("liveLang", "ja")
        set(v) = put("liveLang", v)
    var fileLang: String // "" = 자동 감지
        get() = s("fileLang", "")
        set(v) = put("fileLang", v)
    var translator: String // google | claude | none
        get() = s("translator", "google")
        set(v) = put("translator", v)
    var claudeKey: String
        get() = s("claudeKey", "")
        set(v) = put("claudeKey", v)
    var claudeModel: String
        get() = s("claudeModel", "claude-haiku-5-5")
        set(v) = put("claudeModel", v)
    var notes: String
        get() = s("notes", "")
        set(v) = put("notes", v)
    var diarize: Boolean
        get() = b("diarize", true)
        set(v) = put("diarize", v)
    var sensitivity: String
        get() = s("sensitivity", "보통")
        set(v) = put("sensitivity", v)
    var srtColors: Boolean
        get() = b("srtColors", true)
        set(v) = put("srtColors", v)
    var fontSize: Int
        get() = i("fontSize", 22)
        set(v) = put("fontSize", v)
    var showSource: Boolean
        get() = b("showSource", true)
        set(v) = put("showSource", v)
    var boxStyle: Boolean
        get() = b("boxStyle", false)
        set(v) = put("boxStyle", v)
    var preview: Boolean
        get() = b("preview", true)
        set(v) = put("preview", v)
    var source: String // app | mic
        get() = s("source", "app")
        set(v) = put("source", v)
    var overlayY: Int
        get() = i("overlayY", -1)
        set(v) = put("overlayY", v)
    var skipExisting: Boolean
        get() = b("skipExisting", true)
        set(v) = put("skipExisting", v)
    var outTree: String // "" = Download/SubVivo
        get() = s("outTree", "")
        set(v) = put("outTree", v)
    val threads: Int
        get() = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
}

// ───────────────────────────────────────────────────────── 화면과 처리 사이의 소식
data class Ev(
    val type: String,
    val text: String = "",
    val ko: String = "",
    val src: String = "",
    val speaker: Int? = null,
    val newSpeaker: Boolean = false,
    val sid: Int = 0,
    val index: Int = -1,
    val frac: Float = 0f,
    val start: Double = 0.0,
    val delay: Double = 0.0,
    val sttMs: Long = 0,
    val trMs: Long = 0,
    val lang: String = "",
)

object Bus {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Ev) -> Unit>()
    private val KEEP = setOf("info", "warn", "error", "started", "stopped", "subtitle")

    /** 화면을 다시 열었을 때 보여 줄 최근 기록 (메인 스레드에서만 다룸) */
    val history = ArrayDeque<Ev>()

    fun listen(l: (Ev) -> Unit) = listeners.add(l)
    fun unlisten(l: (Ev) -> Unit) = listeners.remove(l)

    fun post(e: Ev) {
        main.post {
            if (e.type in KEEP) {
                history.addLast(e)
                while (history.size > 300) history.removeFirst()
            }
            for (l in listeners) l(e)
        }
    }

    fun status(t: String) = post(Ev("status", t))
    fun info(t: String) = post(Ev("info", t))
    fun warn(t: String) = post(Ev("warn", t))
}

// ───────────────────────────────────────────────────────── 취소·일시정지
class Cancelled : Exception("취소했습니다.")

class JobControl {
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    @Volatile var cancelled = false; private set
    @Volatile var paused = false; private set

    fun reset() = lock.withLock { cancelled = false; paused = false; cond.signalAll() }
    fun pause() = lock.withLock { paused = true }
    fun resume() = lock.withLock { paused = false; cond.signalAll() }
    fun cancel() = lock.withLock { cancelled = true; paused = false; cond.signalAll() }

    fun checkpoint(onPause: () -> Unit = {}, onResume: () -> Unit = {}) {
        if (cancelled) throw Cancelled()
        if (!paused) return
        onPause()
        lock.withLock { while (paused && !cancelled) cond.await() }
        if (cancelled) throw Cancelled()
        onResume()
    }
}

// ───────────────────────────────────────────────────────── 알림
object Notif {
    const val CHANNEL = "subvivo"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "SubVivo", NotificationManager.IMPORTANCE_LOW))
        }
    }

    fun build(ctx: Context, text: String, stop: PendingIntent? = null, stopLabel: String = "정지"): Notification {
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("SubVivo")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (stop != null) {
            b.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_notify), stopLabel, stop).build())
        }
        return b.build()
    }
}
