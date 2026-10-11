package io.hwpbyhwp.core.parser

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

internal object BinaryData {

    /**
     * Returns image bytes ready for decoding. HWP may store embedded data raw-deflated;
     * anything that already starts with a known image signature is returned as is.
     */
    fun decode(data: ByteArray): ByteArray {
        if (data.isEmpty() || looksLikeImage(data)) return data
        return runCatching { inflateRaw(data) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: data
    }

    private fun looksLikeImage(d: ByteArray): Boolean = when {
        d.startsWith(0x89, 0x50, 0x4E, 0x47) -> true // PNG
        d.startsWith(0xFF, 0xD8, 0xFF) -> true // JPEG
        d.startsWith(0x47, 0x49, 0x46) -> true // GIF
        d.startsWith(0x42, 0x4D) -> true // BMP
        d.startsWith(0xD7, 0xCD, 0xC6, 0x9A) -> true // WMF (placeable)
        d.startsWith(0x01, 0x00, 0x00, 0x00) -> true // EMF
        d.startsWith(0x49, 0x49, 0x2A, 0x00) || d.startsWith(0x4D, 0x4D, 0x00, 0x2A) -> true // TIFF
        else -> false
    }

    private fun ByteArray.startsWith(vararg bytes: Int): Boolean =
        size >= bytes.size && bytes.indices.all { this[it] == bytes[it].toByte() }

    private fun inflateRaw(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 2)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
