package io.nekohasekai.sagernet

object Key {

    const val DB_PUBLIC = "configuration.db"
    const val DB_PROFILE = "sager_net.db"

    const val CLEAR_CACHE = "clearCache"

    // Android-only keys of the configuration store; the desktop keys are in database.SettingsRegistry.

    const val APP_EXPERT = "isExpert"
    const val APP_THEME = "appTheme"
    const val USE_SYSTEM_THEME = "useSystemTheme"
    const val NIGHT_THEME = "nightTheme"
    const val AMOLED_THEME = "amoledTheme"
    const val APP_LANGUAGE = "appLanguage"
    const val SERVICE_MODE = "serviceMode"
    const val MODE_VPN = "vpn"
    const val MODE_PROXY = "proxy"

    const val PROXY_APPS = "proxyApps"
    const val BYPASS_MODE = "bypassMode"
    const val INDIVIDUAL = "individual"
    const val METERED_NETWORK = "meteredNetwork"

    const val SPEED_INTERVAL = "speedInterval"
    const val SHOW_DIRECT_SPEED = "showDirectSpeed"
    const val SHOW_GROUP_IN_NOTIFICATION = "showGroupInNotification"

    const val HTTP_PROXY_BYPASS = "httpProxyBypass"

    const val NETWORK_CHANGE_RESET_CONNECTIONS = "networkChangeResetConnections"
    const val WAKE_RESET_CONNECTIONS = "wakeResetConnections"
    const val LOG_BUF_SIZE = "logBufSize"
    const val ALWAYS_SHOW_ADDRESS = "alwaysShowAddress"

    const val ACQUIRE_WAKE_LOCK = "acquireWakeLock"
    const val HIDE_FROM_RECENT_APPS = "hideFromRecentApps"
    const val PREVIEW_HINT_DISMISSED_VERSION = "previewHintDismissedVersion"
    const val SHOW_BOTTOM_BAR = "showBottomBar"
    const val GROUP_LAYOUT_MODE = "groupLayoutMode"
    const val PROFILE_CARD_STYLE = "profileCardStyle"
    const val YACD_URL = "yacdURL"

    const val PROFILE_DIRTY = "profileDirty"
    const val PROFILE_ID = "profileId"
    const val PROFILE_GROUP = "profileGroup"
    const val PROFILE_CURRENT = "profileCurrent"

    const val SERVER_CONFIG = "serverConfig"

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

    const val WEBDAV_SERVER = "webdavServer"
    const val WEBDAV_USERNAME = "webdavUsername"
    const val WEBDAV_PASSWORD = "webdavPassword"
    const val WEBDAV_PATH = "webdavPath"
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
