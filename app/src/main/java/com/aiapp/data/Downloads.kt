package com.aiapp.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.aiapp.llm.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** Saves files from the web into the phone's Downloads/AIApp folder. */
object Downloads {
    private const val MAX_BYTES = 50L * 1024 * 1024

    suspend fun save(context: Context, url: String): String = withContext(Dispatchers.IO) {
        runCatching {
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@use "Download failed (HTTP ${r.code})."
                val body = r.body ?: return@use "Download failed (empty)."
                if (body.contentLength() > MAX_BYTES) return@use "File is larger than 50 MB, so it wasn't downloaded."
                val mime = body.contentType()?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream"
                val name = url.substringBefore('?').substringAfterLast('/').ifBlank { "download" }
                    .replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
                val bytes = body.bytes()
                if (bytes.size > MAX_BYTES) return@use "File is larger than 50 MB, so it wasn't downloaded."
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AIApp")
                    }
                    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return@use "Couldn't create the file in Downloads."
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                } else {
                    // Android 8–9: save in the app's own Downloads folder (no storage permission needed).
                    val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "AIApp").apply { mkdirs() }
                    File(dir, name).writeBytes(bytes)
                }
                "Saved $name (${bytes.size / 1024} KB) to Downloads/AIApp on the phone."
            }
        }.getOrElse { "Download failed: ${it.message}" }
    }
}
