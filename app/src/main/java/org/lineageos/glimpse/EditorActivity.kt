//
// SPDX-FileCopyrightText: 2026 The VoltageOS Project
// SPDX-License-Identifier: Apache-2.0
//

package org.lineageos.glimpse

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.lineageos.glimpse.datasources.MediaError
import org.lineageos.glimpse.ext.createWriteRequest
import org.lineageos.glimpse.ext.fade
import org.lineageos.glimpse.ext.typefaceFor
import org.lineageos.glimpse.models.RequestStatus
import org.lineageos.glimpse.models.editor.EditorTool
import org.lineageos.glimpse.models.editor.MarkupElement
import org.lineageos.glimpse.models.editor.Transform
import org.lineageos.glimpse.ui.editor.ColorPaletteView
import org.lineageos.glimpse.ui.editor.CropOverlayView
import org.lineageos.glimpse.ui.editor.MarkupView
import org.lineageos.glimpse.viewmodels.EditorViewModel
import kotlin.math.roundToInt

class EditorActivity : AppCompatActivity(R.layout.activity_editor) {
    private val viewModel by viewModels<EditorViewModel>()

    // Views
    private val appBarLayout by lazy { findViewById<AppBarLayout>(R.id.appBarLayout) }
    private val bottomSheetLinearLayout by lazy { findViewById<LinearLayout>(R.id.bottomSheetLinearLayout) }
    private val canvasContainer by lazy { findViewById<FrameLayout>(R.id.canvasContainer) }
    private val colorPaletteView by lazy { findViewById<ColorPaletteView>(R.id.colorPaletteView) }
    private val cropActionsLinearLayout by lazy { findViewById<LinearLayout>(R.id.cropActionsLinearLayout) }
    private val cropButton by lazy { findViewById<MaterialButton>(R.id.cropButton) }
    private val cropOverlayView by lazy { findViewById<CropOverlayView>(R.id.cropOverlayView) }
    private val eraserButton by lazy { findViewById<MaterialButton>(R.id.eraserButton) }
    private val eraserDecreaseButton by lazy { findViewById<MaterialButton>(R.id.eraserDecreaseButton) }
    private val eraserIncreaseButton by lazy { findViewById<MaterialButton>(R.id.eraserIncreaseButton) }
    private val eraserOptionsLinearLayout by lazy { findViewById<LinearLayout>(R.id.eraserOptionsLinearLayout) }
    private val eraserSizeLabel by lazy { findViewById<TextView>(R.id.eraserSizeLabel) }
    private val flipButton by lazy { findViewById<MaterialButton>(R.id.flipButton) }
    private val fontButton by lazy { findViewById<MaterialButton>(R.id.fontButton) }
    private val highlighterButton by lazy { findViewById<MaterialButton>(R.id.highlighterButton) }
    private val markupView by lazy { findViewById<MarkupView>(R.id.markupView) }
    private val moveButton by lazy { findViewById<MaterialButton>(R.id.moveButton) }
    private val penButton by lazy { findViewById<MaterialButton>(R.id.penButton) }
    private val resetButton by lazy { findViewById<MaterialButton>(R.id.resetButton) }
    private val rotateButton by lazy { findViewById<MaterialButton>(R.id.rotateButton) }
    private val textButton by lazy { findViewById<MaterialButton>(R.id.textButton) }
    private val textDecreaseButton by lazy { findViewById<MaterialButton>(R.id.textDecreaseButton) }
    private val textDoneButton by lazy { findViewById<MaterialButton>(R.id.textDoneButton) }
    private val textIncreaseButton by lazy { findViewById<MaterialButton>(R.id.textIncreaseButton) }
    private val textInputEditText by lazy { findViewById<EditText>(R.id.textInputEditText) }
    private val textInputLinearLayout by lazy { findViewById<LinearLayout>(R.id.textInputLinearLayout) }
    private val textOptionsLinearLayout by lazy { findViewById<LinearLayout>(R.id.textOptionsLinearLayout) }
    private val textRotateButton by lazy { findViewById<MaterialButton>(R.id.textRotateButton) }
    private val textSizeLabel by lazy { findViewById<TextView>(R.id.textSizeLabel) }
    private val toolbar by lazy { findViewById<MaterialToolbar>(R.id.toolbar) }
    private val toolsLinearLayout by lazy { findViewById<LinearLayout>(R.id.toolsLinearLayout) }

