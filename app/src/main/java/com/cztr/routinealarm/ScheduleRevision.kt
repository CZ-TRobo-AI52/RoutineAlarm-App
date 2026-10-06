package com.cztr.routinealarm

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.ZoneId

/**
 * Erzeugt eine deterministische Identität für eine konkrete
 * Schedule-Konfiguration innerhalb einer konkreten Zeitzone.
 *
 * Die Revision ändert sich nur bei Eigenschaften, die die reale Ausführung
 * des Plans beeinflussen:
 * - Zeitzone
 * - stabile Event-ID
 * - Wochentage
 * - Uhrzeit
 * - Event-Modus
 * - enabled
 *
 * Reine Präsentationsdaten wie title, spokenText und category sind
 * absichtlich nicht Bestandteil der Revision. Eine Umbenennung darf
 * bestehende RoutineOccurrence-Identitäten nicht verändern.
 */
object ScheduleRevision {

    /*
     * v2 bindet die Revision zusätzlich explizit an die Zeitzone.
     *
     * Dadurch kann dieselbe logische Wochenregel in Europe/Berlin und UTC
     * niemals versehentlich dieselbe Schedule-Revision erhalten.
     */
    private const val VERSION = "sched-v2"

    /**
     * Revision des aktuell verwendeten Wochenplans für [zone].
     *
     * Die Zeitzone wird absichtlich vom Aufrufer übergeben.
     * ScheduleRevision liest nicht versteckt ZoneId.systemDefault(), damit
     * Planung, Tests, Recovery und Reconciliation reproduzierbar bleiben.
     */
    fun current(
        zone: ZoneId
    ): String =
        of(
            events = ScheduleRegistry.scheduledEvents,
            zone = zone
        )

    /**
     * Berechnet für denselben logischen Plan in derselben Zeitzone immer
     * exakt dieselbe Revision.
     *
     * Die Eingabeliste darf in beliebiger Reihenfolge vorliegen.
     * Vor dem Hashing werden Events anhand ihrer stabilen ID sortiert.
     */
    fun of(
        events: List<RoutineEvent>,
        zone: ZoneId
    ): String {
        require(
            events.map { it.id }.distinct().size == events.size
        ) {
            "Schedule revision requires unique RoutineEvent IDs"
        }

        val digest =
            MessageDigest.getInstance("SHA-256")

        addField(
            digest,
            VERSION
        )

        /*
         * ZoneId.id statt eines aktuellen UTC-Offsets:
         *
         * Europe/Berlin bleibt damit eine andere Planidentität als UTC.
         * Die konkrete Sommer-/Winterzeit wird später pro Occurrence vom
         * OccurrencePlanner aufgelöst.
         */
        addField(
            digest,
            zone.id
        )

        addField(
            digest,
            events.size.toString()
        )

        events
            .sortedBy { it.id }
            .forEach { event ->
                addField(
                    digest,
                    event.id
                )

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
     * Length-prefix encoding verhindert strukturelle Mehrdeutigkeiten wie:
     *
     * ["ab", "c"] versus ["a", "bc"]
     *
     * Beide Sequenzen erzeugen dadurch unterschiedliche Eingabebytes für
     * SHA-256.
     */
    private fun addField(
        digest: MessageDigest,
        value: String
    ) {
        val bytes =
            value.toByteArray(
                Charsets.UTF_8
            )

        digest.update(
            ByteBuffer
                .allocate(
                    Int.SIZE_BYTES
                )
                .putInt(
                    bytes.size
                )
                .array()
        )

        digest.update(
            bytes
        )
    }
}