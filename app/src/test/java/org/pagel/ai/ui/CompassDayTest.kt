package org.pagel.ai.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.CompassTestKeys
import org.pagel.ai.data.account.InMemoryAccountStore
import org.pagel.ai.data.compass.CompassApi
import org.pagel.ai.data.compass.CompassDatabase
import org.pagel.ai.data.compass.CompassKey
import org.pagel.ai.data.compass.CompassRepository
import org.pagel.ai.data.compass.CompassRules
import org.pagel.ai.data.db.PagelDatabase
import org.pagel.ai.data.inMemoryDb
import org.pagel.ai.data.today.TodayRepository
import org.pagel.ai.ui.compass.CompassViewModel
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/**
 * Compass checker #5: one ViewModel kept alive across his birthday midnight. Opened on 31 Oct 23:59 it says Year 18;
 * back on screen after midnight it says Year 19 and the age report "Year 18 → 19" (it used to keep the old date).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CompassDayTest {
    private lateinit var main: PagelDatabase
    private lateinit var compass: CompassDatabase
    private var now = Instant.parse("2026-10-31T18:59:00Z").toEpochMilli() // 31 Oct 23:59 Tashkent

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        main = inMemoryDb()
        compass = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CompassDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        compass.close()
        main.close()
    }

    @Test fun theBirthdayMidnightTurnsTheYearInsideOneViewModel() = runBlocking {
        val repo = CompassRepository(
            compass.dao(), CompassApi(OkHttpClient(), Dispatchers.IO), CompassTestKeys(), CompassKey(CoroutineScope(SupervisorJob())),
            main.dao(), TodayRepository(main.dao(), { }, clock = { now }), { }, Dispatchers.IO, clock = { now },
        )
        val vm = CompassViewModel(repo, InMemoryAccountStore(), { })
        val before = withTimeout(5_000) { vm.ui.first { it.today == LocalDate.parse("2026-10-31") } }
        assertEquals("Year 18", CompassRules.yearTitle(before.yearStart))
        now = Instant.parse("2026-10-31T19:00:30Z").toEpochMilli() // 1 Nov 00:00:30 Tashkent
        vm.stay()
        val after = withTimeout(5_000) { vm.ui.first { it.today == LocalDate.parse("2026-11-01") } }
        assertEquals("Year 19", CompassRules.yearTitle(after.yearStart))
        assertEquals("Year 18 → 19", CompassRules.reportTitle(after.reportEnd))
        assertEquals(1, after.season.n)
    }
}
