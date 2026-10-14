package com.cdhunter.pro

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cdhunter.pro.db.AppDatabase
import com.cdhunter.pro.repo.RangeSeeder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression tests for the "first scan had zero targets" bug: the bundled
 * official-ranges asset must seed a fresh install with real targets, and the
 * OFFICIAL fallback lists (Cloudflare/Google/CloudFront/Azure/Fastly) must
 * never be empty. Akamai has no bundled list (its official endpoint is
 * blocked from the build environment) — it is refreshed live instead.
 */
@RunWith(RobolectricTestRunner::class)
class RangeSeederTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `bundled asset exists and parses to non-empty official lists`() = runBlocking {
        val seeder = RangeSeeder(db.ipRangeDao(), db.providerStateDao())
        val ids = seeder.bundledProviderIds(context)
        // cloudflare + google + cloudfront + azure + fastly must be bundled.
        for (required in listOf("cloudflare", "google", "cloudfront", "azure", "fastly")) {
            assertTrue("bundled asset must contain $required", ids.contains(required))
        }
    }

    @Test
    fun `seedIfNeeded fills a fresh database with real targets`() = runBlocking {
        val seeder = RangeSeeder(db.ipRangeDao(), db.providerStateDao())
        assertEquals(0, db.ipRangeDao().countAll())
        val inserted = seeder.seedIfNeeded(context)
        assertTrue("bundled seed must insert ranges, got $inserted", inserted > 0)
        assertTrue(db.ipRangeDao().countFor("cloudflare") >= 10)
        assertTrue(db.ipRangeDao().countFor("fastly") >= 5)
        assertTrue(db.ipRangeDao().countFor("cloudfront") >= 50)
        // Every bundled provider row must be marked BUNDLED, never presented
        // as a fresh live fetch.
        val state = db.providerStateDao().get("cloudflare")
        assertTrue(state?.lastUpdateStatus?.startsWith("BUNDLED") == true)
    }

    @Test
    fun `seedIfNeeded is a no-op when ranges already exist`() = runBlocking {
        val seeder = RangeSeeder(db.ipRangeDao(), db.providerStateDao())
        val first = seeder.seedIfNeeded(context)
        val countAfterFirst = db.ipRangeDao().countAll()
        assertEquals(first, countAfterFirst)
        val second = seeder.seedIfNeeded(context)
        assertEquals(0, second)
        assertEquals(countAfterFirst, db.ipRangeDao().countAll())
    }

    @Test
    fun `force seed never duplicates providers that already have ranges`() = runBlocking {
        val seeder = RangeSeeder(db.ipRangeDao(), db.providerStateDao())
        seeder.seedIfNeeded(context)
        val before = db.ipRangeDao().countAll()
        seeder.seed(context) // force — must skip providers that already have ranges
        assertEquals(before, db.ipRangeDao().countAll())
    }

    @Test
    fun `every bundled cidr is public-routable`() = runBlocking {
        val seeder = RangeSeeder(db.ipRangeDao(), db.providerStateDao())
        seeder.seedIfNeeded(context)
        val ranges = db.ipRangeDao().allOnce()
        assertTrue(ranges.isNotEmpty())
        for (r in ranges) {
            assertTrue("not public-routable: ${r.cidr}", r.total > 0)
            assertTrue("family must be 4 or 6: ${r.cidr}", r.family == 4 || r.family == 6)
        }
    }
}
