package com.sdarquilab.subvivo

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

class Cue(var start: Double, var end: Double, val source: String, var korean: String = "", var speaker: Int? = null)

object Srt {
    private fun ts(sec: Double): String {
        var ms = (max(0.0, sec) * 1000).roundToLong()
        val h = ms / 3_600_000; ms %= 3_600_000
        val m = ms / 60_000; ms %= 60_000
        val s = ms / 1000; ms %= 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms)
    }

    /** 너무 짧은 자막은 늘리고, 다음 자막과 겹치지 않게 맞춘다. */
    private fun fixTiming(cues: List<Cue>, minDur: Double = 1.2, maxDur: Double = 7.0): List<Cue> {
        val sorted = cues.sortedBy { it.start }
        return sorted.mapIndexed { i, c ->
            var end = max(c.end, c.start + minDur)
            end = min(end, c.start + max(maxDur, c.end - c.start))
            if (i + 1 < sorted.size) end = min(end, sorted[i + 1].start - 0.05)
            end = max(end, c.start + 0.3)
            Cue(c.start, end, c.source, c.korean, c.speaker)
        }
    }

    /** field = "korean" | "source". colors=true 면 목소리별 색을 <font color>로 (VLC·MX Player 등에서 보임) */
    fun build(cues: List<Cue>, field: String, withSource: Boolean = false, colors: Boolean = false): String {
        val sb = StringBuilder()
        val list = fixTiming(cues.filter { (if (field == "korean") it.korean else it.source).isNotBlank() })
        for ((i, c) in list.withIndex()) {
            var text = (if (field == "korean") c.korean else c.source).trim()
            val hex = if (colors) SpeakerStyle.hex(c.speaker) else null
            if (hex != null) text = text.lines().joinToString("\n") { "<font color=\"$hex\">$it</font>" }
            if (withSource && field == "korean" && c.source.isNotBlank()) text += "\n" + c.source.trim()
            sb.append(i + 1).append('\n').append(ts(c.start)).append(" --> ").append(ts(c.end)).append('\n')
                .append(text).append("\n\n")
        }
        return sb.toString()
    }
}

/** 자막 파일 저장: 고른 폴더(SAF) 또는 기본 '다운로드/SubVivo' */
object SrtStore {
    private const val REL = "Download/SubVivo/"

    fun where(p: Prefs): String = if (p.outTree.isBlank()) "다운로드/SubVivo" else "고른 폴더"

    fun exists(ctx: Context, p: Prefs, name: String): Boolean =
        if (p.outTree.isNotBlank()) findInTree(ctx, Uri.parse(p.outTree), name) != null
        else findInDownloads(ctx, name) != null

    /** 저장 후 보기 좋은 위치 설명을 돌려준다 */
    fun save(ctx: Context, p: Prefs, name: String, content: String): String {
        val bytes = ("﻿" + content).toByteArray(Charsets.UTF_8) // BOM: 일부 플레이어 한글 깨짐 방지
        if (p.outTree.isNotBlank()) {
            try {
                val tree = Uri.parse(p.outTree)
                findInTree(ctx, tree, name)?.let { DocumentsContract.deleteDocument(ctx.contentResolver, it) }
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val doc = DocumentsContract.createDocument(ctx.contentResolver, parent, "application/octet-stream", name)
                    ?: throw IllegalStateException("파일을 만들지 못했습니다")
                ctx.contentResolver.openOutputStream(doc, "wt")!!.use { it.write(bytes) }
                return "고른 폴더/$name"
            } catch (e: Exception) {
                Bus.warn("고른 폴더에 저장하지 못해 다운로드/SubVivo에 저장합니다 — ${e.message}")
            }
        }
        findInDownloads(ctx, name)?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
        val uri = insertDownload(ctx, name, "application/x-subrip")
            ?: insertDownload(ctx, name, "application/octet-stream")
            ?: throw IllegalStateException("다운로드 폴더에 저장하지 못했습니다")
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, done, null, null)
        return "다운로드/SubVivo/$name"
    }

    private fun insertDownload(ctx: Context, name: String, mime: String): Uri? = try {
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SubVivo")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
    } catch (e: Exception) {
        null
    }

    private fun findInDownloads(ctx: Context, name: String): Uri? {
        val proj = arrayOf(MediaStore.Downloads._ID)
        val sel = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        ctx.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, sel, arrayOf(name, "$REL%"), null)?.use { c ->
            if (c.moveToFirst()) return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
        }
        return null
    }

    private fun findInTree(ctx: Context, tree: Uri, name: String): Uri? = try {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val proj = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        var found: Uri? = null
        ctx.contentResolver.query(children, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) {
                    found = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    break
                }
            }
        }
        found
    } catch (e: Exception) {
        null
    }
}
