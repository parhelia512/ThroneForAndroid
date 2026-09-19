package io.nekohasekai.sagernet.database

import androidx.room.TypeConverter
import io.nekohasekai.sagernet.fmt.Serializable

/** Room converters for the Kryo-framed [SubscriptionBean] column of `proxy_groups`; the byte format is unchanged. */
class SubscriptionConverters {
    companion object {
        @TypeConverter
        @JvmStatic
        fun serialize(bean: SubscriptionBean?): ByteArray = Serializable.serialize(bean)

        @TypeConverter
        @JvmStatic
        fun deserialize(bytes: ByteArray?): SubscriptionBean? {
            if (bytes == null || bytes.isEmpty()) return null
            return Serializable.deserialize(SubscriptionBean(), bytes)
        }
    }
}
