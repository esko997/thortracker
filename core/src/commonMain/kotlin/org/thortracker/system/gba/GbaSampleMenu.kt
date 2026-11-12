package org.thortracker.system.gba

import org.thortracker.editor.MenuContext
import org.thortracker.editor.ViewController

/** Platform-independent menus: files and numeric entry work on controller-only and touch hosts. */
internal object GbaSampleMenu {
    private const val MAX_WAV = GbaSamples.MAX_WAV_BYTES

    fun open(v: ViewController) {
        val session = v.session
        val state = v.state
        val slot = state.instrument
        val key = state.dpcmKey
        val index = state.dpcmSample
        val original = session.song
        val generation = session.documentGeneration
        val version = session.documentVersion
        val revision = state.soundPanel("gba-maxmod").revision
        fun live() = v.state === state && state.kind == org.thortracker.editor.ViewKind.INSTRUMENT &&
            state.instrument == slot && state.dpcmKey == key && state.dpcmSample == index &&
            session.song === original && session.documentGeneration == generation && session.documentVersion == version &&
            state.soundPanel("gba-maxmod").revision == revision
        session.requestMenu { menu ->
            fun change(action: () -> Boolean) {
                if (!live()) { menu.error("The sound changed while the menu was open. Open the tools again."); return }
                try {
                    if (action()) {
                        GbaSoundView.normalise(v)
                        GbaSoundView.heard(v)
                    }
                } catch (e: IllegalArgumentException) { menu.error(e.message ?: "Cannot change this sample") }
            }
            fun transform(label: String, f: (GbaSample) -> GbaSample) = change { session.editGbaSample(index, label, f) }
            fun wav(replace: Boolean, selectedKey: Boolean = false) {
                menu.pickFile(if (replace) "Replace shared sample" else "Import WAV", listOf(".wav"), MAX_WAV) { name, _, bytes ->
                    change {
                        val sample = GbaSamples.fromWav(bytes, name)
                        if (replace) session.editGbaSample(index, "Replace sample") { sample }
                        else session.addGbaSamples(slot, listOf(sample), if (selectedKey) key else null).also {
                            if (it) state.dpcmSample = session.song.gba.samples.lastIndex
                        }
                    }
                }
            }
            val sample = original.gba.samples.getOrNull(index)
            val entries = mutableListOf<Pair<String, () -> Unit>>(
                "Import WAV; map all keys" to { wav(false) },
                "Import WAV; map only ${original.system.noteName(key)}" to { wav(false, true) },
                "Generate an original sample…" to {
                    menu.menu("Generate and map all keys", GbaSamples.KINDS.map { kind -> kind to {
                        change { session.addGbaSamples(slot, listOf(GbaSamples.generate(kind))).also {
                            if (it) state.dpcmSample = session.song.gba.samples.lastIndex
                        } }
                    } })
                },
                "Add original drum kit at ${original.system.noteName(key)}…" to {
                    val kit = listOf("Kick", "Snare", "Hat").map { GbaSamples.generate(it) }
                    menu.menu("Map ${kit.size} samples from ${original.system.noteName(key)} (existing keys replaced)", listOf(
                        "Add kit" to { change { session.addGbaSamples(slot, kit, key).also {
                            if (it) state.dpcmSample = original.gba.samples.size
                        } } },
                    ))
                },
                "Rename instrument…" to {
                    menu.text("Instrument name", original.gba.instrument(slot)?.name.orEmpty(), 22) { name ->
                        change { session.editGbaInstrument(slot, "Rename instrument") { it.copy(name = name) } }
                    }
                },
                "Clear all key mappings…" to {
                    menu.menu("All 96 keys will be silent (Undo restores them)", listOf("Clear mappings" to {
                        change { session.editGbaInstrument(slot, "Clear key mappings") { it.copy(keymap = List(96) { -1 }) } }
                    }))
                },
            )
            if (sample != null) {
                entries += "Rename sample…" to {
                    menu.text("Sample name", sample.name, 22) { name -> transform("Rename sample") { it.copy(name = name) } }
                }
                entries += "Replace WAV (all instruments using this sample)…" to { wav(true) }
                entries += "Trim to frame range…" to {
                    number(menu, "First frame (inclusive)", 0, 0..sample.pcm.lastIndex) { start ->
                        number(menu, "Last frame (exclusive)", sample.pcm.size, (start + 1)..sample.pcm.size) { end ->
                            transform("Trim sample") { GbaSamples.trim(it, start, end) }
                        }
                    }
                }
                entries += "Normalise peak" to { transform("Normalise sample", GbaSamples::normalise) }
                entries += "Gain…" to {
                    number(menu, "Gain percent (100 = unchanged)", 100, 0..400) { percent ->
                        transform("Sample gain") { GbaSamples.gain(it, percent / 100.0) }
                    }
                }
                entries += "Fade in / out…" to {
                    number(menu, "Fade-in frames", 0, 0..sample.pcm.size) { fadeIn ->
                        number(menu, "Fade-out frames", 0, 0..sample.pcm.size) { fadeOut ->
                            transform("Fade sample") { GbaSamples.fade(it, fadeIn, fadeOut) }
                        }
                    }
                }
                entries += "Resample (preserve pitch)…" to {
                    number(menu, "New sample rate in Hz", sample.sampleRate, 1000..GbaSample.MAX_RATE) { rate ->
                        transform("Resample sample") { GbaSamples.resample(it, rate) }
                    }
                }
                entries += "Map selected sample to all keys…" to {
                    menu.menu("Replace this instrument's entire keymap?", listOf("Map ${sample.name} to all keys" to {
                        change { session.editGbaInstrument(slot, "Map sample to all keys") { it.copy(keymap = List(96) { index }) } }
                    }))
                }
                entries += "Map selected sample to a key range…" to {
                    number(menu, "First key (C0 = 0, B7 = 95)", key, 0..95) { first ->
                        number(menu, "Last key (inclusive)", first, first..95) { last ->
                            change { session.editGbaInstrument(slot, "Map key range") { ins ->
                                ins.copy(keymap = ins.keymap.mapIndexed { k, old -> if (k in first..last) index else old })
                            } }
                        }
                    }
                }
                entries += "Map linked C0 and C7–B7…" to {
                    menu.menu("Pinned Maxmod links these 13 keys; all will use ${sample.name}", listOf("Map linked keys" to {
                        change { session.editGbaInstrument(slot, "Map linked octave") { ins ->
                            ins.copy(keymap = ins.keymap.mapIndexed { k, old -> if (k == 0 || k >= 84) index else old })
                        } }
                    }))
                }
                val users = original.gba.instruments.count { index in it.keymap }
                entries += "Delete sample ($users instruments become silent on its keys)…" to {
                    menu.menu("Delete ${sample.name}? Undo restores PCM and mappings", listOf("Delete sample" to {
                        change { session.deleteGbaSample(index) }
                    }))
                }
            }
            val used = original.gba.samples.sumOf { it.pcm.size } / 1024
            menu.menu("Sample tools · $used / 512 KiB · changes affect shared PCM", entries)
        }
    }

    fun number(menu: MenuContext, title: String, initial: Int, range: IntRange, accepted: (Int) -> Unit) {
        menu.text("$title (${range.first}–${range.last})", initial.toString(), 10) { text ->
            val value = text.trim().toIntOrNull()
            if (value == null || value !in range) menu.error("Enter a whole number from ${range.first} to ${range.last}")
            else accepted(value)
        }
    }
}
