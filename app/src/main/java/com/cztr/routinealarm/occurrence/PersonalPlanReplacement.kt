package com.cztr.routinealarm.occurrence

import java.time.Instant
import java.time.ZoneId
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections

/** Snapshot of one event's authority, not an execution journal or a delivery permission. */
data class PersonalPlanAuthority(
    val version: Long = 0,
    val activeRevision: String? = null,
    val bindings: Map<String, String> = emptyMap(),
    val retiredIds: Set<String> = emptySet(),
    val eventId: String? = null,
)

sealed class PersonalPlanDecision {
    data class Conflict(val actualVersion: Long) : PersonalPlanDecision()
    data class Ready(
        val expectedVersion: Long,
        val next: PersonalPlanAuthority,
        val insertions: List<RoutineOccurrence>,
        val retireIds: Set<String>,
        val preservedIds: Set<String>,
    ) : PersonalPlanDecision()
}

object PersonalPlanReplacement {
    /**
     * Pure preparation only. The repository must read the COMPLETE event history and authority,
     * call this function and CAS/commit the whole Ready value in ONE transaction.
     *
     * Each plan revision binds one complete, bounded window, including its executable payload.
     * Extending the window requires a new revision. A past revision can never become current again.
     * Only future, untouched SCHEDULED rows may retire. Due, started and terminal rows remain intact.
     * No AlarmManager call, journal mutation, persistence or restore activation happens here.
     */
    fun decide(scope: OccurrenceScope.PersonalEvent, authority: PersonalPlanAuthority,
        expectedVersion: Long, existing: List<RoutineOccurrence>, candidates: List<RoutineOccurrence>,
        planRevision: String, zone: ZoneId, cutoff: Instant): PersonalPlanDecision {
        require(authority.version >= 0 && expectedVersion >= 0)
        require(authority.eventId == null || authority.eventId == scope.eventId)
        require(authority.eventId != null || (authority.activeRevision == null && authority.bindings.isEmpty()))
        require(authority.activeRevision == null || authority.activeRevision in authority.bindings)
        require(planRevision.isNotBlank())
        cutoff.toEpochMilli()
        if (expectedVersion != authority.version) return PersonalPlanDecision.Conflict(authority.version)
        require(candidates.size <= 366) { "One bounded plan contains at most 366 occurrences" }
        require(existing.all(scope::accepts) && candidates.all(scope::accepts)) { "Wrong personal event scope" }
        require(existing.map { it.id }.distinct().size == existing.size)
        require(candidates.map { it.id }.distinct().size == candidates.size)
        require(candidates.map { it.plannedAt }.distinct().size == candidates.size)
        val stored = existing.associateBy { it.id }
        candidates.forEach { candidate ->
            require(candidate.id.isNotBlank() && candidate.traceId.isNotBlank())
            require(candidate.scheduleRevision == planRevision && candidate.zoneId == zone.id)
            require(candidate.status == OccurrenceStatus.SCHEDULED && candidate.revision == 0L &&
                candidate.generation == 0L && candidate.snoozeCount == 0 && candidate.totalSnoozeMinutes == 0 &&
                candidate.currentTriggerAt == candidate.plannedAt) { "Candidate must be an initial occurrence" }
            candidate.plannedAt.toEpochMilli()
            val resolved = candidate.nominalDate.atTime(candidate.nominalTime).atZone(zone)
            require(listOf(resolved.withEarlierOffsetAtOverlap(), resolved.withLaterOffsetAtOverlap()).any {
                it.offset.totalSeconds == candidate.offsetSeconds && it.toInstant() == candidate.plannedAt
            }) { "Invalid calendar identity" }
            require(candidate.id !in authority.retiredIds) { "Retired identities cannot return" }
            val previous = stored[candidate.id]
            if (previous != null) require(payload(previous) == payload(candidate)) { "Occurrence identity changed" }
            else require(candidate.plannedAt > cutoff) { "A replacement cannot start an already due occurrence" }
        }
        val fingerprint = fingerprint(scope, planRevision, zone, candidates)
        val priorBinding = authority.bindings[planRevision]
        if (priorBinding != null) {
            require(authority.activeRevision == planRevision) { "A previously replaced plan cannot return" }
            require(priorBinding == fingerprint) { "Plan revision reused with different content" }
            require(candidates.all { it.id in stored }) { "Incomplete prior plan commit" }
            return PersonalPlanDecision.Ready(expectedVersion, snapshot(authority), immutableList(emptyList()),
                immutableSet(emptySet()), immutableSet(existing.filter { it.id !in authority.retiredIds }.map { it.id }))
        }
        val candidateIds = candidates.map { it.id }.toSet()
        val retired = existing.filter {
            it.id !in candidateIds && it.id !in authority.retiredIds &&
                it.status == OccurrenceStatus.SCHEDULED && it.revision == 0L && it.generation == 0L &&
                it.snoozeCount == 0 && it.totalSnoozeMinutes == 0 &&
                it.currentTriggerAt == it.plannedAt && it.plannedAt > cutoff
        }.map { it.id }.toSet()
        val preserved = existing.filter { it.id !in retired && it.id !in authority.retiredIds }
        // No new identity may masquerade as an already due/started/completed daily execution.
        val insertions = candidates.filter { it.id !in stored }
        require(insertions.none { fresh -> preserved.any {
            it.id !in authority.retiredIds && it.nominalDate == fresh.nominalDate
        } }) { "Replacement overlaps a preserved execution date" }
        val bindings = LinkedHashMap(authority.bindings).apply { put(planRevision, fingerprint) }
        val next = PersonalPlanAuthority(Math.addExact(authority.version, 1), planRevision,
            Collections.unmodifiableMap(bindings), immutableSet(authority.retiredIds + retired), scope.eventId)
        return PersonalPlanDecision.Ready(expectedVersion, next, immutableList(insertions),
            immutableSet(retired), immutableSet(preserved.map { it.id }))
    }

    /** Execution-state fields and observation time are deliberately absent; all scheduled content is bound. */
    private fun payload(item: RoutineOccurrence): List<String> = listOf(
        item.id, item.eventId, item.scheduleRevision, item.nominalDate.toString(), item.nominalTime.toString(),
        item.zoneId, item.offsetSeconds.toString(), item.plannedAt.toString(), item.traceId,
        item.title, item.spokenText, item.category.name, item.mode.name, item.isTest.toString(),
    )

    private fun fingerprint(scope: OccurrenceScope.PersonalEvent, revision: String, zone: ZoneId,
        candidates: List<RoutineOccurrence>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun frame(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        listOf("personal-plan-v1", scope.key, revision, zone.id, candidates.size.toString()).forEach(::frame)
        candidates.sortedBy { it.id }.forEach { payload(it).forEach(::frame) }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun snapshot(value: PersonalPlanAuthority) = value.copy(
        bindings = Collections.unmodifiableMap(LinkedHashMap(value.bindings)),
        retiredIds = immutableSet(value.retiredIds),
    )
    private fun <T> immutableSet(items: Collection<T>): Set<T> =
        Collections.unmodifiableSet(LinkedHashSet(items))
    private fun <T> immutableList(items: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(items))
}
