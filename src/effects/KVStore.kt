package effects

import Effect
import Program
import interpret
import perform

fun <A> Program<A>.runKVStore(data: MutableMap<String, Any>): Program<A> =
    interpret<KVStore<*>, A> { op, resume ->
        when (op) {
            is Get<*> -> {
                val valFromDb = data[op.key] ?: op.default
                resume(valFromDb)
            }

            is Put<*> -> {
                data[op.key] = op.value as Any
                resume(Unit)
            }
        }
    }

sealed interface KVStore<out R> : Effect<R>

data class Get<T>(
    val key: String,
    val default: T,
) : KVStore<T>

data class Put<T>(
    val key: String,
    val value: T,
) : KVStore<Unit>

// Helpers
fun <T> get(
    key: String,
    default: T,
) = perform(Get(key, default))

fun <T> put(
    key: String,
    value: T,
) = perform(Put(key, value))
