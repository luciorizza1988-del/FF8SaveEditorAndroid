package it.luciorizza.ff8saveeditor

/**
 * Safe FF8 save writer. Works on a copy and never mutates the caller's byte array.
 * Layout and CRC procedure follow Hyne (GPL-3.0); see COPYING.txt.
 */
object FF8SaveWriter {
    private const val RAW_SIZE = 5408
    private const val SLOT_SIZE = 8192
    private const val MAIN_OFFSET = 464
    private const val MAIN_SIZE = 4944
    private const val PARTY_OFFSET = 3188
    private const val GIL_OFFSET = 3212
    private const val CHARACTERS_OFFSET = 1088
    private const val CHARACTER_SIZE = 152

    data class EditResult(val bytes: ByteArray, val saveOffset: Int)

    fun updateGil(source: ByteArray, saveOffset: Int = 0, gil: Long): EditResult? {
        if (gil !in 0L..0xffffffffL) return null
        return edit(source, saveOffset) { main ->
            put32(main, GIL_OFFSET, gil)
        }
    }

    fun updateCharacter(
        source: ByteArray,
        saveOffset: Int = 0,
        characterSlot: Int,
        experience: Long? = null,
        currentHp: Int? = null,
        maxHp: Int? = null
    ): EditResult? {
        if (characterSlot !in 0..7) return null
        if (experience != null && experience !in 0L..0xffffffffL) return null
        if (currentHp != null && currentHp !in 0..65535) return null
        if (maxHp != null && maxHp !in 0..65535) return null
        if (experience == null && currentHp == null && maxHp == null) return null
        return edit(source, saveOffset) { main ->
            val offset = CHARACTERS_OFFSET + characterSlot * CHARACTER_SIZE
            experience?.let { put32(main, offset + 4, it) }
            currentHp?.let { put16(main, offset, it) }
            maxHp?.let { put16(main, offset + 2, it) }
        }
    }

    fun maximizeGf(source: ByteArray, saveOffset: Int = 0): EditResult? =
        edit(source, saveOffset) { main ->
            repeat(16) { index ->
                val offset = index * 68
                if ((main[offset + 17].toInt() and 0xff) != 0) {
                    put32(main, offset + 12, 9999999L)
                    put16(main, offset + 18, 9999)
                }
            }
        }

    fun maximizeItems(source: ByteArray, saveOffset: Int = 0): EditResult? =
        edit(source, saveOffset) { main ->
            val start = 2772 + 32
            repeat(198) { index ->
                val entry = u16(main, start + index * 2)
                val itemId = entry and 0xff
                val quantity = entry ushr 8
                if (itemId != 0 && quantity != 0) put16(main, start + index * 2, itemId or (99 shl 8))
            }
        }

    fun maximizeJunctionMagic(source: ByteArray, saveOffset: Int = 0): EditResult? =
        edit(source, saveOffset) { main ->
            repeat(3) { partyIndex ->
                val characterIndex = main[2724 + partyIndex].toInt() and 0xff
                if (characterIndex in 0..7) {
                    val magicStart = CHARACTERS_OFFSET + characterIndex * CHARACTER_SIZE + 10
                    repeat(32) { spellIndex -> put16(main, magicStart + spellIndex * 2, 100) }
                }
            }
        }

    fun maximizeParty(source: ByteArray, saveOffset: Int = 0): EditResult? =
        edit(source, saveOffset) { main ->
            repeat(3) { partyIndex ->
                val characterIndex = main[2724 + partyIndex].toInt() and 0xff
                if (characterIndex in 0..7) {
                    val offset = CHARACTERS_OFFSET + characterIndex * CHARACTER_SIZE
                    put16(main, offset, 9999)
                    put16(main, offset + 2, 9999)
                    put32(main, offset + 4, 1000000L)
                }
            }
        }

    private inline fun edit(
        source: ByteArray,
        saveOffset: Int,
        changeMain: (ByteArray) -> Unit
    ): EditResult? {
        if (saveOffset < 0 || source.size - saveOffset < RAW_SIZE) return null
        if (source[saveOffset] != 'S'.code.toByte() ||
            source[saveOffset + 1] != 'C'.code.toByte()) return null
        val marker = u16(source, saveOffset + 386)
        if (marker != 0x08ff && marker != 0x0ff8) return null

        val output = source.copyOf()
        val main = output.copyOfRange(saveOffset + MAIN_OFFSET, saveOffset + MAIN_OFFSET + MAIN_SIZE)
        changeMain(main)
        val checksum = crc16(main)
        main.copyInto(output, saveOffset + MAIN_OFFSET)
        put16(output, saveOffset + 384, checksum)

        // A standalone 5408-byte raw save is expanded to a standard 8192-byte block.
        if (output.size - saveOffset == RAW_SIZE) {
            if (saveOffset != 0) return null
            val expanded = ByteArray(SLOT_SIZE)
            output.copyInto(expanded, 0, 0, RAW_SIZE)
            put16(expanded, MAIN_OFFSET + MAIN_SIZE, checksum)
            return EditResult(expanded, 0)
        }
        if (output.size - saveOffset < SLOT_SIZE) return null
        put16(output, saveOffset + MAIN_OFFSET + MAIN_SIZE, checksum)
        return EditResult(output, saveOffset)
    }

    private fun crc16(main: ByteArray): Int {
        var crc = 0xffff
        for (byte in main) {
            val index = ((crc ushr 8) xor (byte.toInt() and 0xff)) and 0xff
            crc = ((crc shl 8) xor crcTable[index]) and 0xffff
        }
        return crc xor 0xffff
    }

    private val crcTable: IntArray = IntArray(256) { value ->
        var crc = value shl 8
        repeat(8) {
            crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x1021) else (crc shl 1)
            crc = crc and 0xffff
        }
        crc
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.toByte()
        data[offset + 1] = (value ushr 8).toByte()
    }

    private fun put32(data: ByteArray, offset: Int, value: Long) {
        repeat(4) { index -> data[offset + index] = (value ushr (8 * index)).toByte() }
    }
}
