package com.benjaminearley.freer

import com.benjaminearley.freer.effects.ioBlocking
import com.benjaminearley.freer.effects.performIO
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IoBlockingTest {
    @Test
    fun ioBlockingKeepsInterpretationOnTheCallingThread() {
        val caller = Thread.currentThread().name
        val prog =
            program {
                val before = performIO { Thread.currentThread().name }.bind()
                val async =
                    performIO {
                        suspendCoroutine { cont ->
                            thread(name = "test-callback") {
                                cont.resume(Thread.currentThread().name)
                            }
                        }
                    }.bind()
                val after = performIO { Thread.currentThread().name }.bind()
                Triple(before, async, after)
            }
        val (before, async, after) = prog.ioBlocking()
        assertEquals(caller, before, "Interpretation escaped the calling thread before the async thunk")
        assertEquals("test-callback", async)
        assertEquals(caller, after, "Interpretation escaped the calling thread after the async thunk")
    }

    @Test
    fun ioBlockingRethrowsAsyncThunkFailuresOnTheCallingThread() {
        val e =
            assertFailsWith<IllegalStateException> {
                program {
                    performIO {
                        suspendCoroutine<Int> { cont ->
                            thread { cont.resumeWithException(IllegalStateException("async boom")) }
                        }
                    }.bind()
                }.ioBlocking()
            }
        assertEquals("async boom", e.message)
    }
}
