package com.cztr.routinealarm

import com.cztr.routinealarm.ui.design.*
import com.cztr.routinealarm.ui.components.*
import com.cztr.routinealarm.ui.shell.*
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : Activity() {

    private var navigation = AppNavigationState()
    private val colors get() = navigation.colors(resources.configuration)

    private val german = Locale.GERMAN
    private val testStatusLoader = com.cztr.routinealarm.execution.LatestUiLoader()

    private var selectedDay: DayOfWeek =
        ZonedDateTime.now().dayOfWeek

    private var showTrainingDetails = false

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)
        navigation = AppNavigationState.restore(savedInstanceState)
        selectedDay = savedInstanceState?.getInt("r52_selected_day")?.takeIf { it in 1..7 }
            ?.let(DayOfWeek::of) ?: selectedDay
        showTrainingDetails = savedInstanceState?.getBoolean("r52_training_details") ?: false
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        navigation.save(outState)
        outState.putInt("r52_selected_day", selectedDay.value)
        outState.putBoolean("r52_training_details", showTrainingDetails)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Platform back navigation")
    override fun onBackPressed() {
        if (navigation.destination != AppDestination.TODAY) {
            navigation.destination = AppDestination.TODAY
            render()
        } else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()

        com.cztr.routinealarm.execution.TestAlarmRuntime.requestRecovery(this)

        if (
            ScheduleState.isEnabled(this) &&
            AlarmScheduler.canScheduleExact(this)
        ) {
            AlarmScheduler.scheduleAll(this)
        }

        render()
    }

    override fun onStop() {
        testStatusLoader.invalidate()
        super.onStop()
    }

    override fun onDestroy() {
        testStatusLoader.close()
        super.onDestroy()
    }

    private fun render() {
        testStatusLoader.invalidate()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        addHeader(root)
        when (navigation.destination) {
            AppDestination.TODAY -> {
                val home = com.cztr.routinealarm.ui.home.HomeUiMapper.map(
                    ZonedDateTime.now(), ScheduleState.isEnabled(this),
                    AlarmScheduler.canScheduleExact(this), ScheduleRegistry.scheduledEvents)
                root.addView(com.cztr.routinealarm.ui.home.HomeScreenView(this, home, colors,
                    onOpenPlan = { navigation.destination = AppDestination.PLAN; render() },
                    onOpenSystem = { navigation.destination = AppDestination.MORE; render() }))
                addTestAlarmCard(root)
            }
            AppDestination.PLAN -> {
                addScheduleToggleCard(root)
                addDaySelector(root)
                addDaySchedule(root)
                addTrainingCard(root)
            }
            AppDestination.STUDIO -> {
                root.addView(sectionTitle("Deine Routinen"))
                root.addView(secondaryText("Bestehende Routinen im Wochenplan. Ein frei bearbeitbarer Editor ist noch nicht verfügbar."))
                val card = cardContainer(0)
                PersonalSchedule.allEvents.forEach { event ->
                    card.addView(eventRow(event))
                    card.addView(secondaryText(if (event.enabled) "Im Plan enthalten" else "Im Plan deaktiviert"))
                    card.addView(divider())
                }
                root.addView(card, spacedMatchWidth())
            }
            AppDestination.HISTORY -> {
                root.addView(sectionTitle("Gespeicherte Entscheidungen"))
                root.addView(secondaryText("Der Testverlauf zeigt gespeicherte Auslösungen, Entscheidungen und Planungsrückmeldungen. Eine gespeicherte Auslösung bestätigt keine hörbare Ausgabe."))
                root.addView(secondaryButton("Testverlauf öffnen").apply { setOnClickListener { openTestHistory() } })
            }
            AppDestination.MORE -> {
                addSystemCard(root)
                root.addView(sectionTitle("Darstellung"))
                listOf("Dunkles Design" to ThemeChoice.DARK, "Helles Design" to ThemeChoice.LIGHT,
                    "Systemdesign" to ThemeChoice.SYSTEM).forEach { (label, choice) ->
                    root.addView(secondaryButton(label).apply {
                        isSelected = navigation.theme == choice
                        setOnClickListener { navigation.theme = choice; render() }
                    }, spacedMatchWidth())
                }
                root.addView(sectionTitle("Buildinfo"))
                @Suppress("DEPRECATION")
                val info = packageManager.getPackageInfo(packageName, 0)
                root.addView(secondaryText("${info.versionName} · $packageName"))
            }
        }
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.surface
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (colors == R52Colors.Light)
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        setContentView(AppShellView(this, navigation.destination, colors, root) { target ->
            navigation.destination = target
            render()
        })
    }

    private fun addHeader(
        root: LinearLayout
    ) {
        val title =
            TextView(this).apply {
                text = if (navigation.destination == AppDestination.TODAY) "RoutineAlarm" else navigation.destination.label
                textSize = 30f

                setTextColor(
                    colors.text
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
                    colors.secondaryText
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

    private fun addTestAlarmCard(root: LinearLayout) {
        val card = cardContainer()
        card.addView(sectionLabel("TESTWECKER"))
        card.addView(primaryText("Teststatus wird geladen …"))
        card.addView(secondaryButton("Testverlauf öffnen").apply {
            setOnClickListener { openTestHistory() }
        })
        root.addView(card, spacedMatchWidth())
        val app = applicationContext
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        testStatusLoader.load({
            com.cztr.routinealarm.execution.TestAlarmRuntime.repository(app).use { repo ->
                repo.statusSummary(now)
            }
        }) { result ->
            if (!isFinishing && !isDestroyed) {
                val status = TestAlarmStatusPresenter.presentSummary(result.getOrNull(), now, zone,
                    AlarmScheduler.canScheduleExact(this))
                showTestAlarmStatus(card, status)
            }
        }
    }

    private fun showTestAlarmStatus(card: LinearLayout, status: TestAlarmStatus) {
        card.removeAllViews()
        card.addView(sectionLabel("TESTWECKER"))
        card.addView(primaryText(status.title))

        if (status.openCount == null) {
            card.addView(secondaryText(
                "Die gespeicherten Testwecker konnten nicht gelesen werden. Öffne den Verlauf erneut."))
            card.addView(secondaryButton("Teststatus erneut laden").apply {
                setOnClickListener { render() }
            })
        } else {
            card.addView(secondaryText(
                "Gespeicherter Stand: ${status.openCount} offen · ${status.triggeredCount} ausgelöst · ${status.overdueCount} überfällig"))
            status.nextText?.let { card.addView(secondaryText(it)) }
            if ((status.overdueCount ?: 0) > 0) {
                card.addView(secondaryText(
                    "Überfällig: Zeitpunkt erreicht, Auslösung noch nicht gespeichert. Im Verlauf prüfen."))
            }
            if ((status.triggeredCount ?: 0) > 0) {
                card.addView(secondaryText(
                    "Eine gespeicherte Auslösung bestätigt noch keinen hörbaren Alarm."))
            }
        }
        card.addView(secondaryText("Testwecker laufen unabhängig vom Wochenplan."))
        if (status.needsExactPermission) {
            card.addView(secondaryText(
                "Exakte Wecker sind nicht erlaubt. Gespeicherte Testwecker benötigen diese Freigabe."))
            card.addView(secondaryButton("Exakte Wecker erlauben").apply {
                setOnClickListener { requestExactAlarmPermission() }
            })
        }
        card.addView(secondaryButton("Testverlauf öffnen").apply {
            setOnClickListener { openTestHistory() }
        })
    }

    private fun openTestHistory() {
        startActivity(Intent(this, com.cztr.routinealarm.execution.TestAlarmActivity::class.java))
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
                    colors.text
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
                    colors.secondaryText
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
                        minimumHeight = dp(48)
                        minimumWidth = dp(48)
                        isSelected = selected
                        contentDescription = "${dayLong(day)} auswählen"

                        setTextColor(
                            if (selected) {
                                colors.accent
                            } else {
                                colors.secondaryText
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
                                    colors.elevated
                                } else {
                                    colors.surface
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
                    colors.text
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
                    colors.text
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
                    colors.secondaryText
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
                LinearLayout.LayoutParams.WRAP_CONTENT,
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
                    colors.secondaryText
                )
            }

        val title =
            TextView(this).apply {
                text = event.title
                textSize = 14f

                setTextColor(
                    colors.text
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
                LinearLayout.LayoutParams.WRAP_CONTENT,
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

        card.addView(secondaryButton("Ereignisverlauf sichern / laden").apply {
            setOnClickListener { startActivity(Intent(this@MainActivity,
                com.cztr.routinealarm.backup.BackupActivity::class.java)) }
        })

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

                    runCatching { AlarmScheduler.scheduleTest(this@MainActivity, 1) }
                        .onSuccess {
                            Toast.makeText(this@MainActivity, "Testwecker für 1 Minute geplant",
                                Toast.LENGTH_LONG).show()
                        }.onFailure {
                            Toast.makeText(this@MainActivity,
                                "Planung fehlgeschlagen. Berechtigungen und Testverlauf prüfen.",
                                Toast.LENGTH_LONG).show()
                        }
                    render()
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
                    colors.secondaryText
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
                        colors.success
                    } else {
                        colors.warning
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

    private fun cardContainer(padding: Int = dp(18)): LinearLayout = R52Card(this, colors, padding)

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
                colors.text
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
                colors.accent
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
                colors.text
            )
        }

    private fun secondaryText(
        text: String
    ): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f

            setTextColor(
                colors.secondaryText
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
                colors.accent
            )

            background =
                roundedBackground(
                    colors.elevated,
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

    private fun secondaryButton(text: String): Button = R52Button(this, text, colors)

    private fun divider(): View =
        View(this).apply {
            setBackgroundColor(
                colors.stroke
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
