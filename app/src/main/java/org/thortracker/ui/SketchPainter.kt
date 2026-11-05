package org.thortracker.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.ViewController
import org.thortracker.editor.channelCount
import org.thortracker.editor.instrument
import org.thortracker.editor.patternAt
import org.thortracker.model.Scale
import org.thortracker.model.Songs
import org.thortracker.model.TonalGuide

/**
 * Tonal playground: the current key's chords and scale tones as pads that only ever sound. Nothing here
 * writes to the song, opens an undo group or reaches export. A settings strip picks the key (following the
 * pattern's scale until changed, with a button back to it), the preview channel, sound and octave. Holding a
 * chord repeats it as an arpeggio, holding a note sustains it; the selected chord's tones are marked on the
 * note pads with their role, so the two rows read as one picture.
 */
internal class SketchPainter(v: TrackerView) : Painter(v) {
    private val stripCells = Array(ViewController.SKETCH_FIELDS.size) { RectF() }
    private val keyButton = RectF()
    private val chordRects = Array(MAX_PADS) { RectF() }
    private val toneRects = Array(MAX_PADS) { RectF() }
    private val chooseScale = RectF()
    private val inset = RectF()
    private val weights = floatArrayOf(0.9f, 1.9f, 1.2f, 1.9f, 0.9f)
    private val buttonWeight = 1.5f
    private var chordCount = 0
    private var toneCount = 0
    private var chromaticShown = false

    // Strings derived from the guide and octave, rebuilt only when either changes.
    private var cachedGuide: TonalGuide? = null
    private var chordTones: Array<String> = emptyArray()
    private var chordsTitle = ""
    private val toneNotes = IntArray(MAX_PADS) { Int.MIN_VALUE }
    private val toneNames = Array(MAX_PADS) { "" }
    /** Per tone: 0 = not in the selected chord, 1 = its root, 2 = third, 3 = fifth. */
    private val toneRole = IntArray(MAX_PADS)

    // touch
    private var touchStrip = -1
    private var touchButton = false
    private var touchChoose = false
    private var touchRow = -1
    private var touchIndex = -1

    // feedback: a struck pad flashes, a released pad keeps a short afterglow, a key change pulses the key button
    private var lit = -1
    private var strikeStart = Motion.OFF
    private var released = -1
    private var releaseStart = Motion.OFF
    private var seenLocal: Boolean? = null
    private var keyChangeStart = Motion.OFF

