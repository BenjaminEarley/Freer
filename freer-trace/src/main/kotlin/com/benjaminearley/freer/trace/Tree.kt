package com.benjaminearley.freer.trace

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.effects.Failure
import com.benjaminearley.freer.interpret
import com.benjaminearley.freer.runOrThrow
import java.util.IdentityHashMap
import kotlin.reflect.KClass

/**
 * The tree of a program under finite response sets. A program is a value: a tree whose nodes
 * are the effects it performs and whose edges are the responses it may receive, with a return
 * value at every leaf. Nothing in the value says what an effect does; the handlers applied to it
 * decide that when it runs, and one run under one stack is a [Trace]. The tree is the program
 * itself, unfolded: an effect whose response type is finite (Boolean, an enum, a sealed
 * hierarchy of objects, Unit) under every response, an effect whose response type is infinite
 * under the [Representatives] the caller names, and an effect that can have no response at all,
 * such as a failure, as a leaf. [tree] builds it.
 */
public sealed interface Tree {
    /** An effect and the subtree under each of its responses, in the order the responses were given. */
    public data class Step(
        val effect: Effect<*>,
        val branches: List<Branch>,
    ) : Tree

    /** Where a path ends. */
    public sealed interface Leaf : Tree

    /** The program finished with [value]. */
    public data class Returns(
        val value: Any?,
    ) : Leaf

    /** The program performed an effect that has no response, such as a failure, so it goes no further. */
    public data class Stops(
        val effect: Effect<*>,
    ) : Leaf
}

/** One response to an effect and the subtree under it. */
public data class Branch(
    val response: Any?,
    val subtree: Tree,
)

/** An effect with the response it received. */
public data class Answer(
    val effect: Effect<*>,
    val response: Any?,
)

/** One path through a [Tree]: the effects performed with their responses, and the leaf it ends in. */
public data class Path(
    val answers: List<Answer>,
    val end: Tree.Leaf,
)

/** What a leaf says: the value returned, or the effect that ended the path. */
public fun Tree.Leaf.outcome(): String =
    when (this) {
        is Tree.Returns -> "returns ${describe(value)}"
        is Tree.Stops -> if (effect is Failure.Raise) "raises ${effect.reason}" else "stops at ${describe(effect)}"
    }

/** Every path of the tree, in the order the branches were given. */
public fun Tree.paths(): List<Path> {
    val paths = mutableListOf<Path>()
    fun walk(
        tree: Tree,
        answers: List<Answer>,
    ) {
        when (tree) {
            is Tree.Leaf -> paths += Path(answers, tree)
            is Tree.Step -> for (branch in tree.branches) walk(branch.subtree, answers + Answer(tree.effect, branch.response))
        }
    }
    walk(this, emptyList())
    return paths
}

/**
 * The responses that opened no path of their own: at an effect, a response whose subtree has
 * the shape of an earlier response's, the shape of a subtree being its effects by class and
 * its leaves by outcome, with responses and effect arguments ignored. Such a response adds no
 * effect and no outcome a reader could not reach through its sibling. The representatives of
 * an infinite response type are well chosen when none of them is listed here.
 */
public fun Tree.redundantResponses(): List<Answer> {
    val shapes = IdentityHashMap<Tree, Any>()
    fun shape(tree: Tree): Any =
        shapes.getOrPut(tree) {
            when (tree) {
                is Tree.Step -> tree.effect::class to tree.branches.map { shape(it.subtree) }
                is Tree.Leaf -> tree.outcome()
            }
        }

    val found = mutableListOf<Answer>()
    fun walk(tree: Tree) {
        if (tree !is Tree.Step) return
        val seen = HashSet<Any>()
        for (branch in tree.branches) {
            if (!seen.add(shape(branch.subtree))) found += Answer(tree.effect, branch.response)
            walk(branch.subtree)
        }
    }
    walk(this)
    return found
}

/**
 * The responses to unfold an effect under when its response type has no finite set of values.
 * [ofEffect] names them for an effect family and wins over everything else; otherwise a finite
 * response type supplies its own values, and [ofType] names them for an infinite one, including
 * a type parameter of the effect class when a property of the effect witnesses it, as a default
 * value of the response type does. Every representative is checked against the response type.
 */
public class Representatives internal constructor() {
    private val byEffect = mutableListOf<Pair<KClass<out Effect<*>>, List<Any?>>>()
    private val byRule = mutableListOf<(Effect<*>) -> List<Any?>?>()
    private val byType = mutableMapOf<KClass<*>, List<Any?>>()

    /** Unfold every effect of [family] under [values]. */
    public fun ofEffect(
        family: KClass<out Effect<*>>,
        values: List<Any?>,
    ) {
        byEffect += family to values
    }

