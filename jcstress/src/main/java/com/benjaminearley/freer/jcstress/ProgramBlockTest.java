package com.benjaminearley.freer.jcstress;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;

/**
 * One unhandled block, shared, interpreted by two threads under handlers of their own with
 * different environments. Neither thread's handlers may show through in the other's value.
 */
@JCStressTest
@Outcome(id = "29, 30", expect = ACCEPTABLE, desc = "Each thread sees its own environment.")
@State
public class ProgramBlockTest {
    @Actor
    public void actor1(II_Result r) {
        r.r1 = SharedPrograms.runBlock(1);
    }

    @Actor
    public void actor2(II_Result r) {
        r.r2 = SharedPrograms.runBlock(2);
    }
}
