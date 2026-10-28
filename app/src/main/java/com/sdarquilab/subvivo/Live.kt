package com.sdarquilab.subvivo

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

/** 실시간 처리 흐름: 소리 받기 → 대사 자르기 → 받아쓰기 → 목소리 구분 → 번역 → 자막 창 (데스크톱 pipeline.py 이식) */
class LivePipeline(
    private val ctx: Context,
    private val prefs: Prefs,
    private val makeRecord: () -> AudioRecord,
    private val sourceLabel: String,
) {
    private class Item(val seg: Segment, val text: String, val lang: String?, val sttMs: Long)

    private val stop = AtomicBoolean(false)
    private val segQ = LinkedBlockingQueue<Segment>()
    private val partQ = LinkedBlockingQueue<Segment>()
    private val textQ = LinkedBlockingQueue<Item>()
    val cues: MutableList<Cue> = Collections.synchronizedList(ArrayList())
    private val history = ArrayList<Pair<String, String>>()

    @Volatile var running = false; private set
    @Volatile private var sttBusy = false
    @Volatile private var lastSttMs = 0L
    @Volatile private var finalizedSid = 0
    @Volatile private var shownSid = 0
    @Volatile private var nowSec = 0.0
    private var lastPreview = 0.0
    private var previewOffUntil = 0L

    private var asr: Asr? = null
    private var tr: Translator = NoTranslator()
    private var previewTr: Translator? = null
    private var embedder: Embedder? = null
    private var speakers: OnlineSpeakers? = null

    fun start() {
        running = true
        thread(name = "subvivo-boot") { boot() }
    }

    fun stop() {
        stop.set(true)
        running = false
    }

    private fun guard(name: String, fn: () -> Unit) = thread(name = name) {
        try {
            fn()
        } catch (e: InterruptedException) {
            // 정지
        } catch (e: Throwable) {
            if (!stop.get()) Bus.post(Ev("error", "처리 중 오류: ${e.message ?: e}"))
            running = false
        }
    }

    private fun boot() {
        try {
            Bus.status("번역기 준비 중…")
            tr = Translator.make(prefs)
            checkTranslator()
            if (prefs.preview && tr !is NoTranslator) previewTr = GoogleTranslator(timeoutMs = 3000)
            val spec = Models.byId(prefs.model)
            Bus.status("'${spec.label}' 모델 불러오는 중…")
            asr = Asr(ctx, spec, prefs.liveLang, prefs.threads)
            if (prefs.diarize) setupSpeakers()
            if (stop.get()) return
            val seg = VadSegmenter(makeVad(ctx))
            guard("subvivo-stt") { sttLoop() }
            guard("subvivo-tr") { trLoop() }
            guard("subvivo-capture") { captureLoop(seg) }
            Bus.post(Ev("started", "듣는 중 — $sourceLabel · ${spec.label} · ${Langs.name(prefs.liveLang)}"))
        } catch (e: Throwable) {
            running = false
            Bus.post(Ev("error", e.message ?: e.toString()))
        }
    }

    private fun checkTranslator() {
        if (tr is NoTranslator) return
        try {
            val t0 = System.currentTimeMillis()
            val out = tr.translate("Gracias.", "es", emptyList())
            Bus.info("번역 연결 확인: Gracias. → $out  (${System.currentTimeMillis() - t0}ms)")
        } catch (e: Exception) {
            Bus.warn("번역 시험 실패 — ${e.message?.take(300)}")
        }
    }

    private fun setupSpeakers() {
        try {
            val f = Models.file(ctx, Models.SPEAKER.files[0])
            if (!f.exists()) throw IllegalStateException("목소리 구분 모델이 없습니다")
            embedder = Embedder(f.absolutePath, 2)
            speakers = OnlineSpeakers(SpeakerStyle.threshold(prefs.sensitivity))
            Bus.info("목소리 구분 켜짐 (민감도 ${prefs.sensitivity}) — 목소리마다 자막 색이 달라집니다")
        } catch (e: Throwable) {
            embedder = null
            Bus.warn("목소리 구분을 켜지 못했습니다 — ${e.message}")
        }
    }

    // ───────────── 1. 소리 받기 + 대사 자르기
    private fun captureLoop(seg: VadSegmenter) {
        val rec = makeRecord()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("소리 받기를 시작하지 못했습니다 (권한 또는 기기 제한)")
        }
        rec.startRecording()
        val sbuf = ShortArray(1600) // 0.1초
        var silentSec = 0.0
        var warnedSilent = false
        try {
            while (!stop.get()) {
                val n = rec.read(sbuf, 0, sbuf.size)
                if (n < 0) throw IllegalStateException("소리 받기 오류 ($n)")
                if (n == 0) continue
                var peak = 0
                val f = FloatArray(n) { i -> val v = sbuf[i].toInt(); if (abs(v) > peak) peak = abs(v); v / 32768f }
                nowSec = seg.time + n.toDouble() / SR
                silentSec = if (peak == 0) silentSec + n.toDouble() / SR else 0.0
                if (silentSec > 15 && !warnedSilent) {
                    warnedSilent = true
                    Bus.warn(
                        "15초 동안 소리가 전혀 들어오지 않습니다. 영상이 재생 중인데도 이렇다면 그 앱이 소리 가져가기를 막은 것입니다" +
                            "(넷플릭스 등). 그때는 '마이크' 방식을 쓰세요.",
                    )
                }
                for (s in seg.push(f)) {
                    finalizedSid = max(finalizedSid, s.sid)
                    segQ.put(s)
                }
                maybePreview(seg)
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            val rest = seg.flush()
            for (s in rest) segQ.put(s)
        }
    }

    private fun maybePreview(seg: VadSegmenter) {
        if (!prefs.preview || !seg.inSpeech) return
        val now = seg.time
        if (seg.speechSeconds >= 1.0 && now - lastPreview >= 0.8 && !sttBusy && partQ.isEmpty() && segQ.isEmpty() &&
            lastSttMs <= 1500
        ) {
            seg.snapshot()?.let {
                lastPreview = now
                partQ.put(it)
            }
        }
    }

    // ───────────── 2. 받아쓰기 (+ 목소리 구분)
    private fun sttLoop() {
        val a = asr ?: return
        while (!stop.get()) {
            val seg = segQ.poll(50, TimeUnit.MILLISECONDS)
            if (seg == null) {
                var part: Segment? = null
                while (true) part = partQ.poll() ?: break
                if (part == null || part.sid <= finalizedSid) continue
                sttBusy = true
                val (text, lang) = try { a.transcribe(part.audio) } finally { sttBusy = false }
                if (text.isNotBlank() && !Hallu.bad(text) && part.sid > finalizedSid) textQ.put(Item(part, text, lang, 0))
                continue
            }
            // 밀렸으면 대기 중인 대사를 합쳐 한 번에
            val pending = arrayListOf(seg)
            while (pending.size < 4) pending.add(segQ.poll() ?: break)
            val merged = if (pending.size == 1) seg else {
                val gap = FloatArray((SR * 0.2).toInt())
                val parts = pending.flatMap { listOf(it.audio, gap) }
                val audio = FloatArray(parts.sumOf { it.size })
                var o = 0
                for (p in parts) { System.arraycopy(p, 0, audio, o, p.size); o += p.size }
                Segment(audio, pending.first().t0, pending.last().t1, pending.last().sid)
            }
            val t0 = System.currentTimeMillis()
            sttBusy = true
            var (text, lang) = try { a.transcribe(merged.audio) } finally { sttBusy = false }
            val ms = System.currentTimeMillis() - t0
            lastSttMs = ms
            if (Hallu.bad(text)) text = ""
            if (text.isNotBlank()) assignSpeaker(merged, pending)
            textQ.put(Item(merged, text, lang, ms)) // 빈 결과도 넘겨 미리보기를 지우게 함
        }
    }

    private fun assignSpeaker(target: Segment, pieces: List<Segment>) {
        val e = embedder ?: return
        val sp = speakers ?: return
        var best: Int? = null
        var bestDur = 0.0
        for (p in pieces) {
            val dur = p.audio.size.toDouble() / SR
            val (lab, isNew) = try { sp.assign(e.embed(p.audio), dur) } catch (t: Throwable) { Pair(null, false) }
            if (isNew) target.newSpeaker = true
            if (lab != null && dur > bestDur) { best = lab; bestDur = dur }
        }
        target.speaker = best
    }

    // ───────────── 3. 번역
    private fun trLoop() {
        while (!stop.get()) {
            val first = textQ.poll(100, TimeUnit.MILLISECONDS) ?: continue
            val items = arrayListOf(first)
            while (true) items.add(textQ.poll() ?: break)
            val finals = items.filter { !it.seg.partial }
            val partials = items.filter { it.seg.partial }
            if (finals.isNotEmpty()) handleFinals(finals)
            val last = partials.lastOrNull()
            if (last != null && last.seg.sid > shownSid && last.seg.sid > (finals.maxOfOrNull { it.seg.sid } ?: 0)) {
                handlePartial(last)
            }
        }
    }

    private fun handleFinals(all: List<Item>) {
        for (f in all.filter { it.text.isBlank() }) {
            shownSid = max(shownSid, f.seg.sid)
            Bus.post(Ev("partial_cancel", sid = f.seg.sid))
        }
        val finals = all.filter { it.text.isNotBlank() }
        if (finals.isEmpty()) return
        val seg: Segment
        val text: String
        if (finals.size >= 2) { // 번역이 밀림 → 합치기
            seg = Segment(finals.last().seg.audio, finals.first().seg.t0, finals.last().seg.t1, finals.last().seg.sid)
            seg.speaker = finals.last().seg.speaker
            seg.newSpeaker = finals.any { it.seg.newSpeaker }
            text = finals.joinToString(" ") { it.text }
        } else {
            seg = finals[0].seg
            text = finals[0].text
        }
        val lang = finals.last().lang
        val t0 = System.currentTimeMillis()
        val ko = try {
            tr.translate(text, lang, history)
        } catch (e: Exception) {
            Bus.warn("번역 실패 — ${e.message?.take(300)}")
            ""
        }
        val trMs = System.currentTimeMillis() - t0
        history.add(Pair(text, ko))
        while (history.size > 12) history.removeAt(0)
        cues.add(Cue(seg.t0, seg.t1, text, ko, seg.speaker))
        shownSid = max(shownSid, seg.sid)
        Bus.post(
            Ev(
                "subtitle", ko = ko, src = text, speaker = seg.speaker, newSpeaker = seg.newSpeaker, sid = seg.sid,
                start = seg.t0, delay = max(0.0, nowSec - seg.t1), sttMs = finals.last().sttMs, trMs = trMs, lang = lang ?: "",
            ),
        )
    }

    private fun handlePartial(item: Item) {
        var ko = ""
        val pt = previewTr
        if (pt != null && System.currentTimeMillis() >= previewOffUntil) {
            ko = try {
                pt.translate(item.text, item.lang, history)
            } catch (e: Exception) {
                previewOffUntil = System.currentTimeMillis() + 60_000 // 막히면 1분간 원문만
                ""
            }
        }
        if (item.seg.sid > shownSid) Bus.post(Ev("partial", ko = ko, src = item.text, sid = item.seg.sid))
    }

    fun release() {
        thread {
            Thread.sleep(800) // 처리 중이던 받아쓰기가 끝나길 잠시 기다림
            runCatching { asr?.release() }
            runCatching { embedder?.release() }
        }
    }
}

