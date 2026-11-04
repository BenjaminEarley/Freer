package com.benjaminearley.freer

/**
 * The nesting depth of interpretation, for the tests of the stack-safety invariant. Every
 * entry into force() or an interpreter loop while another is still running is one level
 * of nesting. The trampoline and the queued binds exist so that this depth depends only
 * on how handlers are nested inside one another, never on how long a program is, and the
 * tests check exactly that. Tracking is off unless a test switches it on, so the cost in
 * normal use is one field read on entry and one on exit.
 */
internal object Nesting {
    var tracking: Boolean = false
    var depth: Int = 0
    var deepest: Int = 0

    fun enter() {
        if (tracking) {
            depth++
            if (depth > deepest) deepest = depth
        }
    }

    fun exit() {
        if (tracking) depth--
    }
}
