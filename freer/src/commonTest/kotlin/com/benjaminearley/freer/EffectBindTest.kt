package com.benjaminearley.freer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** An effect bound directly in a block is the one-step program perform makes of it. */
class EffectBindTest {
    private data object Stop : Effect<Nothing>

    @Test
    fun anEffectBindsAsItsOneStepProgram() {
        val direct =
            program {
                val n = StoreGet("n", 0).bind()
                StorePut("n", n + 1).bind()
                n
            }
        val viaPerform =
            program {
                val n = perform(StoreGet("n", 0)).bind()
                perform(StorePut("n", n + 1)).bind()
                n
            }
        val data = mutableMapOf("n" to 1)
        assertEquals(1, direct.testStore(data).runOrThrow())
        assertEquals(2, data["n"])
        assertEquals(2, viaPerform.testStore(data).runOrThrow())
        assertEquals(3, data["n"])
    }

    @Test
    fun aBoundEffectWithoutAResponseEndsTheBlock() {
        val reached = mutableListOf<String>()
        val stopped =
            program {
                reached += "before"
                Stop.bind()
            }.interpret<Stop, Int> { _, _ -> Program.Done(-1) }
        assertEquals(-1, stopped.runOrThrow())
        assertEquals(listOf("before"), reached)
    }

    @Test
    fun aBoundEffectIsSingleShotLikeAnyBind() {
        val twice =
            program {
                StoreGet("n", 0).bind() + 1
            }.interpret<TestStore<*>, Int> { _, resume -> resume(1).flatMap { resume(2) } }
        assertFailsWith<IllegalStateException> { twice.runOrThrow() }
    }
}
