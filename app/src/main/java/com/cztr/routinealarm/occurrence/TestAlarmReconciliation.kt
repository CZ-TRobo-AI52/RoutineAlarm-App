package com.cztr.routinealarm.occurrence

/** A possible effect reconstructed from our own attempts, not a platform inventory. */
data class KnownOccurrenceDelivery(val firstSequence: Long, val token: AlarmDelivery)
typealias KnownTestDelivery = KnownOccurrenceDelivery

enum class CancellationObservation { TOKEN_NOT_FOUND, CALL_RETURNED, NO_LONGER_OBSOLETE }

/** Transient observations only; neither cancellation nor delivery is durably certified. */
data class ReconciliationResult(
    val examined: Int, val retained: Int, val tokenNotFound: Int,
    val callReturned: Int, val failed: Int,
)

/** Compatibility adapter for existing test-alarm callers. */
class TestAlarmReconciliation(
    repository: SqliteOccurrenceRepository,
    cancel: (AlarmDelivery) -> CancellationObservation,
    onFailure: (Exception) -> Unit = {},
) {
    private val delegate = OccurrenceReconciliation(repository, OccurrenceScope.Tests, cancel, onFailure)

    fun reconcile(occurrenceId: String? = null): ReconciliationResult =
        delegate.reconcile(occurrenceId)

    companion object {
        fun isObsolete(repository: SqliteOccurrenceRepository, token: AlarmDelivery): Boolean =
            OccurrenceReconciliation.isObsolete(repository, token, OccurrenceScope.Tests)
    }
}

class OccurrenceReconciliation(
    private val repository: SqliteOccurrenceRepository,
    private val scope: OccurrenceScope,
    private val cancel: (AlarmDelivery) -> CancellationObservation,
    private val onFailure: (Exception) -> Unit = {},
) {
    fun reconcile(occurrenceId: String? = null): ReconciliationResult {
        val through = repository.schedulingWatermark()
        var after = 0L
        var examined = 0; var retained = 0; var absent = 0; var returned = 0; var failed = 0
        while (true) {
            val page = repository.knownDeliveries(scope, through, after, occurrenceId)
            if (page.isEmpty()) break
            for (known in page) {
                examined++
                try {
                    if (!isObsolete(repository, known.token, scope)) retained++
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
        fun isObsolete(repository: SqliteOccurrenceRepository, token: AlarmDelivery,
            scope: OccurrenceScope): Boolean {
            if (!token.valid || token.epoch != repository.deliveryEpoch()) return false
            val current = repository.get(token.occurrenceId)?.takeIf(scope::accepts) ?: return false
            if (current.generation < token.generation || current.revision < token.revision) return false
            if (current.status in setOf(OccurrenceStatus.DONE, OccurrenceStatus.SKIPPED, OccurrenceStatus.MISSED))
                return true
            if (current.generation > token.generation) return true
            if (current.revision == token.revision) return false
            return !(current.status == OccurrenceStatus.TRIGGERED && current.revision - 1 == token.revision)
        }
    }
}
