package io.nekohasekai.sagernet.database

import android.os.Binder
import androidx.preference.PreferenceDataStore
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sagernet.database.preference.SettingsStore
import io.nekohasekai.sagernet.ktx.boolean
import io.nekohasekai.sagernet.ktx.int
import io.nekohasekai.sagernet.ktx.long
import io.nekohasekai.sagernet.ktx.string
import io.nekohasekai.sagernet.ktx.stringToInt
import moe.matsuri.nb4a.TempDatabase
import java.util.UUID

object DataStore : OnPreferenceDataStoreChangeListener {

    // share service state in main & bg process
    @Volatile
    var serviceState = BaseService.State.Idle

    val configurationStore = SettingsStore(PublicDatabase.instance, SettingsRegistry::defaultOf)
    val profileCacheStore = RoomPreferenceDataStore(TempDatabase.profileCacheDao)

    // last used, but may not be running
    var currentProfile by configurationStore.long(Key.PROFILE_CURRENT)

    var selectedProxy by configurationStore.long(Key.PROFILE_ID)
    var selectedGroup by configurationStore.long(Key.PROFILE_GROUP) { currentGroupId() } // "ungrouped" group id = 1

    // only in bg process
    var vpnService: VpnService? = null
    var baseService: BaseService.Interface? = null

    // main

    var runningTest = false

    fun currentGroupId(): Long {
        val currentSelected = configurationStore.getLong(Key.PROFILE_GROUP, -1)
        if (currentSelected > 0L) return currentSelected
        val groups = SagerDatabase.groupDao.allGroups()
        if (groups.isNotEmpty()) {
            val groupId = groups[0].id
            selectedGroup = groupId
            return groupId
        }
        val groupId = SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
        selectedGroup = groupId
        return groupId
    }

    fun currentGroup(): ProxyGroup {
        var group: ProxyGroup? = null
        val currentSelected = configurationStore.getLong(Key.PROFILE_GROUP, -1)
        if (currentSelected > 0L) {
            group = SagerDatabase.groupDao.getById(currentSelected)
        }
        if (group != null) return group
        val groups = SagerDatabase.groupDao.allGroups()
        if (groups.isEmpty()) {
            group = ProxyGroup(ungrouped = true).apply {
                id = SagerDatabase.groupDao.createGroup(this)
            }
        } else {
            group = groups[0]
        }
        selectedGroup = group.id
        return group
    }

    fun selectedGroupForImport(): Long {
        val current = currentGroup()
        if (current.type == GroupType.BASIC) return current.id
        val groups = SagerDatabase.groupDao.allGroups()
        return groups.find { it.type == GroupType.BASIC }!!.id
    }

    // ------------------------------------------------------------------------------------------------ Android-only

    var appTLSVersion by configurationStore.string(Key.APP_TLS_VERSION)
    var showBottomBar by configurationStore.boolean(Key.SHOW_BOTTOM_BAR)
    var groupLayoutMode by configurationStore.stringToInt(Key.GROUP_LAYOUT_MODE) { 0 }
    var profileCardStyle by configurationStore.stringToInt(Key.PROFILE_CARD_STYLE) { 0 }

    var networkChangeResetConnections by configurationStore.boolean(Key.NETWORK_CHANGE_RESET_CONNECTIONS) { true }
    var wakeResetConnections by configurationStore.boolean(Key.WAKE_RESET_CONNECTIONS)

    var isExpert by configurationStore.boolean(Key.APP_EXPERT)
    var appTheme by configurationStore.int(Key.APP_THEME)
    var useSystemTheme by configurationStore.boolean(Key.USE_SYSTEM_THEME)
    var nightTheme by configurationStore.stringToInt(Key.NIGHT_THEME)
    var amoledTheme by configurationStore.boolean(Key.AMOLED_THEME)
    var appLanguage by configurationStore.string(Key.APP_LANGUAGE) { "" }
    var serviceMode by configurationStore.string(Key.SERVICE_MODE) { Key.MODE_VPN }

