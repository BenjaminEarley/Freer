/*
 * Random programs for the law tests. A program is generated as a small syntax tree over a
 * fixed alphabet of effects and then compiled three ways: into a Program built with flatMap,
 * into a Program built with program{} blocks, and into a model program. One tree, three
 * interpretations of it, which is what lets the tests compare them.
 */
package com.benjaminearley.freer.laws

import com.benjaminearley.freer.Program
import com.benjaminearley.freer.effects.ask
import com.benjaminearley.freer.effects.choose
import com.benjaminearley.freer.effects.getState
import com.benjaminearley.freer.effects.putState
import com.benjaminearley.freer.effects.raise
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.map
import com.benjaminearley.freer.program
import com.benjaminearley.freer.reference.Ref
import com.benjaminearley.freer.reference.ask as refAsk
import com.benjaminearley.freer.reference.choose as refChoose
import com.benjaminearley.freer.reference.flatMap
import com.benjaminearley.freer.reference.getState as refGetState
import com.benjaminearley.freer.reference.map
import com.benjaminearley.freer.reference.pure
import com.benjaminearley.freer.reference.putState as refPutState
import com.benjaminearley.freer.reference.raise as refRaise
import com.benjaminearley.freer.reference.send
import io.kotest.property.Arb
import io.kotest.property.Shrinker
import io.kotest.property.arbitrary.arbitrary
import kotlin.random.Random

/** A program producing an Int. [Seq] binds: inside its [Seq.rest], [Arg] 0 is the value of [Seq.first]. */
sealed interface Expr {
    data class Lit(
        val value: Int,
    ) : Expr

    /** The value bound by the [index]-th enclosing [Seq], counting from the nearest. */
    data class Arg(
        val index: Int,
    ) : Expr

    data class Sum(
        val left: Expr,
        val right: Expr,
    ) : Expr

    /** A tick, producing the count. */
    data object Step : Expr

    /** Read the state. */
    data object Read : Expr

    /** Write the state, producing the value written. */
    data class Write(
        val value: Expr,
    ) : Expr

    /** Read the environment. */
    data object Env : Expr

    /** Choose one of the options, nondeterministically. */
    data class Pick(
        val options: List<Int>,
    ) : Expr

    data class Fail(
        val reason: String,
    ) : Expr

    data class Seq(
        val first: Expr,
        val rest: Expr,
    ) : Expr
}

/** Which effects a generated program may use. */
data class Alphabet(
    val tick: Boolean = true,
    val state: Boolean = true,
    val reader: Boolean = true,
    val choose: Boolean = true,
    val raise: Boolean = true,
)

private const val MAX_CHOICE_POINTS = 3

// Grows a tree of at most [depth] levels, with [scope] values in scope and a bounded number of
// real choice points, so that the branches of one program stay countable.
private class ExprGrower(
    private val random: Random,
    private val alphabet: Alphabet,
) {
    private var choicePoints = 0

    fun grow(
        depth: Int,
        scope: Int,
    ): Expr {
        if (depth == 0 || random.nextInt(10) < 3) return leaf(scope)
        return when (random.nextInt(if (alphabet.state) 3 else 2)) {
            0 -> Expr.Seq(grow(depth - 1, scope), grow(depth - 1, scope + 1))
            1 -> Expr.Sum(grow(depth - 1, scope), grow(depth - 1, scope))
            else -> Expr.Write(grow(depth - 1, scope))
        }
    }

    // Weighted so that a failure, which ends the rest of the program, is the rarest leaf and a
    // real choice between options the most common effect.
    private fun leaf(scope: Int): Expr {
        val choices = mutableListOf<Pair<Int, () -> Expr>>(2 to { Expr.Lit(random.nextInt(-3, 4)) })
        if (scope > 0) choices += 2 to { Expr.Arg(random.nextInt(scope)) }
        if (alphabet.tick) choices += 2 to { Expr.Step }
        if (alphabet.state) choices += 2 to { Expr.Read }
        if (alphabet.reader) choices += 2 to { Expr.Env }
        if (alphabet.choose) {
            choices += 3 to {
                val count =
                    when {
                        choicePoints >= MAX_CHOICE_POINTS -> random.nextInt(0, 2)
                        random.nextInt(10) < 3 -> random.nextInt(0, 2)
                        else -> random.nextInt(2, 4)
                    }
                if (count > 1) choicePoints++
                Expr.Pick(List(count) { random.nextInt(-3, 4) })
            }
        }
        if (alphabet.raise) choices += 1 to { Expr.Fail("e" + random.nextInt(3)) }
        var pick = random.nextInt(choices.sumOf { it.first })
        for ((weight, make) in choices) {
            if (pick < weight) return make()
            pick -= weight
        }
        return choices.last().second()
    }
}

