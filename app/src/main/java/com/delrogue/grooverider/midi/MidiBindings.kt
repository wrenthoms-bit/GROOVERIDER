package com.delrogue.grooverider.midi

/** Something a controller can be taught to drive. Knobs take a CC; pads take a note. */
enum class MidiTarget(val label: String, val pad: Boolean = false) {
    TEXTURE("Texture"), DRIFT("Drift"), SPACE("Space"), PITCH("Pitch"),
    SHIMMER("Shimmer"), TONE("Tone"), REGISTER("Register"), SCAN("Scan"),
    DRONE("Drone", pad = true), REROLL("Re-roll", pad = true),
}

/**
 * MIDI-learn, as in the web app: arm a control, move a knob (or hit a pad), and
 * that knob drives it from then on. One knob per control and one control per
 * knob: binding again replaces what was there.
 */
class MidiBindings {
    sealed class Outcome {
        /** A knob moved a control: [value] is 0 .. 1. */
        data class Turn(val target: MidiTarget, val value: Float) : Outcome()
        /** A pad hit a button. */
        data class Hit(val target: MidiTarget) : Outcome()
        /** The message was used to bind the armed control. */
        data class Bound(val target: MidiTarget) : Outcome()
        /** Not bound to anything: the caller plays it (a note), treats it as the sustain pedal (CC 64), or ignores it. */
        object Unbound : Outcome()
    }

    private val knobs = HashMap<Int, MidiTarget>()   // controller number -> control
    private val pads = HashMap<Int, MidiTarget>()    // note number -> button

    var armed: MidiTarget? = null
        private set

    /** Arms [target] for learning; arming what is already armed cancels. */
    fun arm(target: MidiTarget?) { armed = if (target == armed) null else target }

    fun onControlChange(controller: Int, value: Int): Outcome {
        val waiting = armed
        if (waiting != null && !waiting.pad) {
            knobs.values.remove(waiting)
            knobs[controller] = waiting
            armed = null
            return Outcome.Bound(waiting)
        }
        val target = knobs[controller] ?: return Outcome.Unbound
        return Outcome.Turn(target, (value / 127f).coerceIn(0f, 1f))
    }

    fun onNoteOn(note: Int): Outcome {
        val waiting = armed
        if (waiting != null && waiting.pad) {
            pads.values.remove(waiting)
            pads[note] = waiting
            armed = null
            return Outcome.Bound(waiting)
        }
        val target = pads[note] ?: return Outcome.Unbound
        return Outcome.Hit(target)
    }

    /** A pad bound to a button does not also play the cloud. */
    fun isPad(note: Int): Boolean = note in pads

    /** "CC 74" or "note 36", or null if nothing drives [target] yet. */
    fun describe(target: MidiTarget): String? =
        if (target.pad) pads.entries.firstOrNull { it.value == target }?.let { "note ${it.key}" }
        else knobs.entries.firstOrNull { it.value == target }?.let { "CC ${it.key}" }

    fun clear() { knobs.clear(); pads.clear(); armed = null }

    /** A small text form for storing between launches, e.g. "cc74=TEXTURE;note36=DRONE". */
    fun encode(): String =
        (knobs.entries.sortedBy { it.key }.map { "cc${it.key}=${it.value.name}" } +
            pads.entries.sortedBy { it.key }.map { "note${it.key}=${it.value.name}" }).joinToString(";")

    companion object {
        /** Anything it does not recognise is dropped, so an old or damaged string cannot break the app. */
        fun decode(text: String): MidiBindings = MidiBindings().apply {
            for (entry in text.split(';')) {
                val (key, name) = entry.split('=').takeIf { it.size == 2 } ?: continue
                val target = MidiTarget.entries.firstOrNull { it.name == name } ?: continue
                when {
                    key.startsWith("cc") && !target.pad -> key.drop(2).toIntOrNull()?.let { knobs[it] = target }
                    key.startsWith("note") && target.pad -> key.drop(4).toIntOrNull()?.let { pads[it] = target }
                }
            }
        }
    }
}
