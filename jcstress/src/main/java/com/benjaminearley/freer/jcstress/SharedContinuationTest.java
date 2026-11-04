package com.benjaminearley.freer.jcstress;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;

/**
 * One continuation kept by a rule, resumed by two threads at the same time with different
 * responses. A resumption after the rule has returned is an immutable program of its own,
 * so neither thread may see the other's response.
 */
@JCStressTest
@Outcome(id = "2, 3", expect = ACCEPTABLE, desc = "Each thread's resumption carries its own response.")
@State
public class SharedContinuationTest {
    @Actor
    public void actor1(II_Result r) {
        r.r1 = SharedPrograms.resumeKept(1);
    }

    @Actor
    public void actor2(II_Result r) {
        r.r2 = SharedPrograms.resumeKept(2);
    }
}
