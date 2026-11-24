package it.luciorizza.ff8saveeditor

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class CharacterPreview(
    val slot: Int,
    val characterId: Int,
    val currentHp: Int,
    val maxHp: Int
)

data class SavePreview(
    val gil: Long,
    val characters: List<CharacterPreview>,
    val gfCount: Int,
    val inventoryStacks: Int
)

/**
 * Read-only preview based on the packed structures in Hyne's GPL-3.0 SaveData.h.
 * All offsets below are relative to the beginning of MAIN unless noted.
 */
object FF8SaveReader {
    private const val RAW_SIZE = 5408
    private const val MAIN_OFFSET = 464
    private const val CHARACTERS_OFFSET_IN_MAIN = 1088
    private const val CHARACTER_SIZE = 152
    private const val GF_SIZE = 68
    private const val PARTY_OFFSET_IN_MAIN = 3188 // MISC1.party
    private const val GIL_OFFSET_IN_MAIN = 3212 // MISC1.gils
    private const val ITEMS_OFFSET_IN_MAIN = 3236

    fun read(data: ByteArray, saveOffset: Int = 0): SavePreview? {
        if (saveOffset < 0 || data.size - saveOffset < RAW_SIZE) return null
        if (data[saveOffset] != 'S'.code.toByte() ||
            data[saveOffset + 1] != 'C'.code.toByte()) return null
        val marker = u16(data, saveOffset + 386)
        if (marker != 0x08ff && marker != 0x0ff8) return null

        val main = saveOffset + MAIN_OFFSET
        val gil = u32(data, main + GIL_OFFSET_IN_MAIN)
        val party = (0 until 3).map {
            data[main + PARTY_OFFSET_IN_MAIN + it].toInt() and 0xff
        }
        val characters = party.mapNotNull { characterIndex ->
            if (characterIndex !in 0..7) return@mapNotNull null
            val offset = main + CHARACTERS_OFFSET_IN_MAIN + characterIndex * CHARACTER_SIZE
            val currentHp = u16(data, offset)
            val maxHp = u16(data, offset + 2)
            val id = data[offset + 8].toInt() and 0xff
            CharacterPreview(characterIndex, id, currentHp, maxHp)
        }

        val gfCount = (0 until 16).count { index ->
            data[main + index * GF_SIZE + 17].toInt() and 0xff != 0
        }
        val inventoryStart = main + ITEMS_OFFSET_IN_MAIN + 32
        val inventoryStacks = (0 until 198).count { index ->
            val entry = u16(data, inventoryStart + index * 2)
            (entry and 0xff) != 0 && (entry ushr 8) != 0
        }
        return SavePreview(gil, characters, gfCount, inventoryStacks)
    }

    fun format(preview: SavePreview): String = buildString {
        append("Gil: ").append(preview.gil)
        append("\nGF riconosciuti: ").append(preview.gfCount)
        append("\nTipi di oggetti presenti: ").append(preview.inventoryStacks)
        if (preview.characters.isNotEmpty()) {
            append("\nPersonaggi in squadra:")
            preview.characters.forEachIndexed { index, c ->
                append("\n").append(index + 1).append(". ID ").append(c.characterId)
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
