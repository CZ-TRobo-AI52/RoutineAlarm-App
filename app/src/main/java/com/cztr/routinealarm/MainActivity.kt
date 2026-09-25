package com.cztr.routinealarm

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : Activity() {

    private val german = Locale.GERMAN

    private var selectedDay: DayOfWeek =
        ZonedDateTime.now().dayOfWeek

    private var showTrainingDetails = false

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()

        if (
            ScheduleState.isEnabled(this) &&
            AlarmScheduler.canScheduleExact(this)
        ) {
            AlarmScheduler.scheduleAll(this)
        }

        render()
    }

    private fun render() {
        val scroll =
            ScrollView(this).apply {
                isFillViewport = true
                setBackgroundColor(
                    Color.rgb(246, 247, 249)
                )
            }

        val root =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(20),
                    dp(28),
                    dp(20),
                    dp(40)
                )
            }

        addHeader(root)
        addNextRoutineCard(root)
        addScheduleToggleCard(root)
        addDaySelector(root)
        addDaySchedule(root)
        addTrainingCard(root)
        addSystemCard(root)

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun addHeader(
        root: LinearLayout
    ) {
        val title =
            TextView(this).apply {
                text = "RoutineAlarm"
                textSize = 30f

                setTextColor(
                    Color.rgb(24, 27, 32)
                )

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )
            }

        val date =
            TextView(this).apply {
                text =
                    LocalDate
                        .now()
                        .format(
                            DateTimeFormatter.ofPattern(
                                "EEEE, d. MMMM",
                                german
                            )
                        )
                        .replaceFirstChar {
                            if (it.isLowerCase()) {
                                it.titlecase(german)
                            } else {
                                it.toString()
                            }
                        }

                textSize = 16f

                setTextColor(
                    Color.rgb(100, 105, 114)
                )

                setPadding(
                    0,
                    dp(4),
                    0,
                    dp(22)
                )
            }

        root.addView(title)
        root.addView(date)
    }

    private fun addNextRoutineCard(
        root: LinearLayout
    ) {
        val card = cardContainer()

        card.addView(
            sectionLabel("NÄCHSTE ROUTINE")
        )

        if (!ScheduleState.isEnabled(this)) {
            card.addView(
                primaryText("Wochenplan pausiert")
            )

            card.addView(
                secondaryText(
                    "Aktiviere den Wochenplan, damit Routinen automatisch ausgeführt werden."
                )
            )
        } else {
            val now = ZonedDateTime.now()

            val next =
                ScheduleRegistry
                    .enabledEvents
                    .map { event ->
                        event to
                            AlarmScheduler.nextOccurrence(
                                event,
                                now
                            )
                    }
                    .minByOrNull {
                        it.second.toInstant()
                    }

            if (next == null) {
                card.addView(
                    primaryText(
                        "Keine nächste Routine gefunden"
                    )
                )
            } else {
                val (event, occurrence) = next

                card.addView(
                    primaryText(event.title)
                )

                val dayText =
                    if (
                        occurrence.toLocalDate() ==
                        now.toLocalDate()
                    ) {
                        "Heute"
                    } else {
                        occurrence.dayOfWeek
                            .getDisplayName(
                                TextStyle.FULL,
                                german
                            )
                            .replaceFirstChar {
                                it.titlecase(german)
                            }
                    }

                val time =
                    String.format(
                        german,
                        "%02d:%02d",
                        occurrence.hour,
                        occurrence.minute
                    )

                card.addView(
                    secondaryText(
                        "$dayText · $time Uhr · ${countdown(now, occurrence)}"
                    )
                )

                card.addView(
                    smallBadge(
                        categoryName(event.category)
                    )
                )
            }
        }

        root.addView(
            card,
            spacedMatchWidth()
        )
    }

    private fun addScheduleToggleCard(
        root: LinearLayout
    ) {
        val card = cardContainer()

        val row =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        val textColumn =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }

        val title =
            TextView(this).apply {
                text = "Wochenplan"
                textSize = 19f

                setTextColor(
                    Color.rgb(24, 27, 32)
                )

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )
            }

        val subtitle =
            TextView(this).apply {
                text =
                    "${ScheduleRegistry.enabledEvents.size} aktive Ereignisse"

                textSize = 14f

                setTextColor(
                    Color.rgb(100, 105, 114)
                )

                setPadding(
                    0,
                    dp(3),
                    0,
                    0
                )
            }

        textColumn.addView(title)
        textColumn.addView(subtitle)

        val toggle =
            Switch(this).apply {
                isChecked =
                    ScheduleState.isEnabled(
                        this@MainActivity
                    )
            }

        row.addView(
            textColumn,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        row.addView(toggle)

        toggle.setOnCheckedChangeListener {
                _,
                isChecked ->

            if (isChecked) {
                enableSchedule(toggle)
            } else {
                disableSchedule()
            }
        }

        card.addView(row)

        root.addView(
            card,
            spacedMatchWidth()
        )
    }

    private fun enableSchedule(
        toggle: Switch
    ) {
        if (
            !AlarmScheduler.canScheduleExact(this)
        ) {
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = false

            requestExactAlarmPermission()

            Toast.makeText(
                this,
                "Für den Wochenplan werden exakte Wecker benötigt.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        ScheduleState.setEnabled(
            this,
            true
        )

        val count =
            AlarmScheduler.scheduleAll(this)

        Toast.makeText(
            this,
            "$count Ereignisse eingeplant",
            Toast.LENGTH_LONG
        ).show()

        render()
    }

    private fun disableSchedule() {
        ScheduleState.setEnabled(
            this,
            false
        )

        AlarmScheduler.cancelAll(this)

        stopService(
            Intent(
                this,
                AlarmService::class.java
            )
        )

        stopService(
            Intent(
                this,
                TrainingSessionService::class.java
            )
        )

        Toast.makeText(
            this,
            "Wochenplan pausiert",
            Toast.LENGTH_SHORT
        ).show()

        render()
    }

    private fun addDaySelector(
        root: LinearLayout
    ) {
        root.addView(
            sectionTitle("Woche")
        )

        val horizontal =
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled =
                    false
            }

        val days =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        DayOfWeek
            .values()
            .forEach { day ->
                val selected =
                    day == selectedDay

                val chip =
                    TextView(this).apply {
                        text =
                            day.getDisplayName(
                                TextStyle.SHORT,
                                german
                            )
                                .take(2)
                                .replaceFirstChar {
                                    it.titlecase(german)
                                }

                        textSize = 16f
                        gravity = Gravity.CENTER

                        setTextColor(
                            if (selected) {
                                Color.WHITE
                            } else {
                                Color.rgb(55, 60, 69)
                            }
                        )

                        setTypeface(
                            typeface,
                            if (selected) {
                                Typeface.BOLD
                            } else {
                                Typeface.NORMAL
                            }
                        )

                        background =
                            roundedBackground(
                                if (selected) {
                                    Color.rgb(51, 82, 235)
                                } else {
                                    Color.WHITE
                                },
                                18f
                            )

                        setPadding(
                            dp(18),
                            dp(12),
                            dp(18),
                            dp(12)
                        )

                        setOnClickListener {
                            selectedDay = day
                            showTrainingDetails = false
                            render()
                        }
                    }

                days.addView(
                    chip,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = dp(8)
                    }
                )
            }

        horizontal.addView(days)

        root.addView(
            horizontal,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(20)
            }
        )
    }

    private fun addDaySchedule(
        root: LinearLayout
    ) {
        val today =
            ZonedDateTime
                .now()
                .dayOfWeek

        val heading =
            if (selectedDay == today) {
                "Heute"
            } else {
                selectedDay
                    .getDisplayName(
                        TextStyle.FULL,
                        german
                    )
                    .replaceFirstChar {
                        it.titlecase(german)
                    }
            }

        root.addView(
            sectionTitle(heading)
        )

        val events =
            PersonalSchedule
                .allEvents
                .filter {
                    it.enabled &&
                        selectedDay in
                        it.daysOfWeek
                }
                .sortedBy {
                    it.minuteOfDay
                }

        val card =
            cardContainer(
                padding = 0
            )

        if (events.isEmpty()) {
            card.setPadding(
                dp(18),
                dp(16),
                dp(18),
                dp(16)
            )

            card.addView(
                secondaryText(
                    "Für diesen Tag sind keine persönlichen Routinen eingetragen."
                )
            )
        } else {
            events.forEachIndexed {
                    index,
                    event ->

                card.addView(
                    eventRow(event)
                )

                if (index < events.lastIndex) {
                    card.addView(divider())
                }
            }
        }

        root.addView(
            card,
            spacedMatchWidth()
        )
    }

    private fun eventRow(
        event: RoutineEvent
    ): View {
        val row =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.TOP

                setPadding(
                    dp(16),
                    dp(14),
                    dp(16),
                    dp(14)
                )
            }

        val time =
            TextView(this).apply {
                text =
                    String.format(
                        german,
                        "%02d:%02d",
                        event.hour,
                        event.minute
                    )

                textSize = 16f

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setTextColor(
                    Color.rgb(40, 45, 54)
                )
            }

        val info =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(14),
                    0,
                    0,
                    0
                )
            }

        val title =
            TextView(this).apply {
                text = event.title
                textSize = 16f

                setTextColor(
                    Color.rgb(24, 27, 32)
                )
            }

        val meta =
            TextView(this).apply {
                text =
                    categoryName(
                        event.category
                    )

                textSize = 12f

                setTextColor(
                    Color.rgb(115, 120, 129)
                )

                setPadding(
                    0,
                    dp(3),
                    0,
                    0
                )
            }

        info.addView(title)
        info.addView(meta)

        row.addView(
            time,
            LinearLayout.LayoutParams(
                dp(58),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        row.addView(
            info,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        return row
    }

    private fun addTrainingCard(
        root: LinearLayout
    ) {
        val events =
            TrainingSessionPlan
                .eventsFor(
                    selectedDay
                )
                .sortedBy {
                    it.minuteOfDay
                }

        if (events.isEmpty()) {
            return
        }

        root.addView(
            sectionTitle("Training")
        )

        val card = cardContainer()
        val first = events.first()
        val last = events.last()

        card.addView(
            primaryText(
                "Training ${dayLong(selectedDay)}"
            )
        )

        val start =
            String.format(
                german,
                "%02d:%02d",
                first.hour,
                first.minute
            )

        val end =
            String.format(
                german,
                "%02d:%02d",
                last.hour,
                last.minute
            )

        card.addView(
            secondaryText(
                "$start–$end Uhr · ${events.size} Ansagen"
            )
        )

        val detailsButton =
            secondaryButton(
                if (showTrainingDetails) {
                    "TRAINING EINKLAPPEN"
                } else {
                    "TRAINING ANSEHEN"
                }
            ).apply {
                setOnClickListener {
                    showTrainingDetails =
                        !showTrainingDetails

                    render()
                }
            }

        card.addView(
            detailsButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
        )

        if (showTrainingDetails) {
            val list =
                LinearLayout(this).apply {
                    orientation =
                        LinearLayout.VERTICAL

                    setPadding(
                        0,
                        dp(12),
                        0,
                        0
                    )
                }

            events.forEachIndexed {
                    index,
                    event ->

                list.addView(
                    compactTrainingRow(event)
                )

                if (index < events.lastIndex) {
                    list.addView(divider())
                }
            }

            card.addView(list)
        }

        root.addView(
            card,
            spacedMatchWidth()
        )
    }

    private fun compactTrainingRow(
        event: RoutineEvent
    ): View {
        val row =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL

                setPadding(
                    0,
                    dp(10),
                    0,
                    dp(10)
                )
            }

        val time =
            TextView(this).apply {
                text =
                    String.format(
                        german,
                        "%02d:%02d",
                        event.hour,
                        event.minute
                    )

                textSize = 14f

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setTextColor(
                    Color.rgb(58, 63, 72)
                )
            }

        val title =
            TextView(this).apply {
                text = event.title
                textSize = 14f

                setTextColor(
                    Color.rgb(32, 36, 43)
                )

                setPadding(
                    dp(12),
                    0,
                    0,
                    0
                )
            }

        row.addView(
            time,
            LinearLayout.LayoutParams(
                dp(56),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        row.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        return row
    }

    private fun addSystemCard(
        root: LinearLayout
    ) {
        root.addView(
            sectionTitle("System & Test")
        )

        val card = cardContainer()

        val notificationGranted =
            hasNotificationPermission()

        val exactGranted =
            AlarmScheduler.canScheduleExact(
                this
            )

        card.addView(
            statusRow(
                "Benachrichtigungen",
                notificationGranted
            )
        )

        card.addView(
            statusRow(
                "Exakte Wecker",
                exactGranted
            )
        )

        if (!notificationGranted) {
            card.addView(
                secondaryButton(
                    "BENACHRICHTIGUNGEN ERLAUBEN"
                ).apply {
                    setOnClickListener {
                        requestNotificationPermission()
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(12)
                }
            )
        }

        if (!exactGranted) {
            card.addView(
                secondaryButton(
                    "EXAKTE WECKER ERLAUBEN"
                ).apply {
                    setOnClickListener {
                        requestExactAlarmPermission()
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(10)
                }
            )
        }

        card.addView(
            secondaryButton(
                "TESTWECKER IN 1 MINUTE"
            ).apply {
                setOnClickListener {
                    if (
                        !AlarmScheduler.canScheduleExact(
                            this@MainActivity
                        )
                    ) {
                        requestExactAlarmPermission()
                        return@setOnClickListener
                    }

                    AlarmScheduler.scheduleTest(
                        this@MainActivity,
                        1
                    )

                    Toast.makeText(
                        this@MainActivity,
                        "Testwecker für 1 Minute geplant",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
        )

        root.addView(
            card,
            spacedMatchWidth()
        )
    }

    private fun statusRow(
        label: String,
        okay: Boolean
    ): View {
        val row =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    0,
                    dp(5),
                    0,
                    dp(5)
                )
            }

        val name =
            TextView(this).apply {
                text = label
                textSize = 15f

                setTextColor(
                    Color.rgb(48, 53, 62)
                )
            }

        val state =
            TextView(this).apply {
                text =
                    if (okay) {
                        "Bereit"
                    } else {
                        "Aktion nötig"
                    }

                textSize = 13f

                setTextColor(
                    if (okay) {
                        Color.rgb(25, 125, 75)
                    } else {
                        Color.rgb(173, 78, 30)
                    }
                )

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )
            }

        row.addView(
            name,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        row.addView(state)

        return row
    }

    private fun cardContainer(
        padding: Int =
            dp(18)
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation =
                LinearLayout.VERTICAL

            setPadding(
                padding,
                padding,
                padding,
                padding
            )

            background =
                roundedBackground(
                    Color.WHITE,
                    20f
                )

            elevation =
                dp(1).toFloat()
        }

    private fun sectionTitle(
        text: String
    ): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 20f

            setTypeface(
                typeface,
                Typeface.BOLD
            )

            setTextColor(
                Color.rgb(24, 27, 32)
            )

            setPadding(
                0,
                dp(8),
                0,
                dp(10)
            )
        }

    private fun sectionLabel(
        text: String
    ): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 12f
            letterSpacing = 0.08f

            setTypeface(
                typeface,
                Typeface.BOLD
            )

            setTextColor(
                Color.rgb(51, 82, 235)
            )

            setPadding(
                0,
                0,
                0,
                dp(8)
            )
        }

    private fun primaryText(
        text: String
    ): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 22f

            setTypeface(
                typeface,
                Typeface.BOLD
            )

            setTextColor(
                Color.rgb(24, 27, 32)
            )
        }

    private fun secondaryText(
        text: String
    ): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f

            setTextColor(
                Color.rgb(100, 105, 114)
            )

            setPadding(
                0,
                dp(6),
                0,
                0
            )
        }

    private fun smallBadge(
        text: String
    ): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 12f

            setTextColor(
                Color.rgb(51, 82, 235)
            )

            background =
                roundedBackground(
                    Color.rgb(237, 240, 255),
                    10f
                )

            setPadding(
                dp(10),
                dp(5),
                dp(10),
                dp(5)
            )

            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(12)
                }
        }

    private fun secondaryButton(
        text: String
    ): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f

            setTypeface(
                typeface,
                Typeface.BOLD
            )
        }

    private fun divider(): View =
        View(this).apply {
            setBackgroundColor(
                Color.rgb(235, 237, 241)
            )

            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1)
                )
        }

    private fun spacedMatchWidth():
        LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(18)
        }

    private fun roundedBackground(
        color: Int,
        radiusDp: Float
    ): GradientDrawable =
        GradientDrawable().apply {
            shape =
                GradientDrawable.RECTANGLE

            setColor(color)

            cornerRadius =
                dp(
                    radiusDp.toInt()
                ).toFloat()
        }

    private fun categoryName(
        category: EventCategory
    ): String =
        when (category) {
            EventCategory.MORNING ->
                "Morgenroutine"

            EventCategory.WORK ->
                "Arbeit"

            EventCategory.MEAL ->
                "Mahlzeit"

            EventCategory.TRAINING ->
                "Training"

            EventCategory.PERSONAL ->
                "Persönlich"

            EventCategory.PROJECT ->
                "Projekt"

            EventCategory.EVENING ->
                "Abendroutine"

            EventCategory.SLEEP ->
                "Schlaf"
        }

    private fun dayLong(
        day: DayOfWeek
    ): String =
        day.getDisplayName(
            TextStyle.FULL,
            german
        )
            .replaceFirstChar {
                it.titlecase(german)
            }

    private fun countdown(
        from: ZonedDateTime,
        to: ZonedDateTime
    ): String {
        val duration =
            Duration.between(
                from,
                to
            )

        val minutes =
            duration
                .toMinutes()
                .coerceAtLeast(0)

        val days =
            minutes / (24 * 60)

        val hours =
            (minutes % (24 * 60)) / 60

        val remainingMinutes =
            minutes % 60

        return when {
            days > 0 ->
                "in ${days}T ${hours}Std"

            hours > 0 ->
                "in ${hours}Std ${remainingMinutes}Min"

            else ->
                "in ${remainingMinutes}Min"
        }
    }

    private fun hasNotificationPermission():
        Boolean =
        Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.POST_NOTIFICATIONS
                ),
                100
            )
        }
    }

    private fun requestExactAlarmPermission() {
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S &&
            !AlarmScheduler.canScheduleExact(this)
        ) {
            val intent =
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
                ).apply {
                    data =
                        Uri.parse(
                            "package:$packageName"
                        )
                }

            startActivity(intent)
        }
    }

    private fun dp(
        value: Int
    ): Int =
        (
            value *
                resources
                    .displayMetrics
                    .density
            )
            .toInt()
}
