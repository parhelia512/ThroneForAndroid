package io.nekohasekai.sagernet

object Key {

    const val DB_PUBLIC = "configuration.db"
    const val DB_PROFILE = "sager_net.db"

    const val PERSIST_ACROSS_REBOOT = "isAutoConnect"

    const val CLEAR_CACHE = "clearCache"

    const val APP_EXPERT = "isExpert"
    const val APP_THEME = "appTheme"
    const val USE_SYSTEM_THEME = "useSystemTheme"
    const val NIGHT_THEME = "nightTheme"
    const val AMOLED_THEME = "amoledTheme"
    const val APP_LANGUAGE = "appLanguage"
    const val SERVICE_MODE = "serviceMode"
    const val MODE_VPN = "vpn"
    const val MODE_PROXY = "proxy"

    const val GLOBAL_CUSTOM_CONFIG = "globalCustomConfig"

    const val REMOTE_DNS = "remoteDns"
    const val DIRECT_DNS = "directDns"
    const val ENABLE_DNS_ROUTING = "enableDnsRouting"
    const val ENABLE_FAKEDNS = "enableFakeDns"

    const val IPV6_MODE = "ipv6Mode"

    const val PROXY_APPS = "proxyApps"
    const val BYPASS_MODE = "bypassMode"
    const val INDIVIDUAL = "individual"
    const val METERED_NETWORK = "meteredNetwork"

    const val TRAFFIC_SNIFFING = "trafficSniffing"
    const val RESOLVE_DESTINATION = "resolveDestination"

    const val BYPASS_LAN = "bypassLan"
    const val BYPASS_LAN_IN_CORE = "bypassLanInCore"
    const val CONCURRENT_DIAL = "concurrentDial" // 留作未来补回并发拨号功能
    const val DUAL_NETWORK_ACCELERATION = "dualNetworkAcceleration"

    const val MIXED_PORT = "mixedPort"
    const val DISABLE_MIXED_INBOUND = "disableMixedInbound"
    const val MIXED_USERNAME = "mixedUsername" // 混合入站认证用户名；留空即不启用认证
    const val MIXED_PASSWORD = "mixedPassword" // 混合入站认证密码
    const val MIXED_AUTH_CONFIG = "mixedAuthConfig" // 设置页「配置身份验证」入口，仅 UI 查找用
    const val ALLOW_ACCESS = "allowAccess"
    const val SPEED_INTERVAL = "speedInterval"
    const val SHOW_DIRECT_SPEED = "showDirectSpeed"

    const val HTTP_PROXY_BYPASS = "httpProxyBypass"
    const val DNS_HOSTS = "dnsHosts"
    const val STRICT_ROUTE = "strictRoute"

    const val CONNECTION_TEST_URL = "connectionTestURL"
    const val CONNECTION_TEST_CONCURRENT = "connectionTestConcurrent"
    const val CONNECTION_TEST_TIMEOUT = "connectionTestTimeout"

    const val SPEED_TEST_MODE = "speedTestMode"
    const val SPEED_TEST_TIMEOUT_MS = "speedTestTimeoutMs"
    const val SPEED_TEST_SERVER_LIST_URL = "speedTestServerListURL"
    const val SPEED_TEST_FALLBACK_SERVER_LIST_URL = "speedTestFallbackServerListURL"
    const val SIMPLE_DOWNLOAD_URL = "simpleDownloadURL"

    const val NETWORK_CHANGE_RESET_CONNECTIONS = "networkChangeResetConnections"
    const val WAKE_RESET_CONNECTIONS = "wakeResetConnections"
    const val RULES_PROVIDER = "rulesProvider"
    const val LOG_LEVEL = "logLevel"
    const val LOG_BUF_SIZE = "logBufSize"
    const val MTU = "mtu"
    const val ALWAYS_SHOW_ADDRESS = "alwaysShowAddress"

    const val RULES_GEOSITE_URL = "rulesGeositeUrl"
    const val RULES_GEOIP_URL = "rulesGeoipUrl"
    const val RULES_UPDATE_INTERVAL = "rulesUpdateInterval"

    const val GLOBAL_ALLOW_INSECURE = "globalAllowInsecure"

    const val ACQUIRE_WAKE_LOCK = "acquireWakeLock"
    const val HIDE_FROM_RECENT_APPS = "hideFromRecentApps"
    const val PREVIEW_HINT_DISMISSED_VERSION = "previewHintDismissedVersion"
    const val SHOW_BOTTOM_BAR = "showBottomBar"
    const val CONFIRM_PROFILE_DELETE = "confirmProfileDelete"
    const val GROUP_LAYOUT_MODE = "groupLayoutMode"
    const val PROFILE_CARD_STYLE = "profileCardStyle"

