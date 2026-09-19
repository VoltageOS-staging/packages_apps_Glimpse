//
// SPDX-FileCopyrightText: 2026 The VoltageOS Project
// SPDX-License-Identifier: Apache-2.0
//

package org.lineageos.glimpse.models.editor

import android.graphics.RectF

data class Transform(
    val cropRect: RectF = RectF(0f, 0f, 1f, 1f),
    val rotationDegrees: Int = 0,
    val flipped: Boolean = false,
) {
    fun isIdentity(): Boolean {
        return rotationDegrees % 360 == 0 && !flipped &&
                cropRect.left <= 0f && cropRect.top <= 0f &&
                cropRect.right >= 1f && cropRect.bottom >= 1f
    }
}
