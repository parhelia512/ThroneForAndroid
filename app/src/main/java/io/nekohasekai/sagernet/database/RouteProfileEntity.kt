package io.nekohasekai.sagernet.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.nekohasekai.sagernet.route.RouteProfile
import io.nekohasekai.sagernet.route.RouteRule

/** The desktop's `route_profiles` row (RoutesRepo.cpp:14-31) without the raw and endpoint columns. */
@Entity(tableName = RouteProfileEntity.TABLE)
data class RouteProfileEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    @ColumnInfo(name = "name", defaultValue = "") var name: String = "",
    @ColumnInfo(name = "default_outbound_id", defaultValue = "-1") var defaultOutboundId: Long = -1L,
    @ColumnInfo(name = "is_remote", defaultValue = "0") var isRemote: Boolean = false,
    @ColumnInfo(name = "remote_url", defaultValue = "") var remoteUrl: String = "",
    @ColumnInfo(name = "auto_update", defaultValue = "0") var autoUpdate: Boolean = false,
    @ColumnInfo(name = "remote_last_update", defaultValue = "0") var remoteLastUpdate: Long = 0L,
) {

    fun toModel(rules: List<RouteRuleEntity>): RouteProfile = RouteProfile().also {
        it.id = id
        it.name = name
        it.default_outbound_id = defaultOutboundId
        it.is_remote = isRemote
        it.remote_url = remoteUrl
        it.auto_update = autoUpdate
        it.remote_last_update = remoteLastUpdate
        it.rules = rules.sortedBy { r -> r.ruleOrder }.mapTo(ArrayList<RouteRule>()) { r -> r.toModel() }
    }

    companion object {
        const val TABLE = "route_profiles"

        fun of(p: RouteProfile) = RouteProfileEntity(
            id = p.id,
            name = p.name,
            defaultOutboundId = p.default_outbound_id,
            isRemote = p.is_remote,
            remoteUrl = p.remote_url,
            autoUpdate = p.auto_update,
            remoteLastUpdate = p.remote_last_update,
        )
    }
}