    // System services
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private val inputMethodManager by lazy { getSystemService(InputMethodManager::class.java) }

    private var previousTool = EditorTool.PEN
    private var pendingCropDraft = RectF(0f, 0f, 1f, 1f)
    private var editingTextId: Long? = null
    private var editingAnchor: PointF? = null

    private val textFonts by lazy {
        listOf(getString(R.string.editor_default_font) to null) +
                resources.getStringArray(R.array.editor_text_fonts).map { family ->
                    family.split('-').joinToString(" ") {
                        it.replaceFirstChar(Char::titlecase)
                    } to family
                }
    }

    // Contracts
    private val overwriteContract =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            if (it.resultCode == RESULT_OK) {
                viewModel.save(true)
            }
        }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (isTextEditing()) {
                commitTextEditor()
                return
            }
            MaterialAlertDialogBuilder(this@EditorActivity)
                .setTitle(R.string.editor_discard_dialog_title)
                .setMessage(R.string.editor_discard_dialog_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    finish()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        if (keyguardManager.isKeyguardLocked && intent.action == Intent.ACTION_EDIT) {
            setShowWhenLocked(true)
        }

        onBackPressedDispatcher.addCallback(this, backCallback)

        ViewCompat.setOnApplyWindowInsetsListener(bottomSheetLinearLayout) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            bottomSheetLinearLayout.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = bars.bottom,
            )
            updateCanvasPadding()
            insets
        }

        markupView.nextId = viewModel::nextId
        markupView.onElementAdded = viewModel::addElement
        markupView.onTextRequested = { norm ->
            commitTextEditor()
            showTextEditor(norm)
        }
        markupView.onTextEditRequested = { element ->
            commitTextEditor()
            showTextEditorForEdit(element)
        }
        markupView.onTextMoved = { id, anchor ->
            viewModel.moveText(id, anchor)
        }
        markupView.onMoveStarted = viewModel::beginMove
        markupView.onMoveCancelled = viewModel::cancelMove
        markupView.onElementsMoved = viewModel::moveElement
        cropOverlayView.onZoomGesture = markupView::onTouchEvent

        cropOverlayView.onCropChanged = {
            pendingCropDraft.set(it)
        }

        toolbar.setNavigationOnClickListener {
            if (viewModel.tool.value == EditorTool.CROP) {
                exitCrop(false)
            } else {
                onBackPressedDispatcher.onBackPressed()
            }
        }

        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.undo -> {
                    viewModel.undo()
                    true
                }
                R.id.redo -> {
                    viewModel.redo()
                    true
                }
                else -> false
            }
        }

        penButton.setOnClickListener { selectTool(EditorTool.PEN) }
        highlighterButton.setOnClickListener { selectTool(EditorTool.HIGHLIGHTER) }
        eraserButton.setOnClickListener { selectTool(EditorTool.ERASER) }
        textButton.setOnClickListener { selectTool(EditorTool.TEXT) }
        moveButton.setOnClickListener { selectTool(EditorTool.MOVE) }
        cropButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            enterCrop()
        }

        rotateButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val cur = viewModel.snapshot.value.transform
            viewModel.applyTransform(
                cur.copy(rotationDegrees = (cur.rotationDegrees + 90) % 360)
            )
            cropOverlayView.reset()
            pendingCropDraft.set(0f, 0f, 1f, 1f)
            cropOverlayView.imageRect = markupView.imageRect
        }
        flipButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val cur = viewModel.snapshot.value.transform
            viewModel.applyTransform(cur.copy(flipped = !cur.flipped))
            cropOverlayView.reset()
            pendingCropDraft.set(0f, 0f, 1f, 1f)
            cropOverlayView.imageRect = markupView.imageRect
        }
        resetButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            viewModel.applyTransform(Transform())
            cropOverlayView.reset()
            pendingCropDraft.set(0f, 0f, 1f, 1f)
        }

        colorPaletteView.onColorSelected = {
            viewModel.setColor(it)
        }

        eraserDecreaseButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            viewModel.setEraserWidth(viewModel.eraserWidth.value - MarkupElement.Eraser.WIDTH_STEP)
        }
        eraserIncreaseButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            viewModel.setEraserWidth(viewModel.eraserWidth.value + MarkupElement.Eraser.WIDTH_STEP)
        }
        textDecreaseButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            viewModel.setTextSize(viewModel.textSize.value - MarkupElement.Text.SIZE_STEP)
        }
        textIncreaseButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            viewModel.setTextSize(viewModel.textSize.value + MarkupElement.Text.SIZE_STEP)
        }
        textRotateButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            viewModel.setTextRotation(viewModel.textRotation.value + 90)
            updateTextPreview()
        }
        fontButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            showFontPicker()
        }

        textInputEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitTextEditor()
                true
            } else {
                false
            }
        }
        textInputEditText.doOnTextChanged { _, _, _, _ ->
            updateTextPreview()
        }
        textDoneButton.setOnClickListener {
            commitTextEditor()
        }

        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        viewModel.setUri(uri)

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                loadData()
            }
        }
    }

    private suspend fun loadData() {
        coroutineScope {
            launch {
                viewModel.sourceBitmap.collect { status ->
                    when (status) {
                        is RequestStatus.Loading -> {
                            // keep black, could show spinner
                        }
                        is RequestStatus.Success -> {
                            markupView.bitmap = status.data
                            cropOverlayView.imageRect = markupView.imageRect
                            updateCanvasPadding()
                        }
                        is RequestStatus.Error -> {
                            Snackbar.make(
                                canvasContainer,
                                R.string.intent_media_not_found,
                                Snackbar.LENGTH_LONG,
                            ).show()
                            finish()
                        }
                    }
                }
            }

            launch {
                viewModel.tool.collect {
                    markupView.tool = it
                    updateToolButtons(it)
                    val drawing = it == EditorTool.PEN ||
                            it == EditorTool.HIGHLIGHTER ||
                            it == EditorTool.TEXT
                    if (it != EditorTool.CROP) {
                        colorPaletteView.fade(drawing)
                        textOptionsLinearLayout.fade(it == EditorTool.TEXT)
                        eraserOptionsLinearLayout.fade(it == EditorTool.ERASER)
                    }
                    updateCanvasPadding()
                }
            }

            launch {
                viewModel.color.collect {
                    markupView.color = it
                    colorPaletteView.setSelectedColor(it)
                    styleTextEditor()
                }
            }

            launch {
                viewModel.textSize.collect {
                    textSizeLabel.text =
                        "${(it / MarkupElement.Text.DEFAULT_SIZE * 100).roundToInt()}%"
                    styleTextEditor()
                }
            }

            launch {
                viewModel.textTypeface.collect { family ->
                    fontButton.text = textFonts.firstOrNull { it.second == family }?.first
                        ?: getString(R.string.editor_default_font)
                    styleTextEditor()
                }
            }

            launch {
                viewModel.eraserWidth.collect {
                    markupView.eraserWidth = it
                    eraserSizeLabel.text =
                        "${(it / MarkupElement.Eraser.DEFAULT_WIDTH * 100).roundToInt()}%"
                }
            }

            launch {
                viewModel.snapshot.collect {
                    markupView.elements = it.elements
                    markupView.transform = it.transform
                    // Keep crop overlay in sync when transform changes outside crop
                    if (viewModel.tool.value == EditorTool.CROP) {
                        cropOverlayView.imageRect = markupView.imageRect
                    }
                }
            }

            launch {
                viewModel.canUndo.collect {
                    toolbar.menu.findItem(R.id.undo)?.let { item ->
                        item.isEnabled = it
                        item.icon?.alpha = if (it) 255 else 97
                    }
                }
            }

            launch {
                viewModel.canRedo.collect {
                    toolbar.menu.findItem(R.id.redo)?.let { item ->
                        item.isEnabled = it
                        item.icon?.alpha = if (it) 255 else 97
                    }
                }
            }

            launch {
                viewModel.isDirty.collect {
                    backCallback.isEnabled = it
                }
            }

            launch {
                viewModel.saveStatus.collect { status ->
                    when (status) {
                        null -> {
                            // idle
                        }
                        is RequestStatus.Loading -> {
                            // Could show progress, keep it simple
                        }
                        is RequestStatus.Success -> {
                            setResult(RESULT_OK, Intent().setData(status.data))
                            viewModel.clearSaveStatus()
                            finish()
                        }
                        is RequestStatus.Error -> {
                            val msg = when (status.error) {
                                MediaError.NOT_FOUND -> getString(R.string.intent_media_not_found)
                                else -> getString(R.string.editor_save_failed)
                            }
                            Snackbar.make(canvasContainer, msg, Snackbar.LENGTH_LONG).show()
                            viewModel.clearSaveStatus()
                        }
                    }
                }
            }
        }
    }

    private fun selectTool(tool: EditorTool) {
        if (viewModel.tool.value == EditorTool.CROP) {
            exitCrop(true)
        }
        markupView.cancelGesture()
        previousTool = tool
        viewModel.setTool(tool)
        // Hide keyboard / text editor when switching
        if (tool != EditorTool.TEXT) {
            commitTextEditor()
        }
    }

    private fun updateToolButtons(tool: EditorTool) {
        val map = mapOf(
            penButton to (tool == EditorTool.PEN),
            highlighterButton to (tool == EditorTool.HIGHLIGHTER),
            eraserButton to (tool == EditorTool.ERASER),
            textButton to (tool == EditorTool.TEXT),
            moveButton to (tool == EditorTool.MOVE),
            cropButton to (tool == EditorTool.CROP),
        )
        for ((button, selected) in map) {
            if (button.isSelected != selected) {
                button.isSelected = selected
                button.animate().cancel()
                button.animate()
                    .alpha(if (selected) 1f else 0.7f)
                    .setDuration(150)
                    .start()
            }
        }
    }

    private fun enterCrop() {
        commitTextEditor()
        markupView.cancelGesture()
        previousTool = viewModel.tool.value.takeIf { it != EditorTool.CROP }
            ?: EditorTool.PEN
        viewModel.setTool(EditorTool.CROP)
        pendingCropDraft.set(0f, 0f, 1f, 1f)
        cropOverlayView.cropDraft.set(0f, 0f, 1f, 1f)
        cropOverlayView.imageRect = markupView.imageRect
        toolsLinearLayout.fade(false)
        colorPaletteView.fade(false)
        textOptionsLinearLayout.fade(false)
        eraserOptionsLinearLayout.fade(false)
        cropActionsLinearLayout.fade(true)
        cropOverlayView.fade(true)
        updateCanvasPadding()
        toolbar.title = getString(R.string.editor_crop)
        toolbar.menu.findItem(R.id.undo)?.isVisible = false
        toolbar.menu.findItem(R.id.redo)?.isVisible = false
        toolbar.menu.findItem(R.id.save)?.actionView?.findViewById<MaterialButton>(
            R.id.saveButton
        )?.let {
            it.text = getString(android.R.string.ok)
            it.setOnClickListener { _ ->
                exitCrop(true)
            }
        }
        toolbar.setNavigationIcon(R.drawable.ic_close)
    }

    private fun exitCrop(apply: Boolean) {
        if (apply) {
            val old = viewModel.snapshot.value.transform.cropRect
            val d = pendingCropDraft
            // Compose draft (in current cropped space) onto global crop
            val composed = RectF(
                old.left + d.left * old.width(),
                old.top + d.top * old.height(),
                old.left + d.right * old.width(),
                old.top + d.bottom * old.height(),
            )
            // Clamp
            composed.left = composed.left.coerceIn(0f, 1f)
            composed.top = composed.top.coerceIn(0f, 1f)
            composed.right = composed.right.coerceIn(0f, 1f)
            composed.bottom = composed.bottom.coerceIn(0f, 1f)
            if (composed.width() > 0.01f && composed.height() > 0.01f &&
                composed != old
            ) {
                val cur = viewModel.snapshot.value.transform
                viewModel.applyTransform(cur.copy(cropRect = composed))
            }
        }
        viewModel.setTool(previousTool)
        toolsLinearLayout.fade(true)
        textOptionsLinearLayout.fade(previousTool == EditorTool.TEXT)
        eraserOptionsLinearLayout.fade(previousTool == EditorTool.ERASER)
        cropActionsLinearLayout.fade(false)
        cropOverlayView.fade(false)
        updateCanvasPadding()
        toolbar.title = getString(R.string.file_action_edit)
        toolbar.menu.findItem(R.id.undo)?.isVisible = true
        toolbar.menu.findItem(R.id.redo)?.isVisible = true
        bindSaveButton()
        // Refresh overlay rect for next time
        cropOverlayView.imageRect = markupView.imageRect
    }

    private fun bindSaveButton() {
        toolbar.menu.findItem(R.id.save)?.actionView?.findViewById<MaterialButton>(
            R.id.saveButton
        )?.let { saveButton ->
            saveButton.text = getString(R.string.editor_save)
            saveButton.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                commitTextEditor()
                MaterialAlertDialogBuilder(this)
                    .setItems(
                        arrayOf(
                            getString(R.string.editor_save_copy),
                            getString(R.string.editor_save),
                        )
                    ) { _, which ->
                        if (which == 1) {
                            // Overwrite needs write permission first
                            val target = viewModel.uri.value ?: return@setItems
                            runCatching {
                                overwriteContract.launch(
                                    contentResolver.createWriteRequest(target)
                                )
                            }.onFailure {
                                viewModel.save(true)
                            }
                        } else {
                            viewModel.save(false)
                        }
                    }
                    .show()
            }
        }
    }

    private fun isTextEditing() = textInputLinearLayout.isVisible

    private fun showTextEditor(anchor: PointF) {
        editingTextId = null
        editingAnchor = PointF(anchor.x, anchor.y)
        markupView.hiddenId = null
        textInputEditText.setText("")
        textInputLinearLayout.fade(true)
        styleTextEditor()
        textInputEditText.requestFocus()
        inputMethodManager.showSoftInput(textInputEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun showTextEditorForEdit(element: MarkupElement.Text) {
        editingTextId = element.id
        editingAnchor = PointF(element.anchor.x, element.anchor.y)
        markupView.hiddenId = element.id
        viewModel.setColor(element.color)
        viewModel.setTextSize(element.sizeFraction)
        viewModel.setTextTypeface(element.typeface)
        viewModel.setTextRotation(element.rotationDegrees)
        textInputEditText.setText(element.value)
        textInputEditText.setSelection(element.value.length)
        textInputLinearLayout.fade(true)
        styleTextEditor()
        textInputEditText.requestFocus()
        inputMethodManager.showSoftInput(textInputEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun showFontPicker() {
        val labels = textFonts.map { it.first }.toTypedArray()
        val checked = textFonts.indexOfFirst { it.second == viewModel.textTypeface.value }
            .takeIf { it >= 0 } ?: 0
        var dialog: AlertDialog? = null
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.editor_font)
            .setSingleChoiceItems(labels, checked) { _, which ->
                viewModel.setTextTypeface(textFonts[which].second)
                dialog?.dismiss()
            }
            .show()
    }

    /**
     * Mirror the open editor onto the canvas, so what you type shows
     * where it will land even while the keyboard is up.
     */
    private fun updateTextPreview() {
        val anchor = editingAnchor
        if (!isTextEditing() || anchor == null) {
            markupView.previewText = null
            return
        }
        val raw = textInputEditText.text?.toString().orEmpty()
        markupView.previewText = when {
            raw.isEmpty() -> null
            else -> MarkupElement.Text(
                // Uncommitted preview always draws on top; the real id
                // (and its z-order) is assigned at commit.
                id = editingTextId ?: Long.MAX_VALUE,
                color = viewModel.color.value,
                value = raw,
                anchor = PointF(anchor.x, anchor.y),
                sizeFraction = viewModel.textSize.value,
                typeface = viewModel.textTypeface.value,
                rotationDegrees = viewModel.textRotation.value,
            )
        }
    }

    private fun styleTextEditor() {
        if (!isTextEditing()) {
            return
        }
        textInputEditText.setTextColor(viewModel.color.value)
        textInputEditText.typeface = typefaceFor(viewModel.textTypeface.value)
        updateTextPreview()
    }

    private fun commitTextEditor() {
        if (!isTextEditing()) {
            return
        }
        val text = textInputEditText.text?.toString()?.trim().orEmpty()
        val anchor = editingAnchor
        val id = editingTextId
        textInputLinearLayout.fade(false)
        markupView.previewText = null
        markupView.hiddenId = null
        inputMethodManager.hideSoftInputFromWindow(textInputEditText.windowToken, 0)
        if (anchor == null) {
            editingTextId = null
            editingAnchor = null
            return
        }
        if (id != null) {
            if (text.isEmpty()) {
                viewModel.removeElements(setOf(id))
            } else {
                val color = viewModel.color.value
                val size = viewModel.textSize.value
                val typeface = viewModel.textTypeface.value
                val rotation = viewModel.textRotation.value
                val unchanged = viewModel.snapshot.value.elements
                    .filterIsInstance<MarkupElement.Text>()
                    .firstOrNull { it.id == id }
                    ?.let {
                        it.value == text && it.color == color &&
                                it.sizeFraction == size && it.typeface == typeface &&
                                it.rotationDegrees == rotation
                    } ?: false
                if (!unchanged) {
                    viewModel.updateText(
                        id, text,
                        color = color,
                        sizeFraction = size,
                        typeface = typeface,
                        rotationDegrees = rotation,
                    )
                }
            }
        } else if (text.isNotEmpty()) {
            viewModel.addElement(
                MarkupElement.Text(
                    id = viewModel.nextId(),
                    color = viewModel.color.value,
                    value = text,
                    anchor = anchor,
                    sizeFraction = viewModel.textSize.value,
                    typeface = viewModel.textTypeface.value,
                    rotationDegrees = viewModel.textRotation.value,
                )
            )
        }
        editingTextId = null
        editingAnchor = null
    }

    private fun updateCanvasPadding() {
        appBarLayout.measure(
            View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED
        )
        bottomSheetLinearLayout.measure(
            View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED
        )
        canvasContainer.updatePadding(
            top = appBarLayout.measuredHeight,
            bottom = bottomSheetLinearLayout.measuredHeight,
        )
    }

    override fun onResume() {
        super.onResume()
        // Toolbar save button is inside actionLayout, bind after menu is ready
        toolbar.post { bindSaveButton() }
        colorPaletteView.post {
            colorPaletteView.setColorsAndSelect(viewModel.color.value)
        }
    }

    override fun onPause() {
        commitTextEditor()
        super.onPause()
    }

    companion object {
        @Suppress("unused")
        fun createIntent(
            context: android.content.Context,
            uri: android.net.Uri,
            mimeType: String,
        ) = Intent(context, EditorActivity::class.java).apply {
            action = Intent.ACTION_EDIT
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
