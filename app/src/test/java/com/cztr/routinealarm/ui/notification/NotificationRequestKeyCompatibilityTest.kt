package com.cztr.routinealarm.ui.notification
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
class NotificationRequestKeyCompatibilityTest {
    @Test fun keysStayByteIdenticalToVersionOneContract() {
        fun legacy(r:NotificationActionRoute)="r52-ui-v1-"+MessageDigest.getInstance("SHA-256").digest(NotificationActionRoutes.encode(r).toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
        val base=NotificationActionRoute("routinealarm://test-occurrence/perf-synthetic/0/0?epoch=host-only","tests:synthetic","rev-1",MenuStage.ROOT,MenuChoice.DONE)
        val vectors=listOf(base to "r52-ui-v1-49ec5c658fbb0b8100b12b8a419bb79209f5f3662babef4f82bc2330be1f9bd0",base.copy(scopeKey="tests:Prüfung/人",planRevision="rev-α") to "r52-ui-v1-911bb919658ab805ce2bfff8809fb775f2ca528e00d586d1352bc55418f9e9f2",base.copy(scopeKey="s".repeat(8192)) to "r52-ui-v1-0944f0a625c2ea4417c3ba1d269661ae5ff916386d7f13be507ab248e23757b6")
        for((r,key) in vectors){assertEquals(key,legacy(r));assertEquals(key,NotificationActionRoutes.requestKey(r))}
        repeat(128){i->val r=base.copy(deliveryDataUri="routinealarm://test-occurrence/corpus-$i/${i%7}/${i%11}?epoch=epoch-$i",scopeKey="scope-$i/Prüfung/人",planRevision="rev-$i",stage=if(i%2==0)MenuStage.ROOT else MenuStage.SNOOZE_LONG,choice=if(i%2==0)MenuChoice.DONE else MenuChoice.FIVE);assertEquals(legacy(r),NotificationActionRoutes.requestKey(r))}
    }
}
