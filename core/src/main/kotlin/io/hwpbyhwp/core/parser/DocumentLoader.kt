package io.hwpbyhwp.core.parser

import io.hwpbyhwp.core.model.Document
import kr.dogfoot.hwplib.reader.HWPReader
import kr.dogfoot.hwpxlib.reader.HWPXReader
import java.io.File
import java.io.InputStream

enum class DocumentFormat { HWP, HWPX }

class UnsupportedDocumentException(message: String, cause: Throwable? = null) : Exception(message, cause)

object DocumentLoader {

    /** Detects the format from the file signature, not the extension. */
    fun detectFormat(header: ByteArray): DocumentFormat? = when {
        header.size >= 8 && header.copyOfRange(0, 8).contentEquals(OLE_SIGNATURE) -> DocumentFormat.HWP
        header.size >= 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
            header[2] == 3.toByte() && header[3] == 4.toByte() -> DocumentFormat.HWPX
        else -> null
    }

    fun load(file: File): Document {
        // Avoid InputStream.readNBytes: it needs Android API 33.
        val header = ByteArray(8)
        val read = file.inputStream().use { input ->
            var n = 0
            while (n < header.size) {
                val r = input.read(header, n, header.size - n)
                if (r < 0) break
                n += r
            }
            n
        }
        val format = detectFormat(header.copyOf(read))
            ?: throw UnsupportedDocumentException("HWP/HWPX 파일이 아닙니다: ${file.name}")
        return try {
            when (format) {
                DocumentFormat.HWP -> HwpConverter(HWPReader.fromFile(file)).convert()
                DocumentFormat.HWPX -> HwpxConverter(HWPXReader.fromFile(file)).convert()
            }
        } catch (e: UnsupportedDocumentException) {
            throw e
        } catch (e: Exception) {
            throw UnsupportedDocumentException("문서를 읽을 수 없습니다: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /**
     * Loads from a stream (e.g. an Android content URI). hwpxlib only reads from files,
     * so the stream is spooled to a temporary file in [tempDir] first.
     */
    fun load(input: InputStream, tempDir: File): Document {
        val tmp = File.createTempFile("doc", ".tmp", tempDir)
        try {
            tmp.outputStream().use { input.copyTo(it) }
            return load(tmp)
        } finally {
            tmp.delete()
        }
    }

    private val OLE_SIGNATURE = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
    )
}
