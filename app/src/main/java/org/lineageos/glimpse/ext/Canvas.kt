//
// SPDX-FileCopyrightText: 2026 The VoltageOS Project
// SPDX-License-Identifier: Apache-2.0
//

package org.lineageos.glimpse.ext

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import org.lineageos.glimpse.models.editor.MarkupElement
import org.lineageos.glimpse.models.editor.Transform

private const val HIGHLIGHTER_ALPHA = 0x61

fun Canvas.drawMarkup(
    elements: List<MarkupElement>,
    srcWidth: Int,
    srcHeight: Int,
    dst: RectF,
    transform: Transform,
) {
    if (elements.isEmpty()) {
        return
    }
    if (srcWidth <= 0 || srcHeight <= 0 || dst.isEmpty) {
        return
    }

    val rotated = transform.rotationDegrees % 180 != 0
    val interW = if (rotated) srcHeight else srcWidth
    val interH = if (rotated) srcWidth else srcHeight

    val crop = transform.cropRect
    val cropW = (crop.width() * interW).coerceAtLeast(1f)
    val cropH = (crop.height() * interH).coerceAtLeast(1f)

    val scaleX = dst.width() / cropW
    val scaleY = dst.height() / cropH

    fun mapPoint(n: PointF, out: PointF): PointF {
        val px = n.x * srcWidth
        val py = n.y * srcHeight

        val (ix, iy) = when (((transform.rotationDegrees % 360) + 360) % 360) {
            90 -> (interW - py * interW / srcHeight.coerceAtLeast(1)) to (px * interH / srcWidth.coerceAtLeast(1))
            180 -> (srcWidth - px) * interW / srcWidth.coerceAtLeast(1) to (srcHeight - py) * interH / srcHeight.coerceAtLeast(1)
            270 -> (py * interW / srcHeight.coerceAtLeast(1)) to (interH - px * interH / srcWidth.coerceAtLeast(1))
            else -> px to py
        }

        var fx = ix
        if (transform.flipped) {
            fx = interW - ix
        }

        out.x = dst.left + (fx - crop.left * interW) * scaleX
        out.y = dst.top + (iy - crop.top * interH) * scaleY
        return out
    }

    // Simpler per-point path that avoids allocating too much
    val tmp = PointF()

    // Everything below draws into an isolated layer so eraser punches
    // only eat markup, never the photo underneath.
    val outer = saveLayer(dst, null)

    fun strokeWidthFor(e: MarkupElement.Stroke): Float {
        val base = minOf(dst.width(), dst.height())
        val mult = if (e.highlighter) 3.2f else 1f
        return (e.widthFraction * base * mult).coerceAtLeast(2f)
    }

    val penPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    val highPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.ROUND
        alpha = HIGHLIGHTER_ALPHA
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
    }

    val textPaint = Paint().apply {
        isAntiAlias = true
    }
    val shadowPx = minOf(dst.width(), dst.height()) * 0.004f + 2f
    textPaint.setShadowLayer(shadowPx, 0f, 0f, 0x66000000)

    // Creation order: an eraser only ever punches what was drawn before it,
    // so new ink over old holes is unaffected.
    val ordered = elements.sortedBy { it.id }

    // Highlighters keep their grouped pass; the batch is flushed whenever
    // anything else needs the layer, preserving the old look exactly.
    val highBatch = ArrayList<MarkupElement.Stroke>()
    fun flushHigh() {
        if (highBatch.isEmpty()) {
            return
        }
        val layer = saveLayer(dst, null)
        for (stroke in highBatch) {
            if (stroke.points.size < 2) {
                if (stroke.points.isNotEmpty()) {
                    mapPoint(stroke.points[0], tmp)
                    highPaint.color = stroke.color
                    highPaint.strokeWidth = strokeWidthFor(stroke)
                    drawPoint(tmp.x, tmp.y, highPaint)
                }
                continue
            }
            val path = buildSmoothPath(stroke.points, ::mapPoint)
            highPaint.color = stroke.color
            highPaint.strokeWidth = strokeWidthFor(stroke)
            drawPath(path, highPaint)
        }
        restoreToCount(layer)
        highBatch.clear()
    }

    fun drawPen(stroke: MarkupElement.Stroke) {
        if (stroke.points.size < 2) {
            if (stroke.points.size == 1) {
                mapPoint(stroke.points[0], tmp)
                penPaint.color = stroke.color
                penPaint.strokeWidth = strokeWidthFor(stroke)
                drawPoint(tmp.x, tmp.y, penPaint)
            }
            return
        }
        val path = buildSmoothPath(stroke.points, ::mapPoint)
        penPaint.color = stroke.color
        penPaint.strokeWidth = strokeWidthFor(stroke)
        drawPath(path, penPaint)
    }

    fun drawText(t: MarkupElement.Text) {
        mapPoint(t.anchor, tmp)
        val sizePx = (t.sizeFraction * minOf(dst.width(), dst.height())).coerceAtLeast(12f)
        textPaint.typeface = typefaceFor(t.typeface)
        textPaint.color = t.color
        textPaint.textSize = sizePx
        save()
        if (t.rotationDegrees % 360 != 0) {
            rotate(t.rotationDegrees.toFloat(), tmp.x, tmp.y)
        }
        var dy = 0f
        for (line in t.value.split("\n")) {
            drawText(line, tmp.x, tmp.y + dy + sizePx, textPaint)
            dy += sizePx * 1.2f
        }
        restore()
    }

    val clearPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    val eraserBase = minOf(dst.width(), dst.height())
    fun punch(e: MarkupElement.Eraser) {
        if (e.points.size < 2) {
            e.points.firstOrNull()?.let {
                mapPoint(it, tmp)
                clearPaint.strokeWidth = (e.widthFraction * eraserBase).coerceAtLeast(2f)
                drawPoint(tmp.x, tmp.y, clearPaint)
            }
            return
        }
        clearPaint.strokeWidth = (e.widthFraction * eraserBase).coerceAtLeast(2f)
        drawPath(buildSmoothPath(e.points, ::mapPoint), clearPaint)
    }

    for (el in ordered) {
        when (el) {
            is MarkupElement.Stroke -> {
                if (el.highlighter) {
                    highBatch.add(el)
                } else {
                    flushHigh()
                    drawPen(el)
                }
            }
            is MarkupElement.Text -> {
                flushHigh()
                drawText(el)
            }
            is MarkupElement.Eraser -> {
                flushHigh()
                punch(el)
            }
        }
    }
    flushHigh()

    restoreToCount(outer)
}

private fun buildSmoothPath(
    src: List<PointF>,
    map: (PointF, PointF) -> PointF,
): Path {
    val path = Path()
    val tmp = PointF()
    val first = PointF()
    map(src[0], first)
    path.moveTo(first.x, first.y)

    if (src.size == 2) {
        map(src[1], tmp)
        path.lineTo(tmp.x, tmp.y)
        return path
    }

    val mid = PointF()
    for (i in 1 until src.size - 1) {
        map(src[i], tmp)
        val next = PointF()
        map(src[i + 1], next)
        mid.x = (tmp.x + next.x) / 2f
        mid.y = (tmp.y + next.y) / 2f
        path.quadTo(tmp.x, tmp.y, mid.x, mid.y)
    }
    val last = PointF()
    map(src.last(), last)
    path.lineTo(last.x, last.y)
    return path
}
