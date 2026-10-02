package com.delrogue.grooverider.midi

/** The MIDI messages the app acts on. Channels are ignored, as in the web app: any key or pad plays. */
sealed class MidiEvent {
    data class NoteOn(val note: Int, val velocity: Int) : MidiEvent()
    data class NoteOff(val note: Int) : MidiEvent()
    data class ControlChange(val controller: Int, val value: Int) : MidiEvent()
}

/**
 * Turns the raw byte stream a MIDI port delivers into [MidiEvent]s. Handles
 * running status, real-time bytes arriving mid-message, and skips everything
 * it has no use for (pitch bend, aftertouch, program change, sysex). A note-on
 * with velocity 0 is a note-off, as the MIDI spec has it.
 *
 * Keeps state between calls -- one parser per port -- and is not thread-safe.
 */
class MidiParser(private val sink: (MidiEvent) -> Unit) {
    private var status = 0          // running status; 0 = none
    private var data1 = -1
    private var inSysex = false

    fun feed(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size) {
        for (i in offset until offset + count) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b >= 0xF8 -> Unit                               // real-time: may fall anywhere, changes nothing
                b == 0xF0 -> { inSysex = true; status = 0 }
                b == 0xF7 -> inSysex = false
                b >= 0xF1 -> { status = 0; inSysex = false }    // system common cancels running status
                b >= 0x80 -> { status = b; data1 = -1; inSysex = false }
                inSysex || status == 0 -> Unit
                else -> data(b)
            }
        }
    }

    private fun data(b: Int) {
        val kind = status and 0xF0
        if (kind == 0xC0 || kind == 0xD0) return                // one-byte messages we do not use
        if (data1 < 0) { data1 = b; return }
        val first = data1
        data1 = -1                                              // running status: the next data byte starts a new message
        when (kind) {
            0x90 -> sink(if (b > 0) MidiEvent.NoteOn(first, b) else MidiEvent.NoteOff(first))
            0x80 -> sink(MidiEvent.NoteOff(first))
            0xB0 -> sink(MidiEvent.ControlChange(first, b))
        }
    }
}
