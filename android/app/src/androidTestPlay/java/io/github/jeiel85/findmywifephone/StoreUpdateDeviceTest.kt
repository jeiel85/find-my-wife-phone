package io.github.jeiel85.findmywifephone

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import io.github.jeiel85.findmywifephone.update.SelfUpdate
import io.github.jeiel85.findmywifephone.update.UpdateState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "새 버전 있음" path against Play's own fake (issue #18): only a Play install can get a real
 * answer, so this is the one place the whole check — Play API → settings state → store link — runs.
 * `./gradlew connectedPlayDebugAndroidTest` with an emulator or phone attached.
 */
@RunWith(AndroidJUnit4::class)
class StoreUpdateDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun restore() {
        SelfUpdate.managerFor = { AppUpdateManagerFactory.create(it) }
    }

    @Test fun newerVersionOnPlayShowsStoreUpdate() = runBlocking {
        val fake = FakeAppUpdateManager(context).apply { setUpdateAvailable(BuildConfig.VERSION_CODE + 1) }
        SelfUpdate.managerFor = { fake }
        val expected = UpdateState.StoreUpdate(SelfUpdate.versionName(BuildConfig.VERSION_CODE + 1))
        assertEquals(expected, SelfUpdate.check(context, userInitiated = true))
    }

    @Test fun noNewerVersionIsUpToDate() = runBlocking {
        val fake = FakeAppUpdateManager(context).apply { setUpdateNotAvailable() }
        SelfUpdate.managerFor = { fake }
        assertEquals(UpdateState.UpToDate, SelfUpdate.check(context, userInitiated = true))
    }

    @Test fun storeLinkOpensThisAppInPlayStore() {
        val intent = SelfUpdate.storeIntent(context)
        assertEquals("com.android.vending", intent.`package`)
        assertEquals(Uri.parse("market://details?id=${context.packageName}"), intent.data)
    }
}
