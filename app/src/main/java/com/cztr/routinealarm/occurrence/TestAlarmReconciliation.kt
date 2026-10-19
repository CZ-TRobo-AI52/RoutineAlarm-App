package com.cztr.routinealarm.occurrence

/** A possible effect reconstructed from our own attempts, not a platform inventory. */
data class KnownTestDelivery(val firstSequence: Long, val token: AlarmDelivery)

enum class CancellationObservation { TOKEN_NOT_FOUND, CALL_RETURNED, NO_LONGER_OBSOLETE }

/** Transient observations only; neither cancellation nor delivery is durably certified. */
data class ReconciliationResult(
    val examined: Int, val retained: Int, val tokenNotFound: Int,
    val callReturned: Int, val failed: Int,
)

class TestAlarmReconciliation(
    private val repository: SqliteOccurrenceRepository,
    private val cancel: (AlarmDelivery) -> CancellationObservation,
    private val onFailure: (Exception) -> Unit = {},
) {
    fun reconcile(occurrenceId: String? = null): ReconciliationResult {
        val through = repository.schedulingWatermark()
        var after = 0L
        var examined = 0; var retained = 0; var absent = 0; var returned = 0; var failed = 0
        while (true) {
            val page = repository.knownTestDeliveries(through, after, occurrenceId)
            if (page.isEmpty()) break
            for (known in page) {
                examined++
                try {
                    if (!isObsolete(repository, known.token)) retained++
                    else when (cancel(known.token)) {
                        CancellationObservation.TOKEN_NOT_FOUND -> absent++
                        CancellationObservation.CALL_RETURNED -> returned++
                        CancellationObservation.NO_LONGER_OBSOLETE -> retained++
                    }
                } catch (error: Exception) {
                    failed++
                    onFailure(error)
                }
            }
            after = page.last().firstSequence
        }
        return ReconciliationResult(examined, retained, absent, returned, failed)
    }

    companion object {
        /** Only monotonic invalidation permits cancellation. Import activation and a
         * clock change cannot make an obsolete revision current again in this epoch.
         * A still waiting overdue token and the supported TRIGGERED redelivery survive.
         */
        fun isObsolete(repository: SqliteOccurrenceRepository, token: AlarmDelivery): Boolean {
            if (!token.valid || token.epoch != repository.deliveryEpoch()) return false
            val current = repository.get(token.occurrenceId)?.takeIf { it.isTest } ?: return false
            if (current.generation < token.generation || current.revision < token.revision) return false
            if (current.status in setOf(OccurrenceStatus.DONE, OccurrenceStatus.SKIPPED, OccurrenceStatus.MISSED))
                return true
            if (current.generation > token.generation) return true
            if (current.revision == token.revision) return false
            return !(current.status == OccurrenceStatus.TRIGGERED && current.revision - 1 == token.revision)
        }
    }
}
