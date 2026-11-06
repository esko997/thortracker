package org.thortracker.ui

import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession.Companion.hex1
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.ViewController
import org.thortracker.editor.channelCount
import org.thortracker.editor.instrument
import org.thortracker.editor.patternAt
import org.thortracker.model.Scale
import org.thortracker.model.Songs
import org.thortracker.model.TonalGuide
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Tonal playground: the current key's chords (or the circle of fifths), its scale tones, and a generated
 * phrase. Chords, notes and the circle only ever sound; only the phrase's Insert writes to the song (one
 * undo step at the pattern cursor). A settings strip picks the key (following the pattern's scale until
 * changed, with a button back to it), the preview channel, sound, octave and the chords display. Holding a
 * chord repeats it as an arpeggio, holding a note sustains it, holding the phrase loops it at song tempo; the
 * selected chord's tones are marked on the note pads with their role, so the rows read as one picture.
 */
internal class SketchPainter(v: PaneView) : Painter(v) {
    private val stripCells = Array(ViewController.SKETCH_FIELDS.size) { Rect() }
    private val keyButton = Rect()
    private val chordRects = Array(MAX_PADS) { Rect() }
    private val toneRects = Array(MAX_PADS) { Rect() }
    private val chooseScale = Rect()
    private val inset = Rect()
    private val phraseStrip = Rect()
    /** Phrase controls by [ViewController.PHRASE_FIELDS] index; index 0 (the strip) uses [phraseStrip]. */
    private val phraseCells = Array(ViewController.PHRASE_FIELDS.size) { Rect() }
    private val weights = floatArrayOf(0.9f, 1.9f, 1.2f, 1.9f, 0.9f, 1.2f)
    private val buttonWeight = 1.5f
    private val phraseWeights = floatArrayOf(0f, 0.85f, 1.15f, 1f, 1.05f, 1f, 0.85f, 1f, 1f, 1f, 1f)
    private var chordCount = 0
    private var toneCount = 0
    private var chromaticShown = false

    // circle geometry, kept for touch
    private var circleShown = false
    private var circleX = 0f
    private var circleY = 0f
    private var circleR = 0f

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
    private var touchPhrase = -1
    private var touchRow = -1
    private var touchIndex = -1
    private var touchGlided = false

    // feedback: a struck pad flashes, a released pad keeps a short afterglow, a key change pulses the key button
    private var lit = -1
    private var strikeStart = Motion.OFF
    private var released = -1
    private var releaseStart = Motion.OFF
    private var seenLocal: Boolean? = null
    private var keyChangeStart = Motion.OFF

    override fun draw(c: Surface, area: Rect) {
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
            soundRow == ROW_PHRASE -> padId(ROW_PHRASE, 0)
            soundRow == ROW_NOTES -> if (st.sketchTone in 0 until toneCount) padId(ROW_NOTES, st.sketchTone) else -1
            st.sketchCircle -> padId(ROW_CHORDS, circleIndex(st.sketchCircleKey, st.sketchCircleMinor))
            st.sketchChord in 0 until chordCount -> padId(ROW_CHORDS, st.sketchChord)
            else -> -1
        }
        if (sounding != lit) {
            if (lit >= 0) { released = lit; releaseStart = motion.start() }
            if (sounding >= 0) strikeStart = motion.start()
            lit = sounding
        }
        val strike = motion.fade(strikeStart, 200)
        val after = if (released >= 0) motion.fade(releaseStart, 260) else 0f

