package com.benjaminearley.freer

/** One of two values, with structural equality so that a test can name the outcome it expects. */
sealed interface Either<out L, out R>

data class Left<out L>(
    val value: L,
) : Either<L, Nothing>

data class Right<out R>(
    val value: R,
) : Either<Nothing, R>

/** The outcome of attempt() as an [Either], a failure reduced to its message so that it compares structurally. */
fun <A> Program<Result<A>>.toEither(): Program<Either<String, A>> =
    map { outcome -> outcome.fold({ value -> Right(value) }, { failure -> Left(failure.message.orEmpty()) }) }
