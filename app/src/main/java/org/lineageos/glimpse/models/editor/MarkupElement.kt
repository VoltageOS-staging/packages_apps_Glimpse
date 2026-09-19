//
// SPDX-FileCopyrightText: 2026 The VoltageOS Project
// SPDX-License-Identifier: Apache-2.0
//

package org.lineageos.glimpse.models.editor

import android.graphics.PointF

sealed interface MarkupElement {
    val id: Long
    val color: Int

    data class Stroke(
        override val id: Long,
        override val color: Int,
        val points: List<PointF>,
        val widthFraction: Float,
        val highlighter: Boolean,
    ) : MarkupElement

    /**
     * Raster punch: clears whatever markup the path covers, including
     * parts of text. Drawn with CLEAR, so only pixels under the path go.
     */
    data class Eraser(
        override val id: Long,
        val points: List<PointF>,
        val widthFraction: Float,
        override val color: Int = 0,
    ) : MarkupElement {
        companion object {
            const val DEFAULT_WIDTH = 0.03f
            const val MIN_WIDTH = 0.01f
            const val MAX_WIDTH = 0.08f
            const val WIDTH_STEP = 0.01f
        }
    }

    data class Text(
        override val id: Long,
        override val color: Int,
        val value: String,
        val anchor: PointF,
        val sizeFraction: Float,
        val typeface: String? = null,
        val rotationDegrees: Int = 0,
    ) : MarkupElement {
        companion object {
            const val DEFAULT_SIZE = 0.055f
            const val MIN_SIZE = 0.02f
            const val MAX_SIZE = 0.4f
            const val SIZE_STEP = 0.02f
        }
    }
}
