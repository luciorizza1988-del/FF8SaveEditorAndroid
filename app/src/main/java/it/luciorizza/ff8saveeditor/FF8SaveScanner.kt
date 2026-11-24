package it.luciorizza.ff8saveeditor

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Conservative format recognizer based on Hyne's SaveData::setData and
 * SavecardData::ps layout. It never alters imported bytes.
 */
object FF8SaveScanner {
    private const val PS1_CARD_BYTES = 131072
    private const val SLOT_BYTES = 8192
    private const val FIRST_SLOT_OFFSET = 8192
    private const val FF8_RAW_BYTES = 5408

    fun describe(bytes: ByteArray): String {
        if (bytes.size == FF8_RAW_BYTES && isRawSave(bytes, 0)) {
            return "Salvataggio FF8 PlayStation riconosciuto (file singolo, 5.408 byte)."
        }
        if (bytes.size >= PS1_CARD_BYTES) {
            val found = mutableListOf<String>()
            for (slot in 0 until 15) {
                val offset = FIRST_SLOT_OFFSET + slot * SLOT_BYTES
                if (isRawSave(bytes, offset)) {
                    val gameTime = readUInt32(bytes, offset + 464)
                    found += "Slot ${slot + 1} (tempo di gioco grezzo: ${gameTime})"
                }
            }
            if (found.isNotEmpty()) {
                return "Memory card PlayStation riconosciuta. Salvataggi FF8 trovati: ${found.size}\n" +
                    found.joinToString("\n")
            }
            return "File grande quanto una memory card PS1, ma non ho trovato slot FF8 validi."
        }
        if (bytes.size >= SLOT_BYTES && isRawSave(bytes, 0)) {
            return "Blocco di salvataggio FF8 riconosciuto."
        }
        return "Formato non riconosciuto come salvataggio FF8 PlayStation. Il file è stato conservato senza modificarlo."
    }

    private fun isRawSave(data: ByteArray, offset: Int): Boolean {
        if (offset < 0 || data.size - offset < FF8_RAW_BYTES) return false
        if (data[offset] != 'S'.code.toByte() || data[offset + 1] != 'C'.code.toByte()) return false
        val marker = (data[offset + 386].toInt() and 0xff) or
            ((data[offset + 387].toInt() and 0xff) shl 8)
        return marker == 0x08ff || marker == 0x0ff8
    }

    private fun readUInt32(data: ByteArray, offset: Int): Long {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN)
            .int.toLong() and 0xffffffffL
    }
}