    var speedInterval by configurationStore.stringToInt(Key.SPEED_INTERVAL) { 1000 }
    var showGroupInNotification by configurationStore.boolean(Key.SHOW_GROUP_IN_NOTIFICATION)
    var showDirectSpeed by configurationStore.boolean(Key.SHOW_DIRECT_SPEED) { true }
    var alwaysShowAddress by configurationStore.boolean(Key.ALWAYS_SHOW_ADDRESS)

    var logBufSize by configurationStore.int(Key.LOG_BUF_SIZE) { 0 }
    var acquireWakeLock by configurationStore.boolean(Key.ACQUIRE_WAKE_LOCK)
    var hideFromRecentApps by configurationStore.boolean(Key.HIDE_FROM_RECENT_APPS)
    // 记录用户选择"不再显示"的预览版版本号，仅对该版本隐藏提示
    var previewHintDismissedVersion by configurationStore.string(Key.PREVIEW_HINT_DISMISSED_VERSION) { "" }

    var meteredNetwork by configurationStore.boolean(Key.METERED_NETWORK)
    var proxyApps by configurationStore.boolean(Key.PROXY_APPS)
    var bypass by configurationStore.boolean(Key.BYPASS_MODE) { true }
    var individual by configurationStore.string(Key.INDIVIDUAL)
    var httpProxyBypass by configurationStore.string(Key.HTTP_PROXY_BYPASS) { "" }

    var yacdURL by configurationStore.string(Key.YACD_URL) { "http://127.0.0.1:9090/ui" }

    var webdavServer: String?
        get() = configurationStore.getString(Key.WEBDAV_SERVER)
        set(value) = configurationStore.putString(Key.WEBDAV_SERVER, value)

    var webdavUsername: String?
        get() = configurationStore.getString(Key.WEBDAV_USERNAME)
        set(value) = configurationStore.putString(Key.WEBDAV_USERNAME, value)

    var webdavPassword: String?
        get() = configurationStore.getString(Key.WEBDAV_PASSWORD)
        set(value) = configurationStore.putString(Key.WEBDAV_PASSWORD, value)

    var webdavPath: String?
        get() = configurationStore.getString(Key.WEBDAV_PATH) ?: "Throne"  // 设置默认值
        set(value) = configurationStore.putString(Key.WEBDAV_PATH, value)

    // ------------------------------------------------------------------------------------------------ desktop keys
    // SettingsRegistry: property = lowerCamelCase of the desktop key.

    // general
    var rememberEnable by SettingsRegistry.REMEMBER_ENABLE
    var skipDeleteConfirmation by SettingsRegistry.SKIP_DELETE_CONFIRMATION

    // inbound

    // hopefully hashCode = mHandle doesn't change, currently this is true from KitKat to Nougat
    private val userIndex by lazy { Binder.getCallingUserHandle().hashCode() }

    /** inbound_socks_port; a missing row means 2080 plus the Android user index. */
    var inboundSocksPort: Int
        get() = configurationStore.getRaw(SettingsRegistry.INBOUND_SOCKS_PORT.key)
            ?.let(SettingsRegistry.INBOUND_SOCKS_PORT::decode)
            ?: (SettingsRegistry.INBOUND_SOCKS_PORT.default + userIndex)
        set(value) = SettingsRegistry.INBOUND_SOCKS_PORT.write(configurationStore, value)

    var inboundAddress by SettingsRegistry.INBOUND_ADDRESS
    var disableMixedInbound by SettingsRegistry.DISABLE_MIXED_INBOUND
    var randomInboundPort by SettingsRegistry.RANDOM_INBOUND_PORT
    var inboundAuth by SettingsRegistry.INBOUND_AUTH
    var inboundUser by SettingsRegistry.INBOUND_USER
    var inboundPass by SettingsRegistry.INBOUND_PASS
    var customInbound by SettingsRegistry.CUSTOM_INBOUND

