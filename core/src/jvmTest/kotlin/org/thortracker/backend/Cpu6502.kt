package org.thortracker.backend

/**
 * Test-only 6502 (documented opcodes; decimal mode flags only, as neither player uses it) over a 64 KiB [mem], for the
 * golden tracers ([NesTracer], [SidTracer]): subclasses map [read]/[write] and run host calls with [call].
 */
abstract class Cpu6502 {
    protected val mem = IntArray(0x10000)
    protected var a = 0
    protected var x = 0
    protected var y = 0
    protected var s = 0xFD
    protected var p = 0x24
    protected var pc = 0

    protected abstract fun read(addr: Int): Int
    protected abstract fun write(addr: Int, v: Int)

    /** Runs from [address] with A = [a], X = [x] until the code returns to [returnTo] (pushed like a JSR's return). */
    protected fun call(address: Int, a: Int, x: Int, returnTo: Int = RETURN) {
        this.a = a
        this.x = x
        push((returnTo - 1) shr 8)
        push((returnTo - 1) and 0xff)
        runUntil(address, returnTo)
    }

    /** Runs from [address] until the program counter reaches [stop]. */
    protected fun runUntil(address: Int, stop: Int) {
        pc = address
        var steps = 0
        while (pc != stop) {
            step()
            check(++steps < 1_000_000) { "call at ${address.toString(16)} did not return" }
        }
    }

    protected fun push(v: Int) { mem[0x100 + s] = v and 0xff; s = (s - 1) and 0xff }
    protected fun pull(): Int { s = (s + 1) and 0xff; return mem[0x100 + s] }
    private fun fetch(): Int = read(pc).also { pc = (pc + 1) and 0xffff }
    private fun fetch16(): Int = fetch() or (fetch() shl 8)
    private fun read16zp(zp: Int) = read(zp and 0xff) or (read((zp + 1) and 0xff) shl 8)

    private fun setZN(v: Int): Int { p = (p and 0x7d) or (if (v and 0xff == 0) 2 else 0) or (v and 0x80); return v and 0xff }
    private fun flag(mask: Int, on: Boolean) { p = if (on) p or mask else p and mask.inv() }
    private val carry get() = p and 1

    private fun branch(cond: Boolean) {
        val off = fetch().toByte().toInt()
        if (cond) pc = (pc + off) and 0xffff
    }

    private fun adc(v: Int) {
        val r = a + v + carry
        flag(0x40, (a xor r) and (v xor r) and 0x80 != 0)
        flag(1, r > 0xff)
        a = setZN(r)
    }

    private fun cmp(reg: Int, v: Int) {
        val r = reg - v
        flag(1, r >= 0)
        setZN(r)
    }

    /** Effective address for the addressing mode in the opcode's low bits (cc=01 group) or explicit modes. */
    private fun addr(mode: Int): Int = when (mode) {
        ZP -> fetch()
        ZPX -> (fetch() + x) and 0xff
        ZPY -> (fetch() + y) and 0xff
        ABS -> fetch16()
        ABX -> (fetch16() + x) and 0xffff
        ABY -> (fetch16() + y) and 0xffff
        IZX -> read16zp(fetch() + x)
        IZY -> (read16zp(fetch()) + y) and 0xffff
        else -> error("mode $mode")
    }

