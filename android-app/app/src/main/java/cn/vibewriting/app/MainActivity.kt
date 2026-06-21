package cn.vibewriting.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.Html
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private data class ReferenceFile(
    val name: String,
    val text: String,
    var selected: Boolean = true
)

private data class EditorSnapshot(
    val html: String,
    val selectionStart: Int,
    val selectionEnd: Int
)

class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var completionExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val references = mutableListOf<ReferenceFile>()
    private val articleStore by lazy { ArticleStore(this) }
    private val articles = mutableListOf<LocalArticle>()
    private val secureStore by lazy { SecureStore(this) }
    private val settingsPreferences by lazy {
        getSharedPreferences("vibe_writing_settings", Context.MODE_PRIVATE)
    }

    private lateinit var titleEdit: EditText
    private lateinit var contentEdit: EditText
    private lateinit var statusText: TextView
    private var voiceButton: Button? = null
    private lateinit var recordingPanel: LinearLayout
    private lateinit var recordingPulseText: TextView
    private lateinit var recordingStatusText: TextView
    private lateinit var recordingTimerText: TextView
    private lateinit var stopRecordingButton: Button
    private lateinit var referenceList: LinearLayout
    private var wideLayout = false
    private lateinit var currentArticle: LocalArticle

    private var suggestion: Completion? = null
    private val inlineSuggestionMarker = Any()
    private val inlineAcceptMarker = Any()
    private val inlineDecorationSpans = mutableListOf<Any>()
    private val inlineAcceptLabel = "  接受"
    private var suggestionTouchStartX = 0f
    private var suggestionTouchStartY = 0f
    private var suggestionTouchStartedInside = false
    private var completionVersion = 0
    private var suppressChanges = false
    private var textBeforeChange: EditorSnapshot? = null
    private val undoStack = ArrayDeque<EditorSnapshot>()
    private var recorder: WavRecorder? = null
    private var recordingFile: File? = null
    private var isRecording = false
    private var recordingStartedAt = 0L
    private var recordingPulseStep = 0
    private val completionRunnable = Runnable { requestCompletion() }
    private val draftSaveRunnable = Runnable { saveDraft() }
    private val recordingUiRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            updateRecordingTimer()
            mainHandler.postDelayed(this, 300)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val preferences = newBase.getSharedPreferences("vibe_writing_settings", Context.MODE_PRIVATE)
        val mode = preferences.getString("theme_mode", "system") ?: "system"
        if (mode == "system") {
            super.attachBaseContext(newBase)
            return
        }
        val configuration = Configuration(newBase.resources.configuration)
        val nightMode = if (mode == "dark") {
            Configuration.UI_MODE_NIGHT_YES
        } else {
            Configuration.UI_MODE_NIGHT_NO
        }
        configuration.uiMode =
            configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or nightMode
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()
        articles.addAll(articleStore.loadOrMigrate())
        val currentId = articleStore.currentId(articles.first().id)
        currentArticle = articles.firstOrNull { it.id == currentId } ?: articles.first()
        loadArticleReferences(currentArticle)
        buildInterface()
        renderReferences()
        updateStats()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (::titleEdit.isInitialized && ::contentEdit.isInitialized) saveDraft()
        if (isRecording) runCatching { recorder?.stop() }
        recorder = null
        completionExecutor.shutdownNow()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.paper))
        }
        applySystemBarPadding(root, dp(18), dp(20), dp(18), dp(22))
        root.addView(buildHeader())

        wideLayout = resources.configuration.screenWidthDp >= 840
        val workspace = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val editor = buildEditor()
        buildContextPanel()
        workspace.addView(editor, LinearLayout.LayoutParams(-1, -1))
        root.addView(workspace, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.requestApplyInsets()
    }

    private fun buildHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))

            addView(iconButton("☰", "文章列表") { showArticleDrawer() })
            addView(TextView(this@MainActivity).apply {
                text = "Vibe Writing"
                textSize = 27f
                setTextColor(color(R.color.ink))
                setTypeface(Typeface.SERIF, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f).withMargins(start = 8))

            addView(headerIconButton(R.drawable.ic_copy, "复制 Markdown") { copyMarkdown() })
            addView(headerIconButton(R.drawable.ic_context, "写作上下文") { showContextDrawer() }
                .withLayoutMargins(start = 8))
            addView(headerIconButton(R.drawable.ic_settings, "设置") { showSettings() }
                .withLayoutMargins(start = 8))
        }
    }

    private fun buildEditor(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = getDrawable(R.drawable.panel)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleEdit = EditText(this).apply {
            hint = "无标题文章"
            textSize = 18f
            setTextColor(color(R.color.ink))
            setHintTextColor(color(R.color.muted))
            setSingleLine(true)
            background = null
            setPadding(4, 0, 8, 0)
            setText(currentArticle.title)
            setOnLongClickListener {
                showArticleDrawer()
                true
            }
        }
        statusText = TextView(this).apply {
            text = "字数 ${currentArticle.wordCount}"
            textSize = 12f
            setTextColor(color(R.color.muted))
        }
        top.addView(titleEdit, LinearLayout.LayoutParams(0, dp(48), 1f))
        top.addView(statusText)
        panel.addView(top)

        contentEdit = EditText(this).apply {
            gravity = Gravity.TOP or Gravity.START
            hint = "从这里开始写。停顿片刻后，AI 会准备续写建议。"
            textSize = 17f
            setTextColor(color(R.color.ink))
            setHintTextColor(color(R.color.muted))
            typeface = Typeface.SERIF
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = getDrawable(R.drawable.editor)
            setLineSpacing(0f, 1.35f)
            setText(loadRichContent(currentArticle))
        }
        panel.addView(buildFormatToolbar(), LinearLayout.LayoutParams(-1, dp(50)).withMargins(top = 8))
        panel.addView(contentEdit, LinearLayout.LayoutParams(-1, 0, 1f).withMargins(top = 8))
        // Built-in voice recording is disabled. Use the system keyboard's voice input instead.

        bindEditorEvents()
        return panel
    }

    private fun buildRecordingPanel(): View {
        recordingPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(10), dp(10))
            background = getDrawable(R.drawable.recording_panel)
        }
        recordingPulseText = TextView(this).apply {
            text = "▮▮▮"
            textSize = 18f
            setTextColor(color(R.color.rust))
            setPadding(0, 0, dp(12), 0)
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        recordingStatusText = TextView(this).apply {
            text = "正在语音输入"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.ink))
        }
        recordingTimerText = TextView(this).apply {
            text = "00:00，点击停止后会按你的文风润色"
            textSize = 12f
            setTextColor(color(R.color.muted))
        }
        copy.addView(recordingStatusText)
        copy.addView(recordingTimerText)
        stopRecordingButton = actionButton("停止并润色", true) { stopVoiceRecording() }
        recordingPanel.addView(recordingPulseText)
        recordingPanel.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        recordingPanel.addView(
            stopRecordingButton,
            LinearLayout.LayoutParams(-2, dp(40)).withMargins(start = 10)
        )
        return recordingPanel
    }

    private fun buildFormatToolbar(): View {
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(3), dp(4), dp(3))
            background = getDrawable(R.drawable.editor)
        }
        tools.addView(formatIconButton(R.drawable.ic_undo, "撤销上一步") { undoLastEdit() })
        tools.addView(formatButton("B", "加粗") { applyStyle(Typeface.BOLD) })
        tools.addView(formatButton("I", "斜体") { applyStyle(Typeface.ITALIC) })
        tools.addView(formatButton("标题", "标题") { applyHeading() })
        tools.addView(formatButton("列表", "项目列表") { applyBulletList() })
        tools.addView(formatButton("清除", "清除格式") { clearFormatting() })
        tools.addView(
            formatIconButton(R.drawable.ic_image, "插入本地图片") { openImagePicker() }
        )
        // Built-in ASR entry is hidden for now; system voice input is enough on mobile.
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tools)
        }
    }

    private fun formatButton(label: String, description: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            contentDescription = description
            minimumWidth = 0
            minWidth = dp(46)
            minHeight = dp(40)
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(color(R.color.ink))
            background = getDrawable(R.drawable.button_icon)
            setOnClickListener { action() }
        }
    }

    private fun formatIconButton(
        drawableRes: Int,
        description: String,
        action: () -> Unit
    ): Button {
        return formatButton("", description, action).apply {
            minWidth = dp(40)
            setPadding(dp(10), 0, dp(10), 0)
            setCompoundDrawablesWithIntrinsicBounds(drawableRes, 0, 0, 0)
        }
    }

    private fun selectedRange(): IntRange? {
        val start = contentEdit.selectionStart
        val end = contentEdit.selectionEnd
        if (start < 0 || end <= start) {
            showToast("先选中要设置格式的文字")
            return null
        }
        return start until end
    }

    private fun applyStyle(style: Int) {
        val range = selectedRange() ?: return
        pushUndoSnapshot()
        contentEdit.text.setSpan(
            StyleSpan(style),
            range.first,
            range.last + 1,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        saveDraft()
    }

    private fun applyHeading() {
        val range = selectedRange() ?: return
        pushUndoSnapshot()
        contentEdit.text.setSpan(
            StyleSpan(Typeface.BOLD),
            range.first,
            range.last + 1,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        contentEdit.text.setSpan(
            RelativeSizeSpan(1.45f),
            range.first,
            range.last + 1,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        saveDraft()
    }

    private fun applyBulletList() {
        pushUndoSnapshot()
        val start = contentEdit.selectionStart.coerceAtLeast(0)
        val lineStart = contentEdit.text.lastIndexOf('\n', (start - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        if (!contentEdit.text.substring(lineStart).startsWith("• ")) {
            contentEdit.text.insert(lineStart, "• ")
        }
        saveDraft()
    }

    private fun clearFormatting() {
        val range = selectedRange() ?: return
        pushUndoSnapshot()
        val editable = contentEdit.text
        editable.getSpans(range.first, range.last + 1, StyleSpan::class.java)
            .forEach(editable::removeSpan)
        editable.getSpans(range.first, range.last + 1, RelativeSizeSpan::class.java)
            .forEach(editable::removeSpan)
        saveDraft()
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_IMAGE)
    }

    private fun buildContextPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = getDrawable(R.drawable.panel)
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(TextView(this).apply {
            text = "写作上下文"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.ink))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(actionButton("添加文件", false) { openReferencePicker() })
        panel.addView(heading)
        val contextBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        contextBody.addView(TextView(this).apply {
            text = "勾选的文本文件会参与续写。支持 txt、md、html、json 和 csv。"
            textSize = 12f
            setTextColor(color(R.color.muted))
            setPadding(0, dp(8), 0, dp(8))
        })

        referenceList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        contextBody.addView(ScrollView(this).apply {
            addView(referenceList)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(contextBody, LinearLayout.LayoutParams(-1, 0, 1f))
        return panel
    }

    private fun showContextDrawer() {
        saveDraft()
        val dialog = Dialog(this)
        val panel = buildContextPanel()
        applySystemBarPadding(panel, dp(14), dp(18), dp(14), dp(18))
        renderReferences()
        dialog.setContentView(panel)
        dialog.show()
        panel.requestApplyInsets()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.END)
            setLayout(
                if (wideLayout) dp(380) else (resources.displayMetrics.widthPixels * 0.88f).toInt(),
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun bindEditorEvents() {
        val titleWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressChanges) return
                scheduleDraftSave()
                clearSuggestion()
            }
        }
        val contentWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!suppressChanges) {
                    textBeforeChange = captureEditorSnapshot()
                }
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressChanges) return
                textBeforeChange?.let { pushUndoSnapshot(it) }
                textBeforeChange = null
                scheduleDraftSave()
                updateStats()
                clearSuggestion()
                scheduleCompletion()
            }
        }
        titleEdit.addTextChangedListener(titleWatcher)
        contentEdit.addTextChangedListener(contentWatcher)
        contentEdit.setOnClickListener { scheduleCompletion(250) }
        contentEdit.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    suggestionTouchStartX = event.x
                    suggestionTouchStartY = event.y
                    suggestionTouchStartedInside = isTouchInsideInlineSuggestion(event)
                    suggestionTouchStartedInside
                }
                MotionEvent.ACTION_UP -> {
                    if (suggestionTouchStartedInside) {
                        acceptInlineSuggestionGesture(event)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    suggestionTouchStartedInside = false
                    true
                }
                else -> suggestionTouchStartedInside
            }
        }
    }

    private fun scheduleCompletion(delay: Long = 900) {
        mainHandler.removeCallbacks(completionRunnable)
        completionVersion += 1
        resetCompletionExecutor()
        mainHandler.postDelayed(completionRunnable, delay)
    }

    private fun resetCompletionExecutor() {
        completionExecutor.shutdownNow()
        completionExecutor = Executors.newSingleThreadExecutor()
    }

    private fun selectedChatModel(): ChatModelProvider {
        return ChatModelProvider.fromPreference(settingsPreferences.getString("chat_model", "zhipu"))
    }

    private fun chatApiKey(model: ChatModelProvider = selectedChatModel()): String {
        return when (model) {
            ChatModelProvider.ZHIPU -> secureStore.readApiKey()
            ChatModelProvider.STEPFUN -> secureStore.readStepApiKey()
        }
    }

    private fun requestCompletion() {
        val chatModel = selectedChatModel()
        val key = chatApiKey(chatModel)
        val body = contentPlainText()
        if (key.isBlank()) {
            clearSuggestion()
            showToast("请先在设置里填写${chatModel.displayName}的 API Key")
            return
        }
        if (body.isBlank() && selectedReferenceText().isBlank()) return

        val version = completionVersion
        val title = titleEdit.text.toString()
        val references = selectedReferenceText()
        val styleConstraint = settingsPreferences.getString("style_prompt", "").orEmpty()
        completionExecutor.execute {
            runCatching {
                GlmClient(key, chatModel).complete(title, body, references, styleConstraint)
            }
                .onSuccess { result ->
                    mainHandler.post {
                        if (version != completionVersion) return@post
                        suggestion = result
                        showInlineSuggestion(result.text)
                    }
                }
                .onFailure { error ->
                    mainHandler.post {
                        if (version != completionVersion) return@post
                        clearSuggestion()
                        showToast(error.message ?: "补全请求失败")
                    }
                }
        }
    }

    private fun insertAtCursor(value: String, paragraph: Boolean) {
        pushUndoSnapshot()
        val start = contentEdit.selectionStart.coerceAtLeast(0)
        val end = contentEdit.selectionEnd.coerceAtLeast(start)
        val before = contentEdit.text.substring(0, start)
        val normalizedValue = normalizeInsertedParagraphs(value)
        val spacer = when {
            paragraph && before.isNotBlank() && !before.endsWith("\n\n") -> "\n\n"
            !paragraph && before.lastOrNull()?.isLetterOrDigit() == true -> " "
            else -> ""
        }
        suppressChanges = true
        contentEdit.text.replace(start, end, spacer + normalizedValue)
        suppressChanges = false
        saveDraft()
        updateStats()
        clearSuggestion()
        contentEdit.requestFocus()
        contentEdit.setSelection(start + spacer.length + normalizedValue.length)
    }

    private fun showInlineSuggestion(value: String) {
        if (!::contentEdit.isInitialized) return
        removeInlineSuggestion()
        val start = contentEdit.selectionEnd.coerceAtLeast(0)
        val before = contentEdit.text.substring(0, start)
        val text = normalizeSuggestionForContext(before, value)
        if (text.isBlank()) return
        val spacer = completionSpacer(before, text)
        val inlineText = spacer + text + inlineAcceptLabel
        val acceptStart = start + spacer.length + text.length
        val end = start + inlineText.length
        suppressChanges = true
        contentEdit.text.insert(start, inlineText)
        inlineDecorationSpans.clear()
        val suggestionColorSpan = ForegroundColorSpan(color(R.color.muted))
        val acceptColorSpan = ForegroundColorSpan(color(R.color.rust))
        val acceptStyleSpan = StyleSpan(Typeface.BOLD)
        inlineDecorationSpans.add(suggestionColorSpan)
        inlineDecorationSpans.add(acceptColorSpan)
        inlineDecorationSpans.add(acceptStyleSpan)
        contentEdit.text.setSpan(
            inlineSuggestionMarker,
            start,
            end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        contentEdit.text.setSpan(
            inlineAcceptMarker,
            acceptStart,
            end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        contentEdit.text.setSpan(
            suggestionColorSpan,
            start,
            acceptStart,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        contentEdit.text.setSpan(
            acceptColorSpan,
            acceptStart,
            end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        contentEdit.text.setSpan(
            acceptStyleSpan,
            acceptStart,
            end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        contentEdit.setSelection(start)
        suppressChanges = false
    }

    private fun clearSuggestion() {
        suggestion = null
        removeInlineSuggestion()
    }

    private fun removeInlineSuggestion() {
        if (!::contentEdit.isInitialized) return
        val editable = contentEdit.text
        val start = editable.getSpanStart(inlineSuggestionMarker)
        val end = editable.getSpanEnd(inlineSuggestionMarker)
        if (start < 0 || end <= start || end > editable.length) return
        val cursor = contentEdit.selectionStart
        editable.removeSpan(inlineSuggestionMarker)
        editable.removeSpan(inlineAcceptMarker)
        inlineDecorationSpans.forEach(editable::removeSpan)
        inlineDecorationSpans.clear()
        suggestionTouchStartedInside = false
        suppressChanges = true
        editable.delete(start, end)
        val nextCursor = when {
            cursor > end -> cursor - (end - start)
            cursor in start..end -> start
            else -> cursor
        }.coerceIn(0, editable.length)
        contentEdit.setSelection(nextCursor)
        suppressChanges = false
    }

    private fun acceptInlineSuggestionGesture(event: MotionEvent): Boolean {
        if (!suggestionTouchStartedInside) return false
        val dx = event.x - suggestionTouchStartX
        val dy = event.y - suggestionTouchStartY
        val swipedSideways = kotlin.math.abs(dx) >= dp(26) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.4f
        val tapped = kotlin.math.abs(dx) <= dp(8) && kotlin.math.abs(dy) <= dp(8)
        suggestionTouchStartedInside = false
        if (!swipedSideways && !tapped) return false
        acceptInlineSuggestion()
        return true
    }

    private fun isTouchInsideInlineSuggestion(event: MotionEvent): Boolean {
        if (!::contentEdit.isInitialized) return false
        val editable = contentEdit.text
        val suggestionStart = editable.getSpanStart(inlineSuggestionMarker)
        val suggestionEnd = editable.getSpanEnd(inlineSuggestionMarker)
        if (suggestionStart < 0 || suggestionEnd <= suggestionStart) return false
        val layout = contentEdit.layout ?: return false
        val x = event.x.toInt() - contentEdit.totalPaddingLeft + contentEdit.scrollX
        val y = event.y.toInt() - contentEdit.totalPaddingTop + contentEdit.scrollY
        val line = layout.getLineForVertical(y.coerceAtLeast(0))
        val offset = layout.getOffsetForHorizontal(line, x.toFloat())
        return offset in suggestionStart..suggestionEnd
    }

    private fun acceptInlineSuggestion() {
        if (!::contentEdit.isInitialized) return
        val editable = contentEdit.text
        val start = editable.getSpanStart(inlineSuggestionMarker)
        val end = editable.getSpanEnd(inlineSuggestionMarker)
        if (start < 0 || end <= start) return
        val acceptStart = editable.getSpanStart(inlineAcceptMarker)
        val suggestionTextEnd = if (acceptStart in (start + 1)..end) acceptStart else end
        pushUndoSnapshot()
        val accepted = normalizeInsertedParagraphs(editable.subSequence(start, suggestionTextEnd).toString())
        editable.removeSpan(inlineSuggestionMarker)
        editable.removeSpan(inlineAcceptMarker)
        inlineDecorationSpans.forEach(editable::removeSpan)
        inlineDecorationSpans.clear()
        suppressChanges = true
        editable.replace(start, end, accepted)
        contentEdit.setSelection((start + accepted.length).coerceIn(0, editable.length))
        suppressChanges = false
        suggestion = null
        saveDraft()
        updateStats()
    }

    private fun completionSpacer(before: String, suggestionText: String): String {
        if (before.isBlank()) return ""
        if (before.endsWith("\n\n") || suggestionText.startsWith("\n")) return ""
        val last = before.lastOrNull() ?: return ""
        val first = suggestionText.firstOrNull() ?: return ""
        if (first.isLeadingPunctuation()) return ""
        val startsNewThought = last in listOf('。', '！', '？', '；', '\n', '.', '!', '?', ';') ||
            suggestionText.contains("\n\n")
        if (startsNewThought) return "\n\n"
        return if (last.isAsciiLetterOrDigit() && first.isAsciiLetterOrDigit()) " " else ""
    }

    private fun normalizeSuggestionForContext(before: String, value: String): String {
        var text = normalizeInsertedParagraphs(value)
        val last = before.lastNonWhitespaceChar() ?: return text
        if (last.isEndingPunctuation()) {
            text = text.dropLeadingPunctuation()
        }
        return text.trimStart()
    }

    private fun String.dropLeadingPunctuation(): String {
        var index = 0
        while (index < length && this[index].isLeadingPunctuation()) {
            index += 1
        }
        return substring(index)
    }

    private fun String.lastNonWhitespaceChar(): Char? {
        return lastOrNull { !it.isWhitespace() }
    }

    private fun Char.isLeadingPunctuation(): Boolean {
        return this in listOf('，', '。', '、', '；', '：', '！', '？', ',', '.', ';', ':', '!', '?')
    }

    private fun Char.isEndingPunctuation(): Boolean {
        return this in listOf('。', '！', '？', '；', '.', '!', '?', ';')
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean {
        return this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
    }

    private fun normalizeInsertedParagraphs(value: String): String {
        return value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun contentPlainText(): String {
        if (!::contentEdit.isInitialized) return ""
        return contentWithoutInlineSuggestion().toString()
    }

    private fun contentWithoutInlineSuggestion(): SpannableStringBuilder {
        val copy = SpannableStringBuilder(contentEdit.text)
        val start = contentEdit.text.getSpanStart(inlineSuggestionMarker)
        val end = contentEdit.text.getSpanEnd(inlineSuggestionMarker)
        if (start >= 0 && end > start && end <= copy.length) {
            copy.delete(start, end)
        }
        return copy
    }

    private fun captureEditorSnapshot(): EditorSnapshot? {
        if (!::contentEdit.isInitialized) return null
        return EditorSnapshot(
            Html.toHtml(contentWithoutInlineSuggestion(), Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE),
            contentEdit.selectionStart.coerceAtLeast(0),
            contentEdit.selectionEnd.coerceAtLeast(0)
        )
    }

    private fun pushUndoSnapshot(snapshot: EditorSnapshot? = captureEditorSnapshot()) {
        val next = snapshot ?: return
        val last = undoStack.lastOrNull()
        if (
            last?.html == next.html &&
            last.selectionStart == next.selectionStart &&
            last.selectionEnd == next.selectionEnd
        ) {
            return
        }
        undoStack.addLast(next)
        while (undoStack.size > 60) {
            undoStack.removeFirst()
        }
    }

    private fun undoLastEdit() {
        if (!::contentEdit.isInitialized || undoStack.isEmpty()) {
            showToast("还没有可撤销的内容")
            return
        }
        val snapshot = undoStack.removeLast()
        mainHandler.removeCallbacks(completionRunnable)
        suggestion = null
        suppressChanges = true
        val restored = Html.fromHtml(
            snapshot.html,
            Html.FROM_HTML_MODE_LEGACY,
            imageGetter(),
            null
        )
        contentEdit.setText(restored)
        val start = snapshot.selectionStart.coerceIn(0, contentEdit.text.length)
        val end = snapshot.selectionEnd.coerceIn(0, contentEdit.text.length)
        contentEdit.setSelection(start, end)
        suppressChanges = false
        saveDraft()
        updateStats()
    }

    private fun toggleVoiceRecording() {
        if (isRecording) {
            stopVoiceRecording()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
            return
        }
        startVoiceRecording()
    }

    private fun startVoiceRecording() {
        val chatModel = selectedChatModel()
        val zhipuKeyMissing = secureStore.readApiKey().isBlank()
        val chatKeyMissing = chatApiKey(chatModel).isBlank()
        if (zhipuKeyMissing || chatKeyMissing) {
            showSettings()
            showToast("语音转写需要智谱 Key，润色需要当前模型的 Key")
            return
        }
        val file = File(cacheDir, "voice-${System.currentTimeMillis()}.wav")
        runCatching {
            WavRecorder(file).also {
                recorder = it
                it.start()
            }
        }.onSuccess {
            recordingFile = file
            isRecording = true
            recordingStartedAt = System.currentTimeMillis()
            voiceButton?.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_stop, 0, 0, 0)
            voiceButton?.contentDescription = "停止录音并润色"
            showRecordingPanel()
            mainHandler.post(recordingUiRunnable)
        }.onFailure {
            showToast(it.message ?: "无法启动录音")
        }
    }

    private fun stopVoiceRecording() {
        val file = runCatching { recorder?.stop() }.getOrNull() ?: recordingFile
        recorder = null
        isRecording = false
        mainHandler.removeCallbacks(recordingUiRunnable)
        voiceButton?.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_mic, 0, 0, 0)
        voiceButton?.contentDescription = "语音输入"
        if (file == null || !file.exists() || file.length() <= 44) {
            hideRecordingPanel()
            showToast("没有录到有效声音")
            return
        }
        transcribeAndPolish(file)
    }

    private fun transcribeAndPolish(file: File) {
        val zhipuKey = secureStore.readApiKey()
        val chatModel = selectedChatModel()
        val chatKey = chatApiKey(chatModel)
        val style = contentPlainText()
        val styleConstraint = settingsPreferences.getString("style_prompt", "").orEmpty()
        voiceButton?.isEnabled = false
        showProcessingVoiceState()
        executor.execute {
            runCatching {
                val transcript = GlmClient(zhipuKey, ChatModelProvider.ZHIPU).transcribe(file)
                mainHandler.post { showToast("正在按你的文风润色") }
                GlmClient(chatKey, chatModel).polish(transcript, style, styleConstraint)
            }.onSuccess { polished ->
                mainHandler.post {
                    voiceButton?.isEnabled = true
                    hideRecordingPanel()
                    insertAtCursor(polished, paragraph = true)
                    showToast("语音内容已润色并插入")
                    file.delete()
                }
            }.onFailure { error ->
                mainHandler.post {
                    voiceButton?.isEnabled = true
                    hideRecordingPanel()
                    showToast(error.message ?: "语音处理失败")
                    file.delete()
                }
            }
        }
    }

    private fun showRecordingPanel() {
        if (!::recordingPanel.isInitialized) return
        recordingPanel.visibility = View.VISIBLE
        recordingStatusText.text = "正在语音输入"
        stopRecordingButton.visibility = View.VISIBLE
        updateRecordingTimer()
    }

    private fun showProcessingVoiceState() {
        if (!::recordingPanel.isInitialized) return
        recordingPanel.visibility = View.VISIBLE
        recordingStatusText.text = "正在转写并润色"
        recordingTimerText.text = "请稍等，完成后会插入正文"
        stopRecordingButton.visibility = View.GONE
    }

    private fun hideRecordingPanel() {
        if (!::recordingPanel.isInitialized) return
        recordingPanel.visibility = View.GONE
        stopRecordingButton.visibility = View.VISIBLE
    }

    private fun updateRecordingTimer() {
        if (!::recordingTimerText.isInitialized || !::recordingPulseText.isInitialized) return
        val seconds = ((System.currentTimeMillis() - recordingStartedAt) / 1000).coerceAtLeast(0)
        val minutesPart = seconds / 60
        val secondsPart = seconds % 60
        recordingPulseStep = (recordingPulseStep + 1) % 4
        recordingPulseText.text = when (recordingPulseStep) {
            0 -> "▮▯▯"
            1 -> "▯▮▯"
            2 -> "▯▯▮"
            else -> "▮▮▮"
        }
        recordingTimerText.text =
            "%02d:%02d，点击停止后会按你的文风润色".format(minutesPart, secondsPart)
    }

    private fun showSettings() {
        val currentTheme = settingsPreferences.getString("theme_mode", "system") ?: "system"
        val currentModel = selectedChatModel()
        val systemId = View.generateViewId()
        val lightId = View.generateViewId()
        val darkId = View.generateViewId()
        val zhipuModelId = View.generateViewId()
        val stepfunModelId = View.generateViewId()
        val tint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(color(R.color.rust), color(R.color.muted))
        )

        fun label(textValue: String, size: Float = 13f, bold: Boolean = true): TextView {
            return TextView(this).apply {
                text = textValue
                textSize = size
                setTextColor(color(R.color.ink))
                if (bold) setTypeface(typeface, Typeface.BOLD)
            }
        }

        fun note(textValue: String): TextView {
            return TextView(this).apply {
                text = textValue
                textSize = 12f
                setTextColor(color(R.color.muted))
                setLineSpacing(0f, 1.15f)
            }
        }

        fun section(title: String, description: String, body: View): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = getDrawable(R.drawable.settings_card)
                addView(label(title, 15f))
                addView(note(description), LinearLayout.LayoutParams(-1, -2).withMargins(top = 5))
                addView(body, LinearLayout.LayoutParams(-1, -2).withMargins(top = 12))
            }
        }

        val themeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(this@MainActivity).apply {
                id = systemId
                text = "跟随系统"
                textSize = 14f
                buttonTintList = tint
                setTextColor(color(R.color.ink))
            })
            addView(RadioButton(this@MainActivity).apply {
                id = lightId
                text = "浅色"
                textSize = 14f
                buttonTintList = tint
                setTextColor(color(R.color.ink))
            })
            addView(RadioButton(this@MainActivity).apply {
                id = darkId
                text = "深色"
                textSize = 14f
                buttonTintList = tint
                setTextColor(color(R.color.ink))
            })
            check(
                when (currentTheme) {
                    "light" -> lightId
                    "dark" -> darkId
                    else -> systemId
                }
            )
        }

        val modelGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(RadioButton(this@MainActivity).apply {
                id = zhipuModelId
                text = "智谱 GLM 高速"
                textSize = 14f
                buttonTintList = tint
                setTextColor(color(R.color.ink))
            })
            addView(RadioButton(this@MainActivity).apply {
                id = stepfunModelId
                text = "阶跃 Step 3.7 Flash"
                textSize = 14f
                buttonTintList = tint
                setTextColor(color(R.color.ink))
            })
            check(
                when (currentModel) {
                    ChatModelProvider.STEPFUN -> stepfunModelId
                    else -> zhipuModelId
                }
            )
        }

        fun keyInput(hasKey: Boolean, hintWhenMissing: String, hintWhenSaved: String): EditText {
            return EditText(this).apply {
                hint = if (hasKey) hintWhenSaved else hintWhenMissing
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                transformationMethod = PasswordTransformationMethod.getInstance()
                textSize = 14f
                setTextColor(color(R.color.ink))
                setHintTextColor(color(R.color.muted))
                background = getDrawable(R.drawable.settings_input)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
        }

        fun keyStatus(hasKey: Boolean, configuredText: String = "状态：已配置"): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = getDrawable(R.drawable.settings_status)
                addView(TextView(this@MainActivity).apply {
                    text = if (hasKey) configuredText else "状态：未配置"
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (hasKey) color(R.color.rust) else color(R.color.muted))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(this@MainActivity).apply {
                    text = if (hasKey) "内容已隐藏" else "需要填写"
                    textSize = 12f
                    setTextColor(color(R.color.muted))
                })
            }
        }

        val hasZhipuKey = secureStore.readApiKey().isNotBlank()
        val hasStepKey = secureStore.readStepApiKey().isNotBlank()
        val zhipuInput = keyInput(
            hasZhipuKey,
            "填写智谱 API Key",
            "输入新智谱 API Key 可替换当前配置"
        )
        val stepInput = keyInput(
            hasStepKey,
            "填写 StepFun API Key",
            "输入新 StepFun API Key 可替换当前配置"
        )
        val apiKeyBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("智谱 API Key", 13f))
            addView(keyStatus(hasZhipuKey), LinearLayout.LayoutParams(-1, -2).withMargins(top = 8))
            addView(zhipuInput, LinearLayout.LayoutParams(-1, -2).withMargins(top = 10))
            addView(note("选择智谱模型时，续写和润色使用这把 Key。"),
                LinearLayout.LayoutParams(-1, -2).withMargins(top = 8))
            addView(label("StepFun API Key", 13f), LinearLayout.LayoutParams(-1, -2).withMargins(top = 14))
            addView(keyStatus(hasStepKey), LinearLayout.LayoutParams(-1, -2).withMargins(top = 8))
            addView(stepInput, LinearLayout.LayoutParams(-1, -2).withMargins(top = 10))
            addView(note("选择 Step 3.7 Flash 时，续写和润色使用这把 Key。"),
                LinearLayout.LayoutParams(-1, -2).withMargins(top = 8))
            addView(note("为了安全，已保存的 Key 不会回填到输入框。留空保存会继续使用当前 Key。"),
                LinearLayout.LayoutParams(-1, -2).withMargins(top = 12))
        }
        val styleInput = EditText(this).apply {
            hint = "例如：语言克制、句子简短，多用具体案例，不使用营销腔。"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            maxLines = 7
            gravity = Gravity.TOP or Gravity.START
            textSize = 14f
            setTextColor(color(R.color.ink))
            setHintTextColor(color(R.color.muted))
            background = getDrawable(R.drawable.settings_input)
            setText(settingsPreferences.getString("style_prompt", ""))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(18))
            background = getDrawable(R.drawable.settings_dialog)
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("设置", 23f))
            addView(note("让写作、续写和润色更贴合你的工作流"))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(iconButton("×", "关闭设置") { dialog.dismiss() })
        panel.addView(heading)
        panel.addView(
            section("外观", "选择界面颜色，深色和浅色会立即应用。", themeGroup),
            LinearLayout.LayoutParams(-1, -2).withMargins(top = 18)
        )
        panel.addView(
            section("AI 模型", "选择续写和润色模型。语音输入可以直接使用系统输入法。", modelGroup),
            LinearLayout.LayoutParams(-1, -2).withMargins(top = 12)
        )
        panel.addView(
            section("API Key", "分别保存智谱和 StepFun 的 Key，已保存内容不会明文显示。", apiKeyBody),
            LinearLayout.LayoutParams(-1, -2).withMargins(top = 12)
        )
        panel.addView(
            section("文风约束", "用于 AI 续写和文本润色，优先遵守这里填写的表达规则。", styleInput),
            LinearLayout.LayoutParams(-1, -2).withMargins(top = 12)
        )

        val footer = LinearLayout(this).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(actionButton("取消", false) { dialog.dismiss() })
            addView(actionButton("保存", true) {
                val nextTheme = when (themeGroup.checkedRadioButtonId) {
                    lightId -> "light"
                    darkId -> "dark"
                    else -> "system"
                }
                val nextModel = when (modelGroup.checkedRadioButtonId) {
                    stepfunModelId -> ChatModelProvider.STEPFUN
                    else -> ChatModelProvider.ZHIPU
                }
                settingsPreferences.edit()
                    .putString("theme_mode", nextTheme)
                    .putString("chat_model", nextModel.preferenceValue)
                    .putString("style_prompt", styleInput.text.toString().trim())
                    .apply()
                val nextZhipuKey = zhipuInput.text.toString().trim()
                if (nextZhipuKey.isNotBlank()) {
                    secureStore.saveApiKey(nextZhipuKey)
                }
                val nextStepKey = stepInput.text.toString().trim()
                if (nextStepKey.isNotBlank()) {
                    secureStore.saveStepApiKey(nextStepKey)
                }
                updateStats()
                dialog.dismiss()
                if (nextTheme != currentTheme) {
                    recreate()
                }
            }, LinearLayout.LayoutParams(-2, dp(42)).withMargins(start = 10))
        }
        panel.addView(footer, LinearLayout.LayoutParams(-1, -2).withMargins(top = 18))

        val settingsScroll = ScrollView(this).apply {
            isFillViewport = false
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(panel, LinearLayout.LayoutParams(-1, -2))
        }
        dialog.setContentView(settingsScroll)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                minOf(resources.displayMetrics.widthPixels - dp(44), dp(720)),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun openReferencePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQUEST_FILES)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == REQUEST_IMAGE) {
            data.data?.let { insertLocalImage(it) }
            return
        }
        if (requestCode != REQUEST_FILES) return
        val uris = mutableListOf<Uri>()
        data.data?.let(uris::add)
        data.clipData?.let { clip ->
            repeat(clip.itemCount) { uris.add(clip.getItemAt(it).uri) }
        }
        uris.forEach { addReference(it) }
        renderReferences()
        saveDraft()
    }

    private fun insertLocalImage(uri: Uri) {
        runCatching {
            val directory = File(filesDir, "document-images").apply { mkdirs() }
            val target = File(directory, "image-${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取图片" }
                target.outputStream().use { output -> input.copyTo(output) }
            }
            insertImageSpan(target)
        }.onFailure {
            showToast(it.message ?: "图片插入失败")
        }
    }

    private fun insertImageSpan(file: File) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            ?: throw IllegalStateException("无法解析图片")
        pushUndoSnapshot()
        val maxWidth = resources.displayMetrics.widthPixels - dp(64)
        val scale = minOf(1f, maxWidth.toFloat() / bitmap.width.toFloat())
        val drawable = BitmapDrawable(resources, bitmap).apply {
            setBounds(0, 0, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt())
        }
        val cursor = contentEdit.selectionStart.coerceAtLeast(0)
        val marker = "\n\uFFFC\n"
        contentEdit.text.insert(cursor, marker)
        contentEdit.text.setSpan(
            ImageSpan(drawable, file.absolutePath),
            cursor + 1,
            cursor + 2,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        contentEdit.setSelection(cursor + marker.length)
        saveDraft()
        showToast("图片已插入，仅保存在本机")
    }

    private fun imageGetter(): Html.ImageGetter {
        return Html.ImageGetter { source ->
            val file = File(source.orEmpty())
            if (!file.exists()) return@ImageGetter null
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@ImageGetter null
            val maxWidth = resources.displayMetrics.widthPixels - dp(64)
            val scale = minOf(1f, maxWidth.toFloat() / bitmap.width.toFloat())
            BitmapDrawable(resources, bitmap).apply {
                setBounds(0, 0, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt())
            }
        }
    }

    private fun addReference(uri: Uri) {
        val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: "参考文件"
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.getOrElse {
            showToast("$name 读取失败")
            ""
        }.take(12_000)
        if (text.isNotBlank()) references.add(ReferenceFile(name, cleanReferenceText(text)))
    }

    private fun renderReferences() {
        referenceList.removeAllViews()
        if (references.isEmpty()) {
            referenceList.addView(TextView(this).apply {
                text = "还没有参考文件"
                textSize = 13f
                setTextColor(color(R.color.muted))
                setPadding(0, dp(8), 0, 0)
            })
            return
        }
        references.forEach { reference ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            val checkBox = CheckBox(this).apply {
                isChecked = reference.selected
                text = "${reference.name}  ${reference.text.length} 字符"
                textSize = 13f
                setTextColor(color(R.color.ink))
                setOnCheckedChangeListener { _, checked ->
                    reference.selected = checked
                    saveDraft()
                }
            }
            row.addView(checkBox, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(actionButton("移除", false) {
                references.remove(reference)
                renderReferences()
                saveDraft()
            })
            referenceList.addView(row)
        }
    }

    private fun selectedReferenceText(): String {
        return references.filter { it.selected }.joinToString("\n\n") {
            "文件：${it.name}\n${it.text}"
        }.take(5000)
    }

    private fun cleanReferenceText(value: String): String {
        return value.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun saveDraft() {
        if (!::titleEdit.isInitialized || !::contentEdit.isInitialized) return
        mainHandler.removeCallbacks(draftSaveRunnable)
        val referenceJson = JSONArray()
        references.forEach {
            referenceJson.put(
                JSONObject()
                    .put("name", it.name)
                    .put("text", it.text)
                    .put("selected", it.selected)
            )
        }
        currentArticle.title = titleEdit.text.toString()
        val contentForSave = contentWithoutInlineSuggestion()
        currentArticle.content = contentForSave.toString()
        currentArticle.contentHtml =
            Html.toHtml(contentForSave, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
        currentArticle.referencesJson = referenceJson.toString()
        currentArticle.wordCount = ArticleStore.countWords(currentArticle.content)
        currentArticle.updatedAt = System.currentTimeMillis()
        articleStore.saveAll(articles, currentArticle.id)
        updateStats()
    }

    private fun scheduleDraftSave() {
        mainHandler.removeCallbacks(draftSaveRunnable)
        mainHandler.postDelayed(draftSaveRunnable, 450)
    }

    private fun loadRichContent(article: LocalArticle): Spanned {
        val html = article.contentHtml
        if (html.isNullOrBlank()) {
            return android.text.SpannableString(article.content)
        }
        return Html.fromHtml(
            html,
            Html.FROM_HTML_MODE_LEGACY,
            imageGetter(),
            null
        )
    }

    private fun loadArticleReferences(article: LocalArticle) {
        references.clear()
        runCatching {
            val array = JSONArray(article.referencesJson)
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                references.add(
                    ReferenceFile(
                        item.optString("name", "参考文件"),
                        item.optString("text").take(12_000),
                        item.optBoolean("selected", true)
                    )
                )
            }
        }
    }

    private fun switchArticle(article: LocalArticle) {
        if (article.id == currentArticle.id) return
        saveDraft()
        currentArticle = article
        loadArticleReferences(article)
        suppressChanges = true
        titleEdit.setText(article.title)
        contentEdit.setText(loadRichContent(article))
        contentEdit.setSelection(contentEdit.text.length)
        suppressChanges = false
        undoStack.clear()
        textBeforeChange = null
        renderReferences()
        updateStats()
        clearSuggestion()
        articleStore.saveAll(articles, article.id)
    }

    private fun showArticleDrawer() {
        saveDraft()
        val dialog = Dialog(this)
        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.paper))
        }
        applySystemBarPadding(drawer, dp(18), dp(18), dp(18), dp(18))
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(TextView(this).apply {
            text = "我的文章"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.ink))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(actionButton("新建文章", true) {
            val article = articleStore.newArticle()
            articles.add(0, article)
            switchArticle(article)
            dialog.dismiss()
        })
        drawer.addView(heading)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun renderList() {
            list.removeAllViews()
            articles.sortedByDescending { it.updatedAt }
                .forEach { article ->
                    list.addView(buildArticleRow(article, dialog))
                }
            if (list.childCount == 0) {
                list.addView(TextView(this).apply {
                    text = "还没有文章"
                    setTextColor(color(R.color.muted))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(32), 0, 0)
                })
            }
        }
        renderList()
        drawer.addView(
            ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(-1, 0, 1f).withMargins(top = 14)
        )
        dialog.setContentView(drawer)
        dialog.show()
        drawer.requestApplyInsets()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.START)
            setLayout(
                if (wideLayout) dp(360) else (resources.displayMetrics.widthPixels * 0.85f).toInt(),
                WindowManager.LayoutParams.MATCH_PARENT
            )
            attributes = attributes.apply {
                windowAnimations = android.R.style.Animation_Dialog
            }
        }
    }

    private fun buildArticleRow(article: LocalArticle, dialog: Dialog): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(4), dp(11))
            background = getDrawable(
                if (article.id == currentArticle.id) {
                    R.drawable.article_item_current
                } else {
                    R.drawable.article_item
                }
            )
            setOnClickListener {
                switchArticle(article)
                dialog.dismiss()
            }
        }
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        details.addView(TextView(this).apply {
            text = article.title.ifBlank { "无标题文章" }
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.ink))
            maxLines = 1
        })
        details.addView(TextView(this).apply {
            text = "${formatArticleTime(article.updatedAt)}    ${article.wordCount} 字"
            textSize = 11f
            setTextColor(color(R.color.muted))
        })
        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "⋮"
            textSize = 20f
            contentDescription = "文章操作"
            minHeight = dp(44)
            minWidth = dp(44)
            minimumWidth = dp(44)
            setPadding(0, 0, 0, 0)
            setTextColor(color(R.color.ink))
            background = getDrawable(R.drawable.button_icon)
            setOnClickListener { showArticleMenu(this, article, dialog) }
        })
        row.setOnLongClickListener {
            showArticleActions(article, dialog)
            true
        }
        row.layoutParams = LinearLayout.LayoutParams(-1, -2).withMargins(top = 6)
        return row
    }

    private fun showArticleMenu(anchor: View, article: LocalArticle, dialog: Dialog) {
        PopupMenu(this, anchor).apply {
            menu.add("重命名")
            menu.add("复制")
            menu.add("删除")
            setOnMenuItemClickListener {
                handleArticleAction(it.title.toString(), article, dialog)
                true
            }
            show()
        }
    }

    private fun showArticleActions(article: LocalArticle, dialog: Dialog) {
        AlertDialog.Builder(this)
            .setItems(arrayOf("重命名", "复制", "删除")) { _, which ->
                handleArticleAction(arrayOf("重命名", "复制", "删除")[which], article, dialog)
            }
            .show()
    }

    private fun handleArticleAction(action: String, article: LocalArticle, dialog: Dialog) {
        when (action) {
            "重命名" -> {
                val input = EditText(this).apply {
                    setText(article.title)
                    setSelection(text.length)
                }
                AlertDialog.Builder(this)
                    .setTitle("重命名文章")
                    .setView(input)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("保存") { _, _ ->
                        article.title = input.text.toString().trim()
                        article.updatedAt = System.currentTimeMillis()
                        articleStore.saveAll(articles, currentArticle.id)
                        if (article.id == currentArticle.id) {
                            suppressChanges = true
                            titleEdit.setText(article.title)
                            suppressChanges = false
                        }
                        dialog.dismiss()
                        showArticleDrawer()
                    }
                    .show()
            }
            "复制" -> {
                val copy = articleStore.duplicate(article)
                articles.add(0, copy)
                articleStore.saveAll(articles, currentArticle.id)
                dialog.dismiss()
                showArticleDrawer()
            }
            "删除" -> {
                AlertDialog.Builder(this)
                    .setTitle("删除这篇文章")
                    .setMessage("文章只保存在本机，删除后无法恢复。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ ->
                        val deletingCurrent = article.id == currentArticle.id
                        articles.remove(article)
                        if (articles.isEmpty()) articles.add(articleStore.newArticle())
                        if (deletingCurrent) {
                            currentArticle = articles.maxByOrNull { it.updatedAt } ?: articles.first()
                            loadArticleReferences(currentArticle)
                            suppressChanges = true
                            titleEdit.setText(currentArticle.title)
                            contentEdit.setText(loadRichContent(currentArticle))
                            suppressChanges = false
                            undoStack.clear()
                            textBeforeChange = null
                            renderReferences()
                            updateStats()
                        }
                        articleStore.saveAll(articles, currentArticle.id)
                        dialog.dismiss()
                    }
                    .show()
            }
        }
    }

    private fun formatArticleTime(value: Long): String {
        return SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(value))
    }

    private fun updateStats() {
        val count = contentPlainText().replace(Regex("\\s+"), "").length
        statusText.text = "字数 $count"
    }

    private fun copyMarkdown() {
        val markdown = "# ${titleEdit.text.toString().ifBlank { "无标题文章" }}\n\n" +
            contentPlainText().trim()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Markdown", markdown))
        showToast("Markdown 已复制")
    }

    private fun actionButton(
        label: String,
        primary: Boolean,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            minHeight = dp(42)
            minimumWidth = 0
            setPadding(dp(13), 0, dp(13), 0)
            setTextColor(if (primary) Color.WHITE else color(R.color.ink))
            background = getDrawable(
                if (primary) R.drawable.button_primary else R.drawable.button_secondary
            )
            setOnClickListener { action() }
        }
    }

    private fun iconButton(label: String, description: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 19f
            contentDescription = description
            minHeight = dp(44)
            minWidth = dp(44)
            minimumWidth = dp(44)
            setPadding(0, 0, 0, 0)
            setTextColor(color(R.color.ink))
            background = getDrawable(R.drawable.button_icon)
            setOnClickListener { action() }
        }
    }

    private fun headerIconButton(drawableRes: Int, description: String, action: () -> Unit): Button {
        return iconButton("", description, action).apply {
            setCompoundDrawablesWithIntrinsicBounds(drawableRes, 0, 0, 0)
        }
    }

    private fun View.withLayoutMargins(start: Int = 0, top: Int = 0, end: Int = 0): View {
        layoutParams = LinearLayout.LayoutParams(-2, -2).withMargins(start, top, end)
        return this
    }

    private fun LinearLayout.LayoutParams.withMargins(
        start: Int = 0,
        top: Int = 0,
        end: Int = 0,
        bottom: Int = 0
    ): LinearLayout.LayoutParams {
        setMargins(dp(start), dp(top), dp(end), dp(bottom))
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun color(id: Int): Int = getColor(id)
    private fun showToast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun configureSystemBars() {
        window.statusBarColor = color(R.color.paper)
        window.navigationBarColor = color(R.color.paper)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        val isDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        window.decorView.systemUiVisibility =
            if (isDark) {
                0
            } else {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
    }

    private fun applySystemBarPadding(
        view: View,
        baseLeft: Int,
        baseTop: Int,
        baseRight: Int,
        baseBottom: Int
    ) {
        view.setPadding(baseLeft, baseTop, baseRight, baseBottom)
        view.setOnApplyWindowInsetsListener { target, insets ->
            target.setPadding(
                baseLeft + insets.systemWindowInsetLeft,
                baseTop + insets.systemWindowInsetTop,
                baseRight + insets.systemWindowInsetRight,
                baseBottom + insets.systemWindowInsetBottom
            )
            insets
        }
        if (view.isAttachedToWindow) {
            view.requestApplyInsets()
        } else {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(attachedView: View) {
                    attachedView.removeOnAttachStateChangeListener(this)
                    attachedView.requestApplyInsets()
                }

                override fun onViewDetachedFromWindow(detachedView: View) = Unit
            })
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            showToast("已关闭内置语音输入，请使用系统输入法语音")
        } else if (requestCode == REQUEST_AUDIO) {
            showToast("已关闭内置语音输入")
        }
    }

    companion object {
        private const val REQUEST_AUDIO = 1001
        private const val REQUEST_FILES = 1002
        private const val REQUEST_IMAGE = 1003
    }
}