    /** disable_mixed_inbound only applies in VPN mode: the proxy-only service mode needs the mixed inbound. */
    val mixedInboundDisabled: Boolean
        get() = disableMixedInbound && serviceMode == Key.MODE_VPN

    /** inbound_address is anything but a loopback address (the desktop's "Allow other devices to connect"). */
    val allowLanAccess: Boolean
        get() = !SettingsRegistry.isLoopbackAddress(inboundAddress)

    fun initGlobal() {
        if (!configurationStore.contains(SettingsRegistry.INBOUND_SOCKS_PORT.key)) {
            inboundSocksPort = inboundSocksPort
        }
    }

    // tun
    var vpnImpl by SettingsRegistry.VPN_IMPL
    var vpnMtu by SettingsRegistry.VPN_MTU
    var vpnIpv6 by SettingsRegistry.VPN_IPV6
    var vpnTunIpv4Cidr by SettingsRegistry.VPN_TUN_IPV4_CIDR
    var vpnTunIpv6Cidr by SettingsRegistry.VPN_TUN_IPV6_CIDR
    var disablePrivateRangeBypass by SettingsRegistry.DISABLE_PRIVATE_RANGE_BYPASS
    var vpnPrivateRanges by SettingsRegistry.VPN_PRIVATE_RANGES
    var enableTunRouting by SettingsRegistry.ENABLE_TUN_ROUTING

    // routing
    var currentRouteId by SettingsRegistry.CURRENT_ROUTE_ID
    var outboundDomainStrategy by SettingsRegistry.OUTBOUND_DOMAIN_STRATEGY
    var domainStrategy by SettingsRegistry.DOMAIN_STRATEGY
    var rulesetMirror by SettingsRegistry.RULESET_MIRROR
    var adblockEnable by SettingsRegistry.ADBLOCK_ENABLE
    var routeAutoUpdate by SettingsRegistry.ROUTE_AUTO_UPDATE
    var routeAutoUpdateLast by SettingsRegistry.ROUTE_AUTO_UPDATE_LAST

    // dns
    var remoteDns by SettingsRegistry.REMOTE_DNS
    var remoteDnsDisableIpv6 by SettingsRegistry.REMOTE_DNS_DISABLE_IPV6
    var directDns by SettingsRegistry.DIRECT_DNS
    var directDnsDisableIpv6 by SettingsRegistry.DIRECT_DNS_DISABLE_IPV6
    var coreBoxUnderlyingDns by SettingsRegistry.CORE_BOX_UNDERLYING_DNS
    var dnsFinalOut by SettingsRegistry.DNS_FINAL_OUT
    var enableDnsRouting by SettingsRegistry.ENABLE_DNS_ROUTING
    var fakedns by SettingsRegistry.FAKEDNS
    var fakeipDisableIpv6 by SettingsRegistry.FAKEIP_DISABLE_IPV6
    var dnsUseHosts by SettingsRegistry.DNS_USE_HOSTS
    var dnsPredefinedEnable by SettingsRegistry.DNS_PREDEFINED_ENABLE
    var dnsPredefinedRules by SettingsRegistry.DNS_PREDEFINED_RULES
    var dnsCacheCapacity by SettingsRegistry.DNS_CACHE_CAPACITY
    var dnsQueryTimeout by SettingsRegistry.DNS_QUERY_TIMEOUT
    var dnsOptimistic by SettingsRegistry.DNS_OPTIMISTIC
    var dnsOptimisticTimeout by SettingsRegistry.DNS_OPTIMISTIC_TIMEOUT
    var dnsDisableCache by SettingsRegistry.DNS_DISABLE_CACHE
    var dnsDisableExpire by SettingsRegistry.DNS_DISABLE_EXPIRE
    var dnsPersistCache by SettingsRegistry.DNS_PERSIST_CACHE
    var dnsReverseMapping by SettingsRegistry.DNS_REVERSE_MAPPING
    var useDnsObject by SettingsRegistry.USE_DNS_OBJECT
    var dnsObject by SettingsRegistry.DNS_OBJECT

