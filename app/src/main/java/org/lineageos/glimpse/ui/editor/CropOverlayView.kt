//
// SPDX-FileCopyrightText: 2026 The VoltageOS Project
// SPDX-License-Identifier: Apache-2.0
//

package org.lineageos.glimpse.ui.editor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import org.lineageos.glimpse.ext.shortAnimTime
import kotlin.math.abs

class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var imageRect = RectF()
        set(value) {
            field.set(value)
            clampDraft()
            invalidate()
        }

    var cropDraft = RectF(0f, 0f, 1f, 1f)
        set(value) {
            field.set(value)
            invalidate()
        }

    var onCropChanged: ((RectF) -> Unit)? = null

    /** Second finger goes straight to canvas zoom, both views share coordinates. */
    var onZoomGesture: ((MotionEvent) -> Boolean)? = null

    private var gridAlpha = 0f
    private var gridAnim: ValueAnimator? = null

    private val scrimPaint = Paint().apply {
        color = 0x99000000.toInt()
        style = Paint.Style.FILL
    }
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        style = Paint.Style.FILL
    }
    private val framePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val gridPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        isAntiAlias = true
        alpha = 77
    }
    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        isAntiAlias = true
    }

    private var dragMode = DragMode.NONE
    private var lastX = 0f
    private var lastY = 0f

    private val density
        get() = resources.displayMetrics.density

    private enum class DragMode {
        NONE, MOVE,
        LEFT, RIGHT, TOP, BOTTOM,
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
    }

    fun reset() {
        cropDraft.set(0f, 0f, 1f, 1f)
        invalidate()
        onCropChanged?.invoke(RectF(cropDraft))
    }

    private fun cropViewRect(out: RectF = RectF()): RectF {
        if (imageRect.isEmpty) {
            return out.apply { set(0f, 0f, 0f, 0f) }
        }
        out.set(
            imageRect.left + cropDraft.left * imageRect.width(),
            imageRect.top + cropDraft.top * imageRect.height(),
            imageRect.left + cropDraft.right * imageRect.width(),
            imageRect.top + cropDraft.bottom * imageRect.height(),
        )
        return out
    }

    private fun clampDraft() {
        cropDraft.left = cropDraft.left.coerceIn(0f, 1f)
        cropDraft.top = cropDraft.top.coerceIn(0f, 1f)
        cropDraft.right = cropDraft.right.coerceIn(0f, 1f)
        cropDraft.bottom = cropDraft.bottom.coerceIn(0f, 1f)
        // Enforce min size in normalized units based on 64dp
        if (!imageRect.isEmpty) {
            val minW = (64f * density / imageRect.width()).coerceIn(0f, 1f)
            val minH = (64f * density / imageRect.height()).coerceIn(0f, 1f)
            if (cropDraft.width() < minW) {
                val cx = (cropDraft.left + cropDraft.right) / 2f
                cropDraft.left = (cx - minW / 2f).coerceAtLeast(0f)
                cropDraft.right = (cx + minW / 2f).coerceAtMost(1f)
            }
            if (cropDraft.height() < minH) {
                val cy = (cropDraft.top + cropDraft.bottom) / 2f
                cropDraft.top = (cy - minH / 2f).coerceAtLeast(0f)
                cropDraft.bottom = (cy + minH / 2f).coerceAtMost(1f)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageRect.isEmpty) {
            return
        }
        val cropRect = cropViewRect()

        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        canvas.drawRect(cropRect, clearPaint)
        canvas.restoreToCount(layer)

        framePaint.strokeWidth = 1f * density
        canvas.drawRect(cropRect, framePaint)

        if (gridAlpha > 0.01f) {
            gridPaint.alpha = (77 * gridAlpha).toInt().coerceIn(0, 255)
            gridPaint.strokeWidth = 1f
            for (i in 1..2) {
                val x = cropRect.left + cropRect.width() * i / 3f
                canvas.drawLine(x, cropRect.top, x, cropRect.bottom, gridPaint)
                val y = cropRect.top + cropRect.height() * i / 3f
                canvas.drawLine(cropRect.left, y, cropRect.right, y, gridPaint)
            }
        }

        handlePaint.strokeWidth = 3f * density
        val len = 24f * density
        // Corners as L shapes
        drawCorner(canvas, cropRect.left, cropRect.top, 1f, 1f, len)
        drawCorner(canvas, cropRect.right, cropRect.top, -1f, 1f, len)
        drawCorner(canvas, cropRect.left, cropRect.bottom, 1f, -1f, len)
        drawCorner(canvas, cropRect.right, cropRect.bottom, -1f, -1f, len)
        // Edge bars
        val midX = (cropRect.left + cropRect.right) / 2f
        val midY = (cropRect.top + cropRect.bottom) / 2f
        canvas.drawLine(midX - len / 2f, cropRect.top, midX + len / 2f, cropRect.top, handlePaint)
        canvas.drawLine(midX - len / 2f, cropRect.bottom, midX + len / 2f, cropRect.bottom, handlePaint)
        canvas.drawLine(cropRect.left, midY - len / 2f, cropRect.left, midY + len / 2f, handlePaint)
        canvas.drawLine(cropRect.right, midY - len / 2f, cropRect.right, midY + len / 2f, handlePaint)
    }

    private fun drawCorner(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float, len: Float) {
        canvas.drawLine(x, y, x + dx * len, y, handlePaint)
        canvas.drawLine(x, y, x, y + dy * len, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageRect.isEmpty) {
            return false
        }
        if (event.pointerCount > 1) {
            dragMode = DragMode.NONE
            setGridVisible(false)
            return onZoomGesture?.invoke(event) ?: false
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragMode = pickHandle(event.x, event.y)
                setGridVisible(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dxView = event.x - lastX
                val dyView = event.y - lastY
                lastX = event.x
                lastY = event.y
                if (dragMode == DragMode.NONE) {
                    return true
                }
                val dx = dxView / imageRect.width()
                val dy = dyView / imageRect.height()
                applyDrag(dx, dy, event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode != DragMode.NONE) {
                    // Snap to edge haptic
                    val r = cropViewRect()
                    if (abs(r.left - imageRect.left) < 4f * density ||
                        abs(r.top - imageRect.top) < 4f * density ||
                        abs(r.right - imageRect.right) < 4f * density ||
                        abs(r.bottom - imageRect.bottom) < 4f * density
                    ) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                dragMode = DragMode.NONE
                setGridVisible(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun pickHandle(x: Float, y: Float): DragMode {
        val r = cropViewRect()
        val slop = 24f * density
        val inX = x >= r.left - slop && x <= r.right + slop
        val inY = y >= r.top - slop && y <= r.bottom + slop
        if (!inX || !inY) {
            return DragMode.NONE
        }
        val nearLeft = abs(x - r.left) <= slop
        val nearRight = abs(x - r.right) <= slop
        val nearTop = abs(y - r.top) <= slop
        val nearBottom = abs(y - r.bottom) <= slop
        val midX = (r.left + r.right) / 2f
        val midY = (r.top + r.bottom) / 2f
        val nearMidX = abs(x - midX) <= slop
        val nearMidY = abs(y - midY) <= slop

        return when {
            nearLeft && nearTop -> DragMode.TOP_LEFT
            nearRight && nearTop -> DragMode.TOP_RIGHT
            nearLeft && nearBottom -> DragMode.BOTTOM_LEFT
            nearRight && nearBottom -> DragMode.BOTTOM_RIGHT
            nearLeft && nearMidY -> DragMode.LEFT
            nearRight && nearMidY -> DragMode.RIGHT
            nearTop && nearMidX -> DragMode.TOP
            nearBottom && nearMidX -> DragMode.BOTTOM
            r.contains(x, y) -> DragMode.MOVE
            nearLeft -> DragMode.LEFT
            nearRight -> DragMode.RIGHT
            nearTop -> DragMode.TOP
            nearBottom -> DragMode.BOTTOM
            else -> DragMode.NONE
        }
    }

    private fun applyDrag(dx: Float, dy: Float, x: Float, y: Float) {
        val minW = (64f * density / imageRect.width()).coerceIn(0f, 1f)
        val minH = (64f * density / imageRect.height()).coerceIn(0f, 1f)
        when (dragMode) {
            DragMode.MOVE -> {
                val w = cropDraft.width()
                val h = cropDraft.height()
                var nl = (cropDraft.left + dx).coerceIn(0f, 1f - w)
                var nt = (cropDraft.top + dy).coerceIn(0f, 1f - h)
                cropDraft.left = nl
                cropDraft.top = nt
                cropDraft.right = nl + w
                cropDraft.bottom = nt + h
            }
            DragMode.LEFT -> cropDraft.left = (cropDraft.left + dx)
                .coerceIn(0f, cropDraft.right - minW)
            DragMode.RIGHT -> cropDraft.right = (cropDraft.right + dx)
                .coerceIn(cropDraft.left + minW, 1f)
            DragMode.TOP -> cropDraft.top = (cropDraft.top + dy)
                .coerceIn(0f, cropDraft.bottom - minH)
            DragMode.BOTTOM -> cropDraft.bottom = (cropDraft.bottom + dy)
                .coerceIn(cropDraft.top + minH, 1f)
            DragMode.TOP_LEFT -> {
                cropDraft.left = (cropDraft.left + dx).coerceIn(0f, cropDraft.right - minW)
                cropDraft.top = (cropDraft.top + dy).coerceIn(0f, cropDraft.bottom - minH)
            }
            DragMode.TOP_RIGHT -> {
                cropDraft.right = (cropDraft.right + dx).coerceIn(cropDraft.left + minW, 1f)
                cropDraft.top = (cropDraft.top + dy).coerceIn(0f, cropDraft.bottom - minH)
            }
            DragMode.BOTTOM_LEFT -> {
                cropDraft.left = (cropDraft.left + dx).coerceIn(0f, cropDraft.right - minW)
                cropDraft.bottom = (cropDraft.bottom + dy).coerceIn(cropDraft.top + minH, 1f)
            }
            DragMode.BOTTOM_RIGHT -> {
                cropDraft.right = (cropDraft.right + dx).coerceIn(cropDraft.left + minW, 1f)
                cropDraft.bottom = (cropDraft.bottom + dy).coerceIn(cropDraft.top + minH, 1f)
            }
            DragMode.NONE -> return
        }
        // Clamp pan when dragging with finger outside
        cropDraft.left = cropDraft.left.coerceIn(0f, 1f)
        cropDraft.top = cropDraft.top.coerceIn(0f, 1f)
        cropDraft.right = cropDraft.right.coerceIn(0f, 1f)
        cropDraft.bottom = cropDraft.bottom.coerceIn(0f, 1f)
        invalidate()
        onCropChanged?.invoke(RectF(cropDraft))
    }

    private fun setGridVisible(visible: Boolean) {
        gridAnim?.cancel()
        val from = gridAlpha
        val to = if (visible) 1f else 0f
        if (from == to) {
            return
        }
        gridAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = shortAnimTime.toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                gridAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
}
