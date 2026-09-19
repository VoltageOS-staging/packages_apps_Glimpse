//
// SPDX-FileCopyrightText: 2026 The VoltageOS Project
// SPDX-License-Identifier: Apache-2.0
//

package org.lineageos.glimpse.ext

import android.graphics.Typeface
import android.util.LruCache

private val cache = LruCache<String, Typeface>(16)

/**
 * Resolve a shipped system font family to a bold [Typeface].
 * Unknown families fall back to the default, so this never throws.
 */
fun typefaceFor(family: String?): Typeface {
    if (family.isNullOrBlank()) {
        return Typeface.DEFAULT_BOLD
    }

    cache[family]?.let {
        return it
    }

    val resolved = runCatching {
        Typeface.create(family, Typeface.BOLD)
    }.getOrDefault(Typeface.DEFAULT_BOLD)

    cache.put(family, resolved)

    return resolved
}
