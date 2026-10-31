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
    private val themeStore by lazy { ThemePreferenceStore(this) }
    private val colors get() = navigation.colors(resources.configuration)

    private val german = Locale.GERMAN
    private val testStatusLoader = com.cztr.routinealarm.execution.LatestUiLoader()

    private var selectedDay: DayOfWeek =
        ZonedDateTime.now().dayOfWeek

    private var showTrainingDetails = false
    private var studioFilter = com.cztr.routinealarm.ui.studio.StudioFilter.ALL
    private var studioSelectedId: String? = null
    private var recoveryRequestedThisSession = false
    private var weeklyUiVisible = true

    private val weeklyObserver: () -> Unit = java.lang.ref.WeakReference(this).let { reference ->
        {
            reference.get()?.let { activity ->
                if (activity.weeklyUiVisible && !activity.isFinishing && !activity.isDestroyed) activity.render()
            }
        }
    }


    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)
        navigation = AppNavigationState.restore(savedInstanceState).also { restored ->
            if (savedInstanceState == null) restored.theme = themeStore.read()
        }
        selectedDay = savedInstanceState?.getInt("r52_selected_day")?.takeIf { it in 1..7 }
            ?.let(DayOfWeek::of) ?: selectedDay
        showTrainingDetails = savedInstanceState?.getBoolean("r52_training_details") ?: false
        studioFilter = com.cztr.routinealarm.ui.studio.StudioFilter.entries.firstOrNull {
            it.name == savedInstanceState?.getString("r52_studio_filter")
        } ?: com.cztr.routinealarm.ui.studio.StudioFilter.ALL
        studioSelectedId = savedInstanceState?.getString("r52_studio_selected_id")
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        navigation.save(outState)
        outState.putInt("r52_selected_day", selectedDay.value)
        outState.putBoolean("r52_training_details", showTrainingDetails)
        outState.putString("r52_studio_filter", studioFilter.name)
        outState.putString("r52_studio_selected_id", studioSelectedId)
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
        weeklyUiVisible = true
        ScheduleState.observe(this, weeklyObserver)

        com.cztr.routinealarm.execution.TestAlarmRuntime.requestRecovery(this)
        recoveryRequestedThisSession = true

        if (
            ScheduleState.isEnabled(this) &&
            AlarmScheduler.canScheduleExact(this)
        ) {
            AlarmScheduler.scheduleAll(this)
        }

        render()
    }

    override fun onStop() {
        weeklyUiVisible = false
        ScheduleState.unobserve(this, weeklyObserver)
        testStatusLoader.invalidate()
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && !isFinishing && !isDestroyed) {
            render()
        }
    }

    override fun onDestroy() {
        ScheduleState.unobserve(this, weeklyObserver)
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
        ScheduleState.writeNotice(this)?.let { root.addView(secondaryText(it)) }
        when (navigation.destination) {
            AppDestination.TODAY -> {
                val homeInput = com.cztr.routinealarm.ui.home.HomeProjectionSource.from(
                    ZonedDateTime.now(), ScheduleState.isEnabled(this),
                    AlarmScheduler.canScheduleExact(this), ScheduleRegistry.scheduledEvents)
                val home = com.cztr.routinealarm.ui.home.HomeUiMapper.map(homeInput)
                root.addView(com.cztr.routinealarm.ui.home.HomeScreenView(this, home, colors) { action ->
                    when (action) {
                        com.cztr.routinealarm.ui.home.HomeQuickAction.OPEN_PLAN,
                        com.cztr.routinealarm.ui.home.HomeQuickAction.OPEN_TRAINING -> {
                            navigation.destination = AppDestination.PLAN
                            render()
                        }
                        com.cztr.routinealarm.ui.home.HomeQuickAction.OPEN_HISTORY -> openTestHistory()
                        com.cztr.routinealarm.ui.home.HomeQuickAction.OPEN_BACKUP -> startActivity(
                            Intent(this, com.cztr.routinealarm.backup.BackupActivity::class.java))
                        com.cztr.routinealarm.ui.home.HomeQuickAction.REFRESH -> render()
                        com.cztr.routinealarm.ui.home.HomeQuickAction.OPEN_TEST,
                        com.cztr.routinealarm.ui.home.HomeQuickAction.OPEN_SYSTEM -> {
                            navigation.destination = AppDestination.MORE
                            render()
                        }
                    }
                })
                addTestAlarmCard(root)
            }
            AppDestination.PLAN -> {
                val planInput = com.cztr.routinealarm.ui.plan.PlanProjectionSource.from(
                    planEnabled = ScheduleState.isEnabled(this),
                    exactAllowed = AlarmScheduler.canScheduleExact(this),
                    activeEventCount = ScheduleRegistry.enabledEvents.size,
                    selectedDay = selectedDay,
                    events = PersonalSchedule.allEvents,
                    training = TrainingSessionPlan.eventsFor(selectedDay),
                    showTrainingDetails = showTrainingDetails,
                )
                val plan = com.cztr.routinealarm.ui.plan.PlanUiMapper.map(planInput)
                root.addView(com.cztr.routinealarm.ui.plan.PlanScreenView(
                    this,
                    plan,
                    colors,
                    onPrimaryAction = {
                        if (plan.availability == com.cztr.routinealarm.ui.plan.PlanAvailability.PERMISSION_REQUIRED) {
                            navigation.destination = AppDestination.MORE
                            render()
                        } else if (ScheduleState.requestedEnabled(this)) {
                            disableSchedule()
                        } else {
                            enableScheduleFromPlan()
                        }
                    },
                    onSelectDay = { day ->
                        selectedDay = day
                        showTrainingDetails = false
                        render()
                    },
                    onToggleTraining = {
                        showTrainingDetails = !showTrainingDetails
                        render()
                    },
                    requestedEnabled = ScheduleState.requestedEnabled(this),
                ))
            }
            AppDestination.STUDIO -> {
                val studioInput = com.cztr.routinealarm.ui.studio.StudioProjectionSource.from(
                    PersonalSchedule.allEvents,
                    studioFilter,
                    studioSelectedId,
                )
                val studio = com.cztr.routinealarm.ui.studio.StudioUiMapper.map(studioInput)
                root.addView(com.cztr.routinealarm.ui.studio.StudioScreenView(
                    this,
                    studio,
                    colors,
                    onSelectFilter = { filter ->
                        studioFilter = filter
                        studioSelectedId = null
                        render()
                    },
                    onSelectRoutine = { id ->
                        studioSelectedId = id
                        render()
                    },
                ))
            }
            AppDestination.HISTORY -> addHistoryScreen(root)
            AppDestination.MORE -> {
                @Suppress("DEPRECATION")
                val info = packageManager.getPackageInfo(packageName, 0)
                val systemInput = com.cztr.routinealarm.ui.system.SystemProjectionSource.from(
                    notificationPermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                    notificationGranted = hasNotificationPermission(),
                    exactAlarmGranted = AlarmScheduler.canScheduleExact(this),
                    weeklyPlanEnabled = ScheduleState.isEnabled(this),
                    recoveryRequested = recoveryRequestedThisSession,
                    versionName = info.versionName ?: "Unbekannte Version",
                    packageName = packageName,
                    theme = navigation.theme,
                )
                val system = com.cztr.routinealarm.ui.system.SystemUiMapper.map(systemInput)
                root.addView(com.cztr.routinealarm.ui.system.SystemScreenView(
                    this,
                    system,
                    colors,
                    onAction = { action ->
                        when (action) {
                            com.cztr.routinealarm.ui.system.SystemAction.REQUEST_NOTIFICATIONS -> requestNotificationPermission()
                            com.cztr.routinealarm.ui.system.SystemAction.REQUEST_EXACT_ALARMS -> requestExactAlarmPermission()
                            com.cztr.routinealarm.ui.system.SystemAction.OPEN_BACKUP -> startActivity(
                                Intent(this, com.cztr.routinealarm.backup.BackupActivity::class.java)
                            )
                            com.cztr.routinealarm.ui.system.SystemAction.OPEN_HISTORY -> openTestHistory()
                            com.cztr.routinealarm.ui.system.SystemAction.SCHEDULE_TEST_ALARM -> scheduleOneMinuteTestAlarm()
                            com.cztr.routinealarm.ui.system.SystemAction.SET_THEME_DARK -> setThemeChoice(ThemeChoice.DARK)
                            com.cztr.routinealarm.ui.system.SystemAction.SET_THEME_LIGHT -> setThemeChoice(ThemeChoice.LIGHT)
                            com.cztr.routinealarm.ui.system.SystemAction.SET_THEME_SYSTEM -> setThemeChoice(ThemeChoice.SYSTEM)
                        }
                    },
                ))
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

    private fun addHistoryScreen(root: LinearLayout) {
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun show(input: com.cztr.routinealarm.ui.history.HistoryUiInput) {
            host.removeAllViews()
            host.addView(com.cztr.routinealarm.ui.history.HistoryScreenView(
                this,
                com.cztr.routinealarm.ui.history.HistoryUiMapper.map(input),
                colors,
                onOpenTestHistory = ::openTestHistory,
                onRetry = { render() },
                onOpenSystem = {
                    navigation.destination = AppDestination.MORE
                    render()
                },
            ))
        }
        show(com.cztr.routinealarm.ui.history.HistoryUiInput.loading())
        root.addView(host, spacedMatchWidth())
        val app = applicationContext
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        testStatusLoader.load({
            com.cztr.routinealarm.execution.TestAlarmRuntime.repository(app).use { repo ->
                repo.statusSummary(now)
            }
        }) { result ->
            if (!isFinishing && !isDestroyed && navigation.destination == AppDestination.HISTORY) {
                if (result.isFailure) {
                    show(com.cztr.routinealarm.ui.history.HistoryUiInput.unavailable())
                } else {
                    val status = TestAlarmStatusPresenter.presentSummary(
                        result.getOrNull(),
                        now,
                        zone,
                        AlarmScheduler.canScheduleExact(this),
                    )
                    val open = status.openCount
                    val triggered = status.triggeredCount
                    val overdue = status.overdueCount
                    if (open == null || triggered == null || overdue == null) {
                        show(com.cztr.routinealarm.ui.history.HistoryUiInput.unavailable())
                    } else {
                        show(com.cztr.routinealarm.ui.history.HistoryUiInput.available(
                            open,
                            triggered,
                            overdue,
                            status.nextText,
                            exactAllowed = !status.needsExactPermission,
                        ))
                    }
                }
            }
        }
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
                    ScheduleState.requestedEnabled(
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

    private fun enableScheduleFromPlan() {
        if (!AlarmScheduler.canScheduleExact(this)) {
            requestExactAlarmPermission()
            Toast.makeText(
                this,
                "Für den Wochenplan werden exakte Wecker benötigt.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        requestScheduleChange(true)
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

        requestScheduleChange(true)
    }

    private fun requestScheduleChange(enabled: Boolean) {
        val screen = java.lang.ref.WeakReference(this)
        ScheduleState.requestEnabled(applicationContext, enabled) { result ->
            screen.get()?.takeIf { it.weeklyUiVisible && !it.isFinishing && !it.isDestroyed }?.let { activity ->
                if (result.isSuccess) {
                    val message = if (enabled) result.getOrThrow().toString() + " Ereignisse eingeplant"
                        else "Wochenplan pausiert"
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
        render()
    }

    private fun disableSchedule() {
        requestScheduleChange(false)
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

    private fun setThemeChoice(choice: ThemeChoice) {
        navigation.theme = choice
        themeStore.write(choice)
        render()
    }

    private fun scheduleOneMinuteTestAlarm() {
        if (!AlarmScheduler.canScheduleExact(this)) {
            requestExactAlarmPermission()
            return
        }
        runCatching { AlarmScheduler.scheduleTest(this, 1) }
            .onSuccess {
                Toast.makeText(this, "Testwecker für 1 Minute geplant", Toast.LENGTH_LONG).show()
            }
            .onFailure {
                Toast.makeText(
                    this,
                    "Planung fehlgeschlagen. Berechtigungen und Verlauf prüfen.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        render()
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

            try {
                startActivity(intent)
            } catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(
                    this,
                    "Android-Einstellungen konnten nicht geöffnet werden. Bitte die Freigabe in den Systemeinstellungen prüfen.",
                    Toast.LENGTH_LONG,
                ).show()
            }
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
