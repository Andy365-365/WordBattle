package com.wordbattle.network

object TcpCodec {

    fun encode(jsonStr: String): ByteArray {
        val body = jsonStr.toByteArray(Charsets.UTF_8)
        val length = body.size
        return ByteArray(4 + length).apply {
            this[0] = ((length shr 24) and 0xFF).toByte()
            this[1] = ((length shr 16) and 0xFF).toByte()
            this[2] = ((length shr 8) and 0xFF).toByte()
            this[3] = (length and 0xFF).toByte()
            body.copyInto(this, 4)
        }
    }

    suspend fun decode(inputStream: java.io.InputStream): String {
        val header = ByteArray(4)
        readFully(inputStream, header)
        val length = readInt(header)
        val body = ByteArray(length)
        readFully(inputStream, body)
        return String(body, Charsets.UTF_8)
    }

    private suspend fun readFully(inputStream: java.io.InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = inputStream.read(buffer, offset, buffer.size - offset)
            if (read == -1) throw java.io.EOFException("Connection closed")
            offset += read
        }
    }

    private fun readInt(header: ByteArray): Int {
        return ((header[0].toInt() and 0xFF) shl 24) or
               ((header[1].toInt() and 0xFF) shl 16) or
               ((header[2].toInt() and 0xFF) shl 8) or
               (header[3].toInt() and 0xFF)
    }
}