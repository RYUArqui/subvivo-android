package com.sdarquilab.subvivo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private val listener: (Ev) -> Unit = { onEv(it) }

    private lateinit var modelStatus: TextView
    private lateinit var btnModel: Button
    private lateinit var status: TextView
    private lateinit var liveBox: LinearLayout
    private lateinit var fileBox: LinearLayout
    private lateinit var tabLive: Button
    private lateinit var tabFile: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var logView: TextView
    private lateinit var fileList: LinearLayout
    private lateinit var fileProg: ProgressBar
    private lateinit var btnFStart: Button
    private lateinit var btnFPause: Button
    private lateinit var btnFCancel: Button
    private lateinit var outLabel: TextView
    private lateinit var claudeBox: LinearLayout

    private val logLines = ArrayDeque<CharSequence>()
    private var downloading = false
    private var pendingLive = false
    private var finalSid = 0

    companion object {
        const val REQ_PERM = 1
        const val REQ_PROJ = 2
        const val REQ_FILES = 3
        const val REQ_TREE = 4
    }

    // ═════════════════════════════════════════════ 화면 만들기
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 14f, bold: Boolean = false, color: Int = 0xFF222222.toInt()) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun head(s: String) = text(s, 16f, true, 0xFF111111.toInt()).apply { setPadding(0, dp(16), 0, dp(4)) }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun check(s: String, v: Boolean, on: (Boolean) -> Unit) = CheckBox(this).apply {
        text = s
        isChecked = v
        setOnCheckedChangeListener { _, c -> on(c) }
    }

    private fun spinner(items: List<String>, sel: Int, on: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(sel.coerceIn(0, items.size - 1))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = on(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun edit(hint: String, value: String, password: Boolean = false, lines: Int = 1, on: (String) -> Unit) =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            textSize = 14f
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (lines > 1) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = lines
                gravity = Gravity.TOP
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = on(s?.toString() ?: "")
            })
        }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val scroll = ScrollView(this).apply { fitsSystemWindows = true; setBackgroundColor(Color.WHITE) }
        val root = vbox().apply { setPadding(dp(16), dp(12), dp(16), dp(24)) }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(text("SubVivo", 26f, true, 0xFF111111.toInt()))
        root.addView(text("외국어 영상을 휴대폰 안에서 받아써 한국어 자막으로 띄웁니다", 13f, color = 0xFF666666.toInt()))

        // ─── 공통 설정
        root.addView(head("인식 모델"))
        root.addView(spinner(Models.ASR.map { it.label }, Models.ASR.indexOfFirst { it.id == prefs.model }.coerceAtLeast(0)) {
            prefs.model = Models.ASR[it].id
            refreshModel()
        })
        modelStatus = text("", 13f, color = 0xFF555555.toInt())
        btnModel = button("모델 받기") { downloadNeeded {} }
        root.addView(row(modelStatus, btnModel))

        root.addView(head("번역"))
        val trIds = listOf("google", "claude", "none")
        root.addView(spinner(listOf("구글 번역 (무료)", "Claude (맥락 번역 · API 유료)", "번역 안 함 (원문만)"), trIds.indexOf(prefs.translator)) {
            prefs.translator = trIds[it]
            claudeBox.visibility = if (trIds[it] == "claude") View.VISIBLE else View.GONE
        })
        claudeBox = vbox()
        claudeBox.addView(edit("Claude API 키 (console.anthropic.com)", prefs.claudeKey, password = true) { prefs.claudeKey = it.trim() })
        claudeBox.addView(edit("Claude 모델", prefs.claudeModel) { prefs.claudeModel = it.trim() })
        claudeBox.addView(edit("영화 메모 (인물·말투) 예) 카르멘(어머니)↔루시아(딸) 반말", prefs.notes, lines = 3) { prefs.notes = it })
        claudeBox.visibility = if (prefs.translator == "claude") View.VISIBLE else View.GONE
        root.addView(claudeBox)

        root.addView(head("목소리별 자막 색"))
        root.addView(check("목소리 구분해서 자막 색 다르게", prefs.diarize) { prefs.diarize = it; refreshModel() })
        val sens = SpeakerStyle.SENSITIVITY.keys.toList()
        root.addView(row(text("민감도", 14f), spinner(sens, sens.indexOf(prefs.sensitivity)) { prefs.sensitivity = sens[it] }))
        root.addView(check("SRT 파일에도 색 넣기", prefs.srtColors) { prefs.srtColors = it })

        // ─── 탭
        tabLive = button("실시간 자막") { showTab(true) }
        tabFile = button("파일로 자막 만들기") { showTab(false) }
        root.addView(row(tabLive, tabFile).apply { setPadding(0, dp(16), 0, 0) })
        liveBox = vbox()
        fileBox = vbox()
        root.addView(liveBox)
        root.addView(fileBox)
        buildLive()
        buildFile()

        status = text("", 13f, color = 0xFF444444.toInt()).apply { setPadding(0, dp(12), 0, 0) }
        root.addView(status)

        showTab(true)
        refreshModel()
        for (e in Bus.history) appendLog(e)
        Bus.listen(listener)
        updateLiveButtons()
        refreshFiles()
    }

    private fun buildLive() {
        val b = liveBox
        b.addView(head("영상 언어"))
        val langs = Langs.LIST
        b.addView(spinner(langs.map { it.second }, langs.indexOfFirst { it.first == prefs.liveLang }) { prefs.liveLang = langs[it].first })

        b.addView(head("소리 받는 곳"))
        val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rApp = RadioButton(this).apply { id = View.generateViewId(); text = "휴대폰에서 재생되는 소리 (크롬·유튜브 등 다른 앱)" }
        val rMic = RadioButton(this).apply { id = View.generateViewId(); text = "마이크 (다른 기기의 소리 / 소리 가져가기를 막은 앱)" }
        rg.addView(rApp)
        rg.addView(rMic)
        rg.check(if (prefs.source == "mic") rMic.id else rApp.id)
        rg.setOnCheckedChangeListener { _, id -> prefs.source = if (id == rMic.id) "mic" else "app" }
        b.addView(rg)

        b.addView(head("자막 모양"))
        val sizeLabel = text("글자 크기 ${prefs.fontSize}", 14f)
        val seek = SeekBar(this).apply {
            max = 40
            progress = prefs.fontSize - 12
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.fontSize = p + 12
                    sizeLabel.text = "글자 크기 ${prefs.fontSize}"
                    Bus.post(Ev("style"))
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        b.addView(sizeLabel)
        b.addView(seek)
        b.addView(check("원문도 함께 보기", prefs.showSource) { prefs.showSource = it })
        b.addView(check("검은 상자 배경", prefs.boxStyle) { prefs.boxStyle = it; Bus.post(Ev("style")) })
        b.addView(check("말하는 중에도 미리 보기 (더 빠름, 배터리 더 씀)", prefs.preview) { prefs.preview = it })
        b.addView(text("자막은 손가락으로 끌어서 위아래로 옮길 수 있습니다.", 12f, color = 0xFF777777.toInt()))

        btnStart = button("▶ 실시간 자막 시작") { startLive() }
        btnStop = button("■ 정지") { stopLive() }
        b.addView(row(btnStart, btnStop).apply { setPadding(0, dp(12), 0, 0) })
        b.addView(button("SRT로 저장") { saveLiveSrt() })
        logView = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0xFFF4F4F4.toInt())
            minLines = 8
        }
        b.addView(logView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun buildFile() {
        val b = fileBox
        b.addView(text("영상을 재생하지 않고 파일 전체를 받아써서 자막(SRT)을 만듭니다. 화면을 꺼도 계속합니다.", 13f, color = 0xFF555555.toInt()).apply { setPadding(0, dp(8), 0, 0) })
        b.addView(head("영상 언어"))
        val opts = listOf("" to "자동 감지 (파일마다)") + Langs.LIST
        b.addView(spinner(opts.map { it.second }, opts.indexOfFirst { it.first == prefs.fileLang }.coerceAtLeast(0)) { prefs.fileLang = opts[it].first })
        b.addView(check("한국어 자막(.ko.srt)이 이미 있으면 건너뛰기", prefs.skipExisting) { prefs.skipExisting = it })
        outLabel = text("", 13f, color = 0xFF555555.toInt())
        b.addView(row(outLabel, button("저장 위치 바꾸기") { pickTree() }))
        refreshOut()
        b.addView(row(button("영상 추가") { pickFiles() }, button("목록 비우기") { clearFiles() }))
        fileList = vbox()
        b.addView(fileList)
        fileProg = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        b.addView(fileProg)
        btnFStart = button("▶ 시작") { startFiles() }
        btnFPause = button("⏸ 일시정지") { togglePause() }
        btnFCancel = button("■ 취소") { FileQueue.control.cancel(); status.text = "취소하는 중… (지금 구간이 끝나면 멈춤)" }
        b.addView(row(btnFStart, btnFPause, btnFCancel))
    }

    private fun showTab(live: Boolean) {
        liveBox.visibility = if (live) View.VISIBLE else View.GONE
        fileBox.visibility = if (live) View.GONE else View.VISIBLE
        tabLive.alpha = if (live) 1f else 0.5f
        tabFile.alpha = if (live) 0.5f else 1f
    }

    // ═════════════════════════════════════════════ 모델
    private fun neededModels(): List<ModelSpec> {
        val list = arrayListOf(Models.byId(prefs.model))
        if (prefs.diarize) list.add(Models.SPEAKER)
        return list.filter { !Models.ready(this, it) }
    }

    private fun refreshModel() {
        if (!::modelStatus.isInitialized) return
        val spec = Models.byId(prefs.model)
        val missing = neededModels()
        modelStatus.text = if (missing.isEmpty()) "받아 둠 ✓" else "받아야 함: 약 ${missing.sumOf { it.approxMb }}MB (처음 한 번)"
        btnModel.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        if (spec.kind == "sensevoice") modelStatus.append("\nSenseVoice는 일·중·영·한만 알아듣습니다")
    }

    private fun downloadNeeded(then: () -> Unit) {
        val missing = neededModels()
        if (missing.isEmpty()) { then(); return }
        if (downloading) return
        AlertDialog.Builder(this)
            .setTitle("모델 내려받기")
            .setMessage("약 ${missing.sumOf { it.approxMb }}MB를 내려받습니다 (처음 한 번). 와이파이를 권합니다.")
            .setPositiveButton("받기") { _, _ ->
                downloading = true
                btnModel.isEnabled = false
                thread {
                    try {
                        for (m in missing) Models.download(this, m, { Bus.status(it) })
                        runOnUiThread { downloading = false; btnModel.isEnabled = true; refreshModel(); status.text = "모델 준비 완료"; then() }
                    } catch (e: Exception) {
                        runOnUiThread { downloading = false; btnModel.isEnabled = true; refreshModel(); status.text = "내려받기 실패: ${e.message}" }
                    }
                }
            }
            .setNegativeButton("나중에", null)
            .show()
    }

    // ═════════════════════════════════════════════ 실시간
    private fun startLive() {
        if (LiveService.running) return
        if (prefs.translator == "claude" && prefs.claudeKey.isBlank()) { toast("Claude API 키를 넣거나 구글 번역을 고르세요"); return }
        val perms = arrayListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingLive = true
            requestPermissions(perms.toTypedArray(), REQ_PERM)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            pendingLive = true
            AlertDialog.Builder(this)
                .setTitle("다른 앱 위에 표시")
                .setMessage("자막을 영상 위에 띄우려면 ‘다른 앱 위에 표시’를 허용해야 합니다. 다음 화면에서 SubVivo를 켜고 돌아오세요.")
                .setPositiveButton("설정 열기") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("취소") { _, _ -> pendingLive = false }
                .show()
            return
        }
        pendingLive = false
        downloadNeeded {
            if (prefs.source == "app") {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                @Suppress("DEPRECATION")
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJ)
            } else {
                launchLive(0, null)
            }
        }
    }

    private fun launchLive(code: Int, data: Intent?) {
        val i = Intent(this, LiveService::class.java).putExtra("source", prefs.source).putExtra("code", code)
        if (data != null) i.putExtra("data", data)
        finalSid = 0
        appendLine(SpannableString("— ${SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())} 시작"), 0xFF2A6EBB.toInt())
        startForegroundService(i)
        status.text = "시작하는 중… 이제 영상 앱으로 가서 재생하세요"
        btnStart.isEnabled = false
        btnStop.isEnabled = true
    }

    private fun stopLive() {
        startService(Intent(this, LiveService::class.java).setAction(LiveService.ACTION_STOP))
    }

    private fun updateLiveButtons() {
        btnStart.isEnabled = !LiveService.running
        btnStop.isEnabled = LiveService.running
    }

    private fun saveLiveSrt() {
        val cues = synchronized(LiveService.lastCues) { LiveService.lastCues.toList() }
        if (cues.isEmpty()) { toast(if (LiveService.running) "정지한 뒤에 저장할 수 있습니다" else "저장할 자막이 아직 없습니다"); return }
        val name = "subvivo_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA).format(Date()) + ".ko.srt"
        val hasKo = cues.any { it.korean.isNotBlank() }
        thread {
            val msg = try {
                "저장: " + SrtStore.save(
                    this, prefs, name,
                    Srt.build(cues, if (hasKo) "korean" else "source", withSource = hasKo, colors = prefs.srtColors && cues.any { it.speaker != null }),
                )
            } catch (e: Exception) {
                "저장 실패: ${e.message}"
            }
            runOnUiThread { status.text = msg; appendLine(SpannableString(msg), 0xFF2A6EBB.toInt()) }
        }
    }

    // ═════════════════════════════════════════════ 파일
    private fun pickFiles() {
        if (FileQueue.running) { toast("만드는 중에는 목록을 바꿀 수 없습니다"); return }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_FILES)
    }

    private fun pickTree() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
    }

    private fun refreshOut() {
        outLabel.text = "저장 위치: " + if (prefs.outTree.isBlank()) "다운로드/SubVivo" else Uri.parse(prefs.outTree).lastPathSegment?.substringAfter(':')
    }

    private fun clearFiles() {
        if (FileQueue.running) { toast("만드는 중에는 목록을 바꿀 수 없습니다"); return }
        synchronized(FileQueue.items) { FileQueue.items.clear() }
        fileProg.progress = 0
        refreshFiles()
    }

    private fun refreshFiles() {
        fileList.removeAllViews()
        val items = synchronized(FileQueue.items) { FileQueue.items.toList() }
        if (items.isEmpty()) fileList.addView(text("‘영상 추가’로 파일을 고르세요 (여러 개 가능)", 13f, color = 0xFF888888.toInt()))
        for (it in items) {
            val line = vbox().apply { setPadding(0, dp(6), 0, dp(6)) }
            line.addView(text(it.name, 14f, true))
            line.addView(text(listOf(it.lang, it.state).filter { s -> s.isNotBlank() }.joinToString(" · "), 13f, color = 0xFF555555.toInt()))
            fileList.addView(line)
        }
        btnFStart.isEnabled = !FileQueue.running
        btnFPause.isEnabled = FileQueue.running
        btnFCancel.isEnabled = FileQueue.running
        btnFPause.text = if (FileQueue.control.paused) "▶ 계속" else "⏸ 일시정지"
    }

    private fun startFiles() {
        if (FileQueue.running) return
        if (FileQueue.items.isEmpty()) { toast("먼저 ‘영상 추가’로 파일을 고르세요"); return }
        if (prefs.translator == "claude" && prefs.claudeKey.isBlank()) { toast("Claude API 키를 넣거나 구글 번역을 고르세요"); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        downloadNeeded {
            synchronized(FileQueue.items) { for (it in FileQueue.items) if (!it.state.startsWith("완료")) it.state = "대기" }
            FileQueue.running = false
            startForegroundService(Intent(this, FileJobService::class.java))
            btnFStart.isEnabled = false
            btnFPause.isEnabled = true
            btnFCancel.isEnabled = true
            status.text = "파일 자막 만들기 시작…"
        }
    }

    private fun togglePause() {
        val c = FileQueue.control
        if (c.paused) {
            c.resume()
            btnFPause.text = "⏸ 일시정지"
            status.text = "다시 시작합니다…"
        } else {
            c.pause()
            btnFPause.text = "▶ 계속"
            status.text = "일시정지하는 중… (지금 구간이 끝나면 멈춤. ‘계속’을 누르면 그 자리부터 이어서 합니다)"
        }
    }

    // ═════════════════════════════════════════════ 결과 받기
    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PROJ -> {
                if (resultCode == RESULT_OK && data != null) {
                    launchLive(resultCode, data)
                } else {
                    status.text = "‘시작’을 눌러야 다른 앱 소리를 받을 수 있습니다"
                }
            }
            REQ_FILES -> if (resultCode == RESULT_OK && data != null) {
                val uris = ArrayList<Uri>()
                val clip = data.clipData
                if (clip != null) for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                else data.data?.let { uris.add(it) }
                synchronized(FileQueue.items) {
                    for (u in uris) {
                        runCatching { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        if (FileQueue.items.none { it.uri == u }) FileQueue.items.add(FileQueue.Item(u, displayName(this, u)))
                    }
                }
                refreshFiles()
            }
            REQ_TREE -> if (resultCode == RESULT_OK && data?.data != null) {
                val u = data.data!!
                runCatching {
                    contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                prefs.outTree = u.toString()
                refreshOut()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                if (pendingLive) startLive()
            } else {
                pendingLive = false
                status.text = "소리 받기(마이크) 권한이 있어야 실시간 자막이 됩니다"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingLive && Settings.canDrawOverlays(this) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            startLive()
        }
        if (::btnStart.isInitialized) { updateLiveButtons(); refreshFiles(); refreshModel() }
    }

    override fun onDestroy() {
        Bus.unlisten(listener)
        super.onDestroy()
    }

    // ═════════════════════════════════════════════ 소식 처리
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun appendLine(s: CharSequence, color: Int? = null, bold: Boolean = false) {
        val sp = SpannableString(s)
        if (color != null) sp.setSpan(ForegroundColorSpan(color), 0, sp.length, 0)
        if (bold) sp.setSpan(StyleSpan(Typeface.BOLD), 0, sp.length, 0)
        logLines.addLast(sp)
        while (logLines.size > 200) logLines.removeFirst()
        val sb = SpannableStringBuilder()
        for (l in logLines.toList().asReversed()) sb.append(l).append('\n') // 최근 것이 위로
        logView.text = sb
    }

    private fun appendLog(e: Ev) {
        when (e.type) {
            "info", "started", "stopped" -> appendLine(e.text, 0xFF2A6EBB.toInt())
            "warn", "error" -> appendLine(e.text, 0xFFC0392B.toInt())
            "subtitle" -> {
                val mm = (e.start / 60).toInt()
                val ss = (e.start % 60).toInt()
                val who = if (e.speaker != null) " [${e.speaker + 1}]" else ""
                if (e.ko.isNotBlank()) appendLine("          ${e.ko}", SpeakerStyle.logColor(e.speaker), bold = true)
                appendLine("[%02d:%02d]%s %s".format(mm, ss, who, e.src), 0xFF666666.toInt())
                if (e.newSpeaker && e.speaker != null) {
                    appendLine("● 새 목소리 ${e.speaker + 1} — ${SpeakerStyle.name(e.speaker)}색 자막", SpeakerStyle.logColor(e.speaker), bold = true)
                }
            }
        }
    }

    private fun onEv(e: Ev) {
        when (e.type) {
            "status" -> status.text = e.text
            "info", "warn" -> { appendLog(e); status.text = e.text.take(120) }
            "error" -> { appendLog(e); status.text = "오류: ${e.text.take(120)}"; updateLiveButtons() }
            "started" -> { appendLog(e); status.text = e.text; updateLiveButtons() }
            "stopped" -> { appendLog(e); status.text = e.text; updateLiveButtons() }
            "subtitle" -> {
                appendLog(e)
                status.text = "듣는 중 · 대사 끝→자막 약 %.1f초 (받아쓰기 %.1f초 · 번역 %.1f초)".format(e.delay, e.sttMs / 1000.0, e.trMs / 1000.0)
            }
            "file_progress" -> {
                val total = FileQueue.items.size.coerceAtLeast(1)
                fileProg.progress = (1000 * (FileQueue.done + e.frac) / total).toInt()
                status.text = if (e.text == "일시정지됨") "일시정지됨 — ‘계속’을 누르면 이어서 합니다" else "[${e.index + 1}/$total] ${e.text.take(60)}"
                refreshFiles()
            }
            "file_item" -> {
                fileProg.progress = (1000 * FileQueue.done / FileQueue.items.size.coerceAtLeast(1))
                refreshFiles()
            }
            "file_done" -> { status.text = e.text; refreshFiles() }
        }
    }
}
