package com.cztr.routinealarm.ui.notification

import org.junit.Assert.*
import org.junit.Test

class NotificationMenuTest {
    private val all = NotificationOperation.entries.toSet()

    @Test fun rootOffersDoneLaterAndOptionsWithinThreeSlots() {
        assertEquals(listOf(MenuChoice.DONE, MenuChoice.LATER, MenuChoice.OPTIONS),
            NotificationMenu.buttons(MenuStage.ROOT, all).map { it.choice })
    }
    @Test fun allFiveSnoozeDurationsAreReachableWithoutAnActivity() {
        val short = NotificationMenu.select(MenuStage.ROOT, MenuChoice.LATER, all)
        assertEquals(MenuSelection.Navigate(MenuStage.SNOOZE_SHORT), short)
        assertEquals(MenuSelection.Navigate(MenuStage.SNOOZE_LONG),
            NotificationMenu.select(MenuStage.SNOOZE_SHORT, MenuChoice.MORE, all))
        val choices = listOf(MenuChoice.ONE, MenuChoice.TWO, MenuChoice.THREE, MenuChoice.FOUR, MenuChoice.FIVE)
        val stages = listOf(MenuStage.SNOOZE_SHORT, MenuStage.SNOOZE_SHORT,
            MenuStage.SNOOZE_LONG, MenuStage.SNOOZE_LONG, MenuStage.SNOOZE_LONG)
        assertEquals(listOf(1,2,3,4,5), choices.indices.map {
            (NotificationMenu.select(stages[it], choices[it], all) as MenuSelection.Request).snoozeMinutes
        })
    }
    @Test fun everyRenderedMenuFitsThreeActions() {
        for (stage in MenuStage.entries) {
            val buttons = NotificationMenu.buttons(stage, all)
            assertTrue(buttons.isNotEmpty())
            assertTrue(buttons.size <= 3)
            assertEquals(buttons.size, buttons.map { it.choice }.distinct().size)
            assertTrue(buttons.all { it.label.isNotBlank() })
        }
    }
    @Test fun closeRequestsOnlyCloseOutputAndNeverDoneOrSkip() {
        assertEquals(MenuSelection.Request(NotificationOperation.CLOSE_OUTPUT),
            NotificationMenu.select(MenuStage.OPTIONS, MenuChoice.CLOSE, all))
    }
    @Test fun doneAndSkipHaveSeparateTypedRequests() {
        assertEquals(MenuSelection.Request(NotificationOperation.DONE),
            NotificationMenu.select(MenuStage.ROOT, MenuChoice.DONE, all))
        assertEquals(MenuSelection.Request(NotificationOperation.SKIP),
            NotificationMenu.select(MenuStage.OPTIONS, MenuChoice.SKIP, all))
    }
    @Test fun obsoleteMenuChoiceCannotDispatchFromAnotherStage() {
        assertEquals(MenuSelection.Rejected,
            NotificationMenu.select(MenuStage.ROOT, MenuChoice.FIVE, all))
        assertEquals(MenuSelection.Rejected,
            NotificationMenu.select(MenuStage.SNOOZE_LONG, MenuChoice.DONE, all))
    }
    @Test fun revokedSnoozeIsNotDisplayedOrEmittedFromOldStage() {
        val allowed = setOf(NotificationOperation.DONE, NotificationOperation.CLOSE_OUTPUT)
        assertFalse(NotificationMenu.buttons(MenuStage.ROOT, allowed).any { it.choice == MenuChoice.LATER })
        assertEquals(MenuSelection.Rejected,
            NotificationMenu.select(MenuStage.SNOOZE_LONG, MenuChoice.FIVE, allowed))
    }
    @Test fun noCurrentActionsMeansNoButtonsAndNoRequests() {
        for (stage in MenuStage.entries) {
            assertTrue(NotificationMenu.buttons(stage, emptySet()).isEmpty())
            for (choice in MenuChoice.entries) assertEquals(MenuSelection.Rejected,
                NotificationMenu.select(stage, choice, emptySet()))
        }
    }
    @Test fun optionsReturnsToRootWithoutRequestingADomainAction() {
        assertEquals(MenuSelection.Navigate(MenuStage.ROOT),
            NotificationMenu.select(MenuStage.OPTIONS, MenuChoice.BACK, all))
    }
    @Test fun blockedDoneAndSkipCannotBeEmittedByForgedChoice() {
        val allowed = setOf(NotificationOperation.SNOOZE)
        assertEquals(MenuSelection.Rejected, NotificationMenu.select(MenuStage.ROOT, MenuChoice.DONE, allowed))
        assertEquals(MenuSelection.Rejected, NotificationMenu.select(MenuStage.OPTIONS, MenuChoice.SKIP, allowed))
    }
    @Test fun repeatedRenderingIsDeterministicAndDoesNotAccumulateState() {
        val first = NotificationMenu.buttons(MenuStage.ROOT, all)
        NotificationMenu.buttons(MenuStage.SNOOZE_LONG, setOf(NotificationOperation.SNOOZE))
        assertEquals(first, NotificationMenu.buttons(MenuStage.ROOT, all))
        assertEquals(3, first.size)
    }
    @Test fun snoozeChoicesRequestSnoozeRatherThanCompletion() {
        assertEquals(MenuSelection.Request(NotificationOperation.SNOOZE, 3),
            NotificationMenu.select(MenuStage.SNOOZE_LONG, MenuChoice.THREE, all))
    }
}
