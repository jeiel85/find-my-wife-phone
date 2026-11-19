package io.github.jeiel85.findmywifephone

import com.google.android.play.core.install.model.InstallErrorCode
import com.google.android.play.core.install.model.UpdateAvailability
import io.github.jeiel85.findmywifephone.update.SelfUpdate
import io.github.jeiel85.findmywifephone.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Play reports only a versionCode; it must read back as the versionName build.gradle.kts derived it from. */
class StoreUpdateTest {
    @Test fun versionCodeReadsBackAsVersionName() {
        assertEquals("0.3.4", SelfUpdate.versionName(304))
        assertEquals("0.10.0", SelfUpdate.versionName(1000))
        assertEquals("1.2.15", SelfUpdate.versionName(10215))
    }

    @Test fun playAnswerMapsToSettingsState() {
        assertEquals(UpdateState.StoreUpdate("0.3.5"), SelfUpdate.stateOf(UpdateAvailability.UPDATE_AVAILABLE, 305))
        for (a in listOf(UpdateAvailability.UPDATE_NOT_AVAILABLE, UpdateAvailability.UNKNOWN,
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS)) {
            assertEquals(UpdateState.UpToDate, SelfUpdate.stateOf(a, 305))
        }
    }

    /** Only "not from Play" is permanent; a Play install that is offline must not be told it is not from Play. */
    @Test fun failureReasonSeparatesNotFromPlayFromTransientErrors() {
        val notOwned = SelfUpdate.failureReason(InstallErrorCode.ERROR_APP_NOT_OWNED)
        assertEquals(notOwned, SelfUpdate.failureReason(InstallErrorCode.ERROR_API_NOT_AVAILABLE))
        assertNotEquals(notOwned, SelfUpdate.failureReason(InstallErrorCode.ERROR_INTERNAL_ERROR))
        assertNotEquals(notOwned, SelfUpdate.failureReason(null))
        assertNotEquals(SelfUpdate.failureReason(null), SelfUpdate.failureReason(InstallErrorCode.ERROR_PLAY_STORE_NOT_FOUND))
    }
}
