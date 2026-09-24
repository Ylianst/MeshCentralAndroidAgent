package com.meshcentral.agent

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

// The message is shown on the viewer's files overlay, so keep it operator-friendly.
internal class UploadException(message: String) : Exception(message)

// Top-level shared-storage folders a scoped-storage app may create files in. Anything else under
// the storage root is off limits without MANAGE_EXTERNAL_STORAGE.
internal val standardSharedFolders = setOf(
    "Alarms", "Audiobooks", "DCIM", "Documents", "Download", "Movies", "Music",
    "Notifications", "Pictures", "Podcasts", "Recordings", "Ringtones"
)

// "Sdcard/Download/sub" -> "Download/sub", "Sdcard" -> "", anything else -> null.
internal fun sdcardRelativeDirectory(virtualDir: String): String? {
    if (virtualDir != "Sdcard" && !virtualDir.startsWith("Sdcard/")) return null
    return virtualDir.removePrefix("Sdcard").trim('/')
}

internal fun isStandardSharedFolder(relativeDir: String): Boolean {
    return standardSharedFolders.contains(relativeDir.substringBefore('/'))
}

// Media-only top-level folders, where Android's own index holds every file. Walking such a folder
// through FUSE is slow (4 s for a 14k-photo camera roll) while the index answers in about a second;
// Download and Documents stay on the directory walk because they hold other apps' unindexed files.
internal val mediaOnlySharedFolders = setOf(
    "Alarms", "Audiobooks", "DCIM", "Movies", "Music", "Notifications", "Pictures", "Podcasts",
    "Recordings", "Ringtones"
)

internal fun isMediaOnlySharedFolder(relativeDir: String): Boolean {
    return relativeDir.isNotEmpty() && mediaOnlySharedFolders.contains(relativeDir.substringBefore('/'))
}

// The folder's entries in the file browser's format from MediaStore, or null when the index can't
// be used (older Android, not a media folder, query failure, nothing indexed) so the caller walks
// the directory instead.
@SuppressLint("InlinedApi")
internal fun listIndexedFolder(resolver: ContentResolver, virtualDir: String): JSONArray? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val relativeDir = sdcardRelativeDirectory(virtualDir) ?: return null
    if (!isMediaOnlySharedFolder(relativeDir)) return null
    val projection = arrayOf(
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.MIME_TYPE
    )
    val result = JSONArray()
    try {
        val cursor = resolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(relativeDir.trim('/') + "/"),
            null
        ) ?: return null
        cursor.use {
            val nameColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val mimeColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (it.moveToNext()) {
                val name = it.getString(nameColumn) ?: continue
                val entry = JSONObject()
                entry.put("n", name)
                // Subfolders are the only indexed rows without a MIME type.
                entry.put("t", if (it.isNull(mimeColumn)) 2 else 3)
                entry.put("s", it.getLong(sizeColumn))
                entry.put("d", it.getLong(dateColumn))
                result.put(entry)
            }
        }
    } catch (ex: Exception) {
        return null
    }
    return if (result.length() == 0) null else result
}

