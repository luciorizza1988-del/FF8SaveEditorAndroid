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
    private const val ITEMS_OFFSET = 3236
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

    fun updateMagicStock(source: ByteArray, saveOffset: Int = 0, characterSlot: Int, spellIndex: Int, quantity: Int): EditResult? {
        if (characterSlot !in 0..7 || spellIndex !in 0..31 || quantity !in 0..100) return null
        return edit(source, saveOffset) { main ->
            val offset = CHARACTERS_OFFSET + characterSlot * CHARACTER_SIZE + 16 + spellIndex * 2
            put16(main, offset, quantity)
        }
    }

    /** Junction field: 0..8 stats, 9 elemental attack, 10 status attack, 11..14 elemental defense, 15..18 status defense. */
    fun updateJunction(source: ByteArray, saveOffset: Int = 0, characterSlot: Int, junctionField: Int, magicId: Int): EditResult? {
        if (characterSlot !in 0..7 || junctionField !in 0..18 || magicId !in 0..56) return null
        return edit(source, saveOffset) { main ->
            val offset = CHARACTERS_OFFSET + characterSlot * CHARACTER_SIZE
            val fieldOffset = when (junctionField) {
                in 0..8 -> 92 + junctionField
                9 -> 101
                10 -> 102
                in 11..14 -> 103 + junctionField - 11
                else -> 107 + junctionField - 15
            }
            main[offset + fieldOffset] = magicId.toByte()
        }
    }

    /** Sets item quantity by item ID, adding it to the first free entry when necessary. */
    fun updateItemQuantity(source: ByteArray, saveOffset: Int = 0, itemId: Int, quantity: Int): EditResult? {
        if (itemId !in 1..255 || quantity !in 0..99) return null
        return edit(source, saveOffset) { main ->
            val start = ITEMS_OFFSET + 32
            var target = -1
            var empty = -1
            repeat(198) { index ->
                val entry = u16(main, start + index * 2)
                val id = entry and 0xff
                if (id == itemId) target = index
                if (id == 0 && empty < 0) empty = index
            }
            if (quantity == 0) {
                if (target >= 0) put16(main, start + target * 2, itemId)
            } else {
                val index = if (target >= 0) target else empty
                if (index >= 0) put16(main, start + index * 2, itemId or (quantity shl 8))
            }
        }
    }

    fun updateCard(source: ByteArray, saveOffset: Int = 0, cardIndex: Int, quantity: Int, owned: Boolean): EditResult? {
        if (cardIndex !in 0..76 || quantity !in 0..127) return null
        return edit(source, saveOffset) { main ->
            val value = (if (owned) 0x80 else 0) or quantity
            main[4752 + cardIndex] = value.toByte()
        }
    }

    fun updateRareCard(source: ByteArray, saveOffset: Int = 0, rareIndex: Int, owned: Boolean): EditResult? {
        if (rareIndex !in 0..32) return null
        return edit(source, saveOffset) { main ->
            val offset = 4752 + 110 + rareIndex / 8
            val bit = 1 shl (rareIndex % 8)
            val current = main[offset].toInt() and 0xff
            main[offset] = (if (owned) current or bit else current and bit.inv()).toByte()
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
            val start = ITEMS_OFFSET + 32
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
                val characterIndex = main[3188 + partyIndex].toInt() and 0xff
                if (characterIndex in 0..7) {
                    val magicStart = CHARACTERS_OFFSET + characterIndex * CHARACTER_SIZE + 10
                    repeat(32) { spellIndex -> put16(main, magicStart + spellIndex * 2, 100) }
                }
            }
        }

    fun maximizeParty(source: ByteArray, saveOffset: Int = 0): EditResult? =
        edit(source, saveOffset) { main ->
            repeat(3) { partyIndex ->
                val characterIndex = main[PARTY_OFFSET + partyIndex].toInt() and 0xff
                if (characterIndex in 0..7) {
                    val offset = CHARACTERS_OFFSET + characterIndex * CHARACTER_SIZE
                    put16(main, offset, 9999)
                    put16(main, offset + 2, 9999)
                    put32(main, offset + 4, 1000000L)
                }
            }
        }


    /**
     * Junction fields store indices in Hyne's magic list. Magic ID 19 is Ultima in Hyne's magic list. All 9 stat slots and
     * elemental/status attack and defense slots are assigned to Ultima.
     */
    fun maximizeJunctionStats(source: ByteArray, saveOffset: Int = 0): EditResult? =
        edit(source, saveOffset) { main ->
            repeat(3) { partyIndex ->
                val characterIndex = main[PARTY_OFFSET + partyIndex].toInt() and 0xff
                if (characterIndex in 0..7) {
                    val offset = CHARACTERS_OFFSET + characterIndex * CHARACTER_SIZE
                    // HP, Strength, Vitality, Magic, Spirit, Speed, Evade, Hit, Luck
                    repeat(9) { main[offset + 92 + it] = 19 }
                    // Elemental attack and status attack
                    main[offset + 101] = 19
                    main[offset + 102] = 19
                    // Four elemental and four status defense junctions
                    repeat(4) { main[offset + 103 + it] = 19 }
                    repeat(4) { main[offset + 107 + it] = 19 }
                }
            }
        }

    /** Gives all 77 standard cards 127 copies and marks all 33 rare cards owned. */
    fun maximizeTripleTriad(source: ByteArray, saveOffset: Int = 0): EditResult? =
        edit(source, saveOffset) { main ->
            val cardsOffset = 4752
            repeat(77) { index ->
                main[cardsOffset + index] = 0xff.toByte() // owned flag + quantity 127
            }
            // TTCARDS.cards_rare: 33 rare-card ownership flags in 5 bytes.
            repeat(4) { index -> main[cardsOffset + 110 + index] = 0xff.toByte() }
            main[cardsOffset + 114] = 0x01
            // Rare card holders: 240 is Squall in Hyne's location encoding.
            repeat(33) { index -> main[cardsOffset + 77 + index] = 240.toByte() }
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
