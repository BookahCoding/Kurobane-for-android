package dev.kurobanekotlin.core.chat

/** PNG, JPEG, GIF, and WebP, recognized by magic bytes. Anything else is refused before upload. */
object ImageSniff {
    const val MAX_BYTES = 20 * 1024 * 1024

    fun mime(bytes: ByteArray): String? {
        if (bytes.size < 12 || bytes.size > MAX_BYTES) return null
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) return "image/png"
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return "image/jpeg"
        if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()) {
            return "image/gif"
        }
        if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() && bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) return "image/webp"
        return null
    }

    const val REFUSAL = "Only PNG, JPEG, GIF, and WebP images can be attached. This file was not added."
    const val TOO_BIG = "This image is over 20 MB, so it was not attached."
}
