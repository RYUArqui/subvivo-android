package com.sdarquilab.subvivo

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.math.max
import kotlin.math.min

const val SR = 16000

// ───────────────────────────────────────────────────────── 받아쓰기 (휴대폰 안에서, 오프라인)
class Asr(private val ctx: Context, val spec: ModelSpec, language: String?, private val threads: Int) {
    private var lang: String? = norm(language)
    private var rec: OfflineRecognizer = build(lang)
    private var released = false

    private fun norm(l: String?): String? {
        if (l.isNullOrBlank()) return null
        if (spec.kind == "sensevoice" && l !in Langs.SENSE_VOICE) return null
        return l
    }

    private fun build(l: String?): OfflineRecognizer {
        fun f(n: String) = Models.file(ctx, n).absolutePath
        val mc = if (spec.kind == "whisper") {
            OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = f("${spec.id}-encoder.int8.onnx"),
                    decoder = f("${spec.id}-decoder.int8.onnx"),
                    language = l ?: "", // "" = 스스로 언어 감지
                    task = "transcribe",
                ),
                tokens = f("${spec.id}-tokens.txt"),
                numThreads = threads,
            )
        } else {
            OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = f("sense-voice-model.int8.onnx"),
                    language = l ?: "",
                    useInverseTextNormalization = true,
                ),
                tokens = f("sense-voice-tokens.txt"),
                numThreads = threads,
            )
        }
        return OfflineRecognizer(config = OfflineRecognizerConfig(featConfig = FeatureConfig(SR, 80), modelConfig = mc))
    }

    @Synchronized
    fun setLanguage(l: String?) {
        if (released) return
        val n = norm(l)
        if (n != lang) {
            rec.release()
            rec = build(n)
            lang = n
        }
    }

    /** (원문, 언어) */
    @Synchronized
    fun transcribe(samples: FloatArray): Pair<String, String?> {
        if (released) return Pair("", lang) // 정지 직후 남은 요청은 무시 (해제된 모델 사용 방지)
        val st = rec.createStream()
        try {
            st.acceptWaveform(samples, SR)
            rec.decode(st)
            val r = rec.getResult(st)
            val text = r.text.replace(Regex("<\\|[^|]*\\|>"), "").trim()
            return Pair(text, cleanLang(r.lang) ?: lang)
        } finally {
            st.release()
        }
    }

    @Synchronized
    fun release() {
        if (!released) {
            released = true
            rec.release()
        }
    }

    companion object {
        fun cleanLang(s: String?): String? {
            val t = s?.replace("<|", "")?.replace("|>", "")?.trim()?.lowercase()
            return if (t.isNullOrEmpty() || t == "nospeech" || t == "unk") null else t
        }
    }
}

/** 파일 모드 언어 자동 감지용 (Whisper 계열) */
class WhisperLangId(ctx: Context, spec: ModelSpec, threads: Int) {
    private val lid = SpokenLanguageIdentification(
        config = SpokenLanguageIdentificationConfig(
            whisper = SpokenLanguageIdentificationWhisperConfig(
                encoder = Models.file(ctx, "${spec.id}-encoder.int8.onnx").absolutePath,
                decoder = Models.file(ctx, "${spec.id}-decoder.int8.onnx").absolutePath,
            ),
            numThreads = threads,
        ),
    )

    fun detect(samples: FloatArray): String? {
        val st = lid.createStream()
        try {
            st.acceptWaveform(samples, SR)
            return Asr.cleanLang(lid.compute(st))
        } finally {
            st.release()
        }
    }

    fun release() = lid.release()
}

// ───────────────────────────────────────────────────────── 엉터리 문구 거르기 (데스크톱판과 같은 규칙)
object Hallu {
    private val PATTERNS = listOf(
        "amara\\.org", "subt[ií]tulos? (realizados|por|hechos)", "subt[ií]tols? (realitzats|fets)",
        "legendas? (pela|por) comunidade", "^¡?suscr[ií]bete", "gracias por ver", "thanks? for watching",
        "obrigad[oa] por assistir", "^\\W*m[úu]sica\\W*$",
        "^\\W*(\\[|\\()?(music|música|aplausos|applause|risas)(\\]|\\))?\\W*$",
        "ご視聴ありがとう", "チャンネル登録", "高評価", "字幕.{0,12}(提供|作成|制作)",
        "^\\W*[(（\\[【]?(音楽|拍手|笑)[)）\\]】]?\\W*$",
        "字幕由", "(谢谢|謝謝)(观看|觀看|收看)", "点赞|訂閱|订阅", "^\\W*$",
    )
    private val RE = Regex(PATTERNS.joinToString("|"), RegexOption.IGNORE_CASE)
    private val REPEAT = Regex("(.{2,10}?)\\1{4,}")

