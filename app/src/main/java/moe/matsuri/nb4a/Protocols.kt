package moe.matsuri.nb4a

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.dedupKey
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.outbound.Outbound

// Settings for all protocols
object Protocols {

    // Deduplication

    /** A set / map key: two profiles are duplicates when their stripped JSON links match (desktop Profile.cpp:87-90). */
    class Deduplication(val outbound: Outbound) {

        constructor(entity: ProxyEntity) : this(entity.outbound)

        private val key: String = outbound.dedupKey()

        fun hash(): String = key

        override fun hashCode(): Int = key.hashCode()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            return other is Deduplication && other.key == key
        }

    }

    // Display

    @Suppress("UNUSED_PARAMETER")
    fun Context.getProtocolColor(type: String): Int {
        return getColorAttr(R.attr.accentOrTextSecondary)
    }

    // Test

    fun genFriendlyMsg(msg: String): String {
        val msgL = msg.lowercase()
        return when {
            msgL.contains("timeout") || msgL.contains("deadline") -> {
                app.getString(R.string.connection_test_timeout_error)
            }

            msgL.contains("refused") || msgL.contains("closed pipe") -> {
                app.getString(R.string.connection_test_refused)
            }

            else -> msg
        }
    }

}
