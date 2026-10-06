package com.cztr.routinealarm

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Erzeugt eine deterministische Identität für eine konkrete Schedule-Konfiguration.
 *
 * Die Revision ändert sich nur bei Eigenschaften, die die Ausführung des Plans
 * beeinflussen:
 * - stabile Event-ID
 * - Wochentage
 * - Uhrzeit
 * - Event-Modus
 * - enabled
 *
 * Reine Präsentationsdaten wie title, spokenText und category sind absichtlich
 * nicht Bestandteil der Revision. Eine Umbenennung darf bestehende
 * RoutineOccurrence-Identitäten nicht verändern.
 */
object ScheduleRevision {

    private const val VERSION = "sched-v1"

    /**
     * Revision des aktuell von AlarmManager verwendeten Wochenplans.
     */
    fun current(): String =
        of(ScheduleRegistry.scheduledEvents)

    /**
     * Berechnet für denselben logischen Plan immer exakt dieselbe Revision.
     *
     * Die Eingabeliste darf in beliebiger Reihenfolge vorliegen. Vor dem Hashing
     * werden Events anhand ihrer stabilen ID sortiert.
     */
    fun of(events: List<RoutineEvent>): String {
        require(
            events.map { it.id }.distinct().size == events.size
        ) {
            "Schedule revision requires unique RoutineEvent IDs"
        }

        val digest =
            MessageDigest.getInstance("SHA-256")

        addField(digest, VERSION)
        addField(digest, events.size.toString())

        events
            .sortedBy { it.id }
            .forEach { event ->
                addField(digest, event.id)

                addField(
                    digest,
                    event.daysOfWeek
                        .sortedBy { it.value }
                        .joinToString(",") {
                            it.value.toString()
                        }
                )

                addField(
                    digest,
                    event.hour.toString()
                )

                addField(
                    digest,
                    event.minute.toString()
                )

                addField(
                    digest,
                    event.mode.name
                )

                addField(
                    digest,
                    if (event.enabled) "1" else "0"
                )
            }

        val hash =
            digest.digest()
                .joinToString("") { byte ->
                    "%02x".format(
                        byte.toInt() and 0xff
                    )
                }

        return "$VERSION:$hash"
    }

    /**
     * Length-prefix encoding verhindert Mehrdeutigkeiten wie:
     *
     * ["ab", "c"] versus ["a", "bc"]
     *
     * Beide Sequenzen erzeugen dadurch garantiert unterschiedliche
     * Eingabebytes für SHA-256.
     */
    private fun addField(
        digest: MessageDigest,
        value: String
    ) {
        val bytes =
            value.toByteArray(Charsets.UTF_8)

        digest.update(
            ByteBuffer
                .allocate(Int.SIZE_BYTES)
                .putInt(bytes.size)
                .array()
        )

        digest.update(bytes)
    }
}