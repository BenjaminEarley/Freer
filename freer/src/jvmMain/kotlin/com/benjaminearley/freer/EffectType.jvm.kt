package com.benjaminearley.freer

import kotlin.reflect.KClass

internal actual fun matchesEffect(
    type: KClass<*>,
    effect: Any?,
): Boolean = type.java.isInstance(effect)
