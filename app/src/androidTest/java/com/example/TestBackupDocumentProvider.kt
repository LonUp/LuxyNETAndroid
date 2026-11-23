package com.example

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import java.io.File

/**
 * In-process document provider for instrumentation backup/restore tests.
 * Rejects callers outside the instrumented app UID.
 */
class TestBackupDocumentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun enforceSameUid() {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw SecurityException("TestBackupDocumentProvider is restricted to the instrumented process")
        }
    }

    private fun resolveFile(uri: Uri): File {
        val name = uri.lastPathSegment?.takeIf { it.isNotBlank() && !it.contains('/') && !it.contains("..") }
            ?: "backup.json"
        val dir = File(requireNotNull(context).cacheDir, "test_backup_docs").apply { mkdirs() }
        return File(dir, name)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        enforceSameUid()
        val file = resolveFile(uri)
        if (mode.contains('w')) {
            file.parentFile?.mkdirs()
            if (file.exists()) file.writeBytes(ByteArray(0))
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        enforceSameUid()
        val file = resolveFile(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        val row = cols.map<String, Any?> { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> if (file.exists()) file.length() else 0L
                else -> null
            }
        }.toTypedArray()
        cursor.addRow(row)
        return cursor
    }

    override fun getType(uri: Uri): String {
        enforceSameUid()
        return "application/json"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceSameUid()
        val file = resolveFile(uri)
        return if (file.exists() && file.delete()) 1 else 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val AUTHORITY = "com.aistudio.luxyvpn.kxmzpq.testbackup"

        fun uriFor(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")
    }
}
