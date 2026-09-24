package io.nekohasekai.sagernet.bg.test

import io.nekohasekai.sagernet.bg.proto.CoreConfig
import io.nekohasekai.sagernet.bg.proto.CoreConfigs
import io.nekohasekai.sagernet.bg.proto.applyTo
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.completeWith
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.outbound.config.GeneratedConfig
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.TestRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/** kTestBatchSize (TestRunner.cpp:21): profiles per test build. */
internal const val TEST_BATCH_SIZE = 100

/**
 * Probes of one batch running at once (the desktop pool allows 10). Every probe is a whole sing-box, plus Xray when
 * needed, next to the running VPN instance in :bg, and a custom full config brings its own DNS, routes and rule-sets:
 * three keeps the peak around a few hundred MB on 2-4 GB phones while slow probes still overlap.
 */
internal const val MAX_PARALLEL_PROBES = 3

/** probe.ErrTestAborted: the core's text for a tag a stop kept from starting. */
internal const val ERROR_ABORTED = "test aborted"

internal const val ERROR_NO_RESULT = "no result"

/** classifyTestCandidate's Skip reasons (the generator's texts) and vanished profiles: not tested, never failed. */
internal fun isSkipReason(reason: String): Boolean = reason.startsWith("Skipping ") || reason == "Profile does not exist"

/** Core handlers run on Go threads, where an escaping exception would take the process down. */
internal inline fun guarded(block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        Logs.w(e)
    }
}

/**
 * One core test of a test build (TestRunner::Target): the shared box over its candidates' tags, or a custom full
 * config measured through its default outbound.
 */
internal class TestProbe private constructor(
    private val core: CoreConfig,
    private val tagToProfileId: Map<String, Long>,
    private val fullConfigProfileId: Long,
) {
    val profileIds: List<Long> =
        if (fullConfigProfileId > 0) listOf(fullConfigProfileId) else tagToProfileId.values.toList()

    /** A full config reports its default outbound's tag, which stands for its profile (resolveEntID's fallback). */
    fun profileOf(tag: String?): Long? =
        if (fullConfigProfileId > 0) fullConfigProfileId else tag?.let { tagToProfileId[it] }

    /** The tags the test build gave the profiles [ids] in the shared box. */
    fun tagsOf(ids: Set<Long>): List<String> = tagToProfileId.filterValues { it in ids }.keys.toList()

    fun request(): TestRequest = TestRequest().apply {
        core.applyTo(this)
        useDefaultOutbound = fullConfigProfileId > 0
    }

    companion object {
        /** The probes of a build (TestRunner.cpp:357-373); the shared box goes first, it carries most of the batch. */
        fun plan(generated: GeneratedConfig): List<TestProbe> = buildList {
            if (generated.outboundTags.isNotEmpty()) {
                add(TestProbe(CoreConfig.from(generated), generated.tagToProfileId, -1L))
            }
            for ((id, config) in generated.fullConfigs) add(TestProbe(CoreConfig(coreConfig = config), emptyMap(), id))
        }
    }
}

/**
 * Starts one core test and suspends until the core reports it done; throws when the probe box cannot be built. A stop
 * landing while the test starts may have re-armed the core's test context before the test took it, so it is repeated.
 */
internal suspend fun TestSession.awaitCore(start: (done: () -> Unit) -> Unit) {
    suspendCancellableCoroutine<Unit> { continuation ->
        start { continuation.completeWith(Result.success(Unit)) }
        if (cancelled) Mobile.stopTests()
    }
}

/**
 * One URL or IP result; [measured] is false for what the core never measured (skip, stop, probe failure). A URL
 * failure is reported again with [connectOnly] once the core finds the profile's VPN tunnel up.
 */
internal class ProbeResult(
    @JvmField val profileId: Long,
    @JvmField val latency: Int = 0,
    @JvmField val ip: String = "",
    @JvmField val country: String = "",
    @JvmField val error: String = "",
    @JvmField val measured: Boolean = true,
    @JvmField val connectOnly: Boolean = false,
)

/**
 * runLatencyGroup (TestRunner.cpp:307-406) for URL and IP tests: batches of [TEST_BATCH_SIZE] profiles, one test build
 * each, its probes at most [MAX_PARALLEL_PROBES] at a time; results are reported in arrival order, a chunk per wake-up.
 */
internal abstract class LatencySweep(protected val session: TestSession) {

    /** Starts the core test of [probe], one of [batch]'s; [emit] and [done] are called on Go threads. */
    protected abstract fun start(
        probe: TestProbe, batch: List<ProxyEntity>, emit: (ProbeResult) -> Unit, done: () -> Unit,
    )

    /** Persists the measured results and reports every one. */
    protected abstract fun report(results: List<ProbeResult>)

    /** A candidate the generator rejected (BuildTestConfig: "Skipping invalid config"). */
    protected abstract fun invalid(profileId: Long, reason: String): ProbeResult

    suspend fun run(profiles: List<ProxyEntity>) {
        for (slice in profiles.chunked(TEST_BATCH_SIZE)) {
            if (session.cancelled) break
            runBatch(slice)
        }
    }

    private suspend fun runBatch(batch: List<ProxyEntity>) = coroutineScope {
        val ids = batch.map { it.id }
        val generated = CoreConfigs.buildTest(ids)
        if (!generated.ok) {
            val error = generated.error ?: "config generation failed"
            Logs.w("Failed to build test config for batch: $error")
            report(ids.map { ProbeResult(it, error = error, measured = false) })
            return@coroutineScope
        }
        if (generated.skipped.isNotEmpty()) {
            report(generated.skipped.map { (id, reason) ->
                if (isSkipReason(reason)) ProbeResult(id, error = reason, measured = false) else invalid(id, reason)
            })
        }
        val results = Channel<ProbeResult>(Channel.UNLIMITED)
        val gate = Semaphore(MAX_PARALLEL_PROBES)
        launch {
            TestProbe.plan(generated).map { probe -> launch { gate.withPermit { runProbe(probe, batch, results) } } }
                .joinAll()
            results.close()
        }
        while (true) {
            val chunk = arrayListOf(results.receiveCatching().getOrNull() ?: break)
            while (true) chunk.add(results.tryReceive().getOrNull() ?: break)
            report(chunk)
        }
    }

    private suspend fun runProbe(probe: TestProbe, batch: List<ProxyEntity>, results: Channel<ProbeResult>) {
        val reported: MutableSet<Long> = ConcurrentHashMap.newKeySet()
        val emit: (ProbeResult) -> Unit = { if (reported.add(it.profileId) || it.connectOnly) results.trySend(it) }
        if (session.cancelled) {
            probe.profileIds.forEach { emit(ProbeResult(it, error = ERROR_ABORTED, measured = false)) }
            return
        }
        try {
            session.awaitCore { done -> start(probe, batch, emit, done) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logs.w(e)
            probe.profileIds.forEach { emit(ProbeResult(it, error = e.readableMessage, measured = false)) }
            return
        }
        probe.profileIds.forEach { emit(ProbeResult(it, error = ERROR_NO_RESULT, measured = false)) }
    }
}
