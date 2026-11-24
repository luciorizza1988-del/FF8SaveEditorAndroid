package it.luciorizza.ff8saveeditor

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class CharacterPreview(
    val slot: Int,
    val characterId: Int,
    val level: Long,
    val currentHp: Int,
    val maxHp: Int
)

data class SavePreview(
    val gil: Long,
    val characters: List<CharacterPreview>
)

/**
 * Read-only preview of fields whose packed offsets are documented in Hyne's
 * GPL-3.0 SaveData.h. No bytes are changed by this class.
 */
object FF8SaveReader {
    private const val RAW_SIZE = 5408
    private const val MAIN_OFFSET = 464
    private const val CHARACTERS_OFFSET_IN_MAIN = 1088
    private const val CHARACTER_SIZE = 152
    private const val PARTY_OFFSET_IN_MAIN = 3188
    private const val GIL_OFFSET_IN_MAIN = 3212

    fun read(data: ByteArray, saveOffset: Int = 0): SavePreview? {
        if (saveOffset < 0 || data.size - saveOffset < RAW_SIZE) return null
        if (data[saveOffset] != 'S'.code.toByte() ||
            data[saveOffset + 1] != 'C'.code.toByte()) return null

        val marker = u16(data, saveOffset + 386)
        if (marker != 0x08ff && marker != 0x0ff8) return null

        val main = saveOffset + MAIN_OFFSET
        val gil = u32(data, main + GIL_OFFSET_IN_MAIN)
        val party = (0 until 3).map { data[main + PARTY_OFFSET_IN_MAIN + it].toInt() and 0xff }
        val characters = party.mapNotNull { characterIndex ->
            if (characterIndex !in 0..7) return@mapNotNull null
            val offset = main + CHARACTERS_OFFSET_IN_MAIN + characterIndex * CHARACTER_SIZE
            val currentHp = u16(data, offset)
            val maxHp = u16(data, offset + 2)
            val exp = u32(data, offset + 4)
            val id = data[offset + 8].toInt() and 0xff
            CharacterPreview(characterIndex, id, exp / 1000L + 1L, currentHp, maxHp)
        }
        return SavePreview(gil, characters)
    }

    fun format(preview: SavePreview): String = buildString {
        append("Gil: ").append(preview.gil)
        if (preview.characters.isNotEmpty()) {
            append("\nPersonaggi in squadra:")
            preview.characters.forEachIndexed { index, c ->
                append("\n").append(index + 1).append(". ID ").append(c.characterId)
                    .append(" — livello ").append(c.level)
                    .append(" — HP ").append(c.currentHp).append("/").append(c.maxHp)
            }
        } else {
            append("\nNessun personaggio di squadra riconosciuto.")
        }
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun u32(data: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
}
