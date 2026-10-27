package com.benjaminearley.freer

/**
 * A string-keyed store effect handled over a MutableMap. The handler checks writes
 * against the map's reified value type and reads against the type of the Get's
 * default, so a mistyped operation fails at the handler instead of corrupting the
 * map or the program.
 */
sealed interface TestStore<out R> : Effect<R>

data class StoreGet<T>(
    val key: String,
    val default: T,
) : TestStore<T>

data class StorePut<T>(
    val key: String,
    val value: T,
) : TestStore<Unit>

fun <T> storeGet(
    key: String,
    default: T,
) = perform(StoreGet(key, default))

fun <T> storePut(
    key: String,
    value: T,
) = perform(StorePut(key, value))

// A stored null is returned as is; only an absent key falls back to the default.
inline fun <reified V, A> Program<A>.testStore(data: MutableMap<String, V>): Program<A> =
    handle<TestStore<*>, A> { op ->
        when (op) {
            is StoreGet<*> -> {
                if (data.containsKey(op.key)) checkStoreGetType(op, data[op.key]) else op.default
            }

            is StorePut<*> -> {
                val value = op.value
                check(value is V) {
                    "TestStore handler: Put(\"${op.key}\") carries a ${storeTypeName(value)}, which " +
                        "this store's value type cannot hold."
                }
                data[op.key] = value
            }
        }
    }

// A Get's expected type is only known through its default. The check applies only
// when either side is a scalar: the runtime class of a structured value such as a
// list says nothing about its element type, and two list implementations are
// compatible even though neither class is an instance of the other.
fun checkStoreGetType(
    op: StoreGet<*>,
    stored: Any?,
): Any? {
    val default = op.default
    if (stored != null &&
        default != null &&
        (isScalarLike(stored) || isScalarLike(default)) &&
        !default::class.isInstance(stored) &&
        !stored::class.isInstance(default)
    ) {
        throw IllegalStateException(
            "TestStore handler: Get(\"${op.key}\") expects a ${storeTypeName(default)} but the " +
                "store holds a ${storeTypeName(stored)}.",
        )
    }
    return stored
}

fun isScalarLike(value: Any): Boolean = value is Number || value is Boolean || value is Char || value is CharSequence

fun storeTypeName(value: Any?): String = if (value == null) "null" else value::class.simpleName ?: "unknown"
