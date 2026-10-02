package com.delrogue.grooverider.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiTest {

    private fun parse(vararg chunks: IntArray): List<MidiEvent> {
        val events = ArrayList<MidiEvent>()
        val parser = MidiParser { events.add(it) }
        for (chunk in chunks) parser.feed(ByteArray(chunk.size) { chunk[it].toByte() })
        return events
    }

    // ---------------------------------------------------------------- parser

    @Test
    fun `notes and knobs on any channel`() {
        assertEquals(
            listOf(MidiEvent.NoteOn(60, 100), MidiEvent.NoteOff(60), MidiEvent.ControlChange(74, 127), MidiEvent.NoteOn(36, 90)),
            parse(intArrayOf(0x90, 60, 100, 0x80, 60, 0, 0xB0, 74, 127, 0x99, 36, 90)),
        )
    }

    @Test
    fun `note-on with velocity zero is a note-off`() {
        assertEquals(listOf(MidiEvent.NoteOn(64, 80), MidiEvent.NoteOff(64)), parse(intArrayOf(0x90, 64, 80, 0x90, 64, 0)))
    }

    @Test
    fun `running status, split across packets, with clock bytes in the middle`() {
        // one status byte, then three notes; the second note is cut in half by a packet boundary and a clock tick
        assertEquals(
            listOf(MidiEvent.NoteOn(60, 100), MidiEvent.NoteOn(64, 90), MidiEvent.NoteOn(67, 80)),
            parse(intArrayOf(0x90, 60, 100, 64), intArrayOf(0xF8, 90, 67, 80)),
        )
    }

    @Test
    fun `pitch bend, aftertouch, program change and sysex are skipped without losing what follows`() {
        assertEquals(
            listOf(MidiEvent.ControlChange(1, 5), MidiEvent.NoteOn(62, 70)),
            parse(intArrayOf(0xE0, 0, 64, 0xD0, 30, 0xC0, 5, 0xF0, 1, 2, 3, 0xF7, 0xB0, 1, 5, 0x90, 62, 70)),
        )
    }

    // ---------------------------------------------------------------- chord

    @Test
    fun `latch - a chord stays after release, and the next press starts a new one`() {
        val k = KeyboardChord()
        assertTrue(k.noteOn(60)); k.noteOn(67); k.noteOn(64)
        assertEquals(listOf(60, 64, 67), k.chord)
        k.noteOff(60); k.noteOff(64); k.noteOff(67)
        assertEquals("still there after letting go", listOf(60, 64, 67), k.chord)
        assertTrue(k.gateOpen)
        k.noteOn(62)
        assertEquals("all keys were up, so this starts afresh", listOf(62), k.chord)
        assertEquals(listOf(2f), k.semitones)
    }

    @Test
    fun `gate - grains only while keys are held, and the pedal holds them`() {
        val k = KeyboardChord()
        k.setMode(KeyboardChord.Mode.GATE)
        assertFalse("nothing held: shut", k.gateOpen)
        k.noteOn(60); k.noteOn(67)
        assertTrue(k.gateOpen); assertEquals(listOf(60, 67), k.chord)
        k.noteOff(67)
        assertEquals("the chord is what is held", listOf(60), k.chord)
        k.setSustain(true)
        assertFalse("with the pedal down, lifting a key changes nothing", k.noteOff(60))
        assertTrue(k.gateOpen); assertEquals(listOf(60), k.chord)
        assertTrue(k.setSustain(false))
        assertFalse("pedal up, nothing held: shut", k.gateOpen)
    }

    @Test
    fun `off ignores keys, and a chord is capped at sixteen notes`() {
        val k = KeyboardChord()
        k.setMode(KeyboardChord.Mode.OFF)
        assertFalse(k.noteOn(60)); assertTrue(k.chord.isEmpty())
        k.setMode(KeyboardChord.Mode.LATCH)
        for (n in 40 until 60) k.noteOn(n)
        assertEquals(16, k.chord.size)
        assertTrue(k.clear()); assertTrue(k.chord.isEmpty())
        assertEquals("E♭4", KeyboardChord.noteName(63)); assertEquals("C4", KeyboardChord.noteName(60))
    }

    // ---------------------------------------------------------------- learn

    @Test
    fun `arm a ring, turn a knob, and that knob drives it from then on`() {
        val b = MidiBindings()
        assertEquals(MidiBindings.Outcome.Unbound, b.onControlChange(74, 64))
        b.arm(MidiTarget.SPACE)
        assertEquals(MidiBindings.Outcome.Bound(MidiTarget.SPACE), b.onControlChange(74, 10))
        assertNull(b.armed)
        assertEquals(MidiBindings.Outcome.Turn(MidiTarget.SPACE, 1f), b.onControlChange(74, 127))
        assertEquals("CC 74", b.describe(MidiTarget.SPACE))

        // re-teaching moves the control to the new knob; teaching the knob something else replaces what it drove
        b.arm(MidiTarget.SPACE); b.onControlChange(75, 0)
        assertEquals(MidiBindings.Outcome.Unbound, b.onControlChange(74, 5))
        b.arm(MidiTarget.TONE); b.onControlChange(75, 0)
        assertNull(b.describe(MidiTarget.SPACE)); assertEquals("CC 75", b.describe(MidiTarget.TONE))
    }

    @Test
    fun `pads bind to buttons and stop playing the cloud, and arming twice cancels`() {
        val b = MidiBindings()
        b.arm(MidiTarget.DRONE)
        assertEquals("a knob does not bind a button", MidiBindings.Outcome.Unbound, b.onControlChange(20, 100))
        assertEquals(MidiBindings.Outcome.Bound(MidiTarget.DRONE), b.onNoteOn(36))
        assertEquals(MidiBindings.Outcome.Hit(MidiTarget.DRONE), b.onNoteOn(36))
        assertTrue(b.isPad(36)); assertFalse(b.isPad(37))
        assertEquals(MidiBindings.Outcome.Unbound, b.onNoteOn(60))
        b.arm(MidiTarget.TEXTURE); b.arm(MidiTarget.TEXTURE)
        assertNull(b.armed)
    }

    @Test
    fun `mappings survive being stored, and rubbish in storage is dropped`() {
        val b = MidiBindings()
        b.arm(MidiTarget.TEXTURE); b.onControlChange(70, 1)
        b.arm(MidiTarget.DRONE); b.onNoteOn(36)
        val restored = MidiBindings.decode(b.encode())
        assertEquals("CC 70", restored.describe(MidiTarget.TEXTURE)); assertEquals("note 36", restored.describe(MidiTarget.DRONE))
        val damaged = MidiBindings.decode("cc7=NOPE;;note=DRONE;cc9=DRONE;ccx=TONE;cc71=TONE")
        assertEquals("CC 71", damaged.describe(MidiTarget.TONE)); assertNull(damaged.describe(MidiTarget.DRONE))
    }
}