    private fun step() {
        val op = fetch()
        when (op) {
            // Loads
            0xA9 -> a = setZN(fetch()); 0xA5 -> a = setZN(read(addr(ZP))); 0xB5 -> a = setZN(read(addr(ZPX)))
            0xAD -> a = setZN(read(addr(ABS))); 0xBD -> a = setZN(read(addr(ABX))); 0xB9 -> a = setZN(read(addr(ABY)))
            0xA1 -> a = setZN(read(addr(IZX))); 0xB1 -> a = setZN(read(addr(IZY)))
            0xA2 -> x = setZN(fetch()); 0xA6 -> x = setZN(read(addr(ZP))); 0xB6 -> x = setZN(read(addr(ZPY)))
            0xAE -> x = setZN(read(addr(ABS))); 0xBE -> x = setZN(read(addr(ABY)))
            0xA0 -> y = setZN(fetch()); 0xA4 -> y = setZN(read(addr(ZP))); 0xB4 -> y = setZN(read(addr(ZPX)))
            0xAC -> y = setZN(read(addr(ABS))); 0xBC -> y = setZN(read(addr(ABX)))
            // Stores
            0x85 -> write(addr(ZP), a); 0x95 -> write(addr(ZPX), a); 0x8D -> write(addr(ABS), a); 0x9D -> write(addr(ABX), a)
            0x99 -> write(addr(ABY), a); 0x81 -> write(addr(IZX), a); 0x91 -> write(addr(IZY), a)
            0x86 -> write(addr(ZP), x); 0x96 -> write(addr(ZPY), x); 0x8E -> write(addr(ABS), x)
            0x84 -> write(addr(ZP), y); 0x94 -> write(addr(ZPX), y); 0x8C -> write(addr(ABS), y)
            // Transfers and stack
            0xAA -> x = setZN(a); 0xA8 -> y = setZN(a); 0x8A -> a = setZN(x); 0x98 -> a = setZN(y)
            0xBA -> x = setZN(s); 0x9A -> s = x
            0x48 -> push(a); 0x68 -> a = setZN(pull()); 0x08 -> push(p or 0x30); 0x28 -> p = (pull() and 0xef) or 0x20
            // Logic and arithmetic
            0x29, 0x25, 0x35, 0x2D, 0x3D, 0x39, 0x21, 0x31 -> a = setZN(a and operand(op))
            0x09, 0x05, 0x15, 0x0D, 0x1D, 0x19, 0x01, 0x11 -> a = setZN(a or operand(op))
            0x49, 0x45, 0x55, 0x4D, 0x5D, 0x59, 0x41, 0x51 -> a = setZN(a xor operand(op))
            0x69, 0x65, 0x75, 0x6D, 0x7D, 0x79, 0x61, 0x71 -> adc(operand(op))
            0xE9, 0xE5, 0xF5, 0xED, 0xFD, 0xF9, 0xE1, 0xF1 -> adc(operand(op) xor 0xff)
            0xC9, 0xC5, 0xD5, 0xCD, 0xDD, 0xD9, 0xC1, 0xD1 -> cmp(a, operand(op))
            0xE0 -> cmp(x, fetch()); 0xE4 -> cmp(x, read(addr(ZP))); 0xEC -> cmp(x, read(addr(ABS)))
            0xC0 -> cmp(y, fetch()); 0xC4 -> cmp(y, read(addr(ZP))); 0xCC -> cmp(y, read(addr(ABS)))
            0x24, 0x2C -> {
                val v = read(addr(if (op == 0x24) ZP else ABS))
                p = (p and 0x3d) or (v and 0xc0) or (if (a and v == 0) 2 else 0)
            }
            // Increments
            0xE8 -> x = setZN(x + 1); 0xC8 -> y = setZN(y + 1); 0xCA -> x = setZN(x - 1); 0x88 -> y = setZN(y - 1)
            0xE6, 0xF6, 0xEE, 0xFE -> rmw(op) { setZN(it + 1) }
            0xC6, 0xD6, 0xCE, 0xDE -> rmw(op) { setZN(it - 1) }
            // Shifts
            0x0A -> { flag(1, a and 0x80 != 0); a = setZN(a shl 1) }
            0x4A -> { flag(1, a and 1 != 0); a = setZN(a shr 1) }
            0x2A -> { val c = carry; flag(1, a and 0x80 != 0); a = setZN((a shl 1) or c) }
            0x6A -> { val c = carry; flag(1, a and 1 != 0); a = setZN((a shr 1) or (c shl 7)) }
            0x06, 0x16, 0x0E, 0x1E -> rmw(op) { flag(1, it and 0x80 != 0); setZN(it shl 1) }
            0x46, 0x56, 0x4E, 0x5E -> rmw(op) { flag(1, it and 1 != 0); setZN(it shr 1) }
            0x26, 0x36, 0x2E, 0x3E -> rmw(op) { val c = carry; flag(1, it and 0x80 != 0); setZN((it shl 1) or c) }
            0x66, 0x76, 0x6E, 0x7E -> rmw(op) { val c = carry; flag(1, it and 1 != 0); setZN((it shr 1) or (c shl 7)) }
            // Jumps
            0x4C -> pc = fetch16()
            0x6C -> { val ptr = fetch16(); pc = read(ptr) or (read((ptr and 0xff00) or ((ptr + 1) and 0xff)) shl 8) }
            0x20 -> { val target = fetch16(); val ret = (pc - 1) and 0xffff; push(ret shr 8); push(ret and 0xff); pc = target }
            0x60 -> { pc = (pull() or (pull() shl 8)) + 1 and 0xffff }
            0x40 -> { p = (pull() and 0xef) or 0x20; pc = pull() or (pull() shl 8) }
            0x10 -> branch(p and 0x80 == 0); 0x30 -> branch(p and 0x80 != 0)
            0x50 -> branch(p and 0x40 == 0); 0x70 -> branch(p and 0x40 != 0)
            0x90 -> branch(p and 1 == 0); 0xB0 -> branch(p and 1 != 0)
            0xD0 -> branch(p and 2 == 0); 0xF0 -> branch(p and 2 != 0)
            // Flags
            0x18 -> flag(1, false); 0x38 -> flag(1, true); 0x58 -> flag(4, false); 0x78 -> flag(4, true)
            0xB8 -> flag(0x40, false); 0xD8 -> flag(8, false); 0xF8 -> flag(8, true)
            0xEA -> Unit
            else -> error("opcode ${op.toString(16)} at ${((pc - 1) and 0xffff).toString(16)} (BRK/undocumented: a driver fault)")
        }
    }

    /** Operand of a cc=01 group instruction (ORA/AND/EOR/ADC/CMP/SBC/LDA) by its addressing-mode bits. */
    private fun operand(op: Int): Int = when ((op shr 2) and 7) {
        0 -> read(addr(IZX)); 1 -> read(addr(ZP)); 2 -> fetch(); 3 -> read(addr(ABS))
        4 -> read(addr(IZY)); 5 -> read(addr(ZPX)); 6 -> read(addr(ABY)); else -> read(addr(ABX))
    }

    private inline fun rmw(op: Int, f: (Int) -> Int) {
        val ad = when ((op shr 2) and 7) { 1 -> addr(ZP); 3 -> addr(ABS); 5 -> addr(ZPX); else -> addr(ABX) }
        write(ad, f(read(ad)))
    }

    protected companion object {
        const val ZP = 1; const val ZPX = 2; const val ZPY = 3; const val ABS = 4; const val ABX = 5; const val ABY = 6
        const val IZX = 7; const val IZY = 8

        /** A return address outside the players (in unmapped space): reaching it ends a host call. */
        const val RETURN = 0x7000
    }
}
