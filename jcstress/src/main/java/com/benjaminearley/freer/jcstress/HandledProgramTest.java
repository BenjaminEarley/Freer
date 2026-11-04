package com.benjaminearley.freer.jcstress;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;

/**
 * One already-handled program, built once when the shared programs load, interpreted by two
 * threads at the same time. Each interpretation creates its own coroutine for the block and
 * its own handler state, so both threads must see the value one thread computes.
 */
@JCStressTest
@Outcome(id = "128, 128", expect = ACCEPTABLE, desc = "Both interpretations compute the block's value.")
@State
public class HandledProgramTest {
    @Actor
    public void actor1(II_Result r) {
        r.r1 = SharedPrograms.runHandled();
    }

    @Actor
    public void actor2(II_Result r) {
        r.r2 = SharedPrograms.runHandled();
    }
}
