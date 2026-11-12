package org.thortracker.backend

/**
 * Fixed-size transport for app/src/main/cpp/gba. All integers are little-endian.
 * Reserved header bytes and unused image tail are zero. The bank is an ordinary GBA Maxmod
 * soundbank (one MAS v0x18 module, instrument/sample/pattern tables), not a private player format.
 * Offsets inside its module are relative to the byte after the eight-byte MAS block header.
 */
object GbaPlayerImage {
    const val SIZE = 2 * 1024 * 1024
    const val VERSION = 1
    const val CHANNELS = 8
    const val HEADER_SIZE = 64
    const val OFF_MAGIC = 0
    const val OFF_VERSION = 4
    const val OFF_LENGTH = 8
    const val OFF_BANK_OFFSET = 12
    const val OFF_BANK_LENGTH = 16
    const val OFF_MODULE_ID = 20
    const val BANK_OFFSET = HEADER_SIZE
    const val MIX_RATE = 15768
    const val MAS_VERSION = 0x18
}
