//
// SPDX-FileCopyrightText: 2026 The VoltageOS Project
// SPDX-License-Identifier: Apache-2.0
//

package org.lineageos.glimpse.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.ColorInt
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.glimpse.datasources.MediaError
import org.lineageos.glimpse.ext.decodeSampledBitmap
import org.lineageos.glimpse.models.RequestStatus
import org.lineageos.glimpse.models.editor.EditorSnapshot
import org.lineageos.glimpse.models.editor.EditorTool
import org.lineageos.glimpse.models.editor.MarkupElement
import org.lineageos.glimpse.models.editor.Transform
import org.lineageos.glimpse.utils.EditorExporter
import java.util.concurrent.atomic.AtomicLong

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application

    private val idGen = AtomicLong(1L)

    private val undoStack = ArrayDeque<EditorSnapshot>()
    private val redoStack = ArrayDeque<EditorSnapshot>()

    private val _uri = MutableStateFlow<Uri?>(null)
    val uri = _uri.asStateFlow()

    private val _sourceBitmap =
        MutableStateFlow<RequestStatus<Bitmap, MediaError>>(RequestStatus.Loading())
    val sourceBitmap = _sourceBitmap.asStateFlow()

    private val _tool = MutableStateFlow(EditorTool.PEN)
    val tool = _tool.asStateFlow()

    private val _color = MutableStateFlow(0xFFFFFFFF.toInt())
    val color = _color.asStateFlow()

    private val _textSize = MutableStateFlow(MarkupElement.Text.DEFAULT_SIZE)
    val textSize = _textSize.asStateFlow()

    private val _textTypeface = MutableStateFlow<String?>(null)
    val textTypeface = _textTypeface.asStateFlow()

    private val _textRotation = MutableStateFlow(0)
    val textRotation = _textRotation.asStateFlow()

    private val _eraserWidth = MutableStateFlow(MarkupElement.Eraser.DEFAULT_WIDTH)
    val eraserWidth = _eraserWidth.asStateFlow()

    private val _snapshot = MutableStateFlow(EditorSnapshot())
    val snapshot = _snapshot.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo = _canRedo.asStateFlow()

    private val _isDirty = MutableStateFlow(false)
    val isDirty = _isDirty.asStateFlow()

    private val _saveStatus = MutableStateFlow<RequestStatus<Uri, MediaError>?>(null)
    val saveStatus = _saveStatus.asStateFlow()

    fun setUri(uri: Uri) {
        if (_uri.value == uri) {
            return
        }
        _uri.value = uri
        decodePreview(uri)
    }

    fun nextId(): Long = idGen.getAndIncrement()

    private fun decodePreview(uri: Uri) {
        viewModelScope.launch {
            _sourceBitmap.value = RequestStatus.Loading()
            val bmp = withContext(Dispatchers.IO) {
                val metrics = app.resources.displayMetrics
                val maxSide = maxOf(metrics.widthPixels, metrics.heightPixels) * 2
                    .coerceAtLeast(2048)
                runCatching { app.decodeSampledBitmap(uri, maxSide) }.getOrNull()
            }
            _sourceBitmap.value = if (bmp != null) {
                RequestStatus.Success(bmp)
            } else {
                RequestStatus.Error(MediaError.IO)
            }
        }
    }

    fun setTool(tool: EditorTool) {
        _tool.value = tool
    }

    fun setColor(@ColorInt color: Int) {
        _color.value = color
    }

    fun setTextSize(sizeFraction: Float) {
        _textSize.value = sizeFraction.coerceIn(
            MarkupElement.Text.MIN_SIZE, MarkupElement.Text.MAX_SIZE
        )
    }

    fun setTextTypeface(typeface: String?) {
        _textTypeface.value = typeface
    }

    fun setTextRotation(rotationDegrees: Int) {
        _textRotation.value = ((rotationDegrees % 360) + 360) % 360
    }

    fun setEraserWidth(widthFraction: Float) {
        _eraserWidth.value = widthFraction.coerceIn(
            MarkupElement.Eraser.MIN_WIDTH, MarkupElement.Eraser.MAX_WIDTH
        )
    }

    private fun pushHistory() {
        undoStack.addLast(_snapshot.value)
        if (undoStack.size > 32) {
            undoStack.removeFirst()
        }
        redoStack.clear()
        syncHistoryState()
    }

    private fun syncHistoryState() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
        _isDirty.value = undoStack.isNotEmpty()
    }

    fun addElement(element: MarkupElement) {
        pushHistory()
        _snapshot.value = _snapshot.value.copy(
            elements = _snapshot.value.elements + element
        )
        syncHistoryState()
    }

    fun updateText(
        id: Long,
        value: String,
        @ColorInt color: Int,
        sizeFraction: Float,
        typeface: String?,
        rotationDegrees: Int,
        anchor: android.graphics.PointF? = null,
    ) {
        val current = _snapshot.value.elements
        val idx = current.indexOfFirst { it is MarkupElement.Text && it.id == id }
        if (idx < 0) {
            return
        }
        pushHistory()
        val old = current[idx] as MarkupElement.Text
        val updated = old.copy(
            value = value,
            anchor = anchor ?: old.anchor,
            color = color,
            sizeFraction = sizeFraction,
            typeface = typeface,
            rotationDegrees = ((rotationDegrees % 360) + 360) % 360,
        )
        _snapshot.value = _snapshot.value.copy(
            elements = current.toMutableList().also { it[idx] = updated }
        )
        syncHistoryState()
    }

    fun moveText(id: Long, anchor: android.graphics.PointF) {
        val current = _snapshot.value.elements
        val idx = current.indexOfFirst { it is MarkupElement.Text && it.id == id }
        if (idx < 0) {
            return
        }
        // Move is continuous, don't push each pixel, caller should bracket with begin/end.
        // Here we just apply without history; commitMove() pushes before drag starts.
        val old = current[idx] as MarkupElement.Text
        _snapshot.value = _snapshot.value.copy(
            elements = current.toMutableList().also {
                it[idx] = old.copy(anchor = anchor)
            }
        )
    }

    fun beginMove() {
        // Snapshot before a drag so undo restores pre-drag state
        undoStack.addLast(_snapshot.value)
        if (undoStack.size > 32) {
            undoStack.removeFirst()
        }
        redoStack.clear()
        syncHistoryState()
    }

    /**
     * Drop the bracketing snapshot when a drag is abandoned mid-gesture.
     * Only valid between [beginMove] and the matching commit.
     */
    fun cancelMove() {
        undoStack.removeLastOrNull()
        syncHistoryState()
    }

    /** Shift one element without touching history, bracketed by [beginMove]. */
    fun moveElement(id: Long, dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) {
            return
        }
        val current = _snapshot.value.elements
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) {
            return
        }
        val moved = when (val el = current[idx]) {
            is MarkupElement.Text -> el.copy(
                anchor = android.graphics.PointF(el.anchor.x + dx, el.anchor.y + dy)
            )
            is MarkupElement.Stroke -> el.copy(
                points = el.points.map { android.graphics.PointF(it.x + dx, it.y + dy) }
            )
            is MarkupElement.Eraser -> el.copy(
                points = el.points.map { android.graphics.PointF(it.x + dx, it.y + dy) }
            )
        }
        _snapshot.value = _snapshot.value.copy(
            elements = current.toMutableList().also { it[idx] = moved }
        )
    }

    fun removeElements(ids: Set<Long>) {
        if (ids.isEmpty()) {
            return
        }
        pushHistory()
        _snapshot.value = _snapshot.value.copy(
            elements = _snapshot.value.elements.filterNot { it.id in ids }
        )
        syncHistoryState()
    }

    fun applyTransform(transform: Transform) {
        if (_snapshot.value.transform == transform) {
            return
        }
        pushHistory()
        _snapshot.value = _snapshot.value.copy(transform = transform)
        syncHistoryState()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_snapshot.value)
        _snapshot.value = prev
        syncHistoryState()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_snapshot.value)
        _snapshot.value = next
        syncHistoryState()
    }

    fun save(overwrite: Boolean) {
        val src = _uri.value ?: return
        val snap = _snapshot.value
        if (_saveStatus.value is RequestStatus.Loading<*, *>) {
            return
        }
        viewModelScope.launch {
            _saveStatus.value = RequestStatus.Loading()
            val result = withContext(Dispatchers.IO) {
                EditorExporter.export(app, src, snap, overwrite)
            }
            _saveStatus.value = result
        }
    }

    fun clearSaveStatus() {
        _saveStatus.value = null
    }
}
