package com.cztr.routinealarm.ui.notification

enum class MenuStage { ROOT, SNOOZE_SHORT, SNOOZE_LONG, OPTIONS }
enum class NotificationOperation { DONE, SKIP, SNOOZE, CLOSE_OUTPUT }
enum class MenuChoice { DONE, LATER, OPTIONS, ONE, TWO, MORE, THREE, FOUR, FIVE, SKIP, CLOSE, BACK }
data class MenuButton(val choice: MenuChoice, val label: String)
sealed interface MenuSelection {
    data class Navigate(val stage: MenuStage) : MenuSelection
    data class Request(val operation: NotificationOperation, val snoozeMinutes: Int? = null) : MenuSelection
    data object Rejected : MenuSelection
}
/** Presentation only. Core authorization, action identity and durable receipts are external. */
object NotificationMenu {
    fun buttons(stage: MenuStage, allowed: Set<NotificationOperation>): List<MenuButton> {
        if (allowed.isEmpty()) return emptyList()
        return when (stage) {
            MenuStage.ROOT -> buildList {
                if (NotificationOperation.DONE in allowed) add(MenuButton(MenuChoice.DONE, "Erledigt"))
                if (NotificationOperation.SNOOZE in allowed) add(MenuButton(MenuChoice.LATER, "Später"))
                if (NotificationOperation.SKIP in allowed || NotificationOperation.CLOSE_OUTPUT in allowed)
                    add(MenuButton(MenuChoice.OPTIONS, "Optionen"))
            }
            MenuStage.SNOOZE_SHORT -> if (NotificationOperation.SNOOZE in allowed) listOf(
                MenuButton(MenuChoice.ONE, "1 Minute später"),
                MenuButton(MenuChoice.TWO, "2 Minuten später"),
                MenuButton(MenuChoice.MORE, "3–5 Minuten"),
            ) else emptyList()
            MenuStage.SNOOZE_LONG -> if (NotificationOperation.SNOOZE in allowed) listOf(
                MenuButton(MenuChoice.THREE, "3 Minuten später"),
                MenuButton(MenuChoice.FOUR, "4 Minuten später"),
                MenuButton(MenuChoice.FIVE, "5 Minuten später"),
            ) else emptyList()
            MenuStage.OPTIONS -> buildList {
                if (NotificationOperation.SKIP in allowed) add(MenuButton(MenuChoice.SKIP, "Überspringen"))
                if (NotificationOperation.CLOSE_OUTPUT in allowed) add(MenuButton(MenuChoice.CLOSE, "Schließen"))
                add(MenuButton(MenuChoice.BACK, "Zurück"))
            }
        }
    }

    fun select(stage: MenuStage, choice: MenuChoice, allowed: Set<NotificationOperation>): MenuSelection {
        if (buttons(stage, allowed).none { it.choice == choice }) return MenuSelection.Rejected
        return when (choice) {
            MenuChoice.LATER -> MenuSelection.Navigate(MenuStage.SNOOZE_SHORT)
            MenuChoice.MORE -> MenuSelection.Navigate(MenuStage.SNOOZE_LONG)
            MenuChoice.OPTIONS -> MenuSelection.Navigate(MenuStage.OPTIONS)
            MenuChoice.BACK -> MenuSelection.Navigate(MenuStage.ROOT)
            MenuChoice.DONE -> MenuSelection.Request(NotificationOperation.DONE)
            MenuChoice.SKIP -> MenuSelection.Request(NotificationOperation.SKIP)
            MenuChoice.CLOSE -> MenuSelection.Request(NotificationOperation.CLOSE_OUTPUT)
            MenuChoice.ONE -> MenuSelection.Request(NotificationOperation.SNOOZE, 1)
            MenuChoice.TWO -> MenuSelection.Request(NotificationOperation.SNOOZE, 2)
            MenuChoice.THREE -> MenuSelection.Request(NotificationOperation.SNOOZE, 3)
            MenuChoice.FOUR -> MenuSelection.Request(NotificationOperation.SNOOZE, 4)
            MenuChoice.FIVE -> MenuSelection.Request(NotificationOperation.SNOOZE, 5)
        }
    }
}
