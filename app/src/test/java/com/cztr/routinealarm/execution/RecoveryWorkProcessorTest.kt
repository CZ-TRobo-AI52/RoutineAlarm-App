package com.cztr.routinealarm.execution

import com.cztr.routinealarm.occurrence.RecoveryBatchResult
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

/** Queue protocol only; Android service/SQLite/AlarmManager integration is covered separately. */
class RecoveryWorkProcessorTest {
    @Test fun newRequestDuringAnIncompleteBatchRunsAfterCurrentCycleCompletes() {
        val pending = ArrayDeque(listOf("first"))
        val batches = mutableListOf<String>()
        val completed = mutableListOf<String>()
        RecoveryWorkProcessor(
            dequeue = { pending.pollFirst() },
            cycleId = { it: String -> it },
            recover = { cycle, _ ->
                batches.add(cycle)
                if (batches.size == 1) {
                    pending.addLast("requested-during-work")
                    RecoveryBatchResult(64, false, 0)
                } else RecoveryBatchResult(1, true, 0)
            },
            complete = completed::add,
        ).run { false }
        assertEquals(listOf("first", "first", "requested-during-work"), batches)
        assertEquals(listOf("first", "requested-during-work"), completed)
    }

    @Test fun stopDuringBatchDoesNotAcknowledgeEvenIfThatBatchReportsComplete() {
        val pending = ArrayDeque(listOf("interrupted", "later"))
        val completed = mutableListOf<String>()
        var stopped = false
        RecoveryWorkProcessor(
            dequeue = { pending.pollFirst() },
            cycleId = { it: String -> it },
            recover = { _, shouldStop ->
                assertFalse(shouldStop())
                stopped = true
                assertTrue(shouldStop())
                RecoveryBatchResult(1, true, 0)
            },
            complete = completed::add,
        ).run { stopped }
        assertTrue(completed.isEmpty())
        assertEquals(listOf("later"), pending.toList())
    }

    @Test fun checkpointFailureLeavesWorkUnacknowledgedAndLaterRequestsUntouched() {
        val pending = ArrayDeque(listOf("failed-cycle", "later"))
        val completed = mutableListOf<String>()
        val failure = java.io.IOException("cursor persistence failed")
        val thrown = assertThrows(java.io.IOException::class.java) {
            RecoveryWorkProcessor(
                dequeue = { pending.pollFirst() },
                cycleId = { it: String -> it },
                recover = { _, _ -> throw failure },
                complete = completed::add,
            ).run { false }
        }
        assertSame(failure, thrown)
        assertTrue(completed.isEmpty())
        assertEquals(listOf("later"), pending.toList())
    }
}
