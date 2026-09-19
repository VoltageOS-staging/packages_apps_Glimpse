//
// SPDX-FileCopyrightText: 2026 The VoltageOS Project
// SPDX-License-Identifier: Apache-2.0
//

package org.lineageos.glimpse.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lineageos.glimpse.datasources.MediaError
import org.lineageos.glimpse.ext.drawMarkup
import org.lineageos.glimpse.ext.fullResolutionBitmap
import org.lineageos.glimpse.models.RequestStatus
import org.lineageos.glimpse.models.editor.EditorSnapshot
import kotlin.math.roundToInt

object EditorExporter {
    suspend fun export(
        context: Context,
        source: Uri,
        snapshot: EditorSnapshot,
        overwrite: Boolean,
    ): RequestStatus<Uri, MediaError> = withContext(Dispatchers.IO) {
        try {
            val full = fullResolutionBitmap(context, source)
                ?: return@withContext RequestStatus.Error(MediaError.IO)

            val srcW = full.width
            val srcH = full.height

            val transform = snapshot.transform
            val rotated = transform.rotationDegrees % 180 != 0
            val interW = if (rotated) srcH else srcW
            val interH = if (rotated) srcW else srcH

            // Rotate / flip into intermediate
            val intermediate = if (transform.rotationDegrees != 0 || transform.flipped) {
                val m = Matrix()
                // Rotate around center then re-center
                m.postRotate(
                    transform.rotationDegrees.toFloat(),
                    srcW / 2f, srcH / 2f,
                )
                if (transform.flipped) {
                    // Flip horizontally in rotated space: translate to keep in bounds
                    m.postScale(-1f, 1f, interW / 2f, interH / 2f)
                }
                // Compute bounds after transform to size the bitmap
                val tmpRect = RectF(0f, 0f, srcW.toFloat(), srcH.toFloat())
                val mapped = RectF()
                m.mapRect(mapped, tmpRect)
                val outW = mapped.width().roundToInt().coerceAtLeast(1)
                val outH = mapped.height().roundToInt().coerceAtLeast(1)
                // Shift so top-left is 0,0
                m.postTranslate(-mapped.left, -mapped.top)
                val out = try {
                    Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                } catch (_: OutOfMemoryError) {
                    full.recycle()
                    return@withContext RequestStatus.Error(MediaError.IO)
                }
                val c = Canvas(out)
                c.drawBitmap(full, m, null)
                full.recycle()
                out
            } else {
                full
            }

            // Crop
            val crop = transform.cropRect
            val cx = (crop.left * intermediate.width).roundToInt().coerceIn(0, intermediate.width - 1)
            val cy = (crop.top * intermediate.height).roundToInt().coerceIn(0, intermediate.height - 1)
            val cw = (crop.width() * intermediate.width).roundToInt()
                .coerceIn(1, intermediate.width - cx)
            val ch = (crop.height() * intermediate.height).roundToInt()
                .coerceIn(1, intermediate.height - cy)

            val cropped = try {
                Bitmap.createBitmap(intermediate, cx, cy, cw, ch)
            } catch (_: OutOfMemoryError) {
                // Retry at half res
                val half = try {
                    Bitmap.createScaledBitmap(
                        intermediate,
                        intermediate.width / 2, intermediate.height / 2, true
                    )
                } catch (_: OutOfMemoryError) {
                    intermediate.recycle()
                    return@withContext RequestStatus.Error(MediaError.IO)
                }
                intermediate.recycle()
                return@withContext exportScaled(context, source, snapshot, overwrite, half)
            }
            if (cropped != intermediate) {
                intermediate.recycle()
            }

            // Replay markup at full res
            val out = try {
                Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                cropped.recycle()
                return@withContext RequestStatus.Error(MediaError.IO)
            }
            val canvas = Canvas(out)
            canvas.drawBitmap(cropped, 0f, 0f, null)
            if (snapshot.elements.isNotEmpty()) {
                canvas.drawMarkup(
                    snapshot.elements,
                    srcW, srcH,
                    RectF(0f, 0f, out.width.toFloat(), out.height.toFloat()),
                    transform,
                )
            }
            cropped.recycle()

            val mime = context.contentResolver.getType(source) ?: "image/jpeg"
            val usePng = mime.contains("png", true) ||
                    mime.contains("webp", true) ||
                    mime.contains("gif", true)
            val format = if (usePng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG

            val result = if (overwrite) {
                writeOverwrite(context, source, out, format)
            } else {
                writeCopy(context, source, out, format, mime)
            }
            out.recycle()

            result
        } catch (_: OutOfMemoryError) {
            RequestStatus.Error(MediaError.IO)
        } catch (_: Exception) {
            RequestStatus.Error(MediaError.IO)
        }
    }

    private fun writeOverwrite(
        context: Context,
        target: Uri,
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
    ): RequestStatus<Uri, MediaError> {
        return try {
            context.contentResolver.openOutputStream(target, "wt")?.use { stream ->
                if (!bitmap.compress(format, 95, stream)) {
                    return RequestStatus.Error(MediaError.IO)
                }
            } ?: return RequestStatus.Error(MediaError.IO)
            copyExif(context, target, target)
            // Nudge observers: a plain stream write doesn't always bump
            // these, and the viewer keys its caches off them.
            runCatching {
                context.contentResolver.update(
                    target,
                    ContentValues().apply {
                        put(
                            MediaStore.Images.Media.DATE_MODIFIED,
                            System.currentTimeMillis() / 1000L
                        )
                    },
                    null, null
                )
            }
            RequestStatus.Success(target)
        } catch (_: Exception) {
            RequestStatus.Error(MediaError.IO)
        }
    }

    private fun writeCopy(
        context: Context,
        source: Uri,
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        mime: String,
    ): RequestStatus<Uri, MediaError> {
        val resolver = context.contentResolver

        var displayName: String? = null
        var relativePath: String? = null
        runCatching {
            resolver.query(source, null, null, null, null)?.use { c ->
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && nameIdx != -1) {
                    displayName = c.getString(nameIdx)
                }
            }
        }
        runCatching {
            val projection = arrayOf(MediaStore.Images.Media.RELATIVE_PATH)
            resolver.query(source, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    relativePath = c.getString(0)
                }
            }
        }

