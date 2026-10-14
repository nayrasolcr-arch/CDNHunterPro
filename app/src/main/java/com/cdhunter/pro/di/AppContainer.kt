package com.cdhunter.pro.di

import android.content.Context
import com.cdhunter.pro.core.source.RangeFetcher
import com.cdhunter.pro.db.AppDatabase
import com.cdhunter.pro.export.ExportManager
import com.cdhunter.pro.network.HttpTester
import com.cdhunter.pro.network.NetworkMonitor
import com.cdhunter.pro.network.SpeedTester
import com.cdhunter.pro.network.TcpTester
import com.cdhunter.pro.network.TlsTester
import com.cdhunter.pro.repo.CandidateRepository
import com.cdhunter.pro.repo.RangeRepository
import com.cdhunter.pro.repo.RangeSeeder
import com.cdhunter.pro.repo.ResultRepository
import com.cdhunter.pro.repo.SettingsRepository
import com.cdhunter.pro.scan.ScanEngine
import com.cdhunter.pro.sni.SniEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled application container (no external DI framework — fewer moving
 * parts, full testability through constructor injection).
 */
class AppContainer(context: Context) {

    val context: Context = context.applicationContext

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.build(context) }

    val settings: SettingsRepository by lazy { SettingsRepository(database.settingsDao()) }

    val rangeFetcher: RangeFetcher by lazy { RangeFetcher(RangeFetcher.defaultClient()) }

    val rangeRepository: RangeRepository by lazy {
        RangeRepository(database.ipRangeDao(), database.providerStateDao(), rangeFetcher)
    }

    val rangeSeeder: RangeSeeder by lazy {
        RangeSeeder(database.ipRangeDao(), database.providerStateDao())
    }

    val candidateRepository: CandidateRepository by lazy { CandidateRepository(database.candidateDao()) }

    val resultRepository: ResultRepository by lazy {
        ResultRepository(database.resultDao(), database.sniResultDao(), database.errorLogDao())
    }

    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(context, appScope) }

    val tcpTester: TcpTester by lazy { TcpTester() }

    val tlsTester: TlsTester by lazy { TlsTester() }

    val httpTester: HttpTester by lazy { HttpTester() }

    val speedTester: SpeedTester by lazy { SpeedTester(tcpTester) }

    val sniEngine: SniEngine by lazy {
        SniEngine(tlsTester, httpTester, resultRepository, settings)
    }

    val scanEngine: ScanEngine by lazy {
        ScanEngine(
            context = context,
            scope = appScope,
            scanDao = database.scanDao(),
            rangeRepository = rangeRepository,
            candidateRepository = candidateRepository,
            resultRepository = resultRepository,
            settings = settings,
            monitor = networkMonitor,
            tcpTester = tcpTester,
            tlsTester = tlsTester,
            httpTester = httpTester,
            speedTester = speedTester,
            sniEngine = sniEngine,
        )
    }

    val exportManager: ExportManager by lazy { ExportManager(context, database.exportLogDao()) }

    suspend fun init() {
        settings.load()
        networkMonitor.start()
        scanEngine.recoverStaleScans()
        // First-launch guarantee: seed the OFFICIAL bundled ranges so the very
        // first scan always has real targets (a fresh install used to have an
        // empty ranges table → zero targets → an "empty" scan).
        rangeSeeder.seedIfNeeded(context)
    }
}