    const val ALLOW_INSECURE_ON_REQUEST = "allowInsecureOnRequest"

    const val TUN_IMPLEMENTATION = "tunImplementation"
    const val PROFILE_TRAFFIC_STATISTICS = "profileTrafficStatistics"

    const val PROFILE_DIRTY = "profileDirty"
    const val PROFILE_ID = "profileId"
    const val PROFILE_GROUP = "profileGroup"
    const val PROFILE_CURRENT = "profileCurrent"

    const val SERVER_CONFIG = "serverConfig"

    const val ROUTE_NAME = "routeName"
    const val ROUTE_DOMAIN = "routeDomain"
    const val ROUTE_IP = "routeIP"
    const val ROUTE_PORT = "routePort"
    const val ROUTE_SOURCE_PORT = "routeSourcePort"
    const val ROUTE_NETWORK = "routeNetwork"
    const val ROUTE_SOURCE = "routeSource"
    const val ROUTE_PROTOCOL = "routeProtocol"
    const val ROUTE_RULESET = "routeRuleset"
    const val ROUTE_OUTBOUND = "routeOutbound"
    const val ROUTE_PACKAGES = "routePackages"

    const val GROUP_NAME = "groupName"
    const val GROUP_TYPE = "groupType"
    const val GROUP_ORDER = "groupOrder"
    const val GROUP_IS_SELECTOR = "groupIsSelector"
    const val GROUP_FRONT_PROXY = "groupFrontProxy"
    const val GROUP_LANDING_PROXY = "groupLandingProxy"

    const val GROUP_SUBSCRIPTION = "groupSubscription"
    const val SUBSCRIPTION_LINK = "subscriptionLink"
    const val SUBSCRIPTION_FORCE_RESOLVE = "subscriptionForceResolve"
    const val SUBSCRIPTION_DEDUPLICATION = "subscriptionDeduplication"
    const val SUBSCRIPTION_UPDATE = "subscriptionUpdate"
    const val SUBSCRIPTION_UPDATE_WHEN_CONNECTED_ONLY = "subscriptionUpdateWhenConnectedOnly"
    const val SUBSCRIPTION_USER_AGENT = "subscriptionUserAgent"
    const val SUBSCRIPTION_AUTO_UPDATE = "subscriptionAutoUpdate"
    const val SUBSCRIPTION_AUTO_UPDATE_DELAY = "subscriptionAutoUpdateDelay"
    const val SUBSCRIPTION_FILTER_MODE = "subscriptionFilterMode"
    const val SUBSCRIPTION_FILTER_REGEX = "subscriptionFilterRegex"
    const val SUBSCRIPTION_SERVER_DNS = "subscriptionServerDns"

    //

    const val APP_TLS_VERSION = "appTLSVersion"
    const val ENABLE_CLASH_API = "enableClashAPI"

    const val ENABLE_TLS_FRAGMENT = "enableTLSFragment"

    const val FRAGMENT_LENGTH = "fragmentLength"
    const val FRAGMENT_INTERVAL = "fragmentInterval"

    const val WEBDAV_SERVER = "webdavServer"
    const val WEBDAV_USERNAME = "webdavUsername"
    const val WEBDAV_PASSWORD = "webdavPassword"
    const val WEBDAV_PATH = "webdavPath"

    const val GLOBAL_MODE = "globalMode"
}

object TunImplementation {
    const val GVISOR = 0
    const val SYSTEM = 1
    const val MIXED = 2
}

object IPv6Mode {
    const val DISABLE = 0
    const val ENABLE = 1
    const val PREFER = 2
    const val ONLY = 3
}

object GroupType {
    const val BASIC = 0
    const val SUBSCRIPTION = 1
}

object GroupOrder {
    const val ORIGIN = 0
    const val BY_NAME = 1
    const val BY_DELAY = 2
}

object SubscriptionFilterMode {
    const val DISABLED = 0
    const val INCLUDE = 1
    const val EXCLUDE = 2
}

object Action {
    const val SERVICE = "io.nekohasekai.sagernet.SERVICE"
    const val CLOSE = "io.nekohasekai.sagernet.CLOSE"
    const val RELOAD = "io.nekohasekai.sagernet.RELOAD"

    // const val SWITCH_WAKE_LOCK = "io.nekohasekai.sagernet.SWITCH_WAKELOCK"
    const val RESET_UPSTREAM_CONNECTIONS = "io.nekohasekai.sagernet.RESET_UPSTREAM_CONNECTIONS"
}