        val base = displayName?.substringBeforeLast(".", displayName) ?: "IMG"
        val ext = if (format == Bitmap.CompressFormat.PNG) "png" else "jpg"
        val outName = "${base.substringBeforeLast(".")}_edited.$ext"
        val outMime = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, outName)
            put(MediaStore.Images.Media.MIME_TYPE, outMime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    relativePath ?: "${Environment.DIRECTORY_PICTURES}/Glimpse"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val newUri = resolver.insert(collection, values)
            ?: return RequestStatus.Error(MediaError.IO)

        try {
            resolver.openOutputStream(newUri)?.use { stream ->
                if (!bitmap.compress(format, 95, stream)) {
                    resolver.delete(newUri, null, null)
                    return RequestStatus.Error(MediaError.IO)
                }
            } ?: run {
                resolver.delete(newUri, null, null)
                return RequestStatus.Error(MediaError.IO)
            }

            copyExif(context, source, newUri)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    newUri,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    },
                    null, null
                )
            }
        } catch (_: Exception) {
            runCatching { resolver.delete(newUri, null, null) }
            return RequestStatus.Error(MediaError.IO)
        }

        // Best effort: carry over date taken if we can read it
        return RequestStatus.Success(newUri)
    }

    private fun copyExif(context: Context, from: Uri, to: Uri) {
        if (from == to) {
            return
        }
        try {
            val srcExif = context.contentResolver.openInputStream(from)?.use {
                ExifInterface(it)
            } ?: return

            // Skip output that isn't jpeg; exif on png is mostly ignored anyway
            context.contentResolver.openFileDescriptor(to, "rw")?.use { pfd ->
                val dstExif = ExifInterface(pfd.fileDescriptor)
                for (tag in KEEP_TAGS) {
                    srcExif.getAttribute(tag)?.let {
                        dstExif.setAttribute(tag, it)
                    }
                }
                srcExif.latLong?.let {
                    dstExif.setLatLong(it[0], it[1])
                }
                runCatching { dstExif.saveAttributes() }
            }
        } catch (_: Exception) {
        }
    }

    private fun exportScaled(
        context: Context,
        source: Uri,
        snapshot: EditorSnapshot,
        overwrite: Boolean,
        scaled: Bitmap,
    ): RequestStatus<Uri, MediaError> {
        // Fallback path when full-res crop OOMs: just write the scaled bitmap with markup skipped
        // at reduced res to still give the user something.
        return try {
            val mime = context.contentResolver.getType(source) ?: "image/jpeg"
            val usePng = mime.contains("png", true)
            val format = if (usePng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val result = if (overwrite) {
                writeOverwrite(context, source, scaled, format)
            } else {
                writeCopy(context, source, scaled, format, mime)
            }
            scaled.recycle()
            result
        } catch (_: Exception) {
            RequestStatus.Error(MediaError.IO)
        }
    }

    private val KEEP_TAGS = arrayOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_ISO_SPEED,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
    )
}
