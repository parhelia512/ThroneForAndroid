package io.nekohasekai.sagernet.bg.proto

import io.throneproj.mobile.Instance

class TrafficUpdater(
    private val box: Instance,
    val items: List<TrafficLooperData>, // contain "bypass"
) {

    class TrafficLooperData(
        // Don't associate proxyEntity
        var tag: String,
        var tx: Long = 0,
        var rx: Long = 0,
        var txBase: Long = 0,
        var rxBase: Long = 0,
        var txRate: Long = 0,
        var rxRate: Long = 0,
        var lastUpdate: Long = 0,
        var ignore: Boolean = false,
        var hasTrafficDelta: Boolean = false,
    )

    // The core reports cumulative bytes per outbound tag; keyed by tag rather than by item because
    // a selector switch reassigns tags between items while the counters stay with the tag.
    private val lastTotals = HashMap<String, LongArray>()

    private fun queryDelta(tag: String): LongArray {
        val txTotal = box.queryOutboundStats(tag, "uplink")
        val rxTotal = box.queryOutboundStats(tag, "downlink")
        val last = lastTotals.getOrPut(tag) { LongArray(2) }
        val delta = longArrayOf(
            (txTotal - last[0]).coerceAtLeast(0),
            (rxTotal - last[1]).coerceAtLeast(0),
        )
        last[0] = txTotal
        last[1] = rxTotal
        return delta
    }

    private fun updateOne(item: TrafficLooperData): TrafficLooperData {
        // last update
        val now = System.currentTimeMillis()
        val interval = now - item.lastUpdate
        item.lastUpdate = now
        if (interval <= 0) {
            item.rxRate = 0
            item.txRate = 0
            return TrafficLooperData(tag = item.tag)
        }

        // query
        val (tx, rx) = queryDelta(item.tag)

        // add diff
        item.rx += rx
        item.tx += tx
        item.rxRate = rx * 1000 / interval
        item.txRate = tx * 1000 / interval

        // return diff
        return TrafficLooperData(
            tag = item.tag,
            rx = rx,
            tx = tx,
            rxRate = item.rxRate,
            txRate = item.txRate,
        )
    }

    fun updateAll() {
        val updated = mutableMapOf<String, TrafficLooperData>() // diffs
        items.forEach { item ->
            item.hasTrafficDelta = false
            if (item.ignore) return@forEach
            val diff = updated[item.tag]
            // query a tag only once
            if (diff == null) {
                val newDiff = updateOne(item)
                updated[item.tag] = newDiff
                item.hasTrafficDelta = newDiff.rx != 0L || newDiff.tx != 0L
            } else {
                item.rx += diff.rx
                item.tx += diff.tx
                item.rxRate = diff.rxRate
                item.txRate = diff.txRate
                item.hasTrafficDelta = diff.rx != 0L || diff.tx != 0L
            }
        }
    }
}