    // presets
    var muxProtocol by SettingsRegistry.MUX_PROTOCOL
    var muxConcurrency by SettingsRegistry.MUX_CONCURRENCY
    var muxPadding by SettingsRegistry.MUX_PADDING
    var muxDefaultOn by SettingsRegistry.MUX_DEFAULT_ON
    var xrayMuxConcurrency by SettingsRegistry.XRAY_MUX_CONCURRENCY
    var xrayMuxDefaultOn by SettingsRegistry.XRAY_MUX_DEFAULT_ON
    var fragmentDefaultOn by SettingsRegistry.FRAGMENT_DEFAULT_ON
    var fragmentImplementation by SettingsRegistry.FRAGMENT_IMPLEMENTATION
    var fragmentSize by SettingsRegistry.FRAGMENT_SIZE
    var fragmentSleep by SettingsRegistry.FRAGMENT_SLEEP
    var tlsTricksDefaultOn by SettingsRegistry.TLS_TRICKS_DEFAULT_ON
    var utlsFingerprint by SettingsRegistry.UTLS_FINGERPRINT
    var tlsSpoof by SettingsRegistry.TLS_SPOOF
    var tlsSpoofMethod by SettingsRegistry.TLS_SPOOF_METHOD
    var tlsSpoofDefaultOn by SettingsRegistry.TLS_SPOOF_DEFAULT_ON
    var h2IdleTimeout by SettingsRegistry.H2_IDLE_TIMEOUT
    var h2KeepAlivePeriod by SettingsRegistry.H2_KEEP_ALIVE_PERIOD
    var h2StreamReceiveWindow by SettingsRegistry.H2_STREAM_RECEIVE_WINDOW
    var h2ConnectionReceiveWindow by SettingsRegistry.H2_CONNECTION_RECEIVE_WINDOW
    var h2MaxConcurrentStreams by SettingsRegistry.H2_MAX_CONCURRENT_STREAMS
    var quicInitialPacketSize by SettingsRegistry.QUIC_INITIAL_PACKET_SIZE
    var quicDisablePathMtuDiscovery by SettingsRegistry.QUIC_DISABLE_PATH_MTU_DISCOVERY

    // testing
    var testUrl by SettingsRegistry.TEST_URL
    var urlTestTimeoutMs by SettingsRegistry.URL_TEST_TIMEOUT_MS
    var testConcurrent by SettingsRegistry.TEST_CONCURRENT
    var directTestUrl by SettingsRegistry.DIRECT_TEST_URL
    var speedTestMode by SettingsRegistry.SPEED_TEST_MODE
    var speedTestTimeoutMs by SettingsRegistry.SPEED_TEST_TIMEOUT_MS
    var simpleDlUrl by SettingsRegistry.SIMPLE_DL_URL

    // subscriptions (network)
    var userAgent2 by SettingsRegistry.USER_AGENT2
    var netUseProxy by SettingsRegistry.NET_USE_PROXY
    var netInsecure by SettingsRegistry.NET_INSECURE

    // core
    var logLevel by SettingsRegistry.LOG_LEVEL
    var xrayLogLevel by SettingsRegistry.XRAY_LOG_LEVEL
    var enableStats by SettingsRegistry.ENABLE_STATS
    var disableTrafficStats by SettingsRegistry.DISABLE_TRAFFIC_STATS
    var coreBoxClashApi by SettingsRegistry.CORE_BOX_CLASH_API
    var coreBoxClashListenAddr by SettingsRegistry.CORE_BOX_CLASH_LISTEN_ADDR
    var coreBoxClashApiSecret by SettingsRegistry.CORE_BOX_CLASH_API_SECRET

