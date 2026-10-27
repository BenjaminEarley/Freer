package com.benjaminearley.freer

import kotlin.reflect.KClass

/**
 * Whether [effect] belongs to the effect family [type] that a handler targets.
 *
 * Every interpreter step runs this test, so it is platform-specific: on the JVM,
 * Class.isInstance is an intrinsic the JIT compiles down to a type check, while
 * KClass.isInstance goes through a reflection wrapper first; on the other
 * platforms KClass.isInstance is the native type check.
 */
internal expect fun matchesEffect(
    type: KClass<*>,
    effect: Any?,
): Boolean