/** 화면이 꺼지거나 다른 앱을 보는 동안에도 실시간 자막을 계속하는 포그라운드 서비스 */
class LiveService : Service() {
    companion object {
        const val ACTION_STOP = "com.sdarquilab.subvivo.STOP_LIVE"
        @Volatile var running = false; private set
        /** 마지막 실시간 세션의 자막 (SRT 저장용) */
        val lastCues: MutableList<Cue> = Collections.synchronizedList(ArrayList())
    }

    private var pipeline: LivePipeline? = null
    private var projection: MediaProjection? = null
    private var overlay: Overlay? = null
    private var wake: PowerManager.WakeLock? = null
    private var finalSid = 0
    private var partialSid = 0
    private val listener: (Ev) -> Unit = { onEv(it) }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        val prefs = Prefs(this)
        val source = intent?.getStringExtra("source") ?: prefs.source
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra("data")

        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, LiveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = Notif.build(this, "실시간 자막 켜짐", stopPi)
        val type = if (source == "app") {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            startForeground(1, notif, type)
        } catch (e: Exception) {
            Bus.post(Ev("error", "실시간 자막을 시작하지 못했습니다: ${e.message}"))
            stopSelf()
            return START_NOT_STICKY
        }
        running = true

        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SR)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = max(minBuf, SR * 2)

        val maker: () -> AudioRecord
        val label: String
        if (source == "app") {
            if (code != Activity.RESULT_OK || data == null) {
                Bus.post(Ev("error", "화면 녹화(소리 가져가기) 허용이 필요합니다."))
                stopSelf()
                return START_NOT_STICKY
            }
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val mp = mpm.getMediaProjection(code, data)
            if (mp == null) {
                Bus.post(Ev("error", "소리 가져가기 권한을 얻지 못했습니다."))
                stopSelf()
                return START_NOT_STICKY
            }
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))
            projection = mp
            maker = {
                val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(bufBytes)
                    .setAudioPlaybackCaptureConfig(cfg).build()
            }
            label = "다른 앱 소리"
        } else {
            maker = {
                AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(fmt).setBufferSizeInBytes(bufBytes).build()
            }
            label = "마이크"
        }

        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "subvivo:live").apply { acquire(6 * 60 * 60 * 1000L) }
        overlay = Overlay(this, prefs).also { it.attach() }
        Bus.listen(listener)
        lastCues.clear()
        pipeline = LivePipeline(this, prefs, maker, label).also { it.start() }
        return START_NOT_STICKY
    }

    private fun onEv(e: Ev) {
        val ov = overlay ?: return
        when (e.type) {
            "subtitle" -> {
                finalSid = max(finalSid, e.sid)
                ov.show(e.ko, e.src, color = if (e.speaker != null) SpeakerStyle.color(e.speaker) else null)
            }
            "partial" -> if (e.sid > finalSid) {
                partialSid = e.sid
                ov.show(e.ko, e.src, partial = true)
            }
            "partial_cancel" -> if (e.sid == partialSid && ov.partialShown) ov.clear()
            "style" -> ov.applyStyle()
            "error" -> stopSelf()
        }
    }

    override fun onDestroy() {
        Bus.unlisten(listener)
        pipeline?.let {
            it.stop()
            lastCues.addAll(it.cues)
            it.release()
        }
        pipeline = null
        overlay?.detach()
        overlay = null
        runCatching { projection?.stop() }
        projection = null
        runCatching { if (wake?.isHeld == true) wake?.release() }
        running = false
        Bus.post(Ev("stopped", "정지됨. 자막 ${lastCues.size}줄 — ‘SRT로 저장’으로 보관할 수 있습니다."))
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
