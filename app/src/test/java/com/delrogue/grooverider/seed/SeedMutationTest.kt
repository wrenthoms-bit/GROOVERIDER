package com.delrogue.grooverider.seed

import com.delrogue.grooverider.ui.GrainState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 3.6's acceptance criteria (M7), exercised directly against the pure
 * Kotlin mutation logic -- no engine, no device needed for these. */
class SeedMutationTest {

    private fun parentSeed(): Seed {
        val grain = GrainState()   // the pad defaults
        return Seed(
            id = "parent", name = "Test Parent", masterSeed = 123456789L,
            params = ParamState.pack(grain), modRoutes = ByteArray(0), sourceHash = "hash",
            inPointMs = 0, outPointMs = 0, parentId = null, mutationDistance = 0f,
            lockedParams = 0L, favourite = false, renderCount = 0,
            createdAt = 0L, waveformThumb = ByteArray(0),
        )
    }

    private fun isFinitePatch(g: GrainState): Boolean =
        g.density.isFinite() && g.grainSizeMs.isFinite() && g.position.isFinite() &&
            g.drift.isFinite() && g.reverseProb.isFinite() && g.pitchSt.isFinite() &&
            g.timingJitter.isFinite() && g.sizeJitter.isFinite() && g.sprayMs.isFinite() &&
            g.pitchSpraySt.isFinite() && g.spread.isFinite() &&
            g.density in 0.5f..200f && g.grainSizeMs in 5f..2000f &&
            g.position in 0f..1f && g.reverseProb in 0f..1f && g.spread in 0f..1f

    @Test
    fun `mutate at 0_2 produces six variations, none identical, none broken`() {
        val parent = parentSeed()
        val children = SeedMutation.mutateSix(parent, amount = 0.2f)

        assertEquals(6, children.size)
        assertEquals(6, children.map { it.masterSeed }.distinct().size)   // all different seeds

        val parentGrain = ParamState.unpack(parent.params)
        for (child in children) {
            assertEquals(parent.id, child.parentId)
            assertEquals(0.2f, child.mutationDistance)
            val g = ParamState.unpack(child.params)
            assertTrue("child patch must stay in-range and finite", isFinitePatch(g))
            assertFalse("child must differ from parent somewhere", g == parentGrain)
        }
    }

    @Test
    fun `mutate at 0_9 produces clearly different children, still none broken`() {
        val parent = parentSeed()
        val children = SeedMutation.mutateSix(parent, amount = 0.9f)
        val parentGrain = ParamState.unpack(parent.params)

        for (child in children) {
            val g = ParamState.unpack(child.params)
            assertTrue("child patch must stay in-range and finite even at high mutation", isFinitePatch(g))
            // "clearly different": at least one param moved by a musically
            // significant amount relative to the parent.
            val movedFar = kotlin.math.abs(g.density - parentGrain.density) > 5f ||
                kotlin.math.abs(g.grainSizeMs - parentGrain.grainSizeMs) > 50f ||
                kotlin.math.abs(g.spread - parentGrain.spread) > 0.1f ||
                kotlin.math.abs(g.pitchSpraySt - parentGrain.pitchSpraySt) > 1f ||
                kotlin.math.abs(g.reverseProb - parentGrain.reverseProb) > 0.1f
            assertTrue("amount=0.9 should move at least one param noticeably", movedFar)
        }
    }

    @Test
    fun `locked params survive mutation at amount 1_0`() {
        val parent = parentSeed()
        val lockMask = MutableParam.GRAIN_SIZE.locked(0L, true).let { MutableParam.DENSITY.locked(it, true) }
        val children = SeedMutation.mutateSix(parent, amount = 1.0f, lockedParams = lockMask)
        val parentGrain = ParamState.unpack(parent.params)

        for (child in children) {
            val g = ParamState.unpack(child.params)
            assertEquals("density must be untouched when locked", parentGrain.density, g.density)
            assertEquals("grainSizeMs must be untouched when locked", parentGrain.grainSizeMs, g.grainSizeMs)
        }
    }
}
