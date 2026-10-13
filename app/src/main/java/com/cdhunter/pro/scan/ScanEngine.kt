package com.cdhunter.pro.scan

import android.content.Context
import com.cdhunter.pro.core.model.ErrorCategory
import com.cdhunter.pro.core.model.LatencyStats
import com.cdhunter.pro.core.model.PauseReason
import com.cdhunter.pro.core.model.ProbeOutcome
import com.cdhunter.pro.core.model.ScanConfig
import com.cdhunter.pro.core.model.ScanMode
import com.cdhunter.pro.core.model.ScanProgress
import com.cdhunter.pro.core.model.ScanStatus
import com.cdhunter.pro.core.model.Target
import com.cdhunter.pro.core.registry.CdnRegistry
import com.cdhunter.pro.core.util.Cidr
import com.cdhunter.pro.core.util.IpValidator
import com.cdhunter.pro.db.ScanDao
import com.cdhunter.pro.db.ScanEntity
import com.cdhunter.pro.network.HttpTester
import com.cdhunter.pro.network.NetworkMonitor
import com.cdhunter.pro.network.SpeedTester
import com.cdhunter.pro.network.TcpTester
import com.cdhunter.pro.network.TlsTester
import com.cdhunter.pro.repo.CandidateRepository
import com.cdhunter.pro.repo.RangeRepository
import com.cdhunter.pro.repo.ResultRepository
import com.cdhunter.pro.repo.SettingsRepository
import com.cdhunter.pro.sni.SniEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The three-mode scan engine (quick / standard / deep) with:
 *  - bounded concurrency + timeouts,
 *  - pause/resume with persisted cursor (survives process death),
 *  - data cap, Wi-Fi-only mode, network-change pause,
 *  - adaptive deep phase (top-N re-validation, IP×SNI combos),
 *  - honest progress derived only from real measurements.
 */