    fun bad(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || RE.containsMatchIn(t)) return true
        val m = REPEAT.find(t)
        if (m != null && m.value.length >= 0.6 * t.length) return true
        val words = t.split(Regex("\\s+"))
        if (words.size >= 6 && words.map { it.lowercase() }.toSet().size <= max(1, words.size / 4)) return true
        return false
    }
}

// ───────────────────────────────────────────────────────── 대사 자르기 (데스크톱판 segmenter.py 이식)
class Segment(
    val audio: FloatArray,
    val t0: Double,
    val t1: Double,
    val sid: Int,
    val partial: Boolean = false,
) {
    var speaker: Int? = null
    var newSpeaker = false
}

fun makeVad(ctx: Context): Vad = Vad(
    ctx.assets,
    VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(model = "silero_vad.onnx", threshold = 0.5f, windowSize = 512),
        sampleRate = SR,
        numThreads = 1,
    ),
)

class VadSegmenter(
    private val vad: Vad,
    private val th: Float = 0.4f,
    private val endSilence: Double = 0.4,
    private val maxLen: Double = 6.0,
    private val minSpeech: Double = 0.12,
    preroll: Double = 0.25,
) {
    private val frameLen = 512
    private val fdur = frameLen.toDouble() / SR
    private val negTh = max(0.05f, th - 0.15f)
    private val preMax = max(1, (preroll / fdur).toInt())
    private val pre = ArrayDeque<Pair<FloatArray, Double>>()
    private val buf = FloatArray(frameLen)
    private var bufLen = 0
    private val speech = ArrayList<FloatArray>()
    private var segT0 = 0.0
    private var voiced = 0
    private var silence = 0.0

    var inSpeech = false; private set
    var time = 0.0; private set // 다음 프레임의 시각(초) = 지금까지 받은 소리 길이
    var sid = 0; private set

    val speechSeconds: Double get() = if (inSpeech) speech.size * fdur else 0.0

    fun push(chunk: FloatArray): List<Segment> {
        val out = ArrayList<Segment>()
        var i = 0
        while (i < chunk.size) {
            val n = min(frameLen - bufLen, chunk.size - i)
            System.arraycopy(chunk, i, buf, bufLen, n)
            bufLen += n
            i += n
            if (bufLen == frameLen) {
                val f = buf.copyOf()
                bufLen = 0
                val p = vad.compute(f)
                frame(f, p)?.let { out.add(it) }
            }
        }
        return out
    }

    /** 말하던 대사를 마무리해서 내보낸다 (파일 끝 등). */
    fun flush(): List<Segment> {
        val seg = if (inSpeech) finalize(0.0) else null
        inSpeech = false
        pre.clear()
        vad.reset()
        return if (seg != null) listOf(seg) else emptyList()
    }

    /** 말하는 도중의 대사 복사본 (미리보기 자막용) */
    fun snapshot(): Segment? {
        if (!inSpeech || speech.isEmpty()) return null
        val a = concat(speech)
        return Segment(a, segT0, segT0 + a.size.toDouble() / SR, sid, partial = true)
    }

    private fun frame(f: FloatArray, p: Float): Segment? {
        val t0 = time
        time += fdur
        if (!inSpeech) {
            pre.addLast(Pair(f, t0))
            while (pre.size > preMax) pre.removeFirst()
            if (p >= th) {
                inSpeech = true
                sid += 1
                speech.clear()
                for (x in pre) speech.add(x.first)
                segT0 = pre.first().second
                pre.clear()
                voiced = 1
                silence = 0.0
            }
            return null
        }
        speech.add(f)
        if (p < negTh) {
            silence += fdur
        } else {
            silence = 0.0
            if (p >= th) voiced += 1
        }
        if (silence >= endSilence) {
            val seg = finalize(silence - 0.15)
            inSpeech = false
            return seg
        }
        if (speech.size * fdur >= maxLen) {
            // 너무 길면 끊어서 내보내고, 말이 이어지는 중이므로 바로 다음 덩어리 시작
            val seg = finalize(0.0)
            inSpeech = true
            sid += 1
            speech.clear()
            segT0 = time
            voiced = 0
            silence = 0.0
            return seg
        }
        return null
    }

    private fun finalize(trim: Double): Segment? {
        var frames: List<FloatArray> = speech
        if (trim > 0) {
            val n = (trim / fdur).toInt()
            if (n in 1 until frames.size) frames = frames.subList(0, frames.size - n)
        }
        val ok = frames.isNotEmpty() && voiced * fdur >= minSpeech
        val audio = if (ok) concat(frames) else null
        speech.clear()
        if (audio == null) return null
        return Segment(audio, segT0, segT0 + audio.size.toDouble() / SR, sid)
    }

    private fun concat(frames: List<FloatArray>): FloatArray {
        val out = FloatArray(frames.sumOf { it.size })
        var o = 0
        for (fr in frames) {
            System.arraycopy(fr, 0, out, o, fr.size)
            o += fr.size
        }
        return out
    }
}
