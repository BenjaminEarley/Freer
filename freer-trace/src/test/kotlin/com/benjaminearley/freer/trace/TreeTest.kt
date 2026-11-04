package com.benjaminearley.freer.trace

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.trace.mermaid.flowchart
import com.benjaminearley.freer.effects.raise
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.map
import com.benjaminearley.freer.perform
import com.benjaminearley.freer.program
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Effects with every kind of response type the explorer tells apart.
internal enum class Light { RED, GREEN }

internal sealed interface Door {
    data object Open : Door

    data object Shut : Door
}

internal sealed interface Mixed {
    data object Whole : Mixed

    data class Broken(
        val pieces: Int,
    ) : Mixed
}

internal sealed interface Probe<out R> : Effect<R>

internal data object AskFlag : Probe<Boolean>

internal data object AskLight : Probe<Light>

internal data object AskDoor : Probe<Door>

internal data object AskMixed : Probe<Mixed>

internal data object Ping : Probe<Unit>

internal data object Abort : Probe<Nothing>

internal data object AskCount : Probe<Int>

/** An effect whose response type is its type parameter, witnessed by [default]. */
internal data class AskAs<T>(
    val default: T,
) : Probe<T>

/** An effect whose name is whatever text a test wants in a label. */
internal data class Named(
    val name: String,
) : Probe<Any?> {
    override fun toString(): String = name
}

/** An effect that is not a data class, so prints as its identity by default. */
internal class Plain(
    val n: Int,
    val s: String,
) : Probe<Unit>

private fun Tree.outcomes(): List<String> = paths().map { it.end.outcome() }

class TreeTest {
    @Test
    fun responsesComeFromTheResponseType() {
        assertEquals(listOf(false, true), responses(AskFlag))
        assertEquals(Light.entries, responses(AskLight))
        assertEquals(listOf(Door.Open, Door.Shut), responses(AskDoor))
        assertEquals(listOf(Unit), responses(Ping))
        assertEquals(emptyList(), responses(Abort), "Nothing has no values")
        assertNull(responses(AskCount), "Int is infinite")
        assertNull(responses(AskAs(1)), "Int, witnessed by the default, is infinite")
        assertNull(responses(AskMixed), "a sealed hierarchy with a class in it is not finite")
        assertNull(responses(Named("x")))
    }

    @Test
    fun theTreeUnfoldsEveryResponseAndEndsAtAFailure() {
        val flow =
            program {
                if (perform(AskFlag).bind()) {
                    when (perform(AskLight).bind()) {
                        Light.RED -> raise("stop").bind()
                        Light.GREEN -> {}
                    }
                    "went"
                } else {
                    perform(Ping).bind()
                    "waited"
                }
            }
        assertEquals(
            listOf(
                "AskFlag=false, Ping=kotlin.Unit -> returns waited",
                "AskFlag=true, AskLight=RED -> raises stop",
                "AskFlag=true, AskLight=GREEN -> returns went",
            ),
            flow.tree().paths().map { path -> path.answers.joinToString { "${it.effect}=${it.response}" } + " -> " + path.end.outcome() },
        )
    }

    @Test
    fun anInfiniteResponseTypeNeedsRepresentatives() {
        val flow = perform(AskCount).map { it + 1 }
        val failure = assertFailsWith<IllegalStateException> { flow.tree() }
        assertTrue("AskCount" in failure.message.orEmpty() && "ofType<Int>" in failure.message.orEmpty(), failure.message)
        assertEquals(listOf("returns 1", "returns 3"), flow.tree { ofType<Int>(0, 2) }.outcomes())
    }

    @Test
    fun aTypeParameterIsWitnessedByAProperty() {
        assertEquals(listOf("returns 0", "returns 2"), perform(AskAs(1)).tree { ofType<Int>(0, 2) }.outcomes())
        val failure = assertFailsWith<IllegalStateException> { perform(AskAs("a")).tree { ofType<Int>(0) } }
        assertTrue("kotlin.String" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun representativesAreCheckedAgainstTheResponseType() {
        val failure = assertFailsWith<IllegalStateException> { perform(AskCount).tree { ofEffect<AskCount>(1, "x") } }
        assertTrue("kotlin.Int" in failure.message.orEmpty() && "x" in failure.message.orEmpty(), failure.message)
        assertEquals(listOf("returns null", "returns 1"), perform(Named("n")).tree { ofEffect<Named>(null, 1) }.outcomes(), "Any? admits null")
    }

    @Test
    fun effectRepresentativesWinOverTheResponseType() {
        assertEquals(listOf("returns true"), perform(AskFlag).tree { ofEffect<AskFlag>(true) }.outcomes())
    }

    @Test
    fun aRuleMayDependOnTheEffect() {
        val tree = perform(Named("abc")).tree { ofEffect { if (it is Named) listOf(it.name, it.name.length) else null } }
        assertEquals(listOf("returns abc", "returns 3"), tree.outcomes())
    }

    @Test
    fun aProgramThatIsNotAFunctionOfItsAnswersIsRejected() {
        var runs = 0
        val flow =
            program {
                runs++
                if (runs == 1) perform(AskFlag).bind() else perform(Ping).bind()
                "done"
            }
        val failure = assertFailsWith<IllegalStateException> { flow.tree() }
        assertTrue("not a function of its answers" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun identicalSubtreesAreDrawnOnce() {
        val flow = perform(AskFlag).flatMap { perform(Ping) }.map { "done" }
        val flowchart = flow.tree().flowchart()
        assertEquals(
            """
            flowchart TD
                n0["AskFlag"]
                n0 -- "false" --> n1
                n1["Ping"]
                n1 --> n2
                n2(["returns done"])
                n0 -- "true" --> n1
            """.trimIndent(),
            flowchart,
        )
        recordDiagram("identical subtrees drawn once", flowchart)
    }

    @Test
    fun aResponseWhoseSubtreeHasASiblingsShapeIsRedundant() {
        val flow = perform(AskCount).flatMap { n -> if (n < 0) raise("negative") else perform(Ping).map { "ok" } }
        assertEquals(listOf(Answer(AskCount, 2)), flow.tree { ofType<Int>(-1, 1, 2) }.redundantResponses())
        assertEquals(emptyList(), flow.tree { ofType<Int>(-1, 1) }.redundantResponses())
    }

    @Test
    fun describeShowsThePropertiesOfAnEffectThatPrintsAsItsIdentity() {
        assertEquals("Plain(n=1, s=x)", describe(Plain(1, "x")))
        assertEquals("AskFlag", describe(AskFlag))
        assertEquals("kotlin.Unit", describe(Unit))
        assertEquals("null", describe(null))
        assertEquals(listOf("returns p"), perform(Plain(1, "x")).map { "p" }.tree().outcomes(), "Unit is finite")
    }
}