    override fun draw(c: Canvas, area: RectF) {
        val s = song
        val u = ink.unit
        val pad = u * 0.6f
        val guide = ctl.sketchGuide
        val scale = guide.scale
        val local = st.sketchScale != null
        if (seenLocal != null && seenLocal != local) keyChangeStart = motion.start()
        seenLocal = local
        rebuild(guide)

        val ch = st.channel.coerceIn(0, s.channelCount() - 1)
        val kind = Songs.channelKind(s, ch)
        val col = Palette.channel(ch)
        val channelName = Songs.channelNames(s)[ch]
        val soundName = s.instrument(kind, st.instrument)?.name ?: ""

        // What sounds: the touched pad, else the controller's row while the controller audition is held.
        val auditioning = ctl.sketchAuditioning
        val soundRow = if (touchRow >= 0) touchRow else st.sketchRow
        val sounding = when {
            !auditioning && touchRow < 0 -> -1
            soundRow == ROW_CHORDS && st.sketchChord in 0 until chordCount -> padId(ROW_CHORDS, st.sketchChord)
            soundRow == ROW_NOTES && st.sketchTone in 0 until toneCount -> padId(ROW_NOTES, st.sketchTone)
            else -> -1
        }
        if (sounding != lit) {
            if (lit >= 0) { released = lit; releaseStart = motion.start() }
            if (sounding >= 0) strikeStart = motion.start()
            lit = sounding
        }
        val strike = motion.fade(strikeStart, 200)
        val after = if (released >= 0) motion.fade(releaseStart, 260) else 0f

        // header: always listen-only, which key is in use, and the sound being previewed
        val patternNo = s.patternAt(st.order)
        val keyText = if (local) "Local key ${scale.label} (pattern ${hex2(patternNo)}: ${ctl.patternScale.label})"
        else "Pattern key ${scale.label} (pattern ${hex2(patternNo)})"
        val detail = "$keyText  ·  $channelName sound ${hex2(st.instrument)} $soundName"
        val glow = if (sounding >= 0 && strike > 0f) Palette.alpha(Palette.LISTEN, (strike * 64).toInt()) else 0
        val top = header(c, area, "Sketch · Listen only", detail, glow = glow)
        ink.rect(c, area.left, top - ink.dp(3f), area.right, top, Palette.LISTEN)

        // settings strip plus the pattern-key button
        val stripH = u * 2.6f
        val stripTop = top + pad
        val avail = area.width() - pad * 2
        val total = weights.sum() + buttonWeight
        var x = area.left + pad
        for (i in stripCells.indices) {
            val w = avail * weights[i] / total
            val r = stripCells[i]
            r.set(x + 2, stripTop, x + w - 2, stripTop + stripH)
            val value = when (i) {
                F_ROOT -> Scale.ROOT_NAMES[Math.floorMod(scale.root, 12)]
                F_SCALE -> scale.kind.label
                F_CHANNEL -> channelName
                F_SOUND -> "${hex2(st.instrument)} $soundName"
                else -> "${st.octave}"
            }
            drawStripCell(c, r, i, value, if (i == F_CHANNEL) col else Palette.TEXT)
            x += w
        }
        drawKeyButton(c, x + 2, stripTop, area.right - pad - 2, stripTop + stripH, local)

        // chords and notes share the remaining height; notes get the larger share for touch
        val headingH = u * 1.5f
        var y = stripTop + stripH + pad
        val remaining = area.bottom - pad - y - headingH * 2 - pad
        val chordH = (remaining * 0.38f).coerceIn(u * 4.2f, u * 6.5f)
        heading(c, area, y, headingH, ROW_CHORDS, chordsTitle, if (chordCount > 0) "Hold to hear it arpeggiated" else "")
        y += headingH
        drawChords(c, area.left + pad, y, area.right - pad, y + chordH, guide, sounding, strike, after)
        y += chordH + pad
        heading(c, area, y, headingH, ROW_NOTES, "Notes from octave ${st.octave}", "Hold to hear  ·  marked: tones of the selected chord")
        y += headingH
        drawTones(c, area.left + pad, y, area.right - pad, maxOf(y + u * 3f, area.bottom - pad), guide, col, sounding, strike, after)
    }

    private fun drawStripCell(c: Canvas, r: RectF, i: Int, value: String, valueColor: Int) {
        val u = ink.unit
        val h = r.height()
        val focused = st.sketchRow == ROW_SETTINGS && st.sketchField == i
        ink.round(c, r, ink.dp(4f), if (focused) Palette.CURSOR_FILL else Palette.LANE)
        // The halves step down / up; the arrows sit on the edges to say so.
        ink.label(c, "‹", r.left + u * 0.45f, r.top, h, if (focused) Palette.TEXT else Palette.FAINT, true, u * 1.2f)
        ink.label(c, "›", r.right - u * 0.45f, r.top, h, if (focused) Palette.TEXT else Palette.FAINT, true, u * 1.2f, Paint.Align.RIGHT)
        ink.label(c, ViewController.SKETCH_FIELDS[i], r.centerX(), r.top + h * 0.04f, h * 0.42f, Palette.DIM, size = u * 0.8f, align = Paint.Align.CENTER)
        ink.label(c, ink.fit(value, r.width() - u * 2.6f, bold = true), r.centerX(), r.top + h * 0.36f, h * 0.64f, valueColor, true, align = Paint.Align.CENTER)
        if (focused) ink.outline(c, r, cursorColor(), ink.dp(2f), ink.dp(4f))
    }

    private fun drawKeyButton(c: Canvas, l: Float, t: Float, r: Float, b: Float, local: Boolean) {
        val u = ink.unit
        keyButton.set(l, t, r, b)
        val pulse = motion.fade(keyChangeStart, 380)
        val base = if (local) Palette.alpha(Palette.PLAYHEAD, 60) else Palette.LANE
        ink.round(c, keyButton, ink.dp(4f), base)
        if (pulse > 0f) ink.round(c, keyButton, ink.dp(4f), Palette.alpha(Palette.PLAYHEAD, (pulse * 90).toInt()))
        if (local) ink.outline(c, keyButton, Palette.PLAYHEAD, ink.dp(1.5f), ink.dp(4f))
        val h = keyButton.height()
        val w = keyButton.width() - u
        ink.label(c, ink.fit(if (local) "Local key · tap to reset" else "Key follows pattern", w, u * 0.8f), keyButton.centerX(), t + h * 0.04f, h * 0.42f,
            Palette.DIM, size = u * 0.8f, align = Paint.Align.CENTER)
        ink.label(c, ink.fit(if (local) "↺ Pattern key" else "✓ Pattern key", w, bold = true), keyButton.centerX(), t + h * 0.36f, h * 0.64f,
            if (local) Palette.PLAYHEAD else Palette.DIM, true, align = Paint.Align.CENTER)
    }

