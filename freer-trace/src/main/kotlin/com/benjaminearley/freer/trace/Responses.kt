@file:Suppress("UNCHECKED_CAST")

package com.benjaminearley.freer.trace

import com.benjaminearley.freer.Effect
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.full.allSupertypes
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Every value of the response type of [effect] when that type is finite: `false` and `true`,
 * the constants of an enum, the objects of a sealed hierarchy, `Unit`, or no value at all for
 * an effect declared to respond with `Nothing`. Null when the type is infinite, or unknown
 * because it is a type parameter of the effect class that no property witnesses.
 */
public fun responses(effect: Effect<*>): List<Any?>? = (responseTypeOf(effect) as? ResponseType.Known)?.let { finiteValues(it.type) }

/**
 * [value] as text: its `toString`, unless that is the default identity form, in which case its
 * class name and its properties, as a data class would print them.
 */
public fun describe(value: Any?): String {
    if (value == null) return "null"
    val text = value.toString()
    if (!IDENTITY.matches(text)) return text
    val cls = value::class
    val properties = cls.memberProperties as Collection<KProperty1<Any, *>>
    val ordered = cls.primaryConstructor?.parameters?.mapNotNull { parameter -> properties.firstOrNull { it.name == parameter.name } } ?: properties.sortedBy { it.name }
    return (cls.simpleName ?: text) + ordered.joinToString(", ", "(", ")") { "${it.name}=${describe(runCatching { it.get(value) }.getOrNull())}" }
}

private val IDENTITY = Regex("""[\w.$]+@[0-9a-f]+""")

// Effects that define no equality of their own are the same effect when they are described the
// same, and a stand-in read from text stands for an effect of its class with its description.
internal fun sameEffect(
    recorded: Effect<*>,
    performed: Effect<*>,
): Boolean =
    when {
        recorded == performed -> true
        recorded is Described -> recorded.className == performed::class.simpleName && recorded.text == describe(performed)
        else -> recorded::class == performed::class && describe(recorded) == describe(performed)
    }

/** The response type of an effect, as far as it can be known. */
internal sealed interface ResponseType {
    data class Known(
        val type: KClass<*>,
        val nullable: Boolean,
    ) : ResponseType

    /** A type parameter of the effect class that no property of the effect witnesses. */
    data class Parameter(
        val name: String,
    ) : ResponseType
}

// The argument of Effect among the supertypes of an effect class, read from Kotlin's own
// metadata: the JVM signature cannot say Nothing, and drops the whole supertype when it would.
// A type parameter is resolved through the first property declared with it, whose value's
// class witnesses the type, as the default value of a read does.
internal fun responseTypeOf(effect: Effect<*>): ResponseType? {
    val declared = DECLARED.getOrPut(effect::class) { Declared(effect::class) }
    return when (val classifier = declared.type?.classifier) {
        is KClass<*> -> ResponseType.Known(classifier, declared.type.isMarkedNullable)
        is KTypeParameter ->
            declared.witness
                ?.let { witness -> runCatching { witness.get(effect) }.getOrNull() }
                ?.let { value -> ResponseType.Known(value::class, declared.type.isMarkedNullable) }
                ?: ResponseType.Parameter(classifier.name)

        else -> null
    }
}

private class Declared(
    cls: KClass<out Effect<*>>,
) {
    val type: KType? = cls.allSupertypes.firstOrNull { it.classifier == Effect::class }?.arguments?.single()?.type
    val witness: KProperty1<Any, *>? =
        (type?.classifier as? KTypeParameter)?.let { parameter ->
            (cls.memberProperties as Collection<KProperty1<Any, *>>).firstOrNull { it.returnType.classifier == parameter }
        }
}

private val DECLARED = ConcurrentHashMap<KClass<*>, Declared>()

internal fun finiteValues(type: KClass<*>): List<Any?>? =
    when {
        type == Boolean::class -> listOf(false, true)
        type == Unit::class -> listOf(Unit)
        type == Nothing::class -> emptyList()
        type.java.isEnum -> type.java.enumConstants.toList()
        else -> objectsOf(type)
    }

// A Kotlin object, or a sealed hierarchy of them; null for anything else.
private fun objectsOf(type: KClass<*>): List<Any?>? {
    type.objectInstance?.let { return listOf(it) }
    if (!type.isSealed) return null
    return buildList { for (sub in type.sealedSubclasses) addAll(objectsOf(sub) ?: return null) }
}
