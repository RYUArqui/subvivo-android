package com.sdarquilab.subvivo

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.IOException
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ───────────────────────────────────────────────────────── 영상에서 소리 꺼내기 (재생 없이, 조금씩)
/** 늘어나는 샘플링 레이트 변환 (선형 보간 + 내려받을 때 간단한 평균 필터) */
class Resampler(private val target: Int = SR) {
    private var pos = 0.0
    private var last = 0f
    private var hasLast = false

    fun reset() {
        pos = 0.0; hasLast = false
    }

    fun process(input: FloatArray, rate: Int): FloatArray {
        if (rate == target || input.isEmpty()) return input
        val step = rate.toDouble() / target
        val k = floor(step).toInt()
        val x = if (k >= 2) { // 평균 필터로 잡음 접힘 줄이기
            FloatArray(input.size) { i ->
                var s = 0f; var c = 0
                for (j in max(0, i - k / 2) until min(input.size, i + k - k / 2)) { s += input[j]; c++ }
                s / c
            }
        } else input
        val src = if (hasLast) FloatArray(x.size + 1).also { it[0] = last; System.arraycopy(x, 0, it, 1, x.size) } else x
        val out = FloatArray(((src.size - pos) / step).toInt() + 2)
        var n = 0
        while (pos + 1 < src.size) {
            val i = pos.toInt()
            val fr = (pos - i).toFloat()
            out[n++] = src[i] * (1 - fr) + src[i + 1] * fr
            pos += step
        }
        pos -= (src.size - 1)
        last = src[src.size - 1]
        hasLast = true
        return out.copyOf(n)
    }
}

class AudioDecoder(ctx: Context, uri: Uri) : Closeable {
    private val ex = MediaExtractor()
    private val codec: MediaCodec
    private val format: MediaFormat
    val durationSec: Double
    private var inEos = false
    private var outEos = false
    private var rate: Int
    private var channels: Int
    private var floatPcm = false
    private val rs = Resampler()