    /** A section title; the row the controller is on gets a cursor-coloured bar and bright text. */
    private fun heading(c: Canvas, area: RectF, top: Float, h: Float, row: Int, title: String, detail: String) {
        val u = ink.unit
        val active = st.sketchRow == row
        val x = area.left + u * 0.6f
        if (active) ink.rect(c, x, top + h * 0.2f, x + ink.dp(3f), top + h * 0.85f, cursorColor())
        val tx = x + u * 0.6f
        ink.label(c, title, tx, top, h, if (active) Palette.TEXT else Palette.DIM, true, u * 0.95f)
        if (detail.isEmpty()) return
        val room = area.right - u * 0.6f - (tx + ink.labelWidth(title, u * 0.95f, true) + u * 1.5f)
        if (room > u * 4f) ink.label(c, ink.fit(detail, room, u * 0.8f), area.right - u * 0.6f, top, h, Palette.FAINT, size = u * 0.8f, align = Paint.Align.RIGHT)
    }

    private fun drawChords(c: Canvas, l: Float, t: Float, r: Float, b: Float, guide: TonalGuide, sounding: Int, strike: Float, after: Float) {
        val u = ink.unit
        val chords = guide.chords
        chordCount = minOf(chords.size, MAX_PADS)
        chromaticShown = chordCount == 0
        if (chromaticShown) {
            // Without a diatonic key there is no meaningful chord shortlist; all twelve pitches remain playable.
            chooseScale.set(l, t, r, b)
            ink.round(c, chooseScale, ink.dp(6f), Palette.LANE)
            ink.outline(c, chooseScale, if (st.sketchRow == ROW_CHORDS) cursorColor() else Palette.RULE, ink.dp(if (st.sketchRow == ROW_CHORDS) 2f else 1f), ink.dp(6f))
            val h = chooseScale.height()
            val w = chooseScale.width() - u * 2
            ink.label(c, ink.fit("Choose a scale to explore chords", w, u * 1.1f, true), chooseScale.centerX(), t + h * 0.18f, h * 0.34f,
                Palette.TEXT, true, u * 1.1f, Paint.Align.CENTER)
            ink.label(c, ink.fit("Chromatic keeps all 12 notes available. Tap here to choose a key.", w), chooseScale.centerX(), t + h * 0.52f, h * 0.3f,
                Palette.PLAYHEAD, align = Paint.Align.CENTER)
            return
        }
        val cw = (r - l) / maxOf(chordCount, 7)
        for (i in 0 until chordCount) {
            val chord = chords[i]
            val rect = chordRects[i]
            rect.set(l + i * cw + ink.dp(2f), t, l + (i + 1) * cw - ink.dp(2f), b)
            val id = padId(ROW_CHORDS, i)
            val selected = i == st.sketchChord
            val base = if (selected) Palette.CURSOR_FILL else Palette.BAR
            val isOn = id == sounding
            val fill = when {
                isOn -> if (strike > 0f) Palette.mix(Palette.LISTEN, Palette.TEXT, 0.55f * strike) else Palette.LISTEN
                id == released && after > 0f -> Palette.mix(base, Palette.LISTEN, 0.7f * after)
                else -> base
            }
            ink.round(c, rect, ink.dp(5f), fill)
            val h = rect.height()
            val w = rect.width() - u * 0.6f
            val strong = if (isOn) Palette.BG else Palette.TEXT
            val soft = if (isOn) Palette.BG else Palette.DIM
            ink.label(c, ink.fit(chord.degree, w, u * 1.5f, true), rect.centerX(), rect.top + h * 0.06f, h * 0.38f, strong, true, u * 1.5f, Paint.Align.CENTER)
            ink.label(c, ink.fit(chord.name, w, u * 1.05f, true), rect.centerX(), rect.top + h * 0.42f, h * 0.26f, if (isOn) Palette.BG else Palette.TEXT, true, u * 1.05f, Paint.Align.CENTER)
            ink.mono(c, chordTones[i], rect.centerX(), rect.top + h * 0.68f, h * 0.24f, soft, size = u * 0.8f, align = Paint.Align.CENTER)
            focus(c, rect, ROW_CHORDS, selected)
        }
        // Pentatonic keys hold only a few chords; explain the gap instead of leaving it blank.
        val gapL = l + chordCount * cw + u * 0.6f
        if (chordCount < 7 && r - gapL > u * 8f) {
            val h = b - t
            ink.label(c, ink.fit("Only triads made of this scale's own tones", r - gapL), gapL, t + h * 0.2f, h * 0.3f, Palette.DIM, size = u * 0.85f)
            ink.label(c, ink.fit("No notes are added from outside the key", r - gapL), gapL, t + h * 0.5f, h * 0.3f, Palette.FAINT, size = u * 0.85f)
        }
    }

