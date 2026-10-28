package com.tapecam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves into Movies/TapeCam and Pictures/TapeCam via MediaStore. */
object MediaSaver {

    private const val FOLDER = "TapeCam"

    fun newName(prefix: String): String =
        prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun createVideo(ctx: Context, name: String): Uri? = insert(
        ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "$name.mp4", "video/mp4",
        Environment.DIRECTORY_MOVIES
    )

    fun saveJpeg(ctx: Context, bitmap: Bitmap): Uri? {
        val uri = insert(
            ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "${newName("TAPE_")}.jpg",
            "image/jpeg", Environment.DIRECTORY_PICTURES
        ) ?: return null
        return try {
            ctx.contentResolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            finish(ctx, uri)
            uri
        } catch (e: Exception) {
            ctx.contentResolver.delete(uri, null, null)
            null
        }
    }

    fun finish(ctx: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            ctx.contentResolver.update(uri, v, null, null)
        }
    }

    private fun insert(ctx: Context, collection: Uri, displayName: String, mime: String, dir: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val folder = File(Environment.getExternalStoragePublicDirectory(dir), FOLDER).apply { mkdirs() }
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, File(folder, displayName).absolutePath)
            }
        }
        return ctx.contentResolver.insert(collection, values)
    }
}