    init {
        ex.setDataSource(ctx, uri, null)
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; fmt = f; break }
        }
        if (track < 0 || fmt == null) { ex.release(); throw IOException("이 파일에는 소리 트랙이 없습니다.") }
        format = fmt
        ex.selectTrack(track)
        durationSec = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1e6 else 0.0
        rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
    }

    fun seekTo(sec: Double) {
        ex.seekTo((sec * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inEos = false; outEos = false
        rs.reset()
    }

    /** 다음 소리 조각 (16kHz 모노). 끝이면 null */
    fun read(): FloatArray? {
        val info = MediaCodec.BufferInfo()
        while (!outEos) {
            if (!inEos) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val b = codec.getInputBuffer(ii)!!
                    val n = ex.readSampleData(b, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inEos = true
                    } else {
                        codec.queueInputBuffer(ii, 0, n, max(0L, ex.sampleTime), 0)
                        ex.advance()
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10_000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            } else if (oi >= 0) {
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true
                var mono = FloatArray(0)
                if (info.size > 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val bb = ob.slice().order(ByteOrder.nativeOrder())
                    val ch = max(1, channels)
                    mono = if (floatPcm) {
                        val fb = bb.asFloatBuffer()
                        val frames = fb.remaining() / ch
                        FloatArray(frames) { i -> var s = 0f; for (c in 0 until ch) s += fb.get(i * ch + c); s / ch }
                    } else {
                        val sb = bb.asShortBuffer()
                        val frames = sb.remaining() / ch
                        FloatArray(frames) { i -> var s = 0f; for (c in 0 until ch) s += sb.get(i * ch + c); s / ch / 32768f }
                    }
                }
                codec.releaseOutputBuffer(oi, false)
                val out = rs.process(mono, rate)
                if (out.isNotEmpty()) return out
            }
        }
        return null
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { ex.release() }
    }
}

// ───────────────────────────────────────────────────────── 파일 하나 처리
class FileResult(val count: Int, val lang: String?, val langProb: Float, val speakers: Int, val savedKo: String?)

fun displayName(ctx: Context, uri: Uri): String {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.let { return it }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "video"
}

fun baseName(name: String): String = name.substringBeforeLast('.', name)

class FileJob(
    private val ctx: Context,
    private val prefs: Prefs,
    private val control: JobControl,
    private val progress: (Float, String) -> Unit,
) {
    private var lastFrac = 0f
    private var lastText = ""

    private fun report(f: Float, t: String) {
        lastFrac = f; lastText = t
        progress(f, t)
    }

    private fun check() = control.checkpoint(
        onPause = { progress(lastFrac, "일시정지됨") },
        onResume = { progress(lastFrac, lastText.ifBlank { "다시 시작" }) },
    )

    /** 영상 곳곳 6군데의 말소리를 뽑아 언어를 투표로 정한다 (첫머리 음악·무음에 속지 않게). */
    private fun detectLanguage(uri: Uri, spec: ModelSpec, asrForSv: () -> Asr): Pair<String?, Float> {
        val votes = HashMap<String, Int>()
        var tries = 0
        val lid = if (spec.kind == "whisper") WhisperLangId(ctx, spec, prefs.threads) else null
        try {
            AudioDecoder(ctx, uri).use { dec ->
                val dur = dec.durationSec
                val vad = makeVad(ctx)
                val n = if (dur > 120) 6 else 1
                for (k in 0 until n) {
                    check()
                    val at = if (n == 1) 0.0 else max(0.0, dur * (k + 0.5) / n - 15)
                    dec.seekTo(at)
                    val seg = VadSegmenter(vad, endSilence = 0.3, maxLen = 10.0)
                    val speech = ArrayList<FloatArray>()
                    var decoded = 0.0
                    var speechSec = 0.0
                    while (decoded < 60 && speechSec < 25) {
                        val chunk = dec.read() ?: break
                        decoded += chunk.size.toDouble() / SR
                        for (s in seg.push(chunk)) { speech.add(s.audio); speechSec += s.audio.size.toDouble() / SR }
                    }
                    for (s in seg.flush()) { speech.add(s.audio); speechSec += s.audio.size.toDouble() / SR }
                    if (speechSec < 2.0) continue
                    val all = FloatArray(min(speech.sumOf { it.size }, 28 * SR))
                    var o = 0
                    for (s in speech) { val c = min(s.size, all.size - o); if (c <= 0) break; System.arraycopy(s, 0, all, o, c); o += c }
                    val l = if (lid != null) lid.detect(all) else asrForSv().transcribe(all).second
                    tries++
                    if (l != null) votes[l] = (votes[l] ?: 0) + 1
                    report(0.01f + 0.04f * (k + 1) / n, "언어 알아내는 중… (${k + 1}/$n)")
                }
                vad.release()
            }
        } finally {
            lid?.release()
        }
        val best = votes.maxByOrNull { it.value } ?: return Pair(null, 0f)
        return Pair(best.key, best.value.toFloat() / max(1, tries))
    }

    fun run(uri: Uri): FileResult {
        val spec = Models.byId(prefs.model)
        val name = baseName(displayName(ctx, uri))
        var lang: String? = prefs.fileLang.ifBlank { null }
        var prob = 1f
        var svAsr: Asr? = null
        report(0.01f, "준비 중…")
        try {
            if (lang == null) {
                report(0.02f, "언어 알아내는 중…")
                val (l, p) = detectLanguage(uri, spec) { svAsr ?: Asr(ctx, spec, null, prefs.threads).also { svAsr = it } }
                lang = l; prob = p
                if (spec.kind == "sensevoice" && l != null && l !in Langs.SENSE_VOICE) {
                    Bus.warn("$name: SenseVoice는 일·중·영·한 전용입니다. 다른 언어 영상은 Whisper 모델을 고르세요.")
                }
                report(0.05f, "언어: ${Langs.name(lang)} (${(prob * 100).roundToInt()}%)")
            }
        } finally {
            svAsr?.release()
        }
        check()

        val asr = Asr(ctx, spec, lang, prefs.threads)
        var embedder: Embedder? = null
        if (prefs.diarize) {
            val f = Models.file(ctx, Models.SPEAKER.files[0])
            if (f.exists()) embedder = runCatching { Embedder(f.absolutePath, 2) }.getOrNull()
        }
        val cues = ArrayList<Cue>()
        val embs = ArrayList<FloatArray?>()
        val secs = ArrayList<Double>()
        try {
            AudioDecoder(ctx, uri).use { dec ->
                val dur = max(1.0, dec.durationSec)
                val vad = makeVad(ctx)
                val seg = VadSegmenter(vad, endSilence = 0.5, maxLen = 10.0)
                fun handle(s: Segment) {
                    check()
                    val (text, _) = asr.transcribe(s.audio)
                    if (!Hallu.bad(text)) {
                        cues.add(Cue(s.t0, s.t1, text))
                        embs.add(embedder?.let { runCatching { it.embed(s.audio) }.getOrNull() })
                        secs.add(s.audio.size.toDouble() / SR)
                    }
                    val f = min(1.0, s.t1 / dur)
                    report((0.05 + 0.80 * f).toFloat(), "받아쓰기 ${(f * 100).roundToInt()}% · ${text.take(30)}")
                }
                while (true) {
                    val chunk = dec.read() ?: break
                    for (s in seg.push(chunk)) handle(s)
                }
                for (s in seg.flush()) handle(s)
                vad.release()
            }
        } finally {
            asr.release()
            embedder?.release()
        }
        check()

        var nSpk = 0
        if (embedder != null && cues.isNotEmpty()) {
            val labels = clusterOffline(embs, secs, SpeakerStyle.threshold(prefs.sensitivity))
            for (i in cues.indices) cues[i].speaker = labels[i]
            nSpk = labels.filterNotNull().toSet().size
            report(0.86f, "목소리 ${nSpk}명 구분")
        }

        val tr = Translator.make(prefs)
        if (cues.isNotEmpty() && tr !is NoTranslator) {
            val lines = cues.map { it.source }
            val out = ArrayList<String>()
            var i = 0
            while (i < lines.size) {
                check()
                val end = min(lines.size, i + 30)
                out.addAll(tr.translateBatch(lines.subList(i, end), lang) { check() })
                report(0.86f + 0.13f * end / lines.size, "번역 $end/${lines.size}줄")
                i = end
            }
            for (k in cues.indices) cues[k].korean = out.getOrElse(k) { "" }
        }

        val colors = prefs.srtColors && nSpk > 0
        SrtStore.save(ctx, prefs, "$name.${lang ?: "src"}.srt", Srt.build(cues, "source", colors = colors))
        val saved = if (tr !is NoTranslator) SrtStore.save(ctx, prefs, "$name.ko.srt", Srt.build(cues, "korean", colors = colors)) else null
        report(1f, "완료")
        return FileResult(cues.size, lang, prob, nSpk, saved)
    }
}

// ───────────────────────────────────────────────────────── 파일 목록 처리 서비스 (화면을 꺼도 계속)
object FileQueue {
    class Item(val uri: Uri, val name: String) {
        var lang = ""
        var state = "대기"
    }

    val items = ArrayList<Item>()
    val control = JobControl()
    @Volatile var running = false
    @Volatile var done = 0
}

class FileJobService : Service() {
    companion object {
        const val ACTION_CANCEL = "com.sdarquilab.subvivo.CANCEL_FILES"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            FileQueue.control.cancel()
            return START_NOT_STICKY
        }
        if (FileQueue.running) return START_NOT_STICKY
        val cancelPi = PendingIntent.getService(
            this, 2, Intent(this, FileJobService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        startForeground(2, Notif.build(this, "파일 자막 만드는 중", cancelPi, "취소"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        FileQueue.running = true
        FileQueue.done = 0
        FileQueue.control.reset()
        val wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "subvivo:files").apply { acquire(10 * 60 * 60 * 1000L) }
        val nm = getSystemService(NotificationManager::class.java)
        thread(name = "subvivo-files") {
            val prefs = Prefs(this)
            val items = synchronized(FileQueue.items) { FileQueue.items.toList() }
            var lastNotif = 0L
            try {
                for ((idx, item) in items.withIndex()) {
                    try {
                        FileQueue.control.checkpoint()
                    } catch (c: Cancelled) {
                        break
                    }
                    if (item.state.startsWith("완료")) { FileQueue.done++; continue }
                    if (prefs.skipExisting && SrtStore.exists(this, prefs, baseName(item.name) + ".ko.srt")) {
                        item.state = "건너뜀 (이미 있음)"
                        FileQueue.done++
                        Bus.post(Ev("file_item", index = idx))
                        continue
                    }
                    item.state = "준비 중…"
                    Bus.post(Ev("file_item", index = idx))
                    try {
                        val job = FileJob(this, prefs, FileQueue.control) { frac, text ->
                            if (text.startsWith("언어: ")) item.lang = text.removePrefix("언어: ")
                            item.state = if (text == "일시정지됨") "${(frac * 100).roundToInt()}% 일시정지됨" else "${(frac * 100).roundToInt()}% ${text.substringBefore(" · ").take(18)}"
                            Bus.post(Ev("file_progress", text = text, index = idx, frac = frac))
                            val now = System.currentTimeMillis()
                            if (now - lastNotif > 1500) {
                                lastNotif = now
                                nm.notify(2, Notif.build(this, "[${idx + 1}/${items.size}] ${(frac * 100).roundToInt()}% ${item.name}", cancelPi, "취소"))
                            }
                        }
                        val r = job.run(item.uri)
                        item.lang = Langs.name(r.lang) + if (prefs.fileLang.isBlank()) " ${(r.langProb * 100).roundToInt()}%" else ""
                        item.state = "완료 · ${r.count}줄" + (if (r.speakers > 0) " · 목소리 ${r.speakers}명" else "")
                        r.savedKo?.let { s -> Bus.info("저장: $s") }
                    } catch (c: Cancelled) {
                        item.state = "취소됨"
                        Bus.post(Ev("file_item", index = idx))
                        break
                    } catch (e: Throwable) {
                        item.state = "실패: ${(e.message ?: e.toString()).take(40)}"
                        Bus.warn("${item.name} 실패 — ${e.message ?: e}")
                    }
                    FileQueue.done++
                    Bus.post(Ev("file_item", index = idx))
                }
            } finally {
                FileQueue.running = false
                runCatching { if (wake.isHeld) wake.release() }
                Bus.post(Ev("file_done", "파일 자막 만들기가 끝났습니다. 결과: ${SrtStore.where(prefs)}"))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
}
