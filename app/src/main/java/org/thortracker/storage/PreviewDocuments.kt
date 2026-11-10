package org.thortracker.storage

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/** A preview-only SAF root backed by this package's external files, never ThorTunez or production data. */
class PreviewDocuments : DocumentsProvider() {
    private val directory: File
        get() {
            val base = requireNotNull(context).getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: throw FileNotFoundException("Preview external storage is unavailable")
            return File(base, "ThorTrackerTouchPreview").also {
                if (!it.isDirectory && !it.mkdirs()) throw FileNotFoundException("Cannot create preview documents directory")
            }
        }

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
        return MatrixCursor(columns).apply {
            val row = newRow()
            for (column in columns) row.add(when (column) {
                DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID -> ROOT
                DocumentsContract.Root.COLUMN_TITLE -> "Thor Tracker Touch Preview"
                DocumentsContract.Root.COLUMN_FLAGS -> DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_LOCAL_ONLY
                DocumentsContract.Root.COLUMN_MIME_TYPES -> "*/*"
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES -> directory.usableSpace
                else -> null
            })
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: COLUMNS).apply { addDocument(this, documentId, resolve(documentId)) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        if (parentDocumentId != ROOT) throw FileNotFoundException("Not a directory")
        return MatrixCursor(projection ?: COLUMNS).apply {
            for (file in directory.listFiles().orEmpty().sortedBy { it.name }) {
                if (file.isFile) addDocument(this, file.name, file)
            }
        }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val file = resolve(documentId)
        if (!file.isFile) throw FileNotFoundException("Not a preview file")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (parentDocumentId != ROOT || mimeType == DocumentsContract.Document.MIME_TYPE_DIR)
            throw FileNotFoundException("Only files in the preview root are supported")
        validateName(displayName)
        val dot = displayName.lastIndexOf('.').takeIf { it > 0 } ?: displayName.length
        val stem = displayName.substring(0, dot); val extension = displayName.substring(dot)
        // createNewFile is atomic even when two SAF clients request the same title.
        for (n in 0..999) {
            val name = if (n == 0) displayName else "$stem ($n)$extension"
            if (File(directory, name).createNewFile()) return name
        }
        throw FileNotFoundException("Too many files with this name")
    }

    override fun deleteDocument(documentId: String) {
        if (documentId == ROOT || !resolve(documentId).delete()) throw FileNotFoundException("Cannot delete preview file")
    }

    private fun resolve(id: String): File {
        if (id == ROOT) return directory
        validateName(id)
        val root = directory.canonicalFile
        return File(root, id).canonicalFile.also {
            if (it.parentFile != root) throw FileNotFoundException("Outside preview documents")
        }
    }

    private fun addDocument(cursor: MatrixCursor, id: String, file: File) {
        if (!file.exists()) throw FileNotFoundException(id)
        val row = cursor.newRow()
        for (column in cursor.columnNames) row.add(when (column) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> if (id == ROOT) "ThorTrackerTouchPreview" else file.name
            DocumentsContract.Document.COLUMN_MIME_TYPE -> if (id == ROOT) DocumentsContract.Document.MIME_TYPE_DIR
                else fileMimeType(file.name)
            DocumentsContract.Document.COLUMN_SIZE -> if (id == ROOT) null else file.length()
            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> file.lastModified()
            DocumentsContract.Document.COLUMN_FLAGS -> if (id == ROOT) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                else DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            else -> null
        })
    }

    companion object {
        private const val ROOT = "preview"
        private val COLUMNS = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS)
        fun rootUri(context: Context): Uri = DocumentsContract.buildRootUri("${context.packageName}.documents", ROOT)
        internal fun fileMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "wav" -> "audio/x-wav"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
        internal fun validateName(name: String) {
            if (name.isBlank() || name == ROOT || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it.code < 32 })
                throw FileNotFoundException("Invalid preview document name")
        }
    }
}
