//
// SPDX-FileCopyrightText: 2026 The VoltageOS Project
// SPDX-License-Identifier: Apache-2.0
//

package org.lineageos.glimpse.ui.editor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.util.LruCache
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import org.lineageos.glimpse.ext.drawMarkup
import org.lineageos.glimpse.ext.shortAnimTime
import org.lineageos.glimpse.ext.typefaceFor
import org.lineageos.glimpse.models.editor.EditorTool
import org.lineageos.glimpse.models.editor.MarkupElement
import org.lineageos.glimpse.models.editor.Transform
import kotlin.math.hypot
import kotlin.math.roundToInt

class MarkupView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var bitmap: Bitmap? = null
        set(value) {
            field = value
            zoom = MIN_ZOOM
            panX = 0f
            panY = 0f
            rebuildDisplayBitmap()
            updateImageRect(null, false)
            requestLayout()
            invalidate()
        }

    var transform: Transform = Transform()
        set(value) {
            if (field == value) {
                return
            }
            val oldRect = RectF(imageRect)
            field = value
            pathCache.evictAll()
            rebuildDisplayBitmap()
            updateImageRect(oldRect, true)
            invalidate()
        }

    var elements: List<MarkupElement> = listOf()
        set(value) {
            field = value
            invalidate()
        }

    /** Uncommitted text currently being typed, drawn on top of [elements]. */
    var previewText: MarkupElement.Text? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Element hidden while its replacement [previewText] is shown. */
    var hiddenId: Long? = null
        set(value) {
            field = value
            invalidate()
        }

    var tool: EditorTool = EditorTool.PEN
    var color: Int = 0xFFFFFFFF.toInt()

    var onElementAdded: ((MarkupElement) -> Unit)? = null
    var onTextRequested: ((PointF) -> Unit)? = null
    var onTextEditRequested: ((MarkupElement.Text) -> Unit)? = null
    var onTextMoved: ((Long, PointF) -> Unit)? = null
    var onMoveStarted: (() -> Unit)? = null
    var onMoveCancelled: (() -> Unit)? = null
    var onElementsMoved: ((Long, Float, Float) -> Unit)? = null

    var nextId: () -> Long = { System.currentTimeMillis() }

    val imageRect = RectF()

    private var displayBitmap: Bitmap? = null

    private val pathCache = LruCache<Long, Path>(64)

    private val livePoints = ArrayList<PointF>()
    private var liveHighlighter = false
    private var liveEraser = false
    private var liveWidthFraction = PEN_WIDTH
    private var liveColor = 0xFFFFFFFF.toInt()
    private var liveId = 0L
    private var drawingLive = false

    var eraserWidth: Float = MarkupElement.Eraser.DEFAULT_WIDTH

    private var dragTextId: Long? = null
    private var dragOffset = PointF()
    private var dragStart = PointF()
    private var dragStartNorm = PointF()
    private var dragMoved = false
    private var dragId: Long? = null
    private var dragDeltaNorm = PointF()
    private var downTime = 0L

    private val baseRect = RectF()
    private val viewportTarget = RectF()
    private var zoom = MIN_ZOOM
    private var panX = 0f
    private var panY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var zoomTracking = false
    private lateinit var scaleDetector: ScaleGestureDetector

    private val density
        get() = resources.displayMetrics.density

    private val penPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val eraserPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val measurePaint = Paint().apply {
        isAntiAlias = true
    }

    private var hitBitmap: Bitmap? = null
    private var hitCanvas: Canvas? = null
    private var hitW = 0
    private var hitH = 0

    private var rectAnimator: ValueAnimator? = null

    private val scaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            cancelLive()
            rectAnimator?.cancel()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newZoom = (zoom * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            val k = newZoom / zoom.coerceAtLeast(0.001f)
            zoom = newZoom
            val fx = detector.focusX
            val fy = detector.focusY
            imageRect.set(
                fx - (fx - imageRect.left) * k,
                fy - (fy - imageRect.top) * k,
                fx + (imageRect.right - fx) * k,
                fy + (imageRect.bottom - fy) * k,
            )
            if (zoom <= MIN_ZOOM) {
                imageRect.set(baseRect)
                panX = 0f
                panY = 0f
            } else {
                clampViewport(imageRect)
                panX = imageRect.centerX() - baseRect.centerX()
                panY = imageRect.centerY() - baseRect.centerY()
            }
            invalidate()
            return true
        }
    }

    init {
        scaleDetector = ScaleGestureDetector(context, scaleListener).apply {
            isQuickScaleEnabled = false
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateImageRect(null, false)
    }

    private fun rebuildDisplayBitmap() {
        displayBitmap?.recycle()
        displayBitmap = null
        val src = bitmap ?: return
        if (src.isRecycled) {
            return
        }
        try {
            val rotated = transform.rotationDegrees % 180 != 0
            val interW = if (rotated) src.height else src.width
            val interH = if (rotated) src.width else src.height

            val m = Matrix()
            m.postRotate(
                transform.rotationDegrees.toFloat(),
                src.width / 2f, src.height / 2f,
            )
            if (transform.flipped) {
                m.postScale(-1f, 1f, interW / 2f, interH / 2f)
            }
            val bounds = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
            val mapped = RectF()
            m.mapRect(mapped, bounds)
            m.postTranslate(-mapped.left, -mapped.top)

            val full = Bitmap.createBitmap(
                mapped.width().toInt().coerceAtLeast(1),
                mapped.height().toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            val c = Canvas(full)
            c.drawBitmap(src, m, null)

            val crop = transform.cropRect
            val cx = (crop.left * full.width).toInt().coerceIn(0, full.width - 1)
            val cy = (crop.top * full.height).toInt().coerceIn(0, full.height - 1)
            val cw = (crop.width() * full.width).toInt().coerceIn(1, full.width - cx)
            val ch = (crop.height() * full.height).toInt().coerceIn(1, full.height - cy)
            displayBitmap = Bitmap.createBitmap(full, cx, cy, cw, ch)
            if (displayBitmap != full) {
                full.recycle()
            }
        } catch (_: OutOfMemoryError) {
            displayBitmap = null
        } catch (_: Exception) {
            displayBitmap = null
        }
    }

    private fun updateImageRect(from: RectF?, animate: Boolean) {
        val disp = displayBitmap
        if (disp == null || width == 0 || height == 0) {
            return
        }
        val vw = width.toFloat()
        val vh = height.toFloat()
        val aspect = disp.width.toFloat() / disp.height.toFloat()
        var tw = vw
        var th = vw / aspect
        if (th > vh) {
            th = vh
            tw = vh * aspect
        }
        // Leave a little breathing room so chrome never covers edges
        tw *= 0.92f
        th *= 0.92f
        baseRect.set((vw - tw) / 2f, (vh - th) / 2f, (vw + tw) / 2f, (vh + th) / 2f)

        applyViewport(if (animate) from else null)
    }

    private fun applyViewport(animateFrom: RectF?) {
        val cx = baseRect.centerX()
        val cy = baseRect.centerY()
        viewportTarget.set(
            cx + (baseRect.left - cx) * zoom + panX,
            cy + (baseRect.top - cy) * zoom + panY,
            cx + (baseRect.right - cx) * zoom + panX,
            cy + (baseRect.bottom - cy) * zoom + panY,
        )
        clampViewport(viewportTarget)

        rectAnimator?.cancel()
        if (animateFrom == null || animateFrom.isEmpty) {
            imageRect.set(viewportTarget)
            invalidate()
            return
        }
        val start = RectF(animateFrom)
        val end = RectF(viewportTarget)
        rectAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = shortAnimTime.toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                imageRect.set(
                    start.left + (end.left - start.left) * f,
                    start.top + (end.top - start.top) * f,
                    start.right + (end.right - start.right) * f,
                    start.bottom + (end.bottom - start.bottom) * f,
                )
                invalidate()
            }
            start()
        }
    }

    private fun clampViewport(r: RectF) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw == 0f || vh == 0f) {
            return
        }
        if (r.width() <= vw) {
            r.offset((vw - r.width()) / 2f - r.left, 0f)
        } else {
            if (r.left > 0f) {
                r.offset(-r.left, 0f)
            }
            if (r.right < vw) {
                r.offset(vw - r.right, 0f)
            }
        }
        if (r.height() <= vh) {
            r.offset(0f, (vh - r.height()) / 2f - r.top)
        } else {
            if (r.top > 0f) {
                r.offset(0f, -r.top)
            }
            if (r.bottom < vh) {
                r.offset(0f, vh - r.bottom)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val disp = displayBitmap
        if (disp != null && !disp.isRecycled && !imageRect.isEmpty) {
            canvas.drawBitmap(disp, null, imageRect, null)
        }
        val src = bitmap
        if (src != null && !imageRect.isEmpty) {
            val hidden = hiddenId
            val visible = when (hidden) {
                null -> elements
                else -> elements.filterNot { it.id == hidden }
            }
            val dragged = when (val id = dragId) {
                null -> visible
                else -> visible.map { offsetForDisplay(it, id) }
            }
            val preview = previewText
            canvas.drawMarkup(
                when (preview) {
                    null -> dragged
                    else -> dragged + preview
                },
                src.width, src.height, imageRect, transform,
            )
        }
        if (drawingLive && livePoints.size >= 2) {
            val paint = if (liveEraser) eraserPaint else penPaint
            val base = minOf(imageRect.width(), imageRect.height())
            val mult = if (liveHighlighter) 3.2f else 1f
            paint.strokeWidth = (liveWidthFraction * base * mult).coerceAtLeast(2f)
            if (!liveEraser) {
                paint.color = liveColor
                paint.alpha = if (liveHighlighter) 0x61 else 255
            }
            val path = Path()
            val p0 = normToView(livePoints[0])
            path.moveTo(p0.x, p0.y)
            if (livePoints.size == 2) {
                val p1 = normToView(livePoints[1])
                path.lineTo(p1.x, p1.y)
            } else {
                for (i in 1 until livePoints.size - 1) {
                    val a = normToView(livePoints[i])
                    val b = normToView(livePoints[i + 1])
                    path.quadTo(a.x, a.y, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
                }
                val last = normToView(livePoints.last())
                path.lineTo(last.x, last.y)
            }
            canvas.drawPath(path, paint)
        } else if (drawingLive && livePoints.size == 1) {
            val p = normToView(livePoints[0])
            if (liveEraser) {
                eraserPaint.strokeWidth = (liveWidthFraction *
                        minOf(imageRect.width(), imageRect.height())).coerceAtLeast(8f)
                canvas.drawPoint(p.x, p.y, eraserPaint)
            } else {
                penPaint.color = liveColor
                penPaint.strokeWidth = (liveWidthFraction *
                        minOf(imageRect.width(), imageRect.height())).coerceAtLeast(8f)
                canvas.drawPoint(p.x, p.y, penPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null || imageRect.isEmpty) {
            return false
        }
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount > 1 || scaleDetector.isInProgress) {
            handleZoomGesture(event)
            return true
        }
        parent?.requestDisallowInterceptTouchEvent(true)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val norm = viewToNorm(event.x, event.y) ?: return false
                downTime = event.eventTime
                when (tool) {
                    EditorTool.PEN, EditorTool.HIGHLIGHTER -> {
                        drawingLive = true
                        livePoints.clear()
                        livePoints.add(norm)
                        liveHighlighter = tool == EditorTool.HIGHLIGHTER
                        liveEraser = false
                        liveColor = color
                        liveWidthFraction = if (liveHighlighter) {
                            HIGHLIGHT_WIDTH
                        } else {
                            PEN_WIDTH
                        }
                        liveId = nextId()
                        invalidate()
                        return true
                    }
                    EditorTool.ERASER -> {
                        drawingLive = true
                        livePoints.clear()
                        livePoints.add(norm)
                        liveHighlighter = false
                        liveEraser = true
                        liveWidthFraction = eraserWidth
                        liveId = nextId()
                        invalidate()
                        return true
                    }
                    EditorTool.TEXT -> {
                        val hit = when {
                            visibleInkAt(event.x, event.y) -> findTextAt(event.x, event.y)
                            else -> null
                        }
                        if (hit != null) {
                            dragTextId = hit.id
                            dragId = hit.id
                            dragStartNorm.set(norm.x, norm.y)
                            dragDeltaNorm.set(0f, 0f)
                            val anchorView = normToView(hit.anchor)
                            dragOffset.set(event.x - anchorView.x, event.y - anchorView.y)
                            dragStart.set(event.x, event.y)
                            dragMoved = false
                            onMoveStarted?.invoke()
                        }
                        return true
                    }
                    EditorTool.MOVE -> {
                        val hit = findElementAt(event.x, event.y)
                        if (hit != null) {
                            dragId = hit.id
                            dragStartNorm.set(norm.x, norm.y)
                            dragDeltaNorm.set(0f, 0f)
                            dragStart.set(event.x, event.y)
                            dragMoved = false
                            onMoveStarted?.invoke()
                        }
                        return true
                    }
                    EditorTool.CROP -> return false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when {
                    drawingLive -> {
                        val lastView = normToView(livePoints.last())
                        if (hypot(event.x - lastView.x, event.y - lastView.y) < 2f * density) {
                            return true
                        }
                        val norm = viewToNorm(event.x, event.y) ?: return true
                        livePoints.add(norm)
                        val p = normToView(norm)
                        val r = (liveWidthFraction *
                                minOf(imageRect.width(), imageRect.height())) + 24f
                        invalidate(
                            (p.x - r).toInt(), (p.y - r).toInt(),
                            (p.x + r).toInt(), (p.y + r).toInt(),
                        )
                        return true
                    }
                    dragId != null -> {
                        if (hypot(event.x - dragStart.x, event.y - dragStart.y) > 8f * density) {
                            dragMoved = true
                        }
                        viewToNorm(event.x, event.y)?.let { cur ->
                            dragDeltaNorm.set(cur.x - dragStartNorm.x, cur.y - dragStartNorm.y)
                        }
                        invalidate()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                when {
                    drawingLive -> {
                        drawingLive = false
                        val wasEraser = liveEraser
                        liveEraser = false
                        if (!cancelled && livePoints.size >= 1) {
                            val el = when {
                                wasEraser -> MarkupElement.Eraser(
                                    id = liveId,
                                    points = ArrayList(livePoints),
                                    widthFraction = liveWidthFraction,
                                )
                                else -> MarkupElement.Stroke(
                                    id = liveId,
                                    color = liveColor,
                                    points = ArrayList(livePoints),
                                    widthFraction = liveWidthFraction,
                                    highlighter = liveHighlighter,
                                )
                            }
                            onElementAdded?.invoke(el)
                        }
                        livePoints.clear()
                        invalidate()
                        return true
                    }
                    dragTextId != null -> {
                        val id = dragTextId!!
                        dragTextId = null
                        dragId = null
                        dragDeltaNorm.set(0f, 0f)
                        if (cancelled) {
                            onMoveCancelled?.invoke()
                            invalidate()
                            return true
                        }
                        if (dragMoved) {
                            val norm = viewToNorm(event.x - dragOffset.x, event.y - dragOffset.y)
                            if (norm != null) {
                                onTextMoved?.invoke(id, norm)
                            }
                        } else {
                            // Tap, open for edit. Long-press already handled as drag.
                            elements.filterIsInstance<MarkupElement.Text>()
                                .firstOrNull { it.id == id }?.let {
                                    onTextEditRequested?.invoke(it)
                                }
                        }
                        invalidate()
                        return true
                    }
                    tool == EditorTool.MOVE && !cancelled && dragId != null -> {
                        val id = dragId!!
                        val dx = dragDeltaNorm.x
                        val dy = dragDeltaNorm.y
                        val moved = dragMoved
                        dragId = null
                        dragDeltaNorm.set(0f, 0f)
                        if (moved && (dx != 0f || dy != 0f)) {
                            onElementsMoved?.invoke(id, dx, dy)
                        } else {
                            onMoveCancelled?.invoke()
                        }
                        invalidate()
                        return true
                    }
                    tool == EditorTool.TEXT && !cancelled -> {
                        // Fresh decision at UP time so taps straddling ink edges still land
                        if (event.eventTime - downTime < 500) {
                            val norm = viewToNorm(event.x, event.y)
                            if (norm != null) {
                                val existing = when {
                                    visibleInkAt(event.x, event.y) ->
                                        findTextAt(event.x, event.y)
                                    else -> null
                                }
                                if (existing != null) {
                                    onTextEditRequested?.invoke(existing)
                                } else {
                                    onTextRequested?.invoke(norm)
                                }
                            }
                        }
                        return true
                    }
                }
                if (cancelled) {
                    cancelLive()
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun offsetForDisplay(el: MarkupElement, id: Long): MarkupElement {
        if (el.id != id) {
            return el
        }
        val dx = dragDeltaNorm.x
        val dy = dragDeltaNorm.y
        if (dx == 0f && dy == 0f) {
            return el
        }
        return when (el) {
            is MarkupElement.Text -> el.copy(
                anchor = PointF(el.anchor.x + dx, el.anchor.y + dy)
            )
            is MarkupElement.Stroke -> el.copy(
                points = el.points.map { PointF(it.x + dx, it.y + dy) }
            )
            is MarkupElement.Eraser -> el.copy(
                points = el.points.map { PointF(it.x + dx, it.y + dy) }
            )
        }
    }

    /** Drop any in-progress stroke or drag, rolling back its history bracket. */
    fun cancelGesture() {
        cancelLive()
    }

    private fun cancelLive() {
        if (drawingLive) {
            drawingLive = false
            liveEraser = false
            livePoints.clear()
        }
        if (dragId != null || dragTextId != null) {
            dragId = null
            dragTextId = null
            dragDeltaNorm.set(0f, 0f)
            onMoveCancelled?.invoke()
        }
        zoomTracking = false
        invalidate()
    }

    private fun handleZoomGesture(event: MotionEvent) {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    cancelLive()
                    rectAnimator?.cancel()
                    val c = centroid(event)
                    lastFocusX = c.x
                    lastFocusY = c.y
                    zoomTracking = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val c = centroid(event)
                    if (!zoomTracking) {
                        cancelLive()
                        rectAnimator?.cancel()
                        lastFocusX = c.x
                        lastFocusY = c.y
                        zoomTracking = true
                    } else {
                        panX += c.x - lastFocusX
                        panY += c.y - lastFocusY
                        lastFocusX = c.x
                        lastFocusY = c.y
                        applyViewport(null)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                zoomTracking = false
            }
        }
    }

    private fun centroid(event: MotionEvent): PointF {
        val n = minOf(event.pointerCount, 2).coerceAtLeast(1)
        var x = 0f
        var y = 0f
        for (i in 0 until n) {
            x += event.getX(i)
            y += event.getY(i)
        }
        return PointF(x / n, y / n)
    }

    private fun findElementAt(x: Float, y: Float): MarkupElement? {
        for (i in elements.indices.reversed()) {
            val el = elements[i]
            when (el) {
                is MarkupElement.Text -> {
                    if (findTextAt(x, y)?.id == el.id) {
                        return el
                    }
                }
                is MarkupElement.Stroke -> {
                    if (strokeHit(
                            x, y, el.points, el.widthFraction,
                            if (el.highlighter) 3.2f else 1f,
                        )
                    ) {
                        return el
                    }
                }
                is MarkupElement.Eraser -> {
                    if (strokeHit(x, y, el.points, el.widthFraction)) {
                        return el
                    }
                }
            }
        }
        return null
    }

    private fun strokeHit(
        x: Float,
        y: Float,
        points: List<PointF>,
        widthFraction: Float,
        mult: Float = 1f,
    ): Boolean {
        if (points.isEmpty()) {
            return false
        }
        val tol = widthFraction * minOf(imageRect.width(), imageRect.height()) * mult / 2f +
                12f * density
        var prev: PointF? = null
        for (n in points) {
            val v = normToView(n)
            if (prev != null) {
                if (distToSegment(x, y, prev.x, prev.y, v.x, v.y) <= tol) {
                    return true
                }
            } else if (hypot(x - v.x, y - v.y) <= tol) {
                return true
            }
            prev = v
        }
        return false
    }

    private fun distToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        if (dx == 0f && dy == 0f) {
            return hypot(px - x1, py - y1)
        }
        val t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)
        val c = t.coerceIn(0f, 1f)
        return hypot(px - (x1 + c * dx), py - (y1 + c * dy))
    }

    private fun findTextAt(x: Float, y: Float): MarkupElement.Text? {        // Topmost first
        for (i in elements.indices.reversed()) {
            val el = elements[i]
            if (el !is MarkupElement.Text) {
                continue
            }
            val a = normToView(el.anchor)
            val sizePx = (el.sizeFraction * minOf(imageRect.width(), imageRect.height()))
                .coerceAtLeast(14f * density)
            // Box around the real glyphs: anchor is top-left, unrotate the touch
            measurePaint.textSize = sizePx
            measurePaint.typeface = typefaceFor(el.typeface)
            val lines = el.value.lines()
            val w = lines.maxOfOrNull { measurePaint.measureText(it) } ?: 0f
            val fm = measurePaint.fontMetrics
            val h = lines.size * (fm.descent - fm.ascent)
            // First baseline sits one size below the anchor, glyphs rise above it
            val top = sizePx + fm.ascent
            val pad = 16f * density
            val dx = x - a.x
            val dy = y - a.y
            val (lx, ly) = when (((el.rotationDegrees % 360) + 360) % 360) {
                90 -> dy to -dx
                180 -> -dx to -dy
                270 -> -dy to dx
                else -> dx to dy
            }
            if (lx >= -pad && lx <= w + pad && ly >= top - pad && ly <= top + h + pad) {
                return el
            }
        }
        return null
    }

    /**
     * Whether rendered markup ink survives at this view point. Replays the
     * same renderer as onDraw into a tiny mask, so eraser punches read as
     * empty and taps can never collide with invisible model objects.
     */
    private fun visibleInkAt(x: Float, y: Float): Boolean {
        val src = bitmap ?: return false
        if (elements.isEmpty() || imageRect.isEmpty) {
            return false
        }
        val w = 160
        val h = (w * imageRect.height() / imageRect.width()).roundToInt().coerceAtLeast(1)
        var bmp = hitBitmap
        if (bmp == null || hitW != w || hitH != h) {
            bmp?.recycle()
            bmp = try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            } catch (_: OutOfMemoryError) {
                return false
            }
            hitBitmap = bmp
            hitCanvas = Canvas(bmp)
            hitW = w
            hitH = h
        }
        val canvas = hitCanvas ?: return false
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawMarkup(
            elements, src.width, src.height,
            RectF(0f, 0f, w.toFloat(), h.toFloat()), transform,
        )
        val px = ((x - imageRect.left) / imageRect.width() * w).toInt().coerceIn(0, w - 1)
        val py = ((y - imageRect.top) / imageRect.height() * h).toInt().coerceIn(0, h - 1)
        return Color.alpha(bmp.getPixel(px, py)) > 8
    }

    override fun onDetachedFromWindow() {
        hitBitmap?.recycle()
        hitBitmap = null
        hitCanvas = null
        super.onDetachedFromWindow()
    }

    fun viewToNorm(x: Float, y: Float): PointF? {
        val src = bitmap ?: return null
        if (x < imageRect.left || x > imageRect.right ||
            y < imageRect.top || y > imageRect.bottom
        ) {
            return null
        }
        val srcW = src.width
        val srcH = src.height
        val rotated = transform.rotationDegrees % 180 != 0
        val interW = if (rotated) srcH else srcW
        val interH = if (rotated) srcW else srcH

        val rx = ((x - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
        val ry = ((y - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
        val crop = transform.cropRect
        var ix = crop.left * interW + rx * crop.width() * interW
        val iy = crop.top * interH + ry * crop.height() * interH
        if (transform.flipped) {
            ix = interW - ix
        }
        val (nx, ny) = when (((transform.rotationDegrees % 360) + 360) % 360) {
            90 -> (iy / srcW.toFloat()) to ((srcH - ix) / srcH.toFloat())
            180 -> ((srcW - ix) / srcW.toFloat()) to ((srcH - iy) / srcH.toFloat())
            270 -> ((srcW - iy) / srcW.toFloat()) to (ix / srcH.toFloat())
            else -> (ix / srcW.toFloat()) to (iy / srcH.toFloat())
        }
        return PointF(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
    }

    /** Map a normalized image point into view coordinates. */
    fun normToView(n: PointF): PointF {
        val src = bitmap
        if (src == null) {
            return PointF()
        }
        val srcW = src.width
        val srcH = src.height
        val rotated = transform.rotationDegrees % 180 != 0
        val interW = if (rotated) srcH else srcW
        val interH = if (rotated) srcW else srcH

        val px = n.x * srcW
        val py = n.y * srcH
        var ix: Float
        var iy: Float
        when (((transform.rotationDegrees % 360) + 360) % 360) {
            90 -> {
                ix = interW - py
                iy = px
            }
            180 -> {
                ix = srcW - px
                iy = srcH - py
            }
            270 -> {
                ix = py
                iy = interH - px
            }
            else -> {
                ix = px
                iy = py
            }
        }
        if (transform.flipped) {
            ix = interW - ix
        }
        val crop = transform.cropRect
        val rx = (ix - crop.left * interW) / (crop.width() * interW)
        val ry = (iy - crop.top * interH) / (crop.height() * interH)
        return PointF(
            imageRect.left + rx * imageRect.width(),
            imageRect.top + ry * imageRect.height(),
        )
    }

    companion object {
        const val PEN_WIDTH = 0.008f
        const val HIGHLIGHT_WIDTH = 0.012f
        const val ERASER_WIDTH = MarkupElement.Eraser.DEFAULT_WIDTH
        const val TEXT_SIZE = MarkupElement.Text.DEFAULT_SIZE

        private const val MIN_ZOOM = 1f
        private const val MAX_ZOOM = 5f
    }
}