// The flat media folders offered at the root of the file browser, backed by MediaStore.
internal enum class MediaFolder(
    val virtualName: String,
    private val mimePrefix: String,
    val directory: String,
    val label: String
) {
    IMAGES("Images", "image/", "Pictures", "image"),
    AUDIO("Audio", "audio/", "Music", "audio"),
    VIDEOS("Videos", "video/", "Movies", "video");

    fun accepts(mimeType: String): Boolean = mimeType.startsWith(mimePrefix)

    // Resolved on demand: the Uri constants need the Android runtime, which unit tests lack.
    fun collectionUri(): Uri = when (this) {
        IMAGES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        VIDEOS -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    companion object {
        fun fromVirtualName(name: String): MediaFolder? = values().firstOrNull { it.virtualName == name }
    }
}

internal fun mimeTypeForFileName(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    val mapped = if (extension.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    return mapped ?: "application/octet-stream"
}

// An open upload target: a plain file where Android still allows one, otherwise a MediaStore row.
internal class UploadSink private constructor(
    val stream: OutputStream,
    private val file: File?,
    private val resolver: ContentResolver?,
    private val uri: Uri?,
    // This upload created the file or row, so a failed transfer removes it again.
    private val created: Boolean,
    // A row inserted as pending stays hidden from other apps until finish().
    private val pending: Boolean
) {
    fun finish() {
        stream.close()
        if (pending && resolver != null && uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }

    fun discard() {
        try { stream.close() } catch (ex: Exception) { }
        if (!created) return
        try {
            if (file != null) {
                file.delete()
            } else if (resolver != null && uri != null) {
                resolver.delete(uri, null, null)
            }
        } catch (ex: Exception) { }
    }

    companion object {
        fun open(resolver: ContentResolver, path: String, name: String, append: Boolean): UploadSink {
            if (!isSafeFileName(name)) throw UploadException("invalid file name")
            val mimeType = mimeTypeForFileName(name)

            val media = MediaFolder.fromVirtualName(path)
            if (media != null) {
                if (!media.accepts(mimeType)) {
                    throw UploadException("only ${media.label} files can go in ${media.virtualName}, use Sdcard/Download for other files")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    return openMediaStore(resolver, media.collectionUri(), media.directory, name, mimeType, append, matchDirectory = false)
                }
                @Suppress("DEPRECATION")
                return openRawFile(File(Environment.getExternalStoragePublicDirectory(media.directory), name), append)
            }

            val relativeDir = sdcardRelativeDirectory(path) ?: throw UploadException("unknown folder \"$path\"")
            val file = resolveSdcardChild(Environment.getExternalStorageDirectory(), path, name)
                ?: throw UploadException("invalid path")
            val rawError = try {
                return openRawFile(file, append)
            } catch (ex: Exception) {
                ex
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                throw UploadException("cannot write \"$name\", ${rawError.message}")
            }
            if (!isStandardSharedFolder(relativeDir)) {
                val hint = if (AgentController.allFilesAccessAvailable) ", or grant All files access in the app settings" else ""
                throw UploadException("Android only lets the agent write inside standard folders such as Download, Documents, Pictures, Movies or Music, pick one of those under Sdcard$hint")
            }
            return openMediaStore(resolver, MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), relativeDir, name, mimeType, append, matchDirectory = true)
        }

        private fun openRawFile(file: File, append: Boolean): UploadSink {
            val existed = file.exists()
            val stream = FileOutputStream(file, append)
            return UploadSink(stream, file, null, null, created = !existed, pending = false)
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun openMediaStore(
            resolver: ContentResolver,
            collection: Uri,
            directory: String,
            name: String,
            mimeType: String,
            append: Boolean,
            matchDirectory: Boolean
        ): UploadSink {
            val relativePath = directory.trim('/') + "/"
            val existing = findMediaRow(resolver, collection, if (matchDirectory) relativePath else null, name)
            if (existing != null) {
                val stream = try {
                    resolver.openOutputStream(existing, if (append) "wa" else "wt")
                } catch (ex: SecurityException) {
                    throw UploadException("\"$name\" belongs to another app and cannot be replaced")
                } ?: throw UploadException("cannot open \"$name\"")
                return UploadSink(stream, null, resolver, existing, created = false, pending = false)
            }

            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            val uri = try {
                resolver.insert(collection, values)
            } catch (ex: Exception) {
                // MediaProvider refuses, for example, a text file in Pictures.
                throw UploadException("Android does not allow $mimeType files in $directory, use Download or Documents")
            } ?: throw UploadException("cannot create \"$name\" in $directory")
            val stream = try {
                resolver.openOutputStream(uri, "w")
            } catch (ex: Exception) {
                null
            }
            if (stream == null) {
                try { resolver.delete(uri, null, null) } catch (ex: Exception) { }
                throw UploadException("cannot open \"$name\" for writing")
            }
            return UploadSink(stream, null, resolver, uri, created = true, pending = true)
        }

        @SuppressLint("InlinedApi")
        fun findMediaRow(resolver: ContentResolver, collection: Uri, relativePath: String?, name: String): Uri? {
            val selection = if (relativePath == null) {
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            } else {
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            }
            val args = if (relativePath == null) arrayOf(name) else arrayOf(relativePath, name)
            return try {
                resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use { cursor ->
                    if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
                }
            } catch (ex: Exception) {
                null
            }
        }
    }
}

internal class SharedFile(val name: String, val size: Long, val stream: InputStream)

// Opens a file the viewer named by virtual folder and name for reading, wherever Android lets the
// agent reach it: the raw path, or the MediaStore row for media and for files the agent created.
internal fun openSharedFile(resolver: ContentResolver, path: String, name: String): SharedFile? {
    if (!isSafeFileName(name)) return null
    val media = MediaFolder.fromVirtualName(path)
    if (media != null) {
        val uri = UploadSink.findMediaRow(resolver, media.collectionUri(), null, name) ?: return null
        return openMediaRow(resolver, uri, name)
    }
    val file = resolveSdcardChild(Environment.getExternalStorageDirectory(), path, name) ?: return null
    if (file.isFile) {
        try {
            return SharedFile(name, file.length(), FileInputStream(file))
        } catch (ex: Exception) {
            // Another app's file may still be reachable through its MediaStore row below.
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val relativeDir = sdcardRelativeDirectory(path) ?: return null
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = UploadSink.findMediaRow(resolver, collection, relativeDir.trim('/') + "/", name) ?: return null
        return openMediaRow(resolver, uri, name)
    }
    return null
}

private fun openMediaRow(resolver: ContentResolver, uri: Uri, name: String): SharedFile? {
    return try {
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: return null
        SharedFile(name, descriptor.statSize.coerceAtLeast(0), ParcelFileDescriptor.AutoCloseInputStream(descriptor))
    } catch (ex: Exception) {
        null
    }
}

// SHA-384 of the file the viewer is about to replace, upper-case hex to match the web UI's
// comparison, or null when nothing readable is there. The viewer skips identical uploads.
internal fun hashExistingUpload(resolver: ContentResolver, path: String, name: String): String? {
    val input = try { openSharedFile(resolver, path, name)?.stream } catch (ex: Exception) { null } ?: return null
    return try {
        input.use { stream ->
            val digest = MessageDigest.getInstance("SHA-384")
            val buffer = ByteArray(65536)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02X".format(it) }
        }
    } catch (ex: Exception) {
        null
    }
}
