//
// SPDX-FileCopyrightText: 2026 The VoltageOS Project
// SPDX-License-Identifier: Apache-2.0
//

package org.lineageos.glimpse.ext

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.IOException

fun Context.decodeSampledBitmap(uri: Uri, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    try {
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
    } catch (_: IOException) {
        return null
    }

    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) {
        return null
    }

    var sample = 1
    val longest = maxOf(w, h)
    while (longest / sample > maxSide) {
        sample *= 2
    }

    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
    }
    val decoded = try {
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    } catch (_: OutOfMemoryError) {
        return null
    } ?: return null

    val orientation = runCatching {
        contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    return decoded.applyExifOrientation(orientation)
}

fun Bitmap.applyExifOrientation(orientation: Int): Bitmap {
    val matrix = android.graphics.Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        else -> return this
    }

    return try {
        val out = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        if (out != this) {
            recycle()
        }
        out
    } catch (_: OutOfMemoryError) {
        this
    }
}

fun fullResolutionBitmap(context: Context, uri: Uri, retrySample: Int = 1): Bitmap? {
    fun decode(sample: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inMutable = false
        }
        val bmp = try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (_: OutOfMemoryError) {
            return null
        } ?: return null

        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        return bmp.applyExifOrientation(orientation)
    }

    return try {
        decode(retrySample)
    } catch (_: OutOfMemoryError) {
        null
    } ?: if (retrySample == 1) {
        runCatching { decode(2) }.getOrNull()
    } else {
        null
    }
}
