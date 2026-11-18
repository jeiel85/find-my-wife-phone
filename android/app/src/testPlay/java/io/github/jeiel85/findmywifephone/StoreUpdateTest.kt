package io.github.jeiel85.findmywifephone

import io.github.jeiel85.findmywifephone.update.SelfUpdate
import org.junit.Assert.assertEquals
import org.junit.Test

/** Play reports only a versionCode; it must read back as the versionName build.gradle.kts derived it from. */
class StoreUpdateTest {
    @Test fun versionCodeReadsBackAsVersionName() {
        assertEquals("0.3.4", SelfUpdate.versionName(304))
        assertEquals("0.10.0", SelfUpdate.versionName(1000))
        assertEquals("1.2.15", SelfUpdate.versionName(10215))
    }
}