    /** core_box_api_secret; an empty one is replaced by a random one and saved, as SettingsRepo.cpp:15-19 does. */
    var coreBoxApiSecret: String
        get() = SettingsRegistry.CORE_BOX_API_SECRET.read(configurationStore).ifEmpty {
            UUID.randomUUID().toString().replace("-", "").also { coreBoxApiSecret = it }
        }
        set(value) = SettingsRegistry.CORE_BOX_API_SECRET.write(configurationStore, value)

    var coreDnsInPort by SettingsRegistry.CORE_DNS_IN_PORT
    var xrayVlessPreference by SettingsRegistry.XRAY_VLESS_PREFERENCE
    var skipCert by SettingsRegistry.SKIP_CERT
    var useMozillaCerts by SettingsRegistry.USE_MOZILLA_CERTS
    var enableNtp by SettingsRegistry.ENABLE_NTP
    var ntpServerAddress by SettingsRegistry.NTP_SERVER_ADDRESS
    var ntpServerPort by SettingsRegistry.NTP_SERVER_PORT
    var ntpInterval by SettingsRegistry.NTP_INTERVAL
    var ntpOutbound by SettingsRegistry.NTP_OUTBOUND

    /** core_box_clash_api is on: a positive port. */
    val clashApiEnabled: Boolean get() = coreBoxClashApi > 0

    // ------------------------------------------------------------------------------------------------ old cache, DO NOT ADD

    var dirty by profileCacheStore.boolean(Key.PROFILE_DIRTY)
    var editingId by profileCacheStore.long(Key.PROFILE_ID)
    var editingGroup by profileCacheStore.long(Key.PROFILE_GROUP)

    var frontProxy by profileCacheStore.long(Key.GROUP_FRONT_PROXY + "Long")
    var landingProxy by profileCacheStore.long(Key.GROUP_LANDING_PROXY + "Long")
    var frontProxyTmp by profileCacheStore.stringToInt(Key.GROUP_FRONT_PROXY)
    var landingProxyTmp by profileCacheStore.stringToInt(Key.GROUP_LANDING_PROXY)

    var serverConfig by profileCacheStore.string(Key.SERVER_CONFIG)

    var groupName by profileCacheStore.string(Key.GROUP_NAME)
    var groupType by profileCacheStore.stringToInt(Key.GROUP_TYPE)
    var groupOrder by profileCacheStore.stringToInt(Key.GROUP_ORDER)
    var groupIsSelector by profileCacheStore.boolean(Key.GROUP_IS_SELECTOR)

    var subscriptionLink by profileCacheStore.string(Key.SUBSCRIPTION_LINK)
    var subscriptionForceResolve by profileCacheStore.boolean(Key.SUBSCRIPTION_FORCE_RESOLVE)
    var subscriptionDeduplication by profileCacheStore.boolean(Key.SUBSCRIPTION_DEDUPLICATION)
    var subscriptionUpdateWhenConnectedOnly by profileCacheStore.boolean(Key.SUBSCRIPTION_UPDATE_WHEN_CONNECTED_ONLY)
    var subscriptionUserAgent by profileCacheStore.string(Key.SUBSCRIPTION_USER_AGENT)
    var subscriptionAutoUpdate by profileCacheStore.boolean(Key.SUBSCRIPTION_AUTO_UPDATE)
    var subscriptionAutoUpdateDelay by profileCacheStore.stringToInt(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY) { 360 }
    var subscriptionFilterMode by profileCacheStore.stringToInt(Key.SUBSCRIPTION_FILTER_MODE) { 0 }
    var subscriptionFilterRegex by profileCacheStore.string(Key.SUBSCRIPTION_FILTER_REGEX)
    var subscriptionServerDns by profileCacheStore.string(Key.SUBSCRIPTION_SERVER_DNS)

    var rulesFirstCreate by profileCacheStore.boolean("rulesFirstCreate")

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
    }
}
