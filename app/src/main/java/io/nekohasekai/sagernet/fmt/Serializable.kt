package io.nekohasekai.sagernet.fmt

import android.os.Parcel
import android.os.Parcelable
import com.esotericsoftware.kryo.KryoException
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.byteBuffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The Kryo-framed Parcelable of the group / subscription rows (the profile rows no longer use it). */
abstract class Serializable : Parcelable {
    abstract fun initializeDefaultValues()
    abstract fun serializeToBuffer(output: ByteBufferOutput)
    abstract fun deserializeFromBuffer(input: ByteBufferInput)

    override fun describeContents() = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeByteArray(serialize(this))
    }

    abstract class CREATOR<T : Serializable> : Parcelable.Creator<T> {
        abstract fun newInstance(): T

        override fun createFromParcel(source: Parcel): T {
            return deserialize(newInstance(), source.createByteArray())
        }
    }

    companion object {
        private val NULL = ByteArray(0)

        @JvmStatic
        fun serialize(bean: Serializable?): ByteArray {
            if (bean == null) return NULL
            val out = ByteArrayOutputStream()
            val buffer = out.byteBuffer()
            bean.serializeToBuffer(buffer)
            buffer.flush()
            buffer.close()
            return out.toByteArray()
        }

        @JvmStatic
        fun <T : Serializable> deserialize(bean: T, bytes: ByteArray?): T {
            if (bytes == null) return bean
            val buffer = ByteArrayInputStream(bytes).byteBuffer()
            try {
                bean.deserializeFromBuffer(buffer)
            } catch (e: KryoException) {
                Logs.w(e)
            }
            bean.initializeDefaultValues()
            return bean
        }
    }

}
