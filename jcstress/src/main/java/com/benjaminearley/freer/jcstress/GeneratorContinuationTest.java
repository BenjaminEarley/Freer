package com.benjaminearley.freer.jcstress;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;

/**
 * One continuation that outlived its interpretation, the first yield of a generator built
 * from flatMap alone, resumed by two threads at the same time. A multi-shot continuation is
 * immutable data, so both resumptions must reach the generator's second yield.
 */
@JCStressTest
@Outcome(id = "2, 2", expect = ACCEPTABLE, desc = "Both resumptions reach the second yield.")
@State
public class GeneratorContinuationTest {
    @Actor
    public void actor1(II_Result r) {
        r.r1 = SharedPrograms.resumeFirstYield();
    }

    @Actor
    public void actor2(II_Result r) {
        r.r2 = SharedPrograms.resumeFirstYield();
    }
}
