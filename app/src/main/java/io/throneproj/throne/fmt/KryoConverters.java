package io.throneproj.throne.fmt;

import androidx.room.TypeConverter;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import io.throneproj.throne.database.SubscriptionBean;
import io.throneproj.throne.fmt.http.HttpBean;
import io.throneproj.throne.fmt.hysteria.HysteriaBean;
import io.throneproj.throne.fmt.internal.ChainBean;
import io.throneproj.throne.fmt.mieru.MieruBean;
import io.throneproj.throne.fmt.naive.NaiveBean;
import io.throneproj.throne.fmt.shadowsocks.ShadowsocksBean;
import io.throneproj.throne.fmt.shadowsocksr.ShadowsocksRBean;
import io.throneproj.throne.fmt.anytls.AnyTLSBean;
import io.throneproj.throne.fmt.shadowtls.ShadowTLSBean;
import io.throneproj.throne.fmt.snell.SnellBean;
import io.throneproj.throne.fmt.socks.SOCKSBean;
import io.throneproj.throne.fmt.ssh.SSHBean;
import io.throneproj.throne.fmt.trojan.TrojanBean;
import io.throneproj.throne.fmt.trojan_go.TrojanGoBean;
import io.throneproj.throne.fmt.tuic.TuicBean;
import io.throneproj.throne.fmt.juicity.JuicityBean;
import io.throneproj.throne.fmt.v2ray.VMessBean;
import io.throneproj.throne.fmt.wireguard.WireGuardBean;
import io.throneproj.throne.ktx.KryosKt;
import io.throneproj.throne.ktx.Logs;
import io.throneproj.throne.fmt.config.ConfigBean;
import io.throneproj.throne.fmt.neko.NekoBean;
import io.throneproj.throne.utils.JavaUtil;

public class KryoConverters {

    private static final byte[] NULL = new byte[0];

    @TypeConverter
    public static byte[] serialize(Serializable bean) {
        if (bean == null) return NULL;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBufferOutput buffer = KryosKt.byteBuffer(out);
        bean.serializeToBuffer(buffer);
        buffer.flush();
        buffer.close();
        return out.toByteArray();
    }

    public static <T extends Serializable> T deserialize(T bean, byte[] bytes) {
        if (bytes == null) return bean;
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        ByteBufferInput buffer = KryosKt.byteBuffer(input);
        try {
            bean.deserializeFromBuffer(buffer);
        } catch (KryoException e) {
            Logs.INSTANCE.w(e);
        }
        bean.initializeDefaultValues();
        return bean;
    }

    @TypeConverter
    public static SOCKSBean socksDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new SOCKSBean(), bytes);
    }

    @TypeConverter
    public static HttpBean httpDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new HttpBean(), bytes);
    }

    @TypeConverter
    public static ShadowsocksBean shadowsocksDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new ShadowsocksBean(), bytes);
    }

    @TypeConverter
    public static ShadowsocksRBean shadowsocksrDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new ShadowsocksRBean(), bytes);
    }

    @TypeConverter
    public static ConfigBean configDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new ConfigBean(), bytes);
    }

    @TypeConverter
    public static VMessBean vmessDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new VMessBean(), bytes);
    }

    @TypeConverter
    public static TrojanBean trojanDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new TrojanBean(), bytes);
    }

    @TypeConverter
    public static TrojanGoBean trojanGoDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new TrojanGoBean(), bytes);
    }

    @TypeConverter
    public static MieruBean mieruDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new MieruBean(), bytes);
    }

    @TypeConverter
    public static NaiveBean naiveDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new NaiveBean(), bytes);
    }

    @TypeConverter
    public static HysteriaBean hysteriaDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new HysteriaBean(), bytes);
    }

    @TypeConverter
    public static SSHBean sshDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new SSHBean(), bytes);
    }

    @TypeConverter
    public static WireGuardBean wireguardDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new WireGuardBean(), bytes);
    }

    @TypeConverter
    public static TuicBean tuicDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new TuicBean(), bytes);
    }

    @TypeConverter
    public static JuicityBean juicityDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new JuicityBean(), bytes);
    }

    @TypeConverter
    public static ShadowTLSBean shadowTLSDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new ShadowTLSBean(), bytes);
    }

    @TypeConverter
    public static AnyTLSBean anyTLSDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new AnyTLSBean(), bytes);
    }

    @TypeConverter
    public static SnellBean snellDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new SnellBean(), bytes);
    }

    @TypeConverter
    public static ChainBean chainDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new ChainBean(), bytes);
    }

    @TypeConverter
    public static NekoBean nekoDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new NekoBean(), bytes);
    }

    @TypeConverter
    public static SubscriptionBean subscriptionDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new SubscriptionBean(), bytes);
    }

}
