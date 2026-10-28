package com.sdarquilab.subvivo

import android.graphics.Color
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlin.math.min
import kotlin.math.sqrt

/** 목소리 구분 — 데스크톱판 speakers.py 이식 (같은 모델·같은 기준값). */
object SpeakerStyle {
    private val PALETTE = intArrayOf(
        0xFFFFE14D.toInt(), 0xFF5CD6FF.toInt(), 0xFF8DFF8A.toInt(),
        0xFFFF9AD5.toInt(), 0xFFFFB36B.toInt(), 0xFFC9A7FF.toInt(),
    )
    private val HEX = arrayOf("#ffe14d", "#5cd6ff", "#8dff8a", "#ff9ad5", "#ffb36b", "#c9a7ff")
    private val NAMES = arrayOf("노랑", "하늘", "연두", "분홍", "주황", "보라")

    /** 흰 바탕 기록창에서 읽히는 진한 색 */
    private val LOG = intArrayOf(
        0xFFA07D00.toInt(), 0xFF0A7FAE.toInt(), 0xFF25902A.toInt(),
        0xFFBF2F7A.toInt(), 0xFFBF640C.toInt(), 0xFF7046C4.toInt(),
    )

    val SENSITIVITY = linkedMapOf("낮음 (덜 쪼갬)" to 0.55f, "보통" to 0.60f, "높음 (잘게 구분)" to 0.68f)
    const val DEFAULT_TH = 0.60f

    fun color(spk: Int?): Int = if (spk == null) Color.WHITE else PALETTE[spk % PALETTE.size]
    fun hex(spk: Int?): String? = if (spk == null) null else HEX[spk % HEX.size]
    fun name(spk: Int?): String = if (spk == null) "흰색" else NAMES[spk % NAMES.size]
    fun logColor(spk: Int?): Int = if (spk == null) 0xFF222222.toInt() else LOG[spk % LOG.size]
    fun threshold(label: String): Float = SENSITIVITY[label] ?: DEFAULT_TH
}

private fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

private fun normalized(v: FloatArray): FloatArray? {
    val n = sqrt(dot(v, v))
    if (n <= 0f) return null
    return FloatArray(v.size) { v[it] / n }
}

/** 소리 → 목소리 지문 (길이 1) */
class Embedder(modelPath: String, threads: Int = 2) {
    private val ex = SpeakerEmbeddingExtractor(
        config = SpeakerEmbeddingExtractorConfig(model = modelPath, numThreads = threads),
    )

    @Synchronized
    fun embed(audio: FloatArray): FloatArray? {
        if (audio.size < (0.4 * SR).toInt()) return null
        val st = ex.createStream()
        try {
            st.acceptWaveform(audio, SR)
            st.inputFinished()
            if (!ex.isReady(st)) return null
            return normalized(ex.compute(st))
        } finally {
            st.release()
        }
    }

    fun release() = ex.release()
}

/** 실시간용: 대사가 올 때마다 목소리 번호를 붙인다. */
class OnlineSpeakers(
    private val th: Float = SpeakerStyle.DEFAULT_TH,
    private val maxSpeakers: Int = 6,
    private val minSeconds: Double = 1.0,
) {
    private val sums = ArrayList<FloatArray>()
    private val counts = ArrayList<Double>()
    var last: Int? = null; private set
    val size get() = sums.size

    fun centroids(): List<FloatArray> = sums.map { normalized(it) ?: it }

    /** (번호, 새로 등장했는지) */
    fun assign(emb: FloatArray?, seconds: Double): Pair<Int?, Boolean> {
        if (emb == null) return Pair(null, false)
        if (seconds < minSeconds) {
            // 짧은 대사는 지문이 불안정 → 아주 확실할 때만 기존 목소리, 아니면 판단 보류
            if (sums.isEmpty()) return Pair(null, false)
            val sims = centroids().map { dot(it, emb) }
            val best = sims.indices.maxByOrNull { sims[it] }!!
            return Pair(if (sims[best] >= th + 0.08f) best else null, false)
        }
        if (sums.isEmpty()) return Pair(add(emb, seconds), true)
        val sims = centroids().map { dot(it, emb) }
        val best = sims.indices.maxByOrNull { sims[it] }!!
        if (sims[best] >= th || sums.size >= maxSpeakers) {
            val w = min(seconds, 8.0).toFloat()
            val s = sums[best]
            for (i in s.indices) s[i] += emb[i] * w
            counts[best] = counts[best] + seconds
            last = best
            return Pair(best, false)
        }
        return Pair(add(emb, seconds), true)
    }

    private fun add(emb: FloatArray, seconds: Double): Int {
        val w = min(seconds, 8.0).toFloat()
        sums.add(FloatArray(emb.size) { emb[it] * w })
        counts.add(seconds)
        last = sums.size - 1
        return sums.size - 1
    }
}

/** 파일용: 전체 대사를 본 뒤 목소리 번호를 정한다 (재배정·작은 묶음 흡수·등장순 번호). */
fun clusterOffline(
    embs: List<FloatArray?>,
    seconds: List<Double>,
    th: Float = SpeakerStyle.DEFAULT_TH,
    maxSpeakers: Int = 6,
    minShare: Double = 0.03,
): List<Int?> {
    val online = OnlineSpeakers(th, maxSpeakers * 2, 0.0)
    for (i in embs.indices) if (embs[i] != null) online.assign(embs[i], seconds[i])
    if (online.size == 0) return embs.map { null }

    val idx = embs.indices.filter { embs[it] != null }
    val e = idx.map { embs[it]!! }
    val w = idx.map { min(seconds[it], 8.0) }
    var cent = online.centroids()

    fun labels(): IntArray = IntArray(e.size) { j -> cent.indices.maxByOrNull { dot(cent[it], e[j]) }!! }

    repeat(2) {
        val lab = labels()
        val next = ArrayList<FloatArray>()
        for (k in cent.indices) {
            val v = FloatArray(e[0].size)
            var any = false
            for (j in e.indices) if (lab[j] == k) {
                any = true
                for (d in v.indices) v[d] += e[j][d] * w[j].toFloat()
            }
            if (any) normalized(v)?.let { next.add(it) }
        }
        cent = next
    }
    var lab = labels()
    while (cent.size > 1) {
        val total = w.sum().coerceAtLeast(1e-6)
        val share = DoubleArray(cent.size)
        for (j in e.indices) share[lab[j]] += w[j]
        for (k in share.indices) share[k] /= total
        val small = cent.indices.filter { share[it] < minShare }
        if (small.isEmpty() && cent.size <= maxSpeakers) break
        val drop = (if (small.isNotEmpty()) small else cent.indices.toList()).minByOrNull { share[it] }!!
        cent = cent.filterIndexed { k, _ -> k != drop }
        lab = labels()
    }
    val remap = LinkedHashMap<Int, Int>()
    for (k in lab) if (k !in remap) remap[k] = remap.size
    val out = arrayOfNulls<Int>(embs.size)
    for ((j, i) in idx.withIndex()) out[i] = remap[lab[j]]
    return out.toList()
}
