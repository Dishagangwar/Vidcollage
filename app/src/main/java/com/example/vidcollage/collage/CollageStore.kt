package com.example.vidcollage.collage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Writes finished collages where the user can find them, and where the share sheet can read them. */
object CollageStore {

    private const val ALBUM = "VidCollage"
    private const val MIME_TYPE = "image/png"
    private const val QUALITY = 100

    /** Saves [collage] into the device gallery under a "VidCollage" album. */
    suspend fun saveToGallery(context: Context, collage: Bitmap, videoName: String): Uri =
        withContext(Dispatchers.IO) {
            val fileName = fileNameFor(videoName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, collage, fileName)
            } else {
                saveViaPublicDirectory(context, collage, fileName)
            }
        }

    /** Writes [collage] into the cache and returns a content:// uri other apps may read. */
    suspend fun shareableUri(context: Context, collage: Bitmap, videoName: String): Uri =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(directory, fileNameFor(videoName))
            FileOutputStream(file).use { collage.compress(Bitmap.CompressFormat.PNG, QUALITY, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }

    private fun saveViaMediaStore(context: Context, collage: Bitmap, fileName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("The gallery refused to create an entry for $fileName")

        try {
            resolver.openOutputStream(uri)?.use { collage.compress(Bitmap.CompressFormat.PNG, QUALITY, it) }
                ?: throw IOException("Could not open $uri for writing")
        } catch (error: IOException) {
            resolver.delete(uri, null, null)
            throw error
        }

        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** Pre-Q devices have no scoped-storage insert, so write the file and register it afterwards. */
    private fun saveViaPublicDirectory(context: Context, collage: Bitmap, fileName: String): Uri {
        @Suppress("DEPRECATION")
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val album = File(pictures, ALBUM).apply { mkdirs() }
        val file = File(album, fileName)
        FileOutputStream(file).use { collage.compress(Bitmap.CompressFormat.PNG, QUALITY, it) }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
            @Suppress("DEPRECATION")
            put(MediaStore.Images.Media.DATA, file.absolutePath)
        }
        return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: Uri.fromFile(file)
    }

    private fun fileNameFor(videoName: String): String {
        val stem = videoName.substringBeforeLast('.', videoName)
            .replace(Regex("[^A-Za-z0-9-_]+"), "_")
            .trim('_')
            .ifEmpty { "collage" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "VidCollage_${stem}_$stamp.png"
    }
}
