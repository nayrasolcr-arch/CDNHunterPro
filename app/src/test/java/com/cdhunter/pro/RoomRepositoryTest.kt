package com.cdhunter.pro

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cdhunter.pro.db.AppDatabase
import com.cdhunter.pro.db.CandidateEntity
import com.cdhunter.pro.db.ScanEntity
import com.cdhunter.pro.db.SniResultEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room integration tests (in-memory, Robolectric): candidate dedup, scan
 * state persistence for pause/resume, results upsert, SNI records, settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomRepositoryTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `candidate unique index rejects duplicates`() = runBlocking {
        val dao = db.candidateDao()
        val now = System.currentTimeMillis()
        val e = CandidateEntity(ip = "1.2.3.4", family = 4, providerId = null, origin = "IMPORT_TXT", addedAtMs = now)
        assertEquals(true, dao.insert(e) != -1L)
        assertEquals(-1L, dao.insert(e.copy(addedAtMs = now + 1)))
        assertEquals(1, dao.count())
    }

    @Test
    fun `scan state persists for pause and resume`() = runBlocking {
        val dao = db.scanDao()
        val id = dao.insert(
            ScanEntity(
                mode = "STANDARD",
                status = "RUNNING",
                startedAtMs = 1L,
                configJson = "{}",
                totalTargets = 100,
            ),
        )
        assertNotEquals(0, id)

        // simulate progress batches written by the writer coroutine
        dao.updateProgress(id, tested = 42, reachable = 20, verified = 5, errors = 8, bytes = 90_000, cursor = 42, heartbeat = 500L)

        val loaded = dao.getById(id)!!
        assertEquals(42, loaded.testedCount)
        assertEquals(42, loaded.cursorIndex)
        assertEquals(90_000L, loaded.bytesUsed)

        // pause -> active scan is discoverable after process restart
        dao.finish(id, "PAUSED", null, "USER")
        val active = dao.activeScan()
        assertNotNull(active)
        assertEquals("PAUSED", active!!.status)

        // resume marks running again
        dao.setStatus(id, "RUNNING")
        assertEquals("RUNNING", dao.activeScan()!!.status)
    }

    @Test
    fun `stale heartbeat scan is recoverable`() = runBlocking {
        val dao = db.scanDao()
        val id = dao.insert(
            ScanEntity(
                mode = "STANDARD", status = "RUNNING", startedAtMs = 1L,
                configJson = "{}", totalTargets = 10,
                heartbeatAtMs = System.currentTimeMillis() - 10 * 60_000L, // 10 min old
            ),
        )
        val active = dao.activeScan()
        assertNotNull(active)
        assertEquals("RUNNING", active!!.status)
        // engine.recoverStaleScans() would flip it to PAUSED based on heartbeat age
        assertTrue(System.currentTimeMillis() - active.heartbeatAtMs > 60_000)
    }

    @Test
    fun `result upsert keyed by scan-ip-port`() = runBlocking {
        val rDao = db.resultDao()
        val base = com.cdhunter.pro.db.ResultEntity(
            scanId = 1, ip = "104.16.1.1", port = 443, providerId = "cloudflare",
            tcpOk = true, testedAtMs = 1L, verdict = "CANDIDATE_NEEDS_TESTING",
        )
        rDao.upsert(base)
        rDao.upsert(base.copy(tcpConnectMs = 55, score = 70.0))
        assertEquals(1, rDao.forScanOnce(1).size)
        assertEquals(55, rDao.forScanOnce(1).first().tcpConnectMs)

        // same IP, different port = separate row
        rDao.upsert(base.copy(port = 80))
        assertEquals(2, rDao.forScanOnce(1).size)
    }

    @Test
    fun `sni results upsert and expiry query`() = runBlocking {
        val dao = db.sniResultDao()
        val now = System.currentTimeMillis()
        val s = SniResultEntity(
            ip = "104.16.1.1", sni = "cloudflare.com", providerId = "cloudflare",
            ok = true, certMatched = true, testedAtMs = now, expiresAtMs = now + 1000,
        )
        dao.upsertAll(listOf(s))
        dao.upsertAll(listOf(s.copy(tlsVersion = "TLSv1.3")))
        assertEquals(1, dao.forIp("104.16.1.1").size)
        assertEquals("TLSv1.3", dao.forIp("104.16.1.1").first().tlsVersion)

        dao.deleteExpired(now + 2000)
        assertTrue(dao.forIp("104.16.1.1").isEmpty())
    }

    @Test
    fun `settings key-value roundtrip`() = runBlocking {
        val dao = db.settingsDao()
        dao.upsert(com.cdhunter.pro.db.SettingsEntity("concurrency", "24"))
        dao.upsert(com.cdhunter.pro.db.SettingsEntity("concurrency", "32"))
        assertEquals("32", dao.get("concurrency")?.value)
    }

    @Test
    fun `error log trims beyond cap`() = runBlocking {
        val dao = db.errorLogDao()
        repeat(600) { i ->
            dao.insert(com.cdhunter.pro.db.ErrorLogEntity(atMs = i.toLong(), level = "ERROR", tag = "t", message = "m$i"))
        }
        dao.trim()
        assertEquals(500, dao.count())
        // the visible log query is capped at 200 rows
        assertEquals(200, dao.observeRecent().first().size)
    }

    @Test
    fun `aggregates for dashboard`() = runBlocking {
        val rDao = db.resultDao()
        val base = com.cdhunter.pro.db.ResultEntity(
            scanId = 1, ip = "1.1.1.1", port = 443, providerId = null,
            tcpOk = true, testedAtMs = 5L, verdict = "VERIFIED_COMPATIBLE", score = 90.0,
        )
        rDao.upsert(base)
        rDao.upsert(base.copy(ip = "2.2.2.2", tcpOk = false, verdict = "UNREACHABLE", score = null, errorCategory = "TCP_TIMEOUT"))
        assertEquals(2, rDao.countAll())
        assertEquals(1, rDao.countReachable(1))
        assertEquals(1, rDao.countVerified(1))
        assertEquals(1, rDao.countErrors(1))
        assertEquals(90.0, rDao.bestEver()!!.score!!, 0.001)
        assertEquals(5L, rDao.lastSuccessAt())
    }
}