    private fun drawTones(c: Canvas, l: Float, t: Float, r: Float, b: Float, guide: TonalGuide, col: Int, sounding: Int, strike: Float, after: Float) {
        val u = ink.unit
        val tones = guide.tones
        toneCount = minOf(tones.size, MAX_PADS)
        val chordAudition = sounding >= 0 && sounding / MAX_PADS == ROW_CHORDS
        val pw = (r - l) / maxOf(toneCount, 1)
        val nameSize = minOf(u * 1.4f, pw * 0.26f)
        for (i in 0 until toneCount) {
            val tone = tones[i]
            val rect = toneRects[i]
            rect.set(l + i * pw + ink.dp(2f), t, l + (i + 1) * pw - ink.dp(2f), b)
            val id = padId(ROW_NOTES, i)
            val role = toneRole[i]
            val selected = i == st.sketchTone
            val base = when {
                role != 0 && chordAudition -> Palette.mix(Palette.BAR, Palette.LISTEN, 0.4f)
                role != 0 -> Palette.mix(Palette.BAR, col, 0.26f)
                selected -> Palette.CURSOR_FILL
                else -> Palette.BAR
            }
            val isOn = id == sounding
            val fill = when {
                isOn -> if (strike > 0f) Palette.mix(Palette.LISTEN, Palette.TEXT, 0.55f * strike) else Palette.LISTEN
                id == released && after > 0f -> Palette.mix(base, Palette.LISTEN, 0.7f * after)
                else -> base
            }
            ink.round(c, rect, ink.dp(5f), fill)
            val h = rect.height()
            // chord membership band along the top: thick for the chord root
            if (role != 0) {
                val band = if (chordAudition) Palette.LISTEN else col
                ink.rect(c, rect.left + ink.dp(4f), rect.top + ink.dp(3f), rect.right - ink.dp(4f), rect.top + ink.dp(if (role == 1) 9f else 6f), band)
            }
            val playable = toneNotes[i] in 0..Songs.NOTE_MAX
            val strong = when { isOn -> Palette.BG; !playable -> Palette.FAINT; else -> Palette.TEXT }
            val nameTop = rect.top + minOf(h * 0.12f, u * 2f)
            ink.mono(c, toneNames[i], rect.centerX(), nameTop, u * 2f, strong, true, nameSize, Paint.Align.CENTER)
            val degreeColor = when { isOn -> Palette.BG; tone.interval == 0 -> Palette.TEXT; else -> Palette.DIM }
            ink.label(c, tone.degree, rect.centerX(), nameTop + u * 2f, u * 1.6f, degreeColor, tone.interval == 0, u * 1.05f, Paint.Align.CENTER)
            if (role != 0 && h > u * 6f) {
                ink.label(c, ROLES[role], rect.centerX(), rect.bottom - u * 2f, u * 1.6f, if (isOn) Palette.BG else if (chordAudition) Palette.LISTEN else col,
                    role == 1, u * 0.9f, Paint.Align.CENTER)
            }
            focus(c, rect, ROW_NOTES, selected)
        }
    }

    /** The selected pad of a row: a strong cursor when the controller is on that row, a quiet mark otherwise. */
    private fun focus(c: Canvas, rect: RectF, row: Int, selected: Boolean) {
        if (!selected) return
        inset.set(rect.left + ink.dp(2f), rect.top + ink.dp(2f), rect.right - ink.dp(2f), rect.bottom - ink.dp(2f))
        if (st.sketchRow == row) ink.outline(c, inset, cursorColor(), ink.dp(3f), ink.dp(4f))
        else ink.outline(c, inset, Palette.FAINT, ink.dp(1f), ink.dp(4f))
    }

