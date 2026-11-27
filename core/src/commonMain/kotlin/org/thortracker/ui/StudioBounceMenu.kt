package org.thortracker.ui

import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.system.studio.StudioBounce
import org.thortracker.system.studio.StudioBouncePlan
import org.thortracker.system.studio.StudioPcm
import org.thortracker.system.studio.StudioSound
import org.thortracker.system.studio.StudioTarget
import org.thortracker.system.studio.fxHex

/** The frontend owns ready PCM. Nothing is installed until the user chooses Install in the original document. */
internal class StudioBounceMenu(private val session: EditorSession, private val popups: Popups, private val host: MenuHost) {
    private class Run(val plan: StudioBouncePlan, val version: Long, val display: Int) {
        // Popup and worker callbacks capture only this token, never a discarded song or PCM bank.
        val token = Any()
        var cancel: (() -> Unit)? = null
        var cancelling = false
        var frames = 0L
        var pcm: StudioPcm? = null
        var shown: PopupSpec? = null
    }

    private var current: Run? = null
    val pending: Boolean get() = current != null

    fun chooseSlot(display: Int, pane: () -> ViewState?) {
        if (pending) return reopen()
        val state = pane()?.takeIf { it.kind == ViewKind.PATTERN } ?: return host.status("Choose a track in Pattern first.")
        val song = session.song
        val studio = song.sound as? StudioSound ?: return host.status("Bounce needs a Studio project.")
        val order = state.order
        val channel = state.channel
        val pattern = song.orders.getOrNull(order) ?: return host.status("Choose a pattern order first.")
        val track = studio.tracks.getOrNull(channel) ?: return host.status("Choose an internal track first.")
        if (track.target == StudioTarget.USB_MIDI) return host.error("USB sends MIDI, not local audio. Select an internal track to bounce.")
        val version = session.documentVersion
        fun valid() = version == session.documentVersion && pane() === state && state.kind == ViewKind.PATTERN &&
            state.order == order && state.channel == channel && !pending
        val slots = StudioBounce.slots(song)
        if (slots.isEmpty()) return host.error("No spare Init slot. Clear an unreferenced instrument first. Slot 01 is reserved.")
        val source = "Order ${fxHex(order)} · Pattern ${fxHex(pattern)} · Track ${track.midi.id}"
        popups.show(PopupSpec("Choose sampler slot", "$source\nOnly unreferenced Init instruments are shown.",
            items = slots.map { slot -> PopupItem("${fxHex(slot)} · Init") {
                if (!valid()) host.status("Bounce selection changed. Open Bounce again.") else {
                    val plan = try { StudioBounce.plan(song, order, channel, slot) }
                        catch (e: Exception) { return@PopupItem host.error("Cannot bounce: ${e.message}") }
                    val spec = PopupSpec("Bounce to sampler ${fxHex(slot)}?",
                        "${plan.label}\n${duration(plan.frames)} stereo · ${plan.decodedBytes / 1024} KiB decoded. " +
                        "One pass from row 00 with track inserts, gain and pan. No shared returns or tail after the pattern.",
                        listOf(PopupItem("Render", PopupItem.Tone.PRIMARY) {
                            if (valid()) start(plan, version, display) else host.status("Bounce selection changed. Open Bounce again.")
                        }, PopupItem.dismiss()))
                    popups.show(spec, display)
                }
            } } + PopupItem.dismiss()), display)
    }

    private fun start(plan: StudioBouncePlan, version: Long, display: Int) {
        val run = Run(plan, version, display)
        val token = run.token
        current = run
        popups.closeAll() // Back/Hide from progress must not expose a second Render button.
        show(run)
        val cancel = try {
            host.startStudioBounce(plan, { frames -> progress(token, frames) }, { result -> complete(token, result) })
        } catch (e: Exception) {
            complete(token, Result.failure(e)); return
        }
        if (current !== run || run.pcm != null) return // A synchronous host completion is legal.
        if (cancel == null) {
            dismiss(run)
            host.error("Offline renderer busy or unavailable. Finish the other operation and try again.")
        } else run.cancel = cancel
    }

    /** Only an explicit user action may bring a hidden run back to its originating display. */
    fun reopen() {
        val run = current ?: return host.status("No track bounce is pending.")
        popups.closeAll()
        show(run)
    }

