package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.CoreRuntime
import io.nekohasekai.sagernet.ktx.completeWith
import io.nekohasekai.sagernet.ktx.readableMessage
import io.throneproj.mobile.Instance
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.TestRequest
import io.throneproj.mobile.URLTestHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The URL test of a batch of profiles the desktop way: one test config with every candidate as a `proxy-<n>`
 * chain, one probe box, one batch over all its ingress tags. Candidates the generator skipped are reported as
 * errors, custom full sing-box configs run alone in a box of their own.
 */
class CoreUrlTest(
    private val profileIds: List<Long>,
    private val url: String,
    private val timeoutMs: Int,
    private val concurrency: Int,
) {

    suspend fun run(onResult: (profileId: Long, latencyMs: Int, error: String) -> Unit) {
        val generated = CoreConfigs.buildTest(profileIds)
        if (!generated.ok) {
            val error = generated.error ?: "config generation failed"
            for (id in profileIds) onResult(id, -1, error)
            return
        }
        // Results land on Go threads while the batch runs; the bookkeeping must survive that.
        val reported: MutableSet<Long> = ConcurrentHashMap.newKeySet()
        for ((id, reason) in generated.skipped) {
            reported.add(id)
            onResult(id, -1, reason)
        }

        if (generated.outboundTags.isNotEmpty()) {
            val request = TestRequest().apply {
                CoreConfig.from(generated).applyTo(this)
                this.url = this@CoreUrlTest.url
                this.timeoutMs = this@CoreUrlTest.timeoutMs
                maxConcurrency = concurrency.coerceAtLeast(1)
            }
            try {
                awaitUrlTestBatch(null, request) { tag, latency, error ->
                    val id = generated.tagToProfileId[tag] ?: return@awaitUrlTestBatch
                    reported.add(id)
                    onResult(id, latency, error)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.readableMessage
                for (id in generated.tagToProfileId.values) if (reported.add(id)) onResult(id, -1, error)
            }
        }

        for ((id, config) in generated.fullConfigs) {
            val request = TestRequest().apply {
                coreConfig = config
                useDefaultOutbound = true
                this.url = this@CoreUrlTest.url
                this.timeoutMs = this@CoreUrlTest.timeoutMs
                maxConcurrency = 1
            }
            try {
                awaitUrlTestBatch(null, request) { _, latency, error ->
                    reported.add(id)
                    onResult(id, latency, error)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (reported.add(id)) onResult(id, -1, e.readableMessage)
            }
        }

        for (id in profileIds) if (reported.add(id)) onResult(id, -1, "no result")
    }
}

/** The URL test through the running instance's `proxy` outbound. */
suspend fun urlTestCurrent(instance: Instance, core: CoreConfig, url: String, timeoutMs: Int): Int {
    val request = TestRequest().apply {
        testCurrent = true
        this.url = url
        this.timeoutMs = timeoutMs
        maxConcurrency = 1
        core.outboundTags.forEach(::addOutboundTag)
    }
    val latency = AtomicInteger(-1)
    val failure = AtomicReference<String?>(null)
    awaitUrlTestBatch(instance, request) { _, latencyMs, error ->
        if (error.isEmpty()) latency.set(latencyMs) else failure.set(error)
    }
    val error = failure.get()
    val result = latency.get()
    if (error != null || result < 0) throw IllegalStateException(error ?: "url test produced no result")
    return result
}

internal fun CoreConfig.applyTo(request: TestRequest) {
    request.coreConfig = coreConfig
    request.needXray = needXray
    request.xrayConfig = xrayConfig ?: ""
    request.xrayOutboundDNSStrategy = xrayDnsStrategy
    xrayFullConfigs.forEach(request::addXrayFullConfig)
    outboundTags.forEach(request::addOutboundTag)
}

// Results and the done signal arrive from Go goroutines; the batch resumes once the core reports done.
private suspend fun awaitUrlTestBatch(
    current: Instance?,
    request: TestRequest,
    onResult: (tag: String, latencyMs: Int, error: String) -> Unit,
) = suspendCancellableCoroutine { continuation ->
    Mobile.startURLTest(current, CoreRuntime.platform, request, object : URLTestHandler {
        override fun onResult(tag: String?, latencyMs: Int, error: String?) {
            onResult(tag.orEmpty(), latencyMs, error.orEmpty())
        }

        override fun onDone() {
            continuation.completeWith(Result.success(Unit))
        }
    })
}
