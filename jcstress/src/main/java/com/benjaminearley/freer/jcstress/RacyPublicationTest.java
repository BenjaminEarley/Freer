package com.benjaminearley.freer.jcstress;

import com.benjaminearley.freer.Program;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE_INTERESTING;

/**
 * A program built on one thread and published to another through a plain field, with no
 * synchronization at all. The reader either runs before the publication or interprets a
 * program whose block, handlers and captured environment are all there: a program is made
 * of immutable objects, and that is what makes it safe to share this way.
 */
@JCStressTest
@Outcome(id = "-1", expect = ACCEPTABLE_INTERESTING, desc = "The reader ran before the publication.")
@Outcome(id = "33", expect = ACCEPTABLE, desc = "The reader interpreted the published program.")
@State
public class RacyPublicationTest {
    Program<Integer> program;

    @Actor
    public void publisher() {
        program = SharedPrograms.build(5);
    }

    @Actor
    public void reader(I_Result r) {
        Program<Integer> p = program;
        r.r1 = p == null ? -1 : SharedPrograms.run(p);
    }
}