    /** Unfold every effect of family [E] under [values]. */
    public inline fun <reified E : Effect<*>> ofEffect(vararg values: Any?): Unit = ofEffect(E::class, values.toList())

    /** Unfold every effect for which [rule] gives a list under that list; it may depend on the effect's arguments. */
    public fun ofEffect(rule: (Effect<*>) -> List<Any?>?) {
        byRule += rule
    }

    /** Unfold every effect whose response type is [type] under [values]. */
    public fun ofType(
        type: KClass<*>,
        values: List<Any?>,
    ) {
        byType[type] = values
    }

    /** Unfold every effect whose response type is [T] under [values]. */
    public inline fun <reified T : Any> ofType(vararg values: T?): Unit = ofType(T::class, values.toList())

    internal fun of(effect: Effect<*>): List<Any?> {
        val type = responseTypeOf(effect)
        val given =
            byEffect.firstOrNull { (family, _) -> family.isInstance(effect) }?.second
                ?: byRule.firstNotNullOfOrNull { rule -> rule(effect) }
                ?: (type as? ResponseType.Known)?.let { known -> finiteValues(known.type) ?: byType[known.type] }
                ?: error(
                    when (type) {
                        is ResponseType.Known ->
                            "${describe(effect)} answers with ${known(type)}, which has no finite set of values: give representatives with " +
                                "ofType<${type.type.simpleName}>(...) or ofEffect<${effect::class.simpleName}>(...)"

                        is ResponseType.Parameter ->
                            "${describe(effect)} answers with its type parameter ${type.name}, which no property of the effect witnesses: " +
                                "give representatives with ofEffect<${effect::class.simpleName}>(...)"

                        null -> "${describe(effect)} has no known response type: give representatives with ofEffect<${effect::class.simpleName}>(...)"
                    },
                )
        if (type is ResponseType.Known) {
            for (value in given) {
                check(if (value == null) type.nullable else type.type.isInstance(value)) {
                    "${describe(effect)} answers with ${known(type)}, which ${describe(value)} is not"
                }
            }
        }
        return given
    }

    private fun known(type: ResponseType.Known): String = (type.type.qualifiedName ?: type.type.toString()) + if (type.nullable) "?" else ""
}

/**
 * Every path through this program, as its [Tree]. No handler is applied: a stand-in over the
 * root family answers every effect from its [Representatives] or its finite response type, an
 * effect with no response ends the path, and the program is replayed from the start once per
 * path, with the answers of the path so far and the next alternative at the branch point.
 * Replaying is what makes this work for program blocks, whose continuations cannot be resumed
 * twice, and it demands that the program be a function of its answers: a replay that meets a
 * different effect than the one recorded at the same position fails, naming both. The program
 * must also finish under every answer.
 */
public fun <A> Program<A>.tree(representatives: Representatives.() -> Unit = {}): Tree {
    val responses = Representatives().apply(representatives)

    class Choice(
        val effect: Effect<*>,
        val options: List<Any?>,
        val chosen: Int,
    )

    // One run: the answers in [fixed] for the first effects, the first option for every later one.
    fun run(fixed: List<Choice>): Pair<List<Choice>, Tree.Leaf> {
        val path = mutableListOf<Choice>()
        var leaf: Tree.Leaf? = null
        interpret<Effect<*>, A, Unit>(transformDone = { value ->
            leaf = Tree.Returns(value)
            Program.Done(Unit)
        }) { op, resume ->
            val position = path.size
            val recorded = fixed.getOrNull(position)
            if (recorded != null && !sameEffect(recorded.effect, op)) {
                error(
                    "the program is not a function of its answers: replayed with the same answers, it performed " +
                        "${describe(op)} at step ${position + 1} where it had performed ${describe(recorded.effect)}",
                )
            }
            val options = recorded?.options ?: responses.of(op)
            if (options.isEmpty()) {
                leaf = Tree.Stops(op)
                Program.Done(Unit)
            } else {
                val choice = Choice(op, options, recorded?.chosen ?: 0)
                path += choice
                resume(options[choice.chosen])
            }
        }.runOrThrow()
        return path to checkNotNull(leaf)
    }

    // The subtree after [fixed]: the alternatives at earlier positions belong to the callers.
    fun explore(fixed: List<Choice>): Tree {
        val (path, leaf) = run(fixed)
        fun from(position: Int): Tree =
            if (position == path.size) {
                leaf
            } else {
                val choice = path[position]
                Tree.Step(
                    choice.effect,
                    choice.options.indices.map { option ->
                        Branch(
                            choice.options[option],
                            if (option == choice.chosen) {
                                from(position + 1)
                            } else {
                                explore(path.take(position) + Choice(choice.effect, choice.options, option))
                            },
                        )
                    },
                )
            }
        return from(fixed.size)
    }
    return explore(emptyList())
}