        // header: which key is in use, the sound being previewed, and the tuning caveat for tuned songs
        val patternNo = s.patternAt(st.order)
        val keyText = if (local) "Local key ${scale.label} (pattern ${hex2(patternNo)}: ${ctl.patternScale.label})"
        else "Pattern key ${scale.label} (pattern ${hex2(patternNo)})"
        val tuned = s.tuning?.let { "12-note reference; song tuned to ${it.name}  ·  " } ?: ""
        val detail = "$tuned$keyText  ·  $channelName sound ${hex2(st.instrument)} $soundName"
        val glow = if (sounding >= 0 && strike > 0f) Palette.alpha(Palette.LISTEN, (strike * 64).toInt()) else 0
        val top = header(c, area, "Sketch", detail, glow = glow)
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
                F_ROOT -> Scale.ROOT_NAMES[scale.root.mod(12)]
                F_SCALE -> scale.kind.label
                F_CHANNEL -> channelName
                F_SOUND -> "${hex2(st.instrument)} $soundName"
                ViewController.SKETCH_CHORDS_FIELD -> if (st.sketchCircle) "Circle" else "Triads"
                else -> "${st.octave}"
            }
            drawStripCell(c, r, i, value, if (i == F_CHANNEL) col else Palette.TEXT)
            x += w
        }
        drawKeyButton(c, x + 2, stripTop, area.right - pad - 2, stripTop + stripH, local)

        // Chords (or the circle), notes and the phrase share the remaining height; the phrase has a fixed
        // height, the circle takes what it can, and notes keep the larger share otherwise for touch.
        val headingH = u * 1.5f
        val phraseH = u * 5.6f
        var y = stripTop + stripH + pad
        val remaining = area.bottom - pad - y - headingH * 3 - pad * 2 - phraseH
        val chordH = if (st.sketchCircle) (remaining - u * 4.6f).coerceIn(u * 4.2f, u * 16f)
        else (remaining * 0.4f).coerceIn(u * 4.2f, u * 6.5f)
        if (st.sketchCircle) {
            heading(c, area, y, headingH, ROW_CHORDS, "Circle of fifths", "Outer ring: major keys  ·  inner ring: relative minors  ·  IV and V of the current key marked")
            y += headingH
            drawCircle(c, area.left + pad, y, area.right - pad, y + chordH, guide, sounding, strike, after)
        } else {
            heading(c, area, y, headingH, ROW_CHORDS, chordsTitle, if (chordCount > 0) "Hold to hear it arpeggiated" else "")
            y += headingH
            drawChords(c, area.left + pad, y, area.right - pad, y + chordH, guide, sounding, strike, after)
        }
        y += chordH + pad
        heading(c, area, y, headingH, ROW_NOTES, "Notes from octave ${st.octave}",
            if (st.sketchCircle) "Hold to hear" else "Hold to hear  ·  marked: tones of the selected chord")
        y += headingH
        val notesBottom = maxOf(y + u * 3f, area.bottom - pad - headingH - pad - phraseH)
        drawTones(c, area.left + pad, y, area.right - pad, notesBottom, guide, col, sounding, strike, after)
        y = notesBottom + pad
        val phrase = ctl.sketchPhrase
        val sel = session.selection
        heading(c, area, y, headingH, ROW_PHRASE, "Phrase · ${phrase.length} rows",
            "Insert writes to pattern ${hex2(s.patternAt(sel.order))} row ${hex2(sel.row)}, $channelName  ·  hold to loop at song tempo")
        y += headingH
        drawPhrase(c, area.left + pad, y, area.right - pad, y + phraseH, guide, col, sounding, strike)
    }

    private fun drawStripCell(c: Surface, r: Rect, i: Int, value: String, valueColor: Int) {
        val u = ink.unit
        val h = r.height()
        val focused = st.sketchRow == ROW_SETTINGS && st.sketchField == i
        ink.round(c, r, ink.dp(4f), if (focused) Palette.CURSOR_FILL else Palette.LANE)
        // The halves step down / up; the arrows sit on the edges to say so.
        ink.label(c, "‹", r.left + u * 0.45f, r.top, h, if (focused) Palette.TEXT else Palette.FAINT, true, u * 1.2f)
        ink.label(c, "›", r.right - u * 0.45f, r.top, h, if (focused) Palette.TEXT else Palette.FAINT, true, u * 1.2f, Align.RIGHT)
        ink.label(c, ViewController.SKETCH_FIELDS[i], r.centerX(), r.top + h * 0.04f, h * 0.42f, Palette.DIM, size = u * 0.8f, align = Align.CENTER)
        ink.label(c, ink.fit(value, r.width() - u * 2.6f, bold = true), r.centerX(), r.top + h * 0.36f, h * 0.64f, valueColor, true, align = Align.CENTER)
        if (focused) ink.outline(c, r, cursorColor(), ink.dp(2f), ink.dp(4f))
    }

    private fun drawKeyButton(c: Surface, l: Float, t: Float, r: Float, b: Float, local: Boolean) {
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
            Palette.DIM, size = u * 0.8f, align = Align.CENTER)
        ink.label(c, ink.fit(if (local) "↺ Pattern key" else "✓ Pattern key", w, bold = true), keyButton.centerX(), t + h * 0.36f, h * 0.64f,
            if (local) Palette.PLAYHEAD else Palette.DIM, true, align = Align.CENTER)
    }

    /** A section title; the row the controller is on gets a cursor-coloured bar and bright text. */
    private fun heading(c: Surface, area: Rect, top: Float, h: Float, row: Int, title: String, detail: String) {
        val u = ink.unit
        val active = st.sketchRow == row
        val x = area.left + u * 0.6f
        if (active) ink.rect(c, x, top + h * 0.2f, x + ink.dp(3f), top + h * 0.85f, cursorColor())
        val tx = x + u * 0.6f
        ink.label(c, title, tx, top, h, if (active) Palette.TEXT else Palette.DIM, true, u * 0.95f)
        if (detail.isEmpty()) return
        val room = area.right - u * 0.6f - (tx + ink.labelWidth(title, u * 0.95f, true) + u * 1.5f)
        if (room > u * 4f) ink.label(c, ink.fit(detail, room, u * 0.8f), area.right - u * 0.6f, top, h, Palette.FAINT, size = u * 0.8f, align = Align.RIGHT)
    }

    private fun drawChords(c: Surface, l: Float, t: Float, r: Float, b: Float, guide: TonalGuide, sounding: Int, strike: Float, after: Float) {
        val u = ink.unit
        val chords = guide.chords
        circleShown = false
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
                Palette.TEXT, true, u * 1.1f, Align.CENTER)
            ink.label(c, ink.fit("Chromatic keeps all 12 notes available. Tap here to choose a key.", w), chooseScale.centerX(), t + h * 0.52f, h * 0.3f,
                Palette.PLAYHEAD, align = Align.CENTER)
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
            ink.label(c, ink.fit(chord.degree, w, u * 1.5f, true), rect.centerX(), rect.top + h * 0.06f, h * 0.38f, strong, true, u * 1.5f, Align.CENTER)
            ink.label(c, ink.fit(chord.name, w, u * 1.05f, true), rect.centerX(), rect.top + h * 0.42f, h * 0.26f, if (isOn) Palette.BG else Palette.TEXT, true, u * 1.05f, Align.CENTER)
            ink.mono(c, chordTones[i], rect.centerX(), rect.top + h * 0.68f, h * 0.24f, soft, size = u * 0.8f, align = Align.CENTER)
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

    /**
     * Twelve major keys clockwise by fifths from C at the top, their relative minors on the inner ring. The current
     * Sketch key is filled amber, its IV and V neighbours (on the same ring) are tinted and labelled, and the
     * controller's circle cursor is outlined. A side panel describes the key under the cursor.
     */
    private fun drawCircle(c: Surface, l: Float, t: Float, r: Float, b: Float, guide: TonalGuide, sounding: Int, strike: Float, after: Float) {
        val u = ink.unit
        circleShown = true
        chordCount = 0
        chromaticShown = false
        val h = b - t
        val radius = minOf(h, (r - l) * 0.5f) / 2f - ink.dp(2f)
        val cx = l + u * 0.5f + radius
        val cy = t + h / 2f
        circleX = cx; circleY = cy; circleR = radius
        val focusedRow = st.sketchRow == ROW_CHORDS
        c.fillCircle(cx, cy, radius, Palette.LANE)
        c.strokeCircle(cx, cy, radius, if (focusedRow) Palette.alpha(cursorColor(), 140) else Palette.RULE, ink.dp(if (focusedRow) 2f else 1f))
        c.strokeCircle(cx, cy, radius * RING_SPLIT, Palette.RULE, ink.dp(1f))

        val place = ctl.sketchCirclePlace(guide.scale)
        for (ring in 0..1) {
            val minor = ring == 1
            val ringR = radius * if (minor) 0.43f else 0.81f
            val dot = if (minor) minOf(radius * 0.13f, u * 1.1f) else minOf(radius * 0.17f, u * 1.4f)
            for (pos in 0 until 12) {
                val angle = (-90.0 + 30.0 * pos) * (kotlin.math.PI / 180.0)
                val x = cx + ringR * cos(angle).toFloat()
                val y = cy + ringR * sin(angle).toFloat()
                val current = place != null && place.first == pos && place.second == minor
                val step = if (place != null && place.second == minor) (pos - place.first).mod(12) else -1
                val mark = when (step) { 1 -> if (minor) "v" else "V"; 11 -> if (minor) "iv" else "IV"; else -> "" }
                val id = padId(ROW_CHORDS, circleIndex(pos, minor))
                val isOn = id == sounding
                val base = when {
                    current -> Palette.mix(Palette.BAR, Palette.PLAYHEAD, 0.55f)
                    mark.isNotEmpty() -> Palette.mix(Palette.BAR, Palette.PLAYHEAD, 0.22f)
                    else -> Palette.BAR
                }
                c.fillCircle(x, y, dot, when {
                    isOn -> if (strike > 0f) Palette.mix(Palette.LISTEN, Palette.TEXT, 0.55f * strike) else Palette.LISTEN
                    id == released && after > 0f -> Palette.mix(base, Palette.LISTEN, 0.7f * after)
                    else -> base
                })
                val root = ViewController.circleRoot(pos)
                val name = if (minor) MINOR_NAMES[(root + 9) % 12] else Scale.ROOT_NAMES[root]
                val textColor = if (isOn) Palette.BG else if (current) Palette.TEXT else Palette.DIM
                val nameSize = if (minor) u * 0.85f else u * 1.05f
                if (mark.isEmpty()) {
                    ink.label(c, name, x, y - dot, dot * 2f, textColor, current, nameSize, Align.CENTER)
                } else {
                    ink.label(c, name, x, y - dot * 0.95f, dot * 1.15f, textColor, true, nameSize, Align.CENTER)
                    ink.label(c, mark, x, y + dot * 0.1f, dot * 0.8f, if (isOn) Palette.BG else Palette.PLAYHEAD, size = minOf(nameSize * 0.75f, dot * 0.75f), align = Align.CENTER)
                }
                if (pos == st.sketchCircleKey && minor == st.sketchCircleMinor) {
                    c.strokeCircle(x, y, dot + ink.dp(3f), if (focusedRow) cursorColor() else Palette.FAINT, ink.dp(if (focusedRow) 3f else 1.5f))
                }
            }
        }

        // the key under the cursor, in words
        val px = cx + radius + u * 1.5f
        val pw = r - px
        if (pw < u * 8f) return
        val cursor = ctl.sketchCircleScale()
        val minor = st.sketchCircleMinor
        val title = keyName(cursor.root, minor)
        val relative = if (minor) "Relative major: ${keyName(ViewController.circleRoot(st.sketchCircleKey), false)}"
        else "Relative minor: ${keyName((cursor.root + 9) % 12, true)}"
        val subdominant = keyName((cursor.root + 5).mod(12), minor)
        val dominant = keyName((cursor.root + 7).mod(12), minor)
        val triad = ctl.sketchCircleTriad().joinToString(" ") { Scale.ROOT_NAMES[it % 12] }
        val current = if (place == null) "Sketch key ${guide.scale.label}: no place on the circle"
        else "Sketch key: ${keyName(if (place.second) (ViewController.circleRoot(place.first) + 9) % 12 else ViewController.circleRoot(place.first), place.second)}"
        val k = ctl.keys
        val lines = arrayOf(
            relative,
            "${if (minor) "iv" else "IV"} $subdominant  ·  ${if (minor) "v" else "V"} $dominant",
            "Tonic triad $triad",
            current,
            "${k.label(Action.EDIT)}: use this key  ·  ${k.label(Action.CLEAR)}: major / minor  ·  hold ${k.label(Action.AUDITION)}: listen",
        )
        val lineH = u * 1.45f
        var y = maxOf(t, cy - (lineH * (lines.size + 1.3f)) / 2f)
        ink.label(c, ink.fit(title, pw, u * 1.3f, true), px, y, lineH * 1.3f, Palette.TEXT, true, u * 1.3f)
        y += lineH * 1.3f
        for ((i, line) in lines.withIndex()) {
            if (y + lineH > b) break
            val color = when (i) { 3 -> Palette.PLAYHEAD; 4 -> Palette.FAINT; else -> Palette.DIM }
            ink.label(c, ink.fit(line, pw, u * 0.9f), px, y, lineH, color, size = u * 0.9f)
            y += lineH
        }
    }

    private fun drawTones(c: Surface, l: Float, t: Float, r: Float, b: Float, guide: TonalGuide, col: Int, sounding: Int, strike: Float, after: Float) {
        val u = ink.unit
        val tones = guide.tones
        toneCount = minOf(tones.size, MAX_PADS)
        val chordAudition = sounding >= 0 && sounding / PAD_ROW == ROW_CHORDS
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
            ink.mono(c, toneNames[i], rect.centerX(), nameTop, u * 2f, strong, true, nameSize, Align.CENTER)
            val degreeColor = when { isOn -> Palette.BG; tone.interval == 0 -> Palette.TEXT; else -> Palette.DIM }
            ink.label(c, tone.degree, rect.centerX(), nameTop + u * 2f, u * 1.6f, degreeColor, tone.interval == 0, u * 1.05f, Align.CENTER)
            if (role != 0 && h > u * 6f) {
                ink.label(c, ROLES[role], rect.centerX(), rect.bottom - u * 2f, u * 1.6f, if (isOn) Palette.BG else if (chordAudition) Palette.LISTEN else col,
                    role == 1, u * 0.9f, Align.CENTER)
            }
            focus(c, rect, ROW_NOTES, selected)
        }
    }

    /**
     * The phrase as one column per row (note name at an onset, a held line while the previous note sounds on, a
     * dot before the first note; row numbers below, a rule every four rows), then its settings and actions.
     */
    private fun drawPhrase(c: Surface, l: Float, t: Float, r: Float, b: Float, guide: TonalGuide, col: Int, sounding: Int, strike: Float) {
        val u = ink.unit
        val phrase = ctl.sketchPhrase
        val focusedRow = st.sketchRow == ROW_PHRASE
        val isOn = sounding == padId(ROW_PHRASE, 0)
        val stripH = u * 2.6f
        phraseStrip.set(l, t, r, t + stripH)
        val base = if (focusedRow && st.sketchPhraseField == ViewController.PHRASE_STRIP) Palette.CURSOR_FILL else Palette.LANE
        ink.round(c, phraseStrip, ink.dp(4f), if (isOn) Palette.mix(base, Palette.LISTEN, 0.25f + 0.35f * strike) else base)
        val n = phrase.length
        val cw = phraseStrip.width() / n
        val noteH = stripH * 0.58f
        val lineY = t + stripH * 0.64f
        val noteSize = minOf(u * 0.85f, cw * 0.3f)
        val held = Palette.alpha(if (isOn) Palette.LISTEN else col, 150)
        var noteOn = false
        for (i in 0 until n) {
            val x0 = l + i * cw
            val mid = x0 + cw / 2f
            if (i > 0 && i % 4 == 0) ink.line(c, x0, t + ink.dp(3f), x0, t + stripH - ink.dp(3f), Palette.RULE, ink.dp(1f))
            val note = phrase.steps[i]
            if (note != null) {
                noteOn = true
                val inKey = guide.scale.contains(note)
                ink.mono(c, Songs.noteName(note), mid, t, noteH, if (inKey) Palette.TEXT else Palette.DIM, i % 4 == 0, noteSize, Align.CENTER)
                c.fillCircle(mid, lineY, ink.dp(3f), held)
                if (i + 1 < n && phrase.steps[i + 1] == null) ink.rect(c, mid, lineY - ink.dp(1.5f), x0 + cw, lineY + ink.dp(1.5f), held)
            } else if (noteOn) {
                ink.rect(c, x0, lineY - ink.dp(1.5f), x0 + cw, lineY + ink.dp(1.5f), held)
            } else {
                ink.label(c, "·", mid, t, noteH, Palette.FAINT, true, u, Align.CENTER)
            }
            ink.mono(c, hex1(i), mid, lineY + ink.dp(2f), t + stripH - lineY - ink.dp(2f), Palette.FAINT, size = u * 0.6f, align = Align.CENTER)
        }
        if (st.sketchPhraseField == ViewController.PHRASE_STRIP) {
            ink.outline(c, phraseStrip, if (focusedRow) cursorColor() else Palette.FAINT, ink.dp(if (focusedRow) 2f else 1f), ink.dp(4f))
        }

        // settings and actions
        val cellTop = t + stripH + u * 0.4f
        val total = phraseWeights.sum()
        var x = l
        val settings = st.sketchPhraseSettings
        for (i in 1 until phraseCells.size) {
            val w = (r - l) * phraseWeights[i] / total
            val rect = phraseCells[i]
            rect.set(x + 2, cellTop, x + w - 2, b)
            x += w
            val toggledOn = (i == ViewController.PHRASE_KEEP_RHYTHM && st.sketchKeepRhythm) ||
                (i == ViewController.PHRASE_KEEP_PITCHES && st.sketchKeepPitches)
            val (caption, value) = when (i) {
                ViewController.PHRASE_LENGTH -> "Length" to "${settings.length} rows"
                ViewController.PHRASE_RHYTHM -> "Rhythm" to settings.rhythm.label
                ViewController.PHRASE_MOTION -> "Motion" to settings.motion.label
                ViewController.PHRASE_CHORD_TONES -> "Chord tones" to (
                    if (!settings.chordTones) "Off" else (ctl.sketchPhraseChord()?.let { "On · ${it.name}" } ?: "On · none"))
                ViewController.PHRASE_ANOTHER -> "New phrase" to "Another"
                ViewController.PHRASE_VARY -> "Variation" to "Vary"
                ViewController.PHRASE_KEEP_RHYTHM -> "Keep" to (if (toggledOn) "✓ Rhythm" else "Rhythm")
                ViewController.PHRASE_KEEP_PITCHES -> "Keep" to (if (toggledOn) "✓ Pitches" else "Pitches")
                ViewController.PHRASE_FROM_COPIED -> "From" to "Copied"
                else -> "Write" to "Insert"
            }
            drawPhraseCell(c, rect, i, caption, value, toggledOn, focusedRow)
        }
    }

    private fun drawPhraseCell(c: Surface, r: Rect, i: Int, caption: String, value: String, on: Boolean, focusedRow: Boolean) {
        val u = ink.unit
        val h = r.height()
        val focused = focusedRow && st.sketchPhraseField == i
        val setting = i in ViewController.PHRASE_LENGTH..ViewController.PHRASE_MOTION
        val fill = when {
            on -> Palette.alpha(Palette.PLAYHEAD, 70)
            focused -> Palette.CURSOR_FILL
            setting || i == ViewController.PHRASE_CHORD_TONES -> Palette.LANE
            else -> Palette.BAR
        }
        ink.round(c, r, ink.dp(4f), fill)
        if (on) ink.outline(c, r, Palette.PLAYHEAD, ink.dp(1.5f), ink.dp(4f))
        if (setting) {
            ink.label(c, "‹", r.left + u * 0.4f, r.top, h, if (focused) Palette.TEXT else Palette.FAINT, true, u * 1.1f)
            ink.label(c, "›", r.right - u * 0.4f, r.top, h, if (focused) Palette.TEXT else Palette.FAINT, true, u * 1.1f, Align.RIGHT)
        }
        val w = r.width() - if (setting) u * 2.2f else u * 0.6f
        val valueColor = when {
            i == ViewController.PHRASE_INSERT -> Palette.WRITE
            on -> Palette.PLAYHEAD
            else -> Palette.TEXT
        }
        ink.label(c, ink.fit(caption, w, u * 0.8f), r.centerX(), r.top + h * 0.04f, h * 0.42f, Palette.DIM, size = u * 0.8f, align = Align.CENTER)
        ink.label(c, ink.fit(value, w, bold = true), r.centerX(), r.top + h * 0.36f, h * 0.64f, valueColor, true, align = Align.CENTER)
        if (focused) ink.outline(c, r, cursorColor(), ink.dp(2f), ink.dp(4f))
    }

    /** The selected pad of a row: a strong cursor when the controller is on that row, a quiet mark otherwise. */
    private fun focus(c: Surface, rect: Rect, row: Int, selected: Boolean) {
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
            val root = guide.scale.root.mod(12)
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
        // The circle hides the triads, so it marks no chord on the note pads.
        val chord = if (st.sketchCircle) null else guide.chords.getOrNull(st.sketchChord)
        for (i in 0 until n) {
            val pc = guide.tones[i].interval
            var role = 0
            if (chord != null) for (k in chord.intervals.indices) if (chord.intervals[k].mod(12) == pc) { role = k + 1; break }
            toneRole[i] = role
        }
    }

    private fun keyName(root: Int, minor: Boolean) = "${Scale.ROOT_NAMES[root.mod(12)]} ${if (minor) "minor" else "major"}"

    // ---- touch ---------------------------------------------------------------------------------------

    override fun down(x: Float, y: Float): Boolean {
        for (i in stripCells.indices) if (stripCells[i].contains(x, y)) {
            touchStrip = i
            ctl.sketchSetField(i)
            return true
        }
        if (keyButton.contains(x, y)) { touchButton = true; return true }
        if (chromaticShown && chooseScale.contains(x, y)) { touchChoose = true; return true }
        if (circleShown) {
            val key = hitCircle(x, y)
            if (key >= 0) { strikeCircle(key); return true }
        } else {
            val chord = hit(chordRects, chordCount, x, y)
            if (chord >= 0) { strikeChord(chord); return true }
        }
        val tone = hit(toneRects, toneCount, x, y)
        if (tone >= 0) { strikeTone(tone); return true }
        if (phraseStrip.contains(x, y)) {
            touchRow = ROW_PHRASE
            touchIndex = 0
            ctl.sketchSetPhraseField(ViewController.PHRASE_STRIP)
            ctl.sketchListen()
            return true
        }
        for (i in 1 until phraseCells.size) if (phraseCells[i].contains(x, y)) {
            touchPhrase = i
            ctl.sketchSetPhraseField(i)
            return true
        }
        return false
    }

    override fun move(x: Float, y: Float) {
        // Glide: the pad under the finger takes over the audition; empty space keeps the last one sounding.
        when (touchRow) {
            ROW_CHORDS, ROW_NOTES -> Unit
            else -> return
        }
        if (circleShown) {
            val key = hitCircle(x, y)
            if (key >= 0) { if (touchRow != ROW_CHORDS || touchIndex != key) { touchGlided = true; strikeCircle(key) }; return }
        } else {
            val chord = hit(chordRects, chordCount, x, y)
            if (chord >= 0) { if (touchRow != ROW_CHORDS || touchIndex != chord) strikeChord(chord); return }
        }
        val tone = hit(toneRects, toneCount, x, y)
        if (tone >= 0 && (touchRow != ROW_NOTES || touchIndex != tone)) { touchGlided = true; strikeTone(tone) }
    }

    override fun up(x: Float, y: Float) {
        when {
            touchStrip >= 0 -> {
                val r = stripCells[touchStrip]
                if (r.contains(x, y)) ctl.sketchAdjustField(if (x > r.centerX()) 1 else -1)
            }
            touchButton -> if (keyButton.contains(x, y)) ctl.sketchUsePatternKey()
            touchChoose -> if (chooseScale.contains(x, y)) ctl.sketchSetField(F_SCALE)
            touchPhrase >= 0 -> {
                val r = phraseCells[touchPhrase]
                if (r.contains(x, y)) when (touchPhrase) {
                    ViewController.PHRASE_LENGTH, ViewController.PHRASE_RHYTHM, ViewController.PHRASE_MOTION ->
                        ctl.sketchAdjustPhrase(if (x > r.centerX()) 1 else -1)
                    else -> ctl.sketchPhraseActivate()
                }
            }
            // Lifting from the circle key that was pressed (without gliding) uses it as Sketch's key.
            circleShown && touchRow == ROW_CHORDS && !touchGlided && hitCircle(x, y) == touchIndex -> ctl.sketchCircleSetKey()
        }
        endTouch()
    }

    override fun cancel() = endTouch()

    private fun strikeCircle(key: Int) {
        touchRow = ROW_CHORDS
        touchIndex = key
        ctl.sketchCircleSelect(key % 12, key >= 12)
        ctl.sketchListen()
    }

    private fun strikeChord(i: Int) {
        touchRow = ROW_CHORDS
        touchIndex = i
        ctl.sketchSelectChord(i)
        ctl.sketchListen()
    }

    private fun strikeTone(i: Int) {
        touchRow = ROW_NOTES
        touchIndex = i
        ctl.sketchSelectTone(i)
        ctl.sketchListen()
    }

    private fun endTouch() {
        touchStrip = -1
        touchButton = false
        touchChoose = false
        touchPhrase = -1
        touchGlided = false
        if (touchRow >= 0) {
            touchRow = -1
            touchIndex = -1
            ctl.sketchRelease()
        }
    }

    private fun hit(rects: Array<Rect>, count: Int, x: Float, y: Float): Int {
        for (i in 0 until count) if (rects[i].contains(x, y)) return i
        return -1
    }

    /** Circle key under (x, y): position 0..11 on the major ring, 12..23 on the minor ring; -1 outside or at the centre. */
    private fun hitCircle(x: Float, y: Float): Int {
        val dx = x - circleX
        val dy = y - circleY
        val d = hypot(dx, dy)
        if (circleR <= 0f || d > circleR || d < circleR * 0.2f) return -1
        val degrees = atan2(dy, dx).toDouble() * (180.0 / kotlin.math.PI) + 90.0
        val pos = (degrees / 30.0).roundToInt().mod(12)
        return pos + if (d < circleR * RING_SPLIT) 12 else 0
    }

    private fun circleIndex(position: Int, minor: Boolean) = CIRCLE + position + if (minor) 12 else 0

    private fun padId(row: Int, index: Int) = row * PAD_ROW + index

    private companion object {
        const val MAX_PADS = 12
        /** Pad ids are row * PAD_ROW + index; chord pads use 0..11, circle keys [CIRCLE] + 0..23. */
        const val PAD_ROW = 64
        const val CIRCLE = 16
        /** Boundary between the major (outer) and minor (inner) rings, as a fraction of the radius. */
        const val RING_SPLIT = 0.62f
        const val ROW_SETTINGS = 0
        const val ROW_CHORDS = 1
        const val ROW_NOTES = 2
        const val ROW_PHRASE = 3
        const val F_ROOT = 0
        const val F_SCALE = 1
        const val F_CHANNEL = 2
        const val F_SOUND = 3
        val ROLES = arrayOf("", "root", "3rd", "5th")
        val MINOR_NAMES = Scale.ROOT_NAMES.map { it.lowercase() }
    }
}