    private fun show(run: Run) {
        val token = run.token
        val slot = fxHex(run.plan.slot)
        val spec = if (run.pcm != null) PopupSpec("Bounce ready", "${run.plan.label}\nSampler $slot · ${duration(run.plan.frames)} stereo",
            listOf(PopupItem("Install sampler $slot", PopupItem.Tone.PRIMARY) { install(token) },
                PopupItem("Discard", PopupItem.Tone.DANGER) { discard(token) }, PopupItem("Hide")), selected = 2)
        else PopupSpec("Bouncing to sampler $slot", "${run.plan.label}\n" + when {
            run.cancelling -> "Cancelling…"
            run.frames == run.plan.frames -> "Preparing sampler…"
            else -> "${100L * run.frames / run.plan.frames}% · ${duration(run.frames)} of ${duration(run.plan.frames)}"
        }, listOf(PopupItem("Cancel bounce", PopupItem.Tone.DANGER) { cancel(token) }, PopupItem("Hide (keep rendering)"),
            // A different popup can cover this one while the worker finishes; this row reopens the current state.
            PopupItem("Show status") { if (current?.token === token) reopen() }), selected = 1)
        if (run.shown != null && popups.top === run.shown) popups.replaceTop(spec) else popups.show(spec, run.display)
        run.shown = spec
    }

    private fun progress(token: Any, frames: Long) {
        val run = current?.takeIf { it.token === token && !it.cancelling && it.pcm == null } ?: return
        val n = frames.coerceIn(run.frames, run.plan.frames)
        if (n == run.frames) return
        run.frames = n
        if (popups.top === run.shown) show(run)
    }

    private fun cancel(token: Any) {
        val run = current?.takeIf { it.token === token } ?: return
        if (run.pcm != null) return reopen() // Old Cancel visible beneath a previously covering popup.
        if (!run.cancelling) { run.cancelling = true; run.cancel?.invoke() }
        if (current === run) host.status("Cancelling track bounce…")
    }

    private fun complete(token: Any, result: Result<StudioPcm?>) {
        val run = current?.takeIf { it.token === token && it.pcm == null } ?: return
        val visible = run.shown != null && popups.top === run.shown
        run.cancel = null
        val pcm = result.getOrNull()
        val reason = when {
            run.cancelling -> "Track bounce cancelled."
            result.isFailure -> "Bounce failed: ${result.exceptionOrNull()?.message ?: "render error"}"
            pcm == null -> "Track bounce cancelled."
            else -> null
        }
        if (reason != null) {
            dismiss(run)
            if (visible && result.isFailure && !run.cancelling) host.error(reason) else host.status(reason)
            return
        }
        run.pcm = pcm
        if (visible) {
            popups.back() // New choices must not inherit the progress cursor (Hide would become Discard).
            show(run)
        } else host.status("Track bounce ready for slot ${fxHex(run.plan.slot)}. Choose Show track bounce to install.")
    }

    private fun install(token: Any) {
        val run = current?.takeIf { it.token === token } ?: return
        val pcm = run.pcm ?: return
        if (session.documentVersion != run.version || run.plan.slot !in StudioBounce.slots(session.song)) {
            dismiss(run)
            return host.error("Bounce discarded: the project or sampler slot changed. Render again.")
        }
        if (session.inGesture) return host.status("Finish the active edit, then reopen the bounce to install.")
        val slot = fxHex(run.plan.slot)
        val changed = try { session.apply("Bounce to sampler $slot") { StudioBounce.install(it, run.plan, pcm) } }
            catch (e: Exception) { dismiss(run); return host.error("Bounce not installed: ${e.message}") }
        dismiss(run)
        if (changed) host.status("Installed sampler $slot · ${duration(run.plan.frames)} stereo" + (session.message?.let { " · $it" } ?: ""))
    }

    private fun discard(token: Any) {
        val run = current?.takeIf { it.token === token && it.pcm != null } ?: return
        dismiss(run); host.status("Track bounce discarded.")
    }

    private fun dismiss(run: Run) {
        current = null
        if (run.shown != null && popups.top === run.shown) popups.back()
        run.shown = null; run.cancel = null; run.pcm = null
    }

    private fun duration(frames: Long): String {
        val hundredths = (frames + 240) / 480
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')} s"
    }
}
