package com.benjaminearley.freer.jcstress;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;

/**
 * One handled program with a middleware in its stack, interpreted by two threads at the same
 * time. The middleware's rule parks a coroutine between re-performing the effect and
 * answering it, and that coroutine must belong to one interpretation alone.
 */
@JCStressTest
@Outcome(id = "228, 228", expect = ACCEPTABLE, desc = "Both interpretations see the doubled environment.")
@State
public class InterceptedProgramTest {
    @Actor
    public void actor1(II_Result r) {
        r.r1 = SharedPrograms.runIntercepted();
    }

    @Actor
    public void actor2(II_Result r) {
        r.r2 = SharedPrograms.runIntercepted();
    }
}
