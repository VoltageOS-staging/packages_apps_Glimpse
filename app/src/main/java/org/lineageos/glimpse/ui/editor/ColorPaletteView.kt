//
// SPDX-FileCopyrightText: 2026 The VoltageOS Project
// SPDX-License-Identifier: Apache-2.0
//

package org.lineageos.glimpse.ui.editor

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import org.lineageos.glimpse.R

class ColorPaletteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {
    var onColorSelected: ((Int) -> Unit)? = null

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    private val swatches = ArrayList<View>()
    private var selected = 0

    private val colors: IntArray by lazy {
        runCatching {
            val ta = resources.obtainTypedArray(R.array.markup_colors)
            val out = IntArray(ta.length()) { ta.getColor(it, 0xFFFFFFFF.toInt()) }
            ta.recycle()
            out
        }.getOrElse {
            intArrayOf(
                0xFFFFFFFF.toInt(), 0xFF000000.toInt(),
                0xFFF44336.toInt(), 0xFFFF9800.toInt(),
                0xFFFFEB3B.toInt(), 0xFF4CAF50.toInt(),
                0xFF2196F3.toInt(), 0xFF9C27B0.toInt(),
            )
        }
    }

    init {
        isHorizontalScrollBarEnabled = false
        addView(
            row,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
    }

    fun setColorsAndSelect(current: Int) {
        if (row.childCount == 0) {
            buildRow()
        }
        val idx = colors.indexOf(current).takeIf { it >= 0 } ?: 0
        select(idx, false)
    }

    private fun buildRow() {
        val density = resources.displayMetrics.density
        val size = (32f * density).toInt()
        val margin = (8f * density).toInt()
        val ring = (2f * density).toInt()

        row.removeAllViews()
        swatches.clear()

        for ((i, c) in colors.withIndex()) {
            val v = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(margin, margin, margin, margin)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(ring, 0xFFFFFFFF.toInt())
                }
                // White shows poorly on light scrim without a dark outline,
                // black gets a subtle white halo from the ring above.
                setOnClickListener {
                    select(i, true)
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onColorSelected?.invoke(colors[i])
                }
            }
            row.addView(v)
            swatches.add(v)
        }
    }

    private fun select(index: Int, animate: Boolean) {
        if (index !in swatches.indices) {
            return
        }
        val prev = selected
        selected = index
        for ((i, v) in swatches.withIndex()) {
            val target = if (i == index) 1.15f else 1f
            if (animate) {
                v.animate().cancel()
                v.animate()
                    .scaleX(target)
                    .scaleY(target)
                    .setDuration(120)
                    .setInterpolator(OvershootInterpolator())
                    .start()
            } else {
                v.scaleX = target
                v.scaleY = target
            }
            v.alpha = if (i == index || !animate && prev == i) 1f else 0.85f
        }
    }

    fun setSelectedColor(color: Int) {
        val idx = colors.indexOf(color)
        if (idx >= 0) {
            select(idx, true)
        }
    }
}
