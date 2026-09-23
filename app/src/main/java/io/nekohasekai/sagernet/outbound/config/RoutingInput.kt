package io.nekohasekai.sagernet.outbound.config

import io.nekohasekai.sagernet.route.RouteProfile
import io.nekohasekai.sagernet.route.RuleSetCatalog

/**
 * What a started config reads besides the settings (calculatePrerequisites / buildRouteSection): the route profile
 * named by current_route_id, null when it does not exist, and the srslist.h names that rule_set entries may use.
 * Test configs never read either (generate.cpp BuildTestConfig).
 */
class RoutingInput(
    @JvmField val profile: RouteProfile?,
    @JvmField val catalog: RuleSetCatalog,
) {
    companion object {
        /** The built-in Default profile (RouteProfile::GetDefaultChain) and no rule-set names. */
        @JvmField
        val DEFAULT = RoutingInput(RouteProfile.defaultProfile(), RuleSetCatalog(emptyList()))
    }
}