// Removes the binding at [depth]: references to it become zero, references above it move down.
private fun Expr.unbind(depth: Int = 0): Expr =
    when (this) {
        is Expr.Arg -> if (index == depth) Expr.Lit(0) else if (index > depth) Expr.Arg(index - 1) else this
        is Expr.Seq -> Expr.Seq(first.unbind(depth), rest.unbind(depth + 1))
        is Expr.Sum -> Expr.Sum(left.unbind(depth), right.unbind(depth))
        is Expr.Write -> Expr.Write(value.unbind(depth))
        else -> this
    }

// Shrinks towards a literal zero: sub-trees replace their parent, options drop out one at a time.
private object ExprShrinker : Shrinker<Expr> {
    override fun shrink(value: Expr): List<Expr> =
        when (value) {
            is Expr.Lit -> if (value.value == 0) emptyList() else listOf(Expr.Lit(0))
            is Expr.Arg, Expr.Step, Expr.Read, Expr.Env, is Expr.Fail -> listOf(Expr.Lit(0))
            is Expr.Pick ->
                listOf(Expr.Lit(0)) +
                    value.options.indices.map { dropped -> Expr.Pick(value.options.filterIndexed { i, _ -> i != dropped }) }

            is Expr.Write -> listOf(value.value, Expr.Lit(0)) + shrink(value.value).map { Expr.Write(it) }
            is Expr.Sum ->
                listOf(value.left, value.right) +
                    shrink(value.left).map { Expr.Sum(it, value.right) } +
                    shrink(value.right).map { Expr.Sum(value.left, it) }

            is Expr.Seq ->
                listOf(value.first, value.rest.unbind()) +
                    shrink(value.first).map { Expr.Seq(it, value.rest) } +
                    shrink(value.rest).map { Expr.Seq(value.first, it) }
        }
}

/** Random programs over [alphabet], with [scope] values already in scope (a continuation has one). */
fun arbExpr(
    alphabet: Alphabet = Alphabet(),
    scope: Int = 0,
    depth: Int = 5,
): Arb<Expr> = arbitrary(ExprShrinker) { rs -> ExprGrower(rs.random, alphabet).grow(depth, scope) }

/** The program as a chain of flatMaps. */
fun Expr.toProgram(env: List<Int> = emptyList()): Program<Int> =
    when (this) {
        is Expr.Lit -> Program.Done(value)
        is Expr.Arg -> Program.Done(env.getOrElse(index) { 0 })
        is Expr.Sum -> left.toProgram(env).flatMap { a -> right.toProgram(env).map { b -> a + b } }
        Expr.Step -> tick()
        Expr.Read -> getState()
        is Expr.Write -> value.toProgram(env).flatMap { v -> putState(v).map { v } }
        Expr.Env -> ask()
        is Expr.Pick -> choose(options)
        is Expr.Fail -> raise(reason)
        is Expr.Seq -> first.toProgram(env).flatMap { v -> rest.toProgram(listOf(v) + env) }
    }

/** The program as nested program{} blocks, one bind per sub-program. */
fun Expr.toBlock(env: List<Int> = emptyList()): Program<Int> =
    program {
        when (this@toBlock) {
            is Expr.Lit -> value
            is Expr.Arg -> env.getOrElse(index) { 0 }
            is Expr.Sum -> left.toBlock(env).bind() + right.toBlock(env).bind()
            Expr.Step -> tick().bind()
            Expr.Read -> getState<Int>().bind()
            is Expr.Write -> {
                val v = value.toBlock(env).bind()
                putState(v).bind()
                v
            }

            Expr.Env -> ask<Int>().bind()
            is Expr.Pick -> choose(options).bind()
            is Expr.Fail -> raise(reason).bind()
            is Expr.Seq -> {
                val v = first.toBlock(env).bind()
                rest.toBlock(listOf(v) + env).bind()
            }
        }
    }

/**
 * The program in the model. With [completed], every sub-program reports each time it
 * produces a value, keyed by its path from the root ("" for the root, "0"/"1" for the
 * children of a node, and so on); a value produced twice means the sub-program's
 * continuation was resumed twice.
 */
fun Expr.toRef(
    env: List<Int> = emptyList(),
    completed: ((String) -> Unit)? = null,
    path: String = "",
): Ref<Int> {
    val body: Ref<Int> =
        when (this) {
            is Expr.Lit -> pure(value)
            is Expr.Arg -> pure(env.getOrElse(index) { 0 })
            is Expr.Sum -> left.toRef(env, completed, path + "0").flatMap { a -> right.toRef(env, completed, path + "1").map { b -> a + b } }
            Expr.Step -> send(Tick)
            Expr.Read -> refGetState()
            is Expr.Write -> value.toRef(env, completed, path + "0").flatMap { v -> refPutState(v).map { v } }
            Expr.Env -> refAsk()
            is Expr.Pick -> refChoose(options)
            is Expr.Fail -> refRaise(reason)
            is Expr.Seq -> first.toRef(env, completed, path + "0").flatMap { v -> rest.toRef(listOf(v) + env, completed, path + "1") }
        }
    return if (completed == null) body else body.map { completed(path); it }
}