class ScanEngine(
    private val context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val scanDao: ScanDao,
    private val rangeRepository: RangeRepository,
    private val candidateRepository: CandidateRepository,
    private val resultRepository: ResultRepository,
    private val settings: SettingsRepository,
    private val monitor: NetworkMonitor,
    private val tcpTester: TcpTester,
    private val tlsTester: TlsTester,
    private val httpTester: HttpTester,
    private val speedTester: SpeedTester,
    private val sniEngine: SniEngine,
) {

    @Serializable
    private data class TargetLine(val ip: String, val provider: String? = null, val ports: List<Int> = listOf(80, 443))

    private val json = Json { ignoreUnknownKeys = true }

    private val _progress = MutableStateFlow<ScanProgress?>(null)
    val progress: StateFlow<ScanProgress?> = _progress

    private var job: Job? = null
    private var config: ScanConfig? = null
    private var targets: List<Target> = emptyList()
    private var cursor: Int = 0
    private var scanId: Long = -1L
    private var startedAtMs: Long = 0L
    private var tested: Int = 0
    private var reachable: Int = 0
    private var verified: Int = 0
    private var errors: Int = 0
    private var bytesUsed: Long = 0L
    private var pausedByNetwork: Boolean = false

    val isActive: Boolean get() = job?.isActive == true

    fun targetsFile(scanId: Long): File = File(context.filesDir, "scan_targets_$scanId.txt")

    // ---------------------------------------------------------------- start

    suspend fun start(config: ScanConfig): Result {
        if (isActive) return Result.ALREADY_RUNNING
        val net = monitor.currentState()
        if (config.wifiOnly && net.metered) return Result.WIFI_ONLY_BLOCKED
        if (!net.online) return Result.NO_NETWORK

        this.config = config
        this.targets = buildTargets(config)
        if (targets.isEmpty()) return Result.NO_TARGETS

        val capped = targets.size >= config.maxTargets
        this.scanId = scanDao.insert(
            ScanEntity(
                mode = config.mode.name,
                status = ScanStatus.RUNNING.name,
                startedAtMs = System.currentTimeMillis(),
                configJson = json.encodeToString(ScanConfig.serializer(), config),
                totalTargets = targets.size,
                heartbeatAtMs = System.currentTimeMillis(),
                networkTokenAtStart = net.networkToken,
                note = if (capped) "target cap reached" else null,
            ),
        )
        writeTargetsFile()
        this.cursor = 0
        this.tested = 0
        this.reachable = 0
        this.verified = 0
        this.errors = 0
        this.bytesUsed = 0L
        this.startedAtMs = System.currentTimeMillis()
        launchLoop()
        return Result.STARTED
    }

    private fun launchLoop() {
        val cfg = config ?: return
        job = scope.launch {
            // Watch for network change while running.
            if (cfg.stopOnNetworkChange) {
                val startToken = monitor.currentState().networkToken
                launch {
                    monitor.changes.collect { st ->
                        if (st.networkToken != startToken && isActive) {
                            pausedByNetwork = true
                            pause(PauseReason.NETWORK_CHANGED)
                        }
                    }
                }
            }

            val semaphore = Semaphore(cfg.concurrency.coerceIn(1, 64))
            val pending = targets.drop(cursor)
            val jobs = mutableListOf<Job>()

            for ((index, target) in pending.withIndex()) {
                if (bytesUsed > cfg.dataCapBytes) {
                    pause(PauseReason.DATA_CAP)
                    break
                }
                if (!isActive) break
                val absoluteIndex = cursor + index
                jobs += scope.launch {
                    semaphore.withPermit {
                        if (!isActive) return@withPermit
                        val outcome = runProbe(target, cfg)
                        publish(outcome)
                        advanceCursor(absoluteIndex + 1)
                    }
                }
            }
            jobs.forEach { it.join() }

            if (isActive && !pausedByNetwork) {
                if (bytesUsed > cfg.dataCapBytes) {
                    pause(PauseReason.DATA_CAP)
                } else if (cfg.mode == ScanMode.DEEP) {
                    runDeepPhase(cfg)
                    finish(ScanStatus.COMPLETED, PauseReason.NONE)
                } else {
                    finish(ScanStatus.COMPLETED, PauseReason.NONE)
                }
            }
        }
    }

    // ------------------------------------------------------------ resume/pause

    suspend fun resume(): Boolean {
        if (isActive) return false
        val active = scanDao.activeScan() ?: return false
        val cfg = runCatching {
            json.decodeFromString(ScanConfig.serializer(), active.configJson)
        }.getOrNull() ?: return false
        val file = targetsFile(active.id)
        if (!file.exists()) return false

        this.config = cfg
        this.scanId = active.id
        this.targets = readTargetsFile()
        this.cursor = active.cursorIndex.coerceAtMost(targets.size)
        this.tested = active.testedCount
        this.reachable = active.reachableCount
        this.verified = active.verifiedCount
        this.errors = active.errorCount
        this.bytesUsed = active.bytesUsed
        this.startedAtMs = active.startedAtMs
        this.pausedByNetwork = false
        scanDao.setStatus(active.id, ScanStatus.RUNNING.name)
        scanDao.finish(active.id, ScanStatus.RUNNING.name, null, null)
        launchLoop()
        return true
    }

    fun pause(reason: PauseReason = PauseReason.USER) {
        if (!isActive) return
        job?.cancel()
        job = null
        scope.launch {
            persistProgress(force = true)
            scanDao.finish(scanId, ScanStatus.PAUSED.name, null, reason.name)
            _progress.value = _progress.value?.copy(status = ScanStatus.PAUSED, pauseReason = reason)
        }
    }

    suspend fun cancel() {
        job?.cancel()
        job = null
        scanDao.finish(scanId, ScanStatus.CANCELLED.name, System.currentTimeMillis(), PauseReason.USER.name)
        _progress.value = _progress.value?.copy(status = ScanStatus.CANCELLED)
    }

    /** Marks stale RUNNING scans (crash/kill) as PAUSED so they can resume. */
    suspend fun recoverStaleScans() {
        val active = scanDao.activeScan() ?: return
        val heartbeatAge = System.currentTimeMillis() - active.heartbeatAtMs
        if (active.status == ScanStatus.RUNNING.name && heartbeatAge > 60_000) {
            scanDao.finish(active.id, ScanStatus.PAUSED.name, null, null)
        }
    }

    // ------------------------------------------------------------- probing

    /** Public entry for single-IP re-tests (IP detail screen). */
    suspend fun runProbePublic(target: Target, cfg: ScanConfig): ProbeOutcome = runProbe(target, cfg)

    private suspend fun runProbe(target: Target, cfg: ScanConfig): ProbeOutcome {
        val now = System.currentTimeMillis()
        val portsToTest = if (cfg.mode == ScanMode.QUICK) {
            listOf(target.ports.lastOrNull() ?: 443)
        } else {
            target.ports
        }

        val mainPort = portsToTest.lastOrNull() ?: 443
        val attempts = when (cfg.mode) {
            ScanMode.QUICK -> 1
            ScanMode.STANDARD -> cfg.stabilityAttempts
            ScanMode.DEEP -> maxOf(5, cfg.stabilityAttempts)
        }

        // 1) TCP latency/stability on the main port.
        val (stats, samples) = speedTester.measure(target.ip, mainPort, cfg.timeoutMs, attempts)
        val tcpOk = stats.successes > 0
        var bytes = attempts * HttpTester.REQUEST_OVERHEAD_BYTES

        var tlsOk: Boolean? = null
        var tlsHandshakeMs: Int? = null
        var tlsVersion: String? = null
        var certCn: String? = null
        var certNotAfterMs: Long? = null
        var certMatched: Boolean? = null
        var httpOk: Boolean? = null
        var httpStatus: Int? = null
        var httpTtfbMs: Int? = null
        var httpTotalMs: Int? = null
        var httpVersion: String? = null
        var downloadKbps: Double? = null
        var uploadKbps: Double? = null
        var errorCategory = if (tcpOk) ErrorCategory.NONE else firstError(samples, cfg, target)

        if (tcpOk && cfg.mode != ScanMode.QUICK) {
            // 2) TLS on 443 (SNI if configured for the provider).
            if (mainPort == 443) {
                val sni = pickSni(target, cfg)
                val tls = tlsTester.probe(target.ip, 443, sni, cfg.timeoutMs)
                bytes += HttpTester.REQUEST_OVERHEAD_BYTES
                tlsOk = tls.ok
                if (tls.ok) {
                    tlsHandshakeMs = tls.handshakeMs
                    tlsVersion = tls.protocol
                    certCn = tls.certCn
                    certNotAfterMs = tls.certNotAfterMs
                    certMatched = tls.certMatched
                    errorCategory = tls.category
                } else if (errorCategory == ErrorCategory.NONE) {
                    errorCategory = tls.category
                }
            }

            // 3) HTTP probes on the configured ports.
            val httpPort = portsToTest.firstOrNull { it == 80 } ?: (if (tlsOk == true) 443 else null)
            if (httpPort != null) {
                val sni = pickSni(target, cfg)
                val http = httpTester.probe(
                    ip = target.ip,
                    hostHeaderOrSni = sni.ifEmpty { target.ip },
                    port = httpPort,
                    timeoutMs = cfg.timeoutMs,
                    maxBytes = 64_000L,
                )
                bytes += http.bytesReceived + HttpTester.REQUEST_OVERHEAD_BYTES
                httpOk = http.ok
                httpStatus = http.statusCode
                httpTtfbMs = http.ttfbMs
                httpTotalMs = http.totalMs
                httpVersion = http.version
                if (!http.ok && errorCategory == ErrorCategory.NONE) errorCategory = http.category
            }

            // 4) Download speed (deep mode only, controlled byte budget).
            if (cfg.mode == ScanMode.DEEP) {
                val sni = pickSni(target, cfg)
                if (sni.isNotEmpty()) {
                    val dl = httpTester.probe(
                        ip = target.ip,
                        hostHeaderOrSni = sni,
                        port = 443,
                        timeoutMs = cfg.timeoutMs * 2,
                        maxBytes = cfg.downloadTestBytes.toLong(),
                    )
                    bytes += dl.bytesReceived + HttpTester.REQUEST_OVERHEAD_BYTES
                    if (dl.ok) downloadKbps = dl.downloadKbps
                }
                val def = target.providerId?.let { CdnRegistry.byId(it) }
                if (def?.supportsUploadTest == true && sniForProvider(target, cfg).contains("speed.cloudflare.com")) {
                    val up = httpTester.uploadProbe(target.ip, "speed.cloudflare.com", cfg.timeoutMs)
                    bytes += HttpTester.REQUEST_OVERHEAD_BYTES
                    if (up.ok) uploadKbps = up.downloadKbps
                }
            }
        }

        return ProbeOutcome(
            ip = target.ip,
            port = mainPort,
            providerId = target.providerId,
            tcpOk = tcpOk,
            tcpConnectMs = if (tcpOk) stats.avgMs else null,
            tlsOk = tlsOk,
            tlsHandshakeMs = tlsHandshakeMs,
            tlsVersion = tlsVersion,
            certCn = certCn,
            certNotAfterMs = certNotAfterMs,
            certMatched = certMatched,
            httpOk = httpOk,
            httpStatus = httpStatus,
            httpTtfbMs = httpTtfbMs,
            httpTotalMs = httpTotalMs,
            httpVersion = httpVersion,
            downloadKbps = downloadKbps,
            uploadKbps = uploadKbps,
            latencyMinMs = if (tcpOk) stats.minMs else null,
            latencyAvgMs = if (tcpOk) stats.avgMs else null,
            latencyMedianMs = if (tcpOk) stats.medianMs else null,
            latencyMaxMs = if (tcpOk) stats.maxMs else null,
            jitterMs = if (tcpOk) stats.jitterMs else null,
            latencySeries = samples,
            attempts = stats.attempts,
            successes = stats.successes,
            errorCategory = errorCategory,
            errorMessage = null,
            bytesUsed = bytes,
            testedAtMs = now,
        )
    }

    private fun firstError(samples: List<Int>, cfg: ScanConfig, target: Target): ErrorCategory =
        if (samples.all { it == 0 }) ErrorCategory.TCP_TIMEOUT else ErrorCategory.UNKNOWN

    private fun sniForProvider(target: Target, cfg: ScanConfig): List<String> {
        val user = cfg.sniFor(target.providerId)
        if (user.isNotEmpty()) return user
        return target.providerId?.let { CdnRegistry.byId(it) }?.defaultTestSnis ?: emptyList()
    }

    private fun pickSni(target: Target, cfg: ScanConfig): String =
        sniForProvider(target, cfg).firstOrNull() ?: ""

    // ------------------------------------------------------------ deep phase

    private suspend fun runDeepPhase(cfg: ScanConfig) {
        setDeepPhaseFlag(true)
        val rows = resultRepository.forScan(scanId)
        val top = rows.filter { it.tcpOk }
            .sortedByDescending { it.score ?: 0.0 }
            .take(cfg.deepTopN)
            .map { it.ip }
            .distinct()
        if (top.isNotEmpty()) {
            // Stability re-validation: 5 more attempts per top IP.
            for (ip in top) {
                if (!isActive) break
                val target = targets.firstOrNull { it.ip == ip } ?: continue
                val outcome = runProbe(target.copy(ports = listOf(443)), cfg)
                publish(outcome)
            }
            // IP×SNI combos for the best candidates of each provider.
            val byProvider = rows.filter { it.tcpOk }.groupBy { it.providerId }
            for ((provider, _) in byProvider) {
                if (!isActive) break
                val providerIps = byProvider[provider]?.map { it.ip }?.take(8) ?: continue
                val snis = cfg.sniFor(provider)
                sniEngine.testCombinations(
                    ips = providerIps,
                    providerId = provider,
                    snis = snis,
                    timeoutMs = cfg.timeoutMs,
                    concurrency = cfg.concurrency,
                )
            }
        }
        setDeepPhaseFlag(false)
        resultRepository.recomputeScores(scanId)
    }

    private fun setDeepPhaseFlag(deep: Boolean) {
        _progress.value = _progress.value?.copy(deepPhase = deep)
    }

    // ---------------------------------------------------------- targets/persist

    /** Builds the target list: sampled ranges + imported candidates, deduped, capped. */
    internal suspend fun buildTargets(cfg: ScanConfig): List<Target> {
        val out = LinkedHashMap<String, Target>()
        val ports = cfg.ports.ifEmpty { listOf(443) }

        // 1) imported/manual candidates always included first.
        candidateRepository.allOnce().forEach { c ->
            if (IpValidator.isPublicRoutable(c.ip)) {
                out[c.ip] = Target(c.ip, c.providerId, ports)
            }
        }

        // 2) sample from ranges of selected providers.
        val ranges = rangeRepository.rangesSnapshot()
        for (provider in cfg.providers) {
            val providerRanges = ranges.filter { it.providerId == provider }
            for (range in providerRanges) {
                val samples = Cidr.sample(range.cidr, cfg.samplePerRange)
                for (ip in samples) {
                    if (out.size >= cfg.maxTargets) break
                    if (!out.containsKey(ip)) out[ip] = Target(ip, provider, ports)
                }
                if (out.size >= cfg.maxTargets) break
            }
            if (out.size >= cfg.maxTargets) break
        }
        return out.values.toList().take(cfg.maxTargets)
    }

    private fun writeTargetsFile() {
        val file = targetsFile(scanId)
        file.parentFile?.mkdirs()
        file.writeText(
            targets.joinToString("\n") { t ->
                json.encodeToString(TargetLine.serializer(), TargetLine(t.ip, t.providerId, t.ports))
            },
        )
    }

    private fun readTargetsFile(): List<Target> {
        val file = targetsFile(scanId)
        if (!file.exists()) return emptyList()
        return file.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching { json.decodeFromString(TargetLine.serializer(), line) }
                .getOrNull()
                ?.let { Target(it.ip, it.provider, it.ports) }
        }
    }

    private fun advanceCursor(newCursor: Int) {
        cursor = newCursor
    }

    private suspend fun publish(outcome: ProbeOutcome) {
        resultRepository.storeOutcome(outcome, scanId)
        synchronized(this) {
            tested++
            bytesUsed += outcome.bytesUsed
            if (outcome.tcpOk) reachable++
            if (outcome.tcpOk && outcome.tlsOk == true && outcome.certMatched == true && outcome.httpOk == true) verified++
            if (outcome.errorCategory != ErrorCategory.NONE && !outcome.tcpOk) errors++
        }
        persistProgress()
        emitProgress()
    }

    private var lastPersistMs = 0L

    private suspend fun persistProgress(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistMs < 2_000) return
        lastPersistMs = now
        scanDao.updateProgress(scanId, tested, reachable, verified, errors, bytesUsed, cursor, now)
    }

    private suspend fun finish(status: ScanStatus, reason: PauseReason) {
        persistProgress(force = true)
        scanDao.finish(scanId, status.name, System.currentTimeMillis(), reason.name)
        resultRepository.recomputeScores(scanId)
        _progress.value = _progress.value?.copy(status = status)
        targetsFile(scanId).delete()
        _progress.value = null
    }

    private suspend fun emitProgress() {
        val cfg = config ?: return
        val elapsedSec = ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(1)
        val ipPerSec = tested.toFloat() / elapsedSec
        val remaining = (targets.size - cursor).coerceAtLeast(0)
        val eta = if (ipPerSec > 0.01f) (remaining / ipPerSec).toLong() else -1
        _progress.value = ScanProgress(
            scanId = scanId,
            status = ScanStatus.RUNNING,
            mode = cfg.mode,
            tested = tested,
            total = targets.size,
            reachable = reachable,
            verified = verified,
            errors = errors,
            ipPerSec = ipPerSec,
            etaSec = eta,
            bytesUsed = bytesUsed,
            dataCapBytes = cfg.dataCapBytes,
            pauseReason = PauseReason.NONE,
            deepPhase = false,
            startedAtMs = startedAtMs,
        )
    }

    enum class Result { STARTED, ALREADY_RUNNING, NO_NETWORK, WIFI_ONLY_BLOCKED, NO_TARGETS }
}
