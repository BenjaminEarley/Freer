/** Every stack-safety scenario at a length far beyond what unbounded nesting would survive. */

package com.benjaminearley.freer

import kotlin.test.Test

class TrampolineTest {
    private fun scenario(number: Int) = trampolineScenarios[number - 1].run(N)

    @Test
    fun interpretLowLevelDirectResume() = scenario(1)

    @Test
    fun handleDslDonePathOptimization() = scenario(2)

    @Test
    fun handleWithForwardingPastNonMatchingHandler() = scenario(3)

    @Test
    fun interpretSStatefulHandlerExtractsFinalState() = scenario(4)

    @Test
    fun interceptThenHandleDeferredResume() = scenario(5)

    @Test
    fun leftAssociatedFlatMapChainThenHandle() = scenario(6)

    @Test
    fun deepNonTailRecursionWithWorkAfterSuspendingBind() = scenario(7)

    @Test
    fun longChainsOfPureAlreadyDoneBinds() = scenario(8)

    @Test
    fun longFlatMapChainsOntoProgramHeadUseDeferPendingQueue() = scenario(9)

    @Test
    fun longMapChainsOntoProgramHead() = scenario(10)

    @Test
    fun longFlatMapChainsOntoAlreadyHandledHead() = scenario(11)

    @Test
    fun interpretWithResumeBoundInsideProgramBlock() = scenario(12)

    @Test
    fun sequentialNondeterminismDepthThroughCollectAll() = scenario(13)

    @Test
    fun effectFreeNonTailBindRecursion() = scenario(14)

    @Test
    fun nestedProgramBlockRecursionWithoutAnEffect() = scenario(15)

    @Test
    fun recursiveBindBeforeTheEffect() = scenario(16)

    @Test
    fun interpretWithTheResumedProgramTransformedInPlace() = scenario(17)

    @Test
    fun stateThreadedAsFunctionsInTheResult() = scenario(18)

    @Test
    fun middlewareViaInterpretThenHandle() = scenario(19)

    private companion object {
        const val N = 100_000 // Well beyond the ~5-10K stack limit
    }
}
