package com.delrogue.grooverider.midi

/**
 * Played notes become the cloud's pitch centres: each new grain takes one of
 * the chord's notes. The web app's keyboard logic (docs/index.html, `noteOn`
 * and friends), unchanged.
 *
 *  - LATCH: a chord stays after release; a new press once all keys are up starts a new chord.
 *  - GATE:  grains only spawn while keys (or the sustain pedal) hold them; the space carries the release.
 *  - OFF:   keys are ignored.
 *
 * Each call returns true if the chord or the gate changed and the engine needs telling.
 */
class KeyboardChord {
    enum class Mode { OFF, LATCH, GATE }

    var mode = Mode.LATCH
        private set
    /** MIDI note numbers, lowest first, at most [MAX_NOTES]. */
    var chord: List<Int> = emptyList()
        private set

    private val keysDown = sortedSetOf<Int>()
    private val keysSustained = sortedSetOf<Int>()
    private var sustain = false

    val sounding: Int get() = keysDown.size + keysSustained.size
    /** Whether grains should be spawning: always, except in GATE with nothing held. */
    val gateOpen: Boolean get() = mode != Mode.GATE || sounding > 0
    /** The chord as the engine wants it: semitones from middle C. */
    val semitones: List<Float> get() = chord.map { (it - MIDDLE_C).toFloat() }

    fun setMode(newMode: Mode): Boolean {
        mode = newMode
        keysDown.clear(); keysSustained.clear()
        if (newMode == Mode.OFF) chord = emptyList()
        return true
    }

    fun noteOn(note: Int): Boolean {
        if (mode == Mode.OFF) return false
        if (mode == Mode.LATCH) {
            if (sounding == 0) chord = emptyList()
            if (note !in chord && chord.size < MAX_NOTES) chord = (chord + note).sorted()
        }
        keysDown.add(note); keysSustained.remove(note)
        if (mode == Mode.GATE) gateChord()
        return true
    }

    fun noteOff(note: Int): Boolean {
        if (!keysDown.remove(note)) return false
        if (sustain) { keysSustained.add(note); return false }
        if (mode == Mode.GATE) { gateChord(); return true }
        return false
    }

    fun setSustain(on: Boolean): Boolean {
        sustain = on
        if (on) return false
        keysSustained.clear()
        if (mode == Mode.GATE) { gateChord(); return true }
        return false
    }

    fun clear(): Boolean {
        chord = emptyList(); keysDown.clear(); keysSustained.clear()
        return true
    }

    // In GATE the chord is exactly what is held; with nothing held it keeps its last shape while the gate shuts.
    private fun gateChord() {
        if (sounding > 0) chord = (keysDown + keysSustained).sorted().take(MAX_NOTES)
    }

    companion object {
        const val MAX_NOTES = 16
        const val MIDDLE_C = 60
        private val NAMES = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
        fun noteName(note: Int): String = NAMES[((note % 12) + 12) % 12] + (note / 12 - 1)
    }
}
