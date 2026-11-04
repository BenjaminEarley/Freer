@file:Suppress("UNCHECKED_CAST")

package com.benjaminearley.freer.trace

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.interpretS
import com.benjaminearley.freer.perform

/**
 * This program answered from a recorded run: the effects the trace's program performed, with
 * the responses they got, in order, and nothing else handled. The program's own code computes
 * the result from the responses, so the handlers of the recorded run are not needed, and what
 * they did on the side, the log lines and the writes, does not happen. The first effect that
 * differs from the recording fails, naming both, as does a program that finishes before the
 * recording does or performs an effect the recording has no answer for. The effect the
 * recording left unanswered, because its handler ended the run, is passed on unanswered here
 * too, to whatever handler is applied next. A trace read from text answers with the literals
 * the text keeps typed and fails on a response it only describes.
 */
public fun <A> Program<A>.replay(trace: Trace): Program<A> {
    val top = trace.calls()
    val answered = top.takeWhile { it.answered }
    check(top.size - answered.size <= 1) { "the trace has calls after one that was never answered" }
    return replay(answered.map { Answer(it.effect, it.response) }, top.getOrNull(answered.size)?.effect, returns = null)
}

/**
 * This program answered from one path of its tree, as [replay] of a trace: the path's answers
 * in order, a failure the path stops at passed on unanswered, and the value the path returns
 * checked against the program's.
 */
public fun <A> Program<A>.replay(path: Path): Program<A> =
    replay(path.answers, (path.end as? Tree.Stops)?.effect, path.end as? Tree.Returns)

private fun <A> Program<A>.replay(
    answers: List<Answer>,
    unanswered: Effect<*>?,
    returns: Tree.Returns?,
): Program<A> =
    interpretS<Effect<*>, Int, A, A>(
        0,
        transformDone = { position, value ->
            if (position < answers.size) {
                error("the program finished after $position of ${answers.size} recorded effects, before ${describe(answers[position].effect)}")
            }
            if (position == answers.size && unanswered != null) {
                error("the program finished where the recording performed ${describe(unanswered)}")
            }
            if (returns != null && value != returns.value) {
                error("the program returned ${describe(value)} where the recording returned ${describe(returns.value)}")
            }
            Program.Done(value)
        },
    ) { position, op, resume ->
        val step = position + 1
        when {
            position < answers.size -> {
                val recorded = answers[position]
                if (!sameEffect(recorded.effect, op)) {
                    error("the program performed ${describe(op)} at step $step where the recording has ${describe(recorded.effect)}")
                }
                val response = recorded.response
                if (response is Described) {
                    error("the recording only describes the response to ${describe(op)} at step $step: ${response.text}")
                }
                resume(step, response)
            }

            position == answers.size && unanswered != null && sameEffect(unanswered, op) -> perform(op as Effect<Any?>).flatMap { resume(step, it) }

            else -> error("the program performed ${describe(op)} at step $step, which the recording has no answer for")
        }
    }
