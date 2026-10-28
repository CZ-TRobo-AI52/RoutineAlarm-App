package com.cztr.routinealarm.ui.notification

import org.junit.Assert.*
import org.junit.Test

class NotificationActionRoutesTest {
    private fun route(
        data: String = "routinealarm://test-occurrence/test%3Aa/0/1?epoch=E1",
        scope: String = "tests", plan: String = "test-v1:Europe/Berlin",
        stage: MenuStage = MenuStage.ROOT, choice: MenuChoice = MenuChoice.DONE,
    ) = NotificationActionRoute(data, scope, plan, stage, choice)

    @Test fun opaqueCoreUriAndUnicodeScopeRoundTripWithoutRewriting() {
        val value = route(scope="personal:ä/#?&", plan="A:B/ß")
        assertEquals(value, NotificationActionRoutes.decode(NotificationActionRoutes.encode(value)))
    }
    @Test fun differentOccurrencesNeverShareTransportIdentity() {
        assertNotEquals(NotificationActionRoutes.requestKey(route()),
            NotificationActionRoutes.requestKey(route(data="routinealarm://test-occurrence/test%3Ab/0/1?epoch=E1")))
    }
    @Test fun revisionGenerationEpochScopeAndPlanAllDistinguishRequests() {
        val values = listOf(route(),
            route(data="routinealarm://test-occurrence/test%3Aa/0/2?epoch=E1"),
            route(data="routinealarm://test-occurrence/test%3Aa/1/1?epoch=E1"),
            route(data="routinealarm://test-occurrence/test%3Aa/0/1?epoch=E2"),
            route(scope="personal:eventA"), route(plan="planB"))
        assertEquals(6, values.map { NotificationActionRoutes.requestKey(it) }.distinct().size)
    }
    @Test fun differentChoicesOnOneOccurrenceHaveDifferentIdentities() {
        assertNotEquals(NotificationActionRoutes.requestKey(route()),
            NotificationActionRoutes.requestKey(route(choice=MenuChoice.LATER)))
    }
    @Test fun keyIsDeterministicAndNotAnEmptyPlaceholder() {
        val value = route()
        val first = NotificationActionRoutes.requestKey(value)
        assertTrue(first.matches(Regex("r52-ui-v1-[0-9a-f]{64}")))
        assertEquals(first, NotificationActionRoutes.requestKey(value.copy()))
    }
    @Test fun delimiterLikeFieldValuesCannotAliasEachOther() {
        assertNotEquals(NotificationActionRoutes.encode(route(scope="a:b",plan="c")),
            NotificationActionRoutes.encode(route(scope="a",plan="b:c")))
        assertNotEquals(NotificationActionRoutes.requestKey(route(scope="a:b",plan="c")),
            NotificationActionRoutes.requestKey(route(scope="a",plan="b:c")))
    }
    @Test fun decoderRejectsWrongHostSchemeAndVersion() {
        val uri=NotificationActionRoutes.encode(route())
        assertNotNull(NotificationActionRoutes.decode(uri))
        for (bad in listOf(uri.replace("routinealarm-ui:", "https:"),
            uri.replace("//notification/", "//other/"),uri.replace("/v1/","/v2/"))) {
            assertNull(NotificationActionRoutes.decode(bad))
        }
    }
    @Test fun decoderRejectsOuterQueryFragmentTrailingOrMissingSegments() {
        val uri=NotificationActionRoutes.encode(route())
        assertNotNull(NotificationActionRoutes.decode(uri))
        for (bad in listOf(uri+"?choice=DONE",uri+"#x",uri+"/",uri.substringBeforeLast('/'))) {
            assertNull(NotificationActionRoutes.decode(bad))
        }
    }
    @Test fun decoderRejectsNoncanonicalBase64AndMalformedUtf8() {
        val uri=NotificationActionRoutes.encode(route())
        assertNotNull(NotificationActionRoutes.decode(uri))
        val parts=uri.split('/').toMutableList()
        parts[4] = parts[4]+"="
        assertNull(NotificationActionRoutes.decode(parts.joinToString("/")))
        parts[4] = "_w"
        assertNull(NotificationActionRoutes.decode(parts.joinToString("/")))
    }
    @Test fun encodingRejectsBlankFieldsMalformedUnicodeAndNonUriTarget() {
        for (value in listOf(route(scope=""),route(plan=" "),route(data="not a URI"),route(scope="\uD800"))) {
            assertThrows(IllegalArgumentException::class.java) { NotificationActionRoutes.encode(value) }
        }
    }
    @Test fun encodingRejectsInvalidStageChoicePair() {
        assertThrows(IllegalArgumentException::class.java) {
            NotificationActionRoutes.encode(route(stage=MenuStage.ROOT,choice=MenuChoice.FIVE))
        }
    }
    @Test fun decoderDoesNotTurnMalformedRoutesIntoExecutableRequests() {
        for (bad in listOf("", "x", "routinealarm-ui://notification/v1/", "routinealarm-ui://notification/v1/%/tests/p/ROOT/DONE")) {
            assertNull(NotificationActionRoutes.decode(bad))
        }
    }
    @Test fun currentAvailabilityIsAppliedAgainAfterDecoding() {
        val value=route(stage=MenuStage.SNOOZE_LONG,choice=MenuChoice.FIVE)
        val decoded=NotificationActionRoutes.decode(NotificationActionRoutes.encode(value))
        assertNotNull(decoded)
        assertEquals(MenuSelection.Rejected,
            NotificationActionRoutes.selection(decoded!!, setOf(NotificationOperation.DONE)))
    }
    @Test fun validSnoozeAndCloseKeepExistingMenuSemantics() {
        assertEquals(MenuSelection.Request(NotificationOperation.SNOOZE,5),
            NotificationActionRoutes.selection(route(stage=MenuStage.SNOOZE_LONG,choice=MenuChoice.FIVE),
                setOf(NotificationOperation.SNOOZE)))
        assertEquals(MenuSelection.Request(NotificationOperation.CLOSE_OUTPUT),
            NotificationActionRoutes.selection(route(stage=MenuStage.OPTIONS,choice=MenuChoice.CLOSE),
                setOf(NotificationOperation.CLOSE_OUTPUT)))
    }
    @Test fun oversizedInputIsRejectedBeforeUnboundedDecoding() {
        assertNull(NotificationActionRoutes.decode("x".repeat(32769)))
        assertThrows(IllegalArgumentException::class.java) {
            NotificationActionRoutes.encode(route(plan="x".repeat(8193)))
        }
    }
    @Test fun oversizedFieldsFailBeforeUriOrUnicodeParsing() {
        val values = listOf(route(data=" ".repeat(8193)),
            route(scope="\uD800" + "x".repeat(8193)), route(plan="\uD800" + "x".repeat(8193)))
        for (value in values) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                NotificationActionRoutes.encode(value)
            }
            assertNull("Oversized fields must fail before URI/UTF8 parser causes", error.cause)
        }
    }

    @Test fun transportDoesNotRewriteCanonicalCoreQueryOrder() {
        val value=route(data="routinealarm://test-occurrence/a/0/1?epoch=E1&opaque=x")
        assertEquals(value.deliveryDataUri,
            NotificationActionRoutes.decode(NotificationActionRoutes.encode(value))?.deliveryDataUri)
    }
}
