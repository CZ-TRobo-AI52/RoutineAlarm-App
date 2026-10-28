package com.cztr.routinealarm

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Alpha14ReleaseMetadataTest {
    private fun packageInfo() = RuntimeEnvironment.getApplication().let { app ->
        app.packageManager.getPackageInfo(app.packageName, 0)
    }

    @Test fun releaseBaseVersionIsAlpha14() {
        assertEquals("0.2.0-alpha14", packageInfo().versionName!!.removeSuffix("-test"))
    }

    @Test fun debugVersionIsAlpha14Test() {
        assertEquals("0.2.0-alpha14-test", packageInfo().versionName)
    }

    @Test fun versionCodeIsSixteen() {
        assertEquals(16L, packageInfo().longVersionCode)
    }

    @Test fun debugPackagePreservesUpdateIdentity() {
        assertEquals("com.cztr.routinealarm.dev", packageInfo().packageName)
    }
}
