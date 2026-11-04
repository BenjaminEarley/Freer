package com.benjaminearley.freer.jcstress;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;

/**
 * One handled search, interpreted by two threads at the same time: three branches replay one
 * continuation under a stateful handler outside the search, so the per-branch blocks and the
 * threaded counter of each interpretation must stay its own.
 */
@JCStressTest
@Outcome(id = "368, 368", expect = ACCEPTABLE, desc = "Both interpretations collect the same three answers.")
@State
public class MultiShotProgramTest {
    @Actor
    public void actor1(II_Result r) {
        r.r1 = SharedPrograms.runSearch();
    }

    @Actor
    public void actor2(II_Result r) {
        r.r2 = SharedPrograms.runSearch();
    }
}
