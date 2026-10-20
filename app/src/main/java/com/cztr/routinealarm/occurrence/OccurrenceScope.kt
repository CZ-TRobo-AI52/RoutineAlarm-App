package com.cztr.routinealarm.occurrence

/** Explicit selection of one occurrence family; the API never implies all personal events. */
sealed class OccurrenceScope {
    abstract val key: String
    object Tests : OccurrenceScope() { override val key = "tests" }
    data class PersonalEvent(val eventId: String) : OccurrenceScope() {
        init {
            require(eventId.isNotBlank()) { "A personal event ID is required" }
            require(eventId.toByteArray(Charsets.UTF_8).size <= 256) { "Personal event ID exceeds 256 UTF-8 bytes" }
        }
        override val key = "personal:$eventId"
    }
    fun accepts(item: RoutineOccurrence): Boolean = when (this) {
        Tests -> item.isTest
        is PersonalEvent -> !item.isTest && item.eventId == eventId
    }
}