    private fun cursorColor() = when {
        ctl.sketchAuditioning || touchRow >= 0 -> Palette.LISTEN
        v.controllerFocused -> Palette.PLAYHEAD
        else -> Palette.DIM
    }

    /** Refreshes guide-derived strings, the note names at the current octave and the selected chord's roles. */
    private fun rebuild(guide: TonalGuide) {
        if (guide !== cachedGuide) {
            cachedGuide = guide
            val root = Math.floorMod(guide.scale.root, 12)
            chordTones = Array(guide.chords.size) { i -> guide.chords[i].intervals.joinToString(" ") { Scale.ROOT_NAMES[(root + it) % 12] } }
            chordsTitle = if (guide.chords.isEmpty()) "Chords" else "Chords in ${guide.scale.label}"
        }
        val n = minOf(guide.tones.size, MAX_PADS)
        for (i in 0 until n) {
            val note = ctl.sketchNote(i)
            if (note != toneNotes[i]) {
                toneNotes[i] = note
                toneNames[i] = if (note in 0..Songs.NOTE_MAX) Songs.noteName(note) else "—"
            }
        }
        val chord = guide.chords.getOrNull(st.sketchChord)
        for (i in 0 until n) {
            val pc = guide.tones[i].interval
            var role = 0
            if (chord != null) for (k in chord.intervals.indices) if (Math.floorMod(chord.intervals[k], 12) == pc) { role = k + 1; break }
            toneRole[i] = role
        }
    }

    // ---- touch ---------------------------------------------------------------------------------------

    override fun down(x: Float, y: Float): Boolean {
        for (i in stripCells.indices) if (stripCells[i].contains(x, y)) {
            touchStrip = i
            ctl.sketchSetField(i)
            return true
        }
        if (keyButton.contains(x, y)) { touchButton = true; return true }
        if (chromaticShown && chooseScale.contains(x, y)) { touchChoose = true; return true }
        val chord = hit(chordRects, chordCount, x, y)
        if (chord >= 0) { strikeChord(chord); return true }
        val tone = hit(toneRects, toneCount, x, y)
        if (tone >= 0) { strikeTone(tone); return true }
        return false
    }

    override fun move(x: Float, y: Float) {
        if (touchRow < 0) return
        // Glide: the pad under the finger takes over the audition; empty space keeps the last one sounding.
        val chord = hit(chordRects, chordCount, x, y)
        if (chord >= 0) { if (touchRow != ROW_CHORDS || touchIndex != chord) strikeChord(chord); return }
        val tone = hit(toneRects, toneCount, x, y)
        if (tone >= 0 && (touchRow != ROW_NOTES || touchIndex != tone)) strikeTone(tone)
    }

    override fun up(x: Float, y: Float) {
        when {
            touchStrip >= 0 -> {
                val r = stripCells[touchStrip]
                if (r.contains(x, y)) ctl.sketchAdjustField(if (x > r.centerX()) 1 else -1)
            }
            touchButton -> if (keyButton.contains(x, y)) ctl.sketchUsePatternKey()
            touchChoose -> if (chooseScale.contains(x, y)) ctl.sketchSetField(F_SCALE)
        }
        endTouch()
    }

    override fun cancel() = endTouch()

    private fun strikeChord(i: Int) {
        touchRow = ROW_CHORDS
        touchIndex = i
        ctl.sketchSelectChord(i)
        ctl.sketchListen(true)
    }

    private fun strikeTone(i: Int) {
        touchRow = ROW_NOTES
        touchIndex = i
        ctl.sketchSelectTone(i)
        ctl.sketchListen(false)
    }

    private fun endTouch() {
        touchStrip = -1
        touchButton = false
        touchChoose = false
        if (touchRow >= 0) {
            touchRow = -1
            touchIndex = -1
            ctl.sketchRelease()
        }
    }

    private fun hit(rects: Array<RectF>, count: Int, x: Float, y: Float): Int {
        for (i in 0 until count) if (rects[i].contains(x, y)) return i
        return -1
    }

    private fun padId(row: Int, index: Int) = row * MAX_PADS + index

    private companion object {
        const val MAX_PADS = 12
        const val ROW_SETTINGS = 0
        const val ROW_CHORDS = 1
        const val ROW_NOTES = 2
        const val F_ROOT = 0
        const val F_SCALE = 1
        const val F_CHANNEL = 2
        const val F_SOUND = 3
        val ROLES = arrayOf("", "root", "3rd", "5th")
    }
}
