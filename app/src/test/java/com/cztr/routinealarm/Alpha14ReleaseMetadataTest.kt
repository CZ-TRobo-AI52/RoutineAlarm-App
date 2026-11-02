package com.cztr.routinealarm

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
/** Historical testcase IDs retained; expected metadata advances to the Alpha14.1 successor. */
class Alpha14ReleaseMetadataTest {
    private fun packageInfo() = RuntimeEnvironment.getApplication().let { app ->
        app.packageManager.getPackageInfo(app.packageName, 0)
    }

    @Test fun releaseBaseVersionIsAlpha14() {
        assertEquals("0.2.0-alpha14.1", packageInfo().versionName!!.removeSuffix("-test"))
    }

    @Test fun debugVersionIsAlpha14Test() {
        assertEquals("0.2.0-alpha14.1-test", packageInfo().versionName)
    }

    @Test fun versionCodeIsSixteen() {
        assertEquals(17L, packageInfo().longVersionCode)
    }

    @Test fun debugPackagePreservesUpdateIdentity() {
        assertEquals("com.cztr.routinealarm.dev", packageInfo().packageName)
    }
}
