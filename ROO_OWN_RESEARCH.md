# OwnBox (OwnBoxForAndroid) 深度调研报告

> 调研对象：`C:\repos\OwnBoxForAndroid`（origin: https://github.com/Own716/OwnBoxForAndroid ）
> 基准仓库：`C:\repos\ThroneForAndroid`（T4A，nb4a.properties v1.6.4 / versionCode 47，sing-box v1.13.16）
> 报告生成时间：初版调研于 T4A main @ `48547d2`（2026-09-04 "bump version"）；
> **2026-09-12 增量更新：重新扫描至 Own v2.6.0（HEAD `4372435`，versionCode 260）**，见第 0 章。
> **本轮增量更新：扫描 OwnBox origin/main `7bccca0b`（Own v2.8.0），聚焦 sing-box 1.14.1 升级专项**，见第 0B 章。

---

## 0. v2.6.0 增量扫描（相对上版报告基线 `3769c07`）⭐ 新增

上版报告基线为 `3769c07`（当时 OwnBox 约 v2.3.10）。本次重扫覆盖 `3769c07..4372435` 共 **29 个提交**（v2.4.0 → v2.6.0 全程）：

```
276 files changed, 7211 insertions(+), 1462 deletions(-)
```

当前状态：**v2.6.0 正式版**，versionCode 260，包名仍为 `com.ownbox.app`，sing-box **v1.14.0**（未再升级）。
版本号规则变更：`app/build.gradle.kts` 改为从 `nb4a.properties` metadata 动态读取 `VERSION_NAME`/`VERSION_CODE`（versionCode × 5）。

### 0.1 测速哲学再次反转（重要结论）⭐⭐

上版报告记录的"OwnBox 单次真实握手 vs T4A 双 HEAD 预热"冲突**已消除**：v2.5.8 起 OwnBox 把 `libcore/box.go` 的 `urlTest` **改回两阶段 Keep-Alive 预热方案**，与 T4A/官方 sing-box 趋同：

- 阶段一（预热）：经 outbound 建立代理隧道完成 HTTP 握手，长连接推入连接池（`DisableKeepAlives: false`、`MaxIdleConns: 5`）；
- 阶段二（测量）：复用池中连接发 HEAD，测纯 1-RTT（~100-180ms）；远端不支持 Keep-Alive 时回退用阶段一耗时；
- 新增 **fallback URL 互备**：主测速 URL 失败自动切换 cloudflare↔gstatic（`getFallbackLink`）；UA 改为完整浏览器 UA；
- `UrlTestFull` 现在只是 `UrlTest` 的别名（单次请求 TTFB 算法统一）；
- `libcore/http.go`：HTTP 状态码判定从 `!= 200` 放宽为 `>= 400`（修复 204/3xx 被误判失败）；
- 新增 AIDL `urlTestCustomUrl(url, timeoutMs)`（`ISagerNetService.aidl` + `BaseService` 实现）：直接经 default outbound 拨号，**绕过路由规则**，杜绝 geosite:cn 分流劫持测速；
- Kotlin 侧 `UrlTest` 支持 `resolveLink(profile)`：每组独立测试 URL 优先（`groupUrlTestUrl(groupId)`），其次全局 URL；
- `TcpPing` 修复虚高：UDP-only 协议（Hysteria/Hysteria2/TUIC/WireGuard）直接转 URLTest；connect 前预解析 `InetSocketAddress`（DNS 解析耗时不再计入）；Hysteria 多端口取 `getFirstPort`。

### 0.2 负载均衡升级为独立节点类型（Balancer）⭐ 大功能

v2.4.x 起，负载均衡从"分组开关"升级为**可保存的独立 Profile**：

- **`BalancerBean`**（`fmt/internal/BalancerBean.java`，113 行，Kryo v1）：`balancerType`（0=手选列表 / 1=整分组）、`targetGroupId`、`proxies`、`strategy`（random/leastPing/leastLoad）、`testUrl`、`interval`；
- `ProxyEntity` 新增 `TYPE_BALANCER = 25` + `balancerBean` 列；**Room v9 → v10**（`AutoMigration(9,10)` + 手写 `MIGRATION_9_10`：PRAGMA 检查后 `ALTER TABLE ... ADD COLUMN balancerBean BLOB DEFAULT NULL`）；DB 打开失败兜底升级为**先备份 `.bak_<ts>` 再删库重建**；
- `KryoConverters.balancerDeserialize`、`TypeMap["balancer"]`、`BalancerSettingsActivity`（311 行）+ `balancer_preferences.xml`；
- libcore `loadbalance` outbound 支持 `strategy` 字段（`LoadBalanceOptions`，random=随机 / 默认轮询），`Close()` 中 `interruptGroup.Interrupt(true)`（修 9e8613a）；
- `ConfigBuilder.buildConfig`：`buildChain` 内递归构建 balancer outbound（leastPing 映射为 urltest，其余映射为 loadbalance）；`ConfigBuildResult` 新增 `balancerMemberMap`；
- **`TrafficLooper`**：balancer 成员流量聚合（成员 tx/rx/rate 求和写入 balancer 实体）、选中 balancer 时成员 ignore 联动、main 汇总排除 balancer 成员防重复计数；
- **分组 outbound tag 改为分组名**（`groupTag`）：selector/urltest/loadbalance 的 tag 用 `group.name`（可读名经 `readableTag` 去重），并新增 **route.rules outbound tag 存在性校验**（TAG_PROXY↔groupTag 互换、缺失回退 mainProxyTag），避免 "tag not found"；
- 新增测试 `ConfigBuilderLoadBalanceTest.kt`（31 行）。

### 0.3 IPv6 泄露修复全套（对应 release note "修复禁用 IPv6 时的流量与 DNS 泄露问题"）⭐

- `VpnService`：**即使 IPv6Mode.DISABLE 也始终添加 IPv6 虚拟地址（/126）与路由**（`2000::/3` 或 `::/0`，另加 `fc00::/7`），让系统完整接管 IPv6，杜绝物理网卡旁路泄露；
- `ConfigBuilder`（ipv6Mode == DISABLE 时）：
  - `dns.strategy = "ipv4_only"`、`autoDnsDomainStrategy` 强制 `ipv4_only`、链路 `defaultServerDomainStrategy` 强制 `ipv4_only`；
  - DNS 规则头部插入 `query_type=["AAAA"] action=reject`；route 规则头部插入 `ip_version=6 action=reject`；
  - fakeip 不再生成 `inet6_range`，fakeip 规则 `query_type` 只保留 `A`；
  - tun `address` 不再因 DISABLE 而省略 IPv6 虚拟地址。

### 0.4 协议连通性修复（HY2 / TUIC / WireGuard / Trojan / SS）

| 文件 | 修复 |
|---|---|
| `HysteriaFmt.kt` | `getFirstPort` 兼容 `port-range`（`-` 分隔）；SNI 为空回退 `serverAddress`；h2 分支 alpn 改为从 bean 解析（不再硬编码 h3）；`udp_fragment = true`；**`hopPortsToSingboxList` 输出格式改为 `start:end`**（1.14 语义，与上版报告记录的 `start-end` 相反，注意版本差异） |
| `TuicFmt.kt` | SNI 空回退 serverAddress + 尊重 `disableSNI`；`udp_fragment = true` |
| `V2RayFmt.kt` | **Trojan 强制启用 TLS**（`buildSingBoxOutboundTLS` 对 Trojan 不再因 security!=tls 返回 null，且兜底构造 enabled TLS）；`server_name` 空回退 serverAddress；链接 query 含 `{ } " 空格 | \ ^ < >` 时逐字符 percent-encode 后重试解析，再退 `java.net.URI`；`net=` 参数识别；kcp `headerType` 非法值回退 `none`（不再 error）；`extra` xhttp 转换失败回退原文 |
| `ShadowsocksFmt.kt` | v2ray-plugin 强制补 `mux=0`（修复 sing-box 1.14 下 v2ray-plugin 握手异常）；空 plugin_opts 置 null |
| `TcpPing.kt` | 见 0.1 |

新增测试 `HysteriaFmtTest.kt`（79 行，覆盖 getFirstPort / hopPorts）。

### 0.5 订阅链路健壮化（对应"修复批量更新并发冲突与闪退"、"剪贴板强制去重缺陷"）⭐

- **`GroupUpdater.executeUpdate`**：`coroutineScope` → `supervisorScope`（单个订阅失败不再取消整批）；`subscription` 判空防 NPE；后台批量更新失败不再弹 UI（仅 `byUser` 时报错）；重复触发改为返回 false 而非 cancel 抛异常；
- **`GroupFragment` 批量更新**：改为 `supervisorScope + async/awaitAll` 并发更新全部订阅，逐个 try-catch，汇总成功/失败数（修复并发冲突闪退）；
- **`RawUpdater`**：
  - 下载失败回退：连接状态下代理拉取失败自动转**直连**重试（每个 UA）；
  - 新增 `Profile-Title`/`X-Profile-Title` 响应头捕获（含 base64: 前缀解码）；
  - **机场名自动提取 `extractAirportName()`**（4 级回退）：content-disposition filename（含 filename* RFC5987）→ profile-title 头 → URL query `name`/`title` → 订阅域名二级域（过滤 github/cloudflare/oss 等公共域）→ 全体节点名公共括号前缀（`[xx]`/`【xx】`/`(xx)`）或分隔符前缀；配合 `isDefaultGroupName()`（识别 "Subscription #"、"我的分组" 等默认名）只在默认名时覆写；`GroupSettingsActivity`/`MainActivity` 创建订阅时同样预填；
  - **订阅 diff 重写**：按顺序匹配（`remainingExists.indexOfFirst`），保持 userOrder 稳定；新增**熔断器**：`exists >= 10 && fetched < exists*70%` 时跳过删除（防机场抽风清空节点）；插入改为先收集 `toInsert` 再逐条入库；
  - **流量/到期文本回退提取**：`Subscription-Userinfo` 缺失时，用正则从节点名提取 `剩余流量/已用/套餐到期` 并解析为 bytes/时间戳写入 subscription（供资产卡片显示）；
  - clash 解析增强：`xhttp`/`splithttp` 类型直接建 VMessBean（不再要求 isVLESS）、`xhttp-opts`/`splithttp-opts` 通用、v2ray-plugin opts 修正（mode 默认 websocket、tls 由 `tls: true` 控制、mux=false 补 `mux=0`）；
- **剪贴板导入去重**（`MainActivity`）：仅当目标分组（或当前分组）`subscription.deduplication == true` 时才 `deduplicateProxies()`（修复强制去重缺陷）；新增 `Formats.kt` 的 `dedupKey()`/`deduplicateProxies()`（基于 `Protocols.Deduplication`）；
- **`Protocols.Deduplication` 键重构**：从 name 参与哈希改为按协议字段（uuid/password/sni/pubKey/privateKey/peerPublicKey/localAddress/credentials 等）+ `finalPort`/Hysteria 首端口，同服务器不同凭据不再误合并、同名不同凭据不再漏合并；
- `SubscriptionBean` v4：新增 `lockUserAgent`（锁定后 `migrateSubscriptionUserAgents` 跳过该订阅）；`UserAgentPreference` 重构为对话框（UA 预设 spinner + 自定义输入 + 锁定开关，`layout_dialog_group_user_agent.xml`）；默认 UA 改为 `NekoBox/Android/1.4.2 (Prefer ClashMeta Format)`；`Nets.USER_AGENT` 常量改为跟随该设置；
- `DataStore`：`connectionTestURL`/`groupUrlTestUrl` **撤销强制 Cloudflare 迁移**（回退为资源 `default_connection_test_url`，仅 trim）——上版报告第 5.4 条行为变更已不存在。

### 0.6 UI / 产品功能（对应 release notes 前六条）

1. **应用更名 Own**：`app_name`="Own"、`app_name_long`="Own for Android"；`SendLog`/`CrashHandler` 品牌串 NB4A→OWN；**图标系统大精简**：删除原神角色/节日等约 13 个 `activity-alias` 与全套 mipmap webp，仅保留默认/明暗两套，新增 `drawable-night`/`mipmap-night-*` 自适应深浅色图标 + `ic_launcher_monochrome.png`；`AppIconManager` 加 `init()`（启动时归一化别名状态）与全面 try-catch 容错；
2. **设置页手风琴折叠**：新增 `ExpandablePreferenceCategory`（100 行，点击展开/收起 + 子项可见性规则 `setChildVisibilityRule/updateChildVisibility`）；`global_preferences.xml` 重构为 categoryUI/categoryVPN/categoryMode/categoryCore/categoryDNS/categoryFragment/categoryObservatory/categoryAdvanced；`SettingsPreferenceFragment` 接入（rulesProvider==4 时才显示自定义 geosite/geoip URL 等）；设置图标按主题色 tint；
3. **全自由自定义主题色 + 纯白模式**：`ColorPickerPreference` 重写（+333 行：HSV/RGB 三滑条 + HEX 输入 + 预设色板）；`Theme` 新增 `CUSTOM`、`getClosestThemeForColor()`、`isWhiteTheme()`、`getPrimaryColor()`；`DataStore.customThemeColor`（默认 0x00E676）；`ThemedActivity`/`ToolbarFragment`/`MainActivity`（FAB 反色）适配纯白模式；主题变更（含 CUSTOM 色值变化）触发 recreate；
4. **多规格液态玻璃 Widget**：新增 `OwnBoxWidgets.kt`（322 行：`OwnBoxWidgetHelper` 统一刷新 + `OwnBoxWidget1x1/2x2/4x1/4x2` 四个 Provider）+ 4 套布局（`widget_proxy_*.xml`）+ `ownbox_widget_{1x1,2x2,4x1,4x2}_info.xml` + 液态玻璃背景 drawable（`bg_widget_glass_liquid*`、`bg_widget_capsule_liquid`）；旧 `OwnBoxWidgetProvider` 保留为 classic 规格并委托 Helper；
5. **主页置顶订阅资产信息卡片**：`ConfigurationFragment.updateSubscriptionInfoCard()`（流量剩余/已用/到期，`Subscription-Userinfo` 优先，缺失时用 0.5 节的正则回退提取）；开关 `show_subscription_info_card`；`layout_main.xml`/`layout_profile_list.xml` 配套改版；
6. **节点卡片操作菜单**：`showNodeActionDialog()`（`dialog_profile_actions.xml`）+ 重复节点清理逻辑 + 活动配置删除/编辑保护（`cannot_delete_active_profile`/`cannot_edit_active_profile`）；
7. **连通性多维测试**：新增 `ConnectivityTestActivity`（666 行，`activity_connectivity_test.xml`）：直连 TCP / TCP RST 检测 / 经 outbound HTTP（含状态码提取）/ Google CN 路由检测 / 原始 TLS ClientHello 探针；走独立 `CONNECTION_ID_CONNECTIVITY_TEST` 连接与 `urlTestCustomUrl` AIDL；
8. **落地 IP 与速率排版**：`StatsBar` 重构（`updateStatusViews`/`refreshDisplay`，落地 IP 按钮对比度、速率排版）；`LandingIpBottomSheet` 扩展；开关 `showLandingIp`；
9. **媒体解锁重构**：`MediaUnlockActivity`（+373 行）：统一浏览器请求头 `applyBrowserHeaders`、新增 Wikipedia 检测项（`ic_platform_wikipedia`）；
10. **Assets 更新健壮化**：`AssetsActivity.updateAsset` 返回 Boolean、逐文件 Toast 反馈、API 失败回退 latest/download 直链、tag 缺失时用日期兜底版本号；
11. **杂项防崩溃**：`Utils.snackbar` 改为安全解析（`MessageStore.getCurrentActivity()` 回退 + `safeSnackbar` Toast 兜底，修复批量更新时 Fragment detached 闪退）；`getColorAttr` 容错（attribute 缺失/解析失败返回透明色）；`BackupFragment`/`GroupFragment` 导出改用安全 resolver；`ToolbarFragment` toolbar 判空。

### 0.7 服务保活与唤醒（注意：偏激进，移植需取舍）

- `BaseService.onStartCommand` 返回值 `START_NOT_STICKY` → **`START_STICKY`**；
- 注册 `ACTION_SCREEN_ON`/`ACTION_USER_PRESENT`：亮屏时 `box.wake()`，且 `wakeResetConnections`（新 Key）开启时 `Libcore.resetAllConnections(true)`；新增 `Action.CLOSE` 停止；
- `VpnService` 新增 **WifiLock**（`WIFI_MODE_FULL_HIGH_PERF`，非引用计数），wakeLock 改为判空防重复获取；
- 设置中**隐藏"忽略电池优化"**（release notes 最后一条，`global_preferences.xml` 移除对应项）。

### 0.8 v2.6.0 增量小结（对 T4A 的可移植性影响）

- **测速冲突解除**：OwnBox v2.6.0 的 urlTest 与 T4A 双 HEAD 方案已趋同（两阶段 Keep-Alive），上版"不建议移植 urlTest 替换"的结论可更新为"无需移植，两边已一致"；可借鉴的只剩：fallback URL 互备、`>=400` 状态码判定、`urlTestCustomUrl` 绕分流 AIDL、TcpPing UDP-only 回退 + DNS 预解析；
- **纯增量、低成本高价值（建议 P0）**：IPv6 泄露修复全套（0.3）、http.go `>=400`、GroupUpdater supervisorScope + 批量更新并发化、RawUpdater 熔断器 + 顺序保持 + 代理失败回退直连、剪贴板按订阅设置去重、Deduplication 键重构、safeSnackbar/getColorAttr 防崩溃、Hysteria/TUIC/SS-plugin/Trojan-TLS 连通性修复、TcpPing 修复、assets.sh（上版已列）；
- **中等成本（P1）**：机场名自动提取（extractAirportName + isDefaultGroupName）、订阅资产信息卡片（含正则回退）、BalancerBean 独立负载均衡节点（依赖 libcore loadbalance strategy + Room v10 迁移）、每组 URLTest URL、lockUserAgent、分组名作为 outbound tag + route tag 校验；
- **大工程/产品向（P2）**：ConnectivityTestActivity、多规格液态玻璃 Widget、自定义主题色 + 纯白模式、ExpandablePreferenceCategory 手风琴、媒体解锁重构；
- **不建议**：START_STICKY + 亮屏 wake/resetConnections + WifiLock（功耗激进，与 T4A 定位需权衡）、品牌/更名/图标精简（与 T4A custom-icon-pack 冲突）、versionCode×5 的 metadata 动态读取（T4A 有自己的版本体系）。

---

## 0B. v2.6.0 → v2.8.0 增量扫描（sing-box 1.14.1 升级专项）⭐ 最新

> 扫描口径：`git diff 4372435..origin/main`（Own v2.6.0 → Own v2.8.0，origin/main 当前 @ `7bccca0b`，约 98+ 提交）。
> **注意**：本机 `C:\repos\OwnBoxForAndroid` 检出落后（main 停在 `3769c07`），本轮已 `git fetch`，以下结论均以 `origin/main` 为准。
> 内核时间线：OwnBox 的 sing-box 版本从 **v1.14.0 直接跳到 v1.15.0-alpha.4**（`8bb680f8`），**从未使用过 1.14.1**。
> 因此对 T4A 升级 1.14.1（稳定版补丁）而言：
> - OwnBox 在 **1.14.0 内核期间（`4372435..8bb680f8` 之间）** 的全部改动可直接借鉴；
> - OwnBox 的 1.15 迁移提交 `8bb680f8` 本身适配面极小（见 0B.4），**反向确认**了其 1.14 适配层（第 2.A/2.B 章 `9160df5d`/`a5c0685`）就是完整的 1.14 适配集合，且 1.15 专属改动 T4A 上 1.14.1 时不需要做。

### 0B.1 内核层新增改动（1.14.0 内核期间，libcore，Go）⭐

| 文件 | 改动内容 |
|---|---|
| `libcore/box.go` | ① `urlTest` 预热阶段 HEAD 不兼容（EOF/405/403/5xx）时自动以 **GET 重试**再预热；② 启用 **HTTP/2**：`NextProtos: ["h2","http/1.1"]` + `ForceAttemptHTTP2` + `http2.ConfigureTransport`；③ 5xx 判失败（预热与测量两阶段都查 `>= 500`）；④ **primary/fallback 超时拆分**：`timeout>3500` 时主测 `timeout-1500`、备用固定 2000ms；⑤ urltest 实例 `Close()` 后**节流 GC**（`lastUrlTestGc` 2s 内只跑一次 `runtime.GC()+FreeOSMemory`）；⑥ `newSingBoxInstance` selector 兜底：显式 selector 为空时扫描全部 outbounds 找第一个 `*group.Selector` |
| `libcore/protocol/loadbalance/outbound.go` | **目的地粘性哈希**：`hashDestination`（FNV-1a）+ `pickByDestination`，同一目标地址始终映射同一节点，修复大文件分块上传卡死 / 连接漂移 / TLS 会话恢复跳 IP（`dcd78681`）；`NewConnection`/`NewPacketConnection` 均改走粘性选择 |
| `libcore/speedtest.go` | 下载阶段**实时 progress 回调**（分段报告速率）；0 字节下载明确报错（`simple download transferred no data`）而非静默成功 |
| `libcore/protocol/vless/xhttp/{client,conn,dialer,options}.go` | **XHTTP 传输大修**（`2d395981`）：headers、**ALPN 协商**、连接 teardown 修正；新增 `xhttp_test.go`（135 行）测试套件。T4A 的 xhttp 移植层与 1.14 升级需一并参考 |
| `libcore/nb4a.go` | ① `ForceGc` 改为 `runtime.GC()+debug.FreeOSMemory()` 双阶段；② 新增 **`SetMemoryProfile(performancePriority)`**：低内存模式 GOGC=20 + `SetMemoryLimit(128MB)`，性能优先模式 GOGC=100 + 无限制；③ **perf_mode 标志文件机制**（`no_backup/perf_mode` 存在与否决定模式，避免新增 JNI 绑定）；④ `InitCore` 中提前 chdir 并设置 `protectSocketPath` 绝对路径 + `GetProtectSocketPath()` |
| `libcore/protect.go` / `platform_box.go` | **protect 通道加固**：socket `chmod 0666`；`AutoDetectInterfaceControl` 失败**重试 3 次**（间隔 25ms）；`sendFdToProtect` 超时从 100ms 提到 **2s**（`timeout.Sec = 2`）；protect 路径改用绝对路径 |
| `libcore/go.mod`（v2.6.0 状态，1.14.0 依赖族） | `sing v0.9.0-beta.4`、`quic-go v0.61.0-sing-box-mod.7`、`sing-tun v0.9.0-beta.4`、`sing-vmess v0.2.8-*`、`sing-mux v0.3.5`（对比 T4A 现状：sing v0.8.12、quic-go v0.59.0-sing-box-mod.4、sing-tun v0.8.12）——即 **1.14 稳定线的依赖族整体上移一个 minor**；1.14.1 应以官方 tag 的 go.mod 为准、OwnBox 该状态为参照 |

### 0B.2 配置生成层新增改动（1.14.0 内核期间，Kotlin）⭐ 对 1.14.1 升级为必改项

1. **sc_\* XHTTP 字段必须为 `{"from":N,"to":N}` 范围对象**（`249ca740`，1.14 内核实测踩坑）：
   - sing-box 1.14.x 内核对 `sc_max_each_post_bytes` / `sc_min_posts_interval_ms` / `sc_stream_up_server_secs` 等要求 range 对象，纯数字报错 `cannot unmarshal number into Go struct field`；
   - OwnBox 为此在 `SingBoxOptions.java` 引入 `XHTTPRangeValue{from,to}`（1.15 迁移时 `8bb680f8` 才回退为 JsonElement）；`V2RayFmt.kt` 中 `RANGE_KEYS` 集合在合并 extra 时把纯数字包成 `{"from":N,"to":N}`；
   - **T4A 现状**：`SingBoxOptions.V2RayTransportOptions_XHTTPOptions` 的 sc_\* 字段仍是 `JsonElement`，`V2RayFmt.kt`/`XhttpExtraConverter.kt` 无范围包装逻辑 → **升级 1.14.1 必改**。
2. **`V2RayFmt.kt` extra 合并过滤**：`BLOCKED_KEYS = {"encryption"}`（sing-box 1.12+ 已移除该字段，静默丢弃避免 unknown field error）；`sc_max_buffered_posts` 也列入 RANGE_KEYS。
3. **`V2RayFmt.kt` TLS/REALITY 增强**（`buildSingBoxOutboundTLS`）：security 判定扩展接受 `reality` / `realityPubKey` 非空；sni/serverAddress trim；ALPN 为空且 type=xhttp/splithttp 时补 `["h2","http/1.1"]`；uTLS fingerprint trim+小写，REALITY 下默认 `chrome`（none/random/randomized 也强制 chrome）；short_id trim+小写；xhttp transport 强制 `no_grpc_header = true`。
4. **`HysteriaFmt.kt` QUIC 韧性参数**（配套 `SingBoxOptions.Outbound_HysteriaOptions` 新增 6 字段：`idle_timeout`/`keep_alive_period`/`stream_receive_window`/`connection_receive_window`/`disable_path_mtu_discovery`/`bbr_profile`）：
   - hopPorts 列表为空时回退 `getFirstPort`；`hop_interval` 最小 15s（默认 30s）；
   - 新增 `keep_alive_period="15s"`、`idle_timeout="30s"`、`disable_path_mtu_discovery`、两个 receive_window、`bbr_profile="standard"`（移动网络韧性优化）。
5. `extra` xhttp 转换失败回退原文：`runCatching { XhttpExtraConverter.xrayToSingBox(it) }.getOrDefault(it)`。

### 0B.3 服务稳定性（1.14.0 内核期间）

- **内核/服务自动恢复**（`0c604172`，v2.7.8）：`BaseService` 捕获启动异常时识别 **cache.db 损坏**（`invalid freelist page` / `initialize cache-file` 等特征）→ `deleteCorruptedCacheDb()`（扫描 cacheDir/filesDir/noBackupFilesDir 等 4 处候选路径）删除后**自动重启一次**（`cacheRecoveryAttempts` 限 1 次防循环）；`proxy.close()` 移入 `Dispatchers.IO`；restart 前加 `delay(100)`；
- **Doze 行为修正**：设备进入 Doze 时不再 `box.sleep()`（会阻断后台推送/同步导致假断连），改为只 `Libcore.forceGc()`；新增 `ACTION_SCREEN_OFF` 触发 forceGc 保后台 RSS 低位；
- 磁贴通知 `postNotificationSpeed` 回调补齐。

### 0B.4 OwnBox 的 1.15 迁移提交 `8bb680f8` —— 反向确认 1.14.1 升级边界 ⭐

该提交（v1.14.0 → v1.15.0-alpha.4）全部适配面仅 5 个文件：

| 文件 | 改动 |
|---|---|
| `libcore/go.mod` | 依赖族升级：sing v0.9.5-*、sing-quic v0.7.1-*、sing-tun v0.9.4-*、sing-vmess v0.2.8、sing-mux v0.3.7-*、sing-snell/sing-anytls snapshot、wireguard-go v0.0.6 |
| `libcore/platform_box.go` | ① `adapter.ConnectionOwner` 字段改名 `AndroidPackageNames` → `PackageNames`；② 新增 `UsePlatformAutoRedirect()`/`CreateAutoRedirect()` 空实现（1.15 新平台接口） |
| `SingBoxOptions.java` | 移除 `XHTTPRangeValue`（1.15 内核重新接受 JsonElement/任意 JSON）；RANGE_KEYS 去掉 `sc_max_buffered_posts` |
| `V2RayFmt.kt` | 同上单行 |
| `nb4a.properties` | SINGBOX_VERSION → v1.15.0-alpha.4 |

**结论**：① T4A 升到 1.14.1 **不需要** PackageNames 改名 / AutoRedirect / 1.15 依赖族；② 1.14.x 的 sc_\* range 对象要求在 1.15 被取消，属 1.14 特有坑，T4A 上 1.14.1 必须处理；③ OwnBox 的 1.14 适配层 + 0B.1/0B.2 增量 = T4A 升级 1.14.1 的完整参考实现。

### 0B.5 1.15 专属改动（T4A 上 1.14.1 不适用，仅备案）

- Sing-Tun 官方自研栈接入（`db13ab6a`）与 1.15 native tun 优化（`95be8b72`，safe MTU / stack 调优）；
- xhttp 2.8.0 全面重写（`4ef86a0a`，VLESS+XHTTP Xray 对齐 + 测试套件，基于 1.15 内核）——T4A 后续升 1.15 时再评估；
- 性能优先模式跨进程生效、后台深度省电（`6cf9dd0e`，依赖 0B.1 的 SetMemoryProfile + perf_mode 文件）。

### 0B.6 v2.6.0→v2.8.0 非内核类改动速览（与本 change 无关，简记）

主题系统大重构（MD3 深度重构、色板、纯白修复）、全部分组聚合搜索、节点多格式分享/二维码导出、局域网共享（免 Root 热点检测）、sing-box 官方仪表盘 1:1 移植（`25a537cf`）、DocsFragment 帮助中心、负载均衡 UI 完善（前置/落地代理、正则过滤、策略联动）、balancer 链式（`18ab110c`）。这些属 UI/产品向，若 T4A 需要另立 change。

### 0B.7 对 T4A「升级 sing-box v1.14.1」的移植清单汇总（供 propose）

1. **基础适配**（OwnBox 第 2.A/2.B 章 + `9160df5d`/`a5c0685`）：go.mod 1.14 依赖族（以官方 1.14.1 tag 为准）、`box.go` ResetNetwork 新签名 + `CertificateProviderRegistry`、`platform_box.go` 平台接口补齐、`ruleset.go` Tag 类型、DNS schema typed servers / fakeip 服务器化 / dns-block→reject、TLS fragment detour 重构、xhttp `qtls.Dial` 签名修复；
2. **1.14 必改新增**（0B.2）：sc_\* 字段 `{"from","to"}` 范围包装、extra `encryption` 丢弃、xhttp ALPN `["h2","http/1.1"]`、REALITY uTLS 默认 chrome、no_grpc_header、Hysteria QUIC 韧性字段（+SingBoxOptions 6 字段）、hopPorts 空回退 + hop_interval 下限；
3. **libcore 增强可选**（0B.1，与内核版本无关但建议随升级一并做）：urlTest HTTP/2 + GET 回退 + 超时拆分、urltest 节流 GC、protect 重试/超时/chmod、speedtest progress + 0 字节报错、loadbalance 粘性哈希（若 T4A 移植负载均衡则必做）、SetMemoryProfile（性能优先模式，可选独立 change）；
4. **稳定性可选**（0B.3）：cache.db 损坏自动恢复、Doze 不 sleep、SCREEN_OFF forceGc；
5. **不适用**：0B.4/0B.5 全部 1.15 专属项。

---

## 1. 调研方法与基线说明（重要）

直接用 `git merge-base` 得到的分叉点是 `5768494`（T4A v1.4.2，2026-02-09），但这**不是有效的对比基线**：

- OwnBox 从 T4A v1.4.2 分叉后，其首个提交 `e541277`（2026-09-06）就把 T4A 后续约 560 个提交的成果整体移植了进来（提交信息自称 "port all Throne features"）。
- 因此 `git diff 5768494..OwnBox-HEAD` 会把 T4A 自己的工作（openspec/、tools/diagnostics/、SpeedTestRunner、ThroneDesktopBackupImporter、XHTTP 移植等）误算成 OwnBox 的改动。

**本报告采用的正确基线**：`git diff t4a/main(48547d2) OwnBox-基线(3769c07)`，即 OwnBox 在"移植完 T4A 2026-09-04 状态"之后**真正属于自己的增量**（上版报告口径）：

```
172 files changed, 9240 insertions(+), 479 deletions(-)
```

**2026-09-12 增量口径**：`git diff 3769c07..4372435`（v2.4.0→v2.6.0，29 提交）：

```
276 files changed, 7211 insertions(+), 1462 deletions(-)
```

（其中约 150 个文件为图标 mipmap/drawable 二进制增删，代码增量集中在 libcore、fmt/group/database、ui/widget。）

OwnBox 当前版本：**v2.6.0（versionCode 260）**，包名 `com.ownbox.app`，sing-box **v1.14.0**。

**2026-09-XX 复核**：origin/main 已推进至 `7bccca0b`（**Own v2.8.0**，versionCode 28x），sing-box 升至 **v1.15.0-alpha.4**（`8bb680f8`，从未经过 1.14.1）；1.14 相关增量见第 0B 章。

---

## 2. 改动全景分类（上版报告内容，v2.3.10 基线，仍然有效）

### A. 内核层（libcore，Go）—— sing-box 1.14 升级及适配 ⭐ 最大差异

T4A 仍在 sing-box **v1.13.16**，OwnBox 已升级到 **v1.14.0** 并完成全套 API 适配：

| 文件 | 改动内容 |
|---|---|
| `libcore/go.mod` | 依赖整体升级到 sing-box 1.14 生态（sing v0.6.x 系、grpc 1.79 等） |
| `libcore/box.go` | `ResetNetwork(ctx)` 新签名；注册 `CertificateProviderRegistry`；urlTest 经历"单次握手→两阶段 Keep-Alive"反转（见 0.1） |
| `libcore/platform_box.go` | 1.14 平台接口适配：新增 `ProcessPlatformOptions`、`CancelNotification`、`ReadWIFIState(ctx)`、Neighbor/Shell/Bridge/Tailscale 系列空实现 |
| `libcore/box_include.go` | 注册自定义 `loadbalance` outbound |
| `libcore/protocol/loadbalance/outbound.go` | 自定义负载均衡分组 outbound；v2.4+ 支持 `strategy`（random/轮询）与 `Close()` interrupt |
| `libcore/ruleset.go` | 适配 1.14 中 `RuleSet.Tag` 类型变化（`[]string` → 取首元素） |
| `libcore/protocol/vless/xhttp/client.go` | 修复 `qtls.Dial` 新签名（移除 `bufio.NewUnbindPacketConn` 包装） |
| `libcore/dns_box.go` | 小幅适配 |
| `libcore/http.go` | （v2.6.0）状态码判定 `!=200` → `>=400` |

### B. 配置生成层（ConfigBuilder / SingBoxOptions）—— sing-box 1.14 DNS schema 适配 ⭐

提交 `a5c0685`（+209/-67）是移植 1.14 的关键配套：

- **`DNSServerOptions` 结构重写**：旧 `address/address_resolver/address_strategy/address_fallback_delay` → 新 `type/server/server_port/path/domain_resolver/domain_strategy`（`SingBoxOptions.java`）。
- **新增 `buildDnsServer()` 工厂函数**（ConfigBuilder.kt，约 130 行）：把 `tls://`、`quic://`、`h3://`、`https://`、`tcp://`、`local`、`hosts` 等旧式 address 字符串解析为新式 typed DNS server，非 IP 地址自动补 `domain_resolver/domain_strategy`。
- **FakeDNS 迁移**：1.14 移除顶层 `dns.fakeip`，改为 `dns.servers` 中 `type: "fakeip"` 的 server（含 `inet4_range/inet6_range`）。
- **DNS 规则**：`dns-block` server 改为 `action: "reject"`（1.14 移除了 block server 类型）；`DNSRule_DefaultOptions` 增加 `action/rcode` 字段。
- **TLS 分片重构**：不再生成独立 fragment outbound，改为在主代理 outbound 上挂 `detour` + 按逗号/区间解析 `fragmentInterval` 首值计算 delay（详见 `74e97e7`）。
- （v2.6.0 增量）分组 outbound tag 使用分组名 + route rules tag 校验回退，见 0.2。

### C. 订阅与节点解析修复（RawUpdater / Formats / Util）⭐ 高价值

1. **多 UA 回退下载**（`c6142f6` + `74e97e7`，RawUpdater.kt）：
   - 候选 UA 链：用户自定义 UA → `Singbox/1.14` → `clash-meta` → `v2rayN/7.8.2` → `Throne/1.0.0` → `sing-box/1.14.0`，逐个尝试直到解析出非空节点；记录最后一次成功的 `Subscription-Userinfo` 与 `content-disposition`。
   - 下载客户端移除 `tryH3Direct()`（该模式在部分服务器上失败）。
   - （v2.6.0 增量）代理拉取失败自动回退直连；新增 Profile-Title 头；机场名自动提取；diff 熔断器（见 0.5）。
2. **`parseProxies` 重构**（Formats.kt）：
   - 按协议 scheme（ss/vmess/vless/trojan/hysteria2/tuic/snell/anytls/awg 等 18 种）智能切分同一行多个链接（v2.6.0 改为按 scheme 索引位置精确切分，替代按空格切分）；
   - 单链接文本中的 `clash://`/`sn://subscription` 才抛 `SubscriptionFoundException`，混合内容不再误判；
   - 行解析结果优先（`entitiesByLine.size >= entities.size` 时取行解析）。
3. **Base64 解码健壮化**（Util.kt `b64Decode`）：trim、去空白/换行、自动补 padding、URL_SAFE flag、cleaned/padded 双轮尝试。
4. **YAML 解析容错**（RawUpdater.kt）：clash proxies 逐条 try-catch（单条坏节点不再毁掉整个订阅）、type 小写化、空结果继续走后续解析分支；WireGuard `[Interface]` 同样空结果不提前 return。
5. **节点去重键增强**（Protocols.kt）：见 0.5（v2.6.0 进一步重构，name 不再参与哈希）。
6. **链接解析修复**：
   - `V2RayFmt.kt`/`TrojanFmt.kt`：fragment 先剥离并 URL-decode 作为节点名；`toHttpUrlOrNull()` + 显式 error；（v2.6.0）query 特殊字符 sanitize 双重回退；
   - `SnellFmt.kt`：非标准 snell:// 链接的 regex 回退解析；
   - `WireGuardFmt.kt`：`parseWireGuardLink()`（61 行）——支持 `wireguard://`/`awg://` URI（含 base64 整配置回退、AWG 混淆参数 Jc/S1/H1 识别、`[AWG-Compat]` 前缀）；
   - `HysteriaFmt.kt` `hopPortsToSingboxList`：端口区间格式规范化（**v2.6.0 输出 `start:end`**）。

### D. 稳定性 / 崩溃修复 ⭐ 高价值低成本

| 提交 | 内容 |
|---|---|
| `f231a46` | Room：`ssrBean`/`snellBean` 列加 `@ColumnInfo(defaultValue = "NULL")`，修复升级迁移崩溃；`SagerDatabase` 初始化加 **try-open → 失败删库重建** 兜底 + `fallbackToDestructiveMigrationOnDowngrade()`（v2.6.0 升级为先备份再删库） |
| `2481d78` | MediaUnlock/TrafficChart/StatsBar：移除不存在的 `statusCode` 调用、`forEachLine` 返回值误用、补 `isActive` import |
| `614d7ec`/`5d272b3` | `SubscriptionUserAgentPreference` 崩溃修复（MaterialComponents 主题上下文、Logs import） |
| `a5c0685` | MaterialCardView 崩溃修复（themes.xml 增加 `cardStroke` 等兼容 attr，values-v26 主题） |
| `3b5b35c`/`047be8e`/`b95895a`/`fa70920`/`6db9d67` | 缺失 drawable/字符串资源、XML 声明、重复资源清理 |
| `8643e92` | modernTLS imports、YouTube view binding 命名、HTTP response 处理 |
| `578cc59`/`d182069`/`90a5b06` | suspend 函数、snackbar、`toBase64Str`、协程 scope、TileService `setSubtitle` 等编译期修复 |
| （v2.6.0） | `safeSnackbar`/`getColorAttr` 容错、Fragment detached 闪退（`0a948b8`）、批量更新并发冲突（supervisorScope） |

### E. 新功能（OwnBox 自有）

1. **TCP Ping**（`bg/proto/TcpPing.kt`）：纯 TCP connect 延迟测试（绑定 underlyingNetwork + protect socket）；v2.6.0 修复 UDP-only 协议与 DNS 预解析（见 0.1）。
2. **负载均衡**：v2.3 时代为分组开关；v2.4+ 升级为独立 BalancerBean 节点类型（见 0.2）。
3. **每分组 URLTest 细化选项**：`groupUrlTestInterval/Tolerance/IdleTimeout/InterruptExist/Url`（每组独立存储）、`groupDisabled` 开关；v2.6.0 起 URLTest 运行时也优先取每组 URL（`resolveLink`）。
4. **订阅 UA 设置**：`SubscriptionUserAgentPreference` + 每订阅 UA + `lockUserAgent` 锁定（v2.6.0）。
5. **落地 IP（Landing IP）**：`LandingIpManager.kt` + StatsBar 集成 + `LandingIpBottomSheet`；v2.6.0 排版优化 + `showLandingIp` 开关。
6. **实时流量图表**：`TrafficChartActivity`（497 行）+ `TrafficChartView`，经 Clash API 拉取实时速率绘图。
7. **网络工具**：`IpPurityActivity`（IP 纯度检测）、`MediaUnlockActivity`（流媒体解锁检测；v2.6.0 重构 + Wikipedia）。
8. **节点选择对话框**：`NodeSelectDialogActivity`（widget 用的单实例悬浮节点切换）。
9. **桌面 Widget**：v2.3 单一 classic widget → v2.6.0 四规格液态玻璃（1x1/2x2/4x1/4x2，见 0.6.4）。
10. **应用图标主题**：v2.3 有 15 个 activity-alias（原神角色等）→ v2.6.0 精简为明暗自适应两套（见 0.6.1）。
11. **连通性多维测试**（v2.6.0 新增）：`ConnectivityTestActivity`（见 0.6.7）。
12. **订阅资产信息卡片**（v2.6.0 新增）：见 0.6.5。
13. **自定义主题色/纯白模式**（v2.6.0 新增）：见 0.6.3。
14. **其他**：AMOLED 纯黑主题、`CountryFlagUtils`（节点国旗）、`BackupHelper` 扩展、`ThemedActivity` 基类扩展。
15. **测试**：`SnellFmtTest.kt`（94 行）+ v2.6.0 新增 `ConfigBuilderLoadBalanceTest.kt`、`HysteriaFmtTest.kt`。

### F. CI / 构建

- `buildScript/lib/assets.sh`（`4d73b6c`）：geoip/geosite 版本获取改为 **releases/latest 302 重定向解析**（绕过 GitHub API 限流），失败回退 `GITHUB_TOKEN` API / `latest/download` 直链。
- `buildSrc Helpers.kt`：release 签名兜底（无 keystore 时回落 debug 签名——**品牌相关，不建议移植**）；APK 命名 Ownbox-*。
- `.github/workflows/release.yml` 大改（自动发布、make_latest）、`preview.yml` 微调。
- （v2.6.0）`app/build.gradle.kts` versionName/versionCode 从 `nb4a.properties` metadata 动态读取（versionCode × 5）；导出日志前缀改 OWN（`4f9ff37`）。

### G. 品牌 / 不建议移植

README、FUNDING、Telegram 链接、`com.ownbox.app` 包名、release.keystore、捐赠码、应用更名 Own、`AboutFragment` OwnBox 化。

---

## 3. 与 T4A 现状的重叠分析

| OwnBox 改动 | T4A main 现状 | 结论 |
|---|---|---|
| sing-box 1.14 升级 + DNS schema 适配 | 仍为 v1.13.16，旧式 DNS schema | **T4A 迟早要做**，OwnBox 已趟平适配路径（含 fakeip/hosts/reject 迁移细节） |
| 多 UA 订阅回退、parseProxies 重构、b64Decode 健壮化 | T4A 无 | 纯增量，可直接移植 |
| Room defaultValue NULL + 删库重建兜底（v2.6.0 先备份） | T4A 无 | 纯增量，低成本高价值 |
| 负载均衡（v2.6.0 BalancerBean 独立节点 + strategy + 流量聚合） | T4A 无 | 纯增量；libcore 需随 1.14 升级一起做 |
| 每组 URLTest 选项 / TCP Ping | T4A 无 | 纯增量 |
| IPv6 泄露修复全套（VpnService 常驻 v6 路由 + AAAA reject + ipv4_only） | T4A 无 | 纯增量，低成本高价值 |
| 机场名自动提取 / 订阅资产卡片 / diff 熔断器 | T4A 无 | 纯增量 |
| 落地 IP / 流量图表 / IP 纯度 / 媒体解锁 / 国旗 | T4A 无 | 纯增量，但体量大、偏"成品 App"向 |
| activity-alias 图标主题系统 | T4A 已有 `CustomIconFragment`（custom-icon-pack，方案不同） | **功能重叠**；OwnBox v2.6.0 已大幅精简（仅明暗两套），冲突减小但仍需二选一 |
| urlTest 测速方案 | T4A 为"预热+复用双 HEAD" | **冲突已解除**：OwnBox v2.6.0 改回两阶段 Keep-Alive，两边趋同；可借鉴 fallback URL、`>=400`、绕分流 AIDL |
| assets.sh 限流修复 | T4A 用原版 API 方式 | 低成本可移植 |
| xhttp `qtls.Dial` 签名修复 | T4A 的 xhttp 移植基于 1.13 API | 随 1.14 升级一并处理 |
| START_STICKY / 亮屏 wake / WifiLock | T4A 无 | 功耗激进，移植需取舍 |
| 品牌/签名/包名/更名/README | — | 不移植 |

---

## 4. 可移植性分级建议（供 propose 参考，含 v2.6.0 增量）

**P0（低成本、高确定性、无依赖）**
1. Room `ssrBean/snellBean` defaultValue NULL + 数据库打开失败**先备份再**删库重建兜底（`f231a46` + v2.6.0 增强）
2. `b64Decode` 健壮化（`c6142f6`）
3. `parseProxies` scheme 感知切分 + 单链接订阅判定 + 行解析优先（`74e97e7` + v2.6.0 索引切分）
4. V2Ray/Trojan 链接 fragment 剥离与 URL-decode 节点名、`toHttpUrlOrNull` 防崩溃、query sanitize（`74e97e7`/`d1b9b95`/v2.6.0）
5. Snell 非标准链接 regex 回退解析（`fa9b523` 系）
6. Hysteria hopPorts 规范化 + `getFirstPort` 兼容（注意 v2.6.0 输出格式为 `start:end`）
7. YAML/JSON/WG 解析逐条容错（`c6142f6`）
8. assets.sh 限流修复（`4d73b6c`）
9. MaterialCardView 主题崩溃修复（`a5c0685` 中 themes 部分）
10. `2481d78` 类杂项编译/运行时修复
11. **IPv6 泄露修复全套**（VpnService 常驻 v6 地址/路由 + ConfigBuilder AAAA reject/ipv4_only/fakeip 适配）
12. **协议连通性修复包**：HY2 SNI 回退/alpn/udp_fragment、TUIC 同、SS v2ray-plugin mux=0、Trojan 强制 TLS、`libcore/http.go >=400`
13. **TcpPing 修复**：UDP-only 协议转 URLTest、DNS 预解析防虚高
14. **GroupUpdater supervisorScope + 批量更新并发化 + 失败静默**（修并发冲突闪退）
15. **RawUpdater diff 熔断器 + 顺序保持 + 代理失败回退直连**
16. **剪贴板导入按订阅 deduplication 设置去重 + Deduplication 键重构**
17. `safeSnackbar`/`getColorAttr`/toolbar 判空等防崩溃杂项

**P1（中等成本、独立成 change）**
18. 订阅多 UA 回退下载 + 订阅 UA 设置 UI + `lockUserAgent`（v2.6.0 对话框版）
19. 每组 URLTest 选项 + 测试 URL（默认值已回退 gstatic，无强制迁移问题）
20. WireGuard/AWG URI 解析（`parseWireGuardLink`）
21. **机场名自动提取**（extractAirportName + isDefaultGroupName + 创建/更新时预填）
22. **订阅资产信息卡片**（含节点名正则回退提取流量/到期）
23. **BalancerBean 独立负载均衡节点**（Bean + DB v10 迁移 + BalancerSettingsActivity + libcore strategy/Close + TrafficLooper 聚合）——依赖 sing-box 1.14 升级
24. **分组名作为 outbound tag + route tag 存在性校验**
25. `urlTestCustomUrl` AIDL（绕分流测速）+ fallback URL 互备

**P2（大工程，建议单独立项）**
26. sing-box 1.14 升级全套（go.mod、libcore API 适配、DNS schema、fakeip、ruleset、xhttp qtls、TLS fragment 重构）——OwnBox 的 `a5c0685`+libcore diff 是现成参考实现
27. 落地 IP / 流量图表 / 网络工具（IP 纯度、媒体解锁）——产品向功能，按 T4A 产品定位取舍
28. ConnectivityTestActivity 多维连通性测试
29. 多规格液态玻璃 Widget / 自定义主题色 + 纯白模式 / 设置页手风琴——UI 产品向，按 T4A 设计语言取舍

**不建议**：品牌/更名/图标系统（与 T4A custom-icon-pack 冲突）、签名/CI 发布 OwnBox 化、START_STICKY + 亮屏 wake/resetConnections + WifiLock（功耗激进）、urlTest 方案替换（两边已趋同，无需动作）。

---

## 5. 风险与注意事项

1. **OwnBox 代码质量参差**：大量提交是"修自己上一个提交引入的编译错误"，移植时应以最终状态 diff 为准，而非逐提交 cherry-pick。
2. **sing-box 1.14 是破坏性升级**：DNS schema、fakeip、ruleset Tag、平台接口、qtls 签名全变；T4A 的 XHTTP 移植层（libcore/protocol/vless/**）需同步适配。
3. **DB 版本**：OwnBox 已到 **Room version 10**（T4A 为 9），且 schema 细节分叉（defaultValue NULL、balancerBean 列）；移植 DB 相关改动时需重做 schema 导出与迁移验证。
4. **hopPorts 输出格式反转**：OwnBox v2.6.0 的 `hopPortsToSingboxList` 输出 `start:end`（上版报告记录的是 `start-end`），移植时以 v2.6.0 为准并确认 sing-box 1.14 的 hysteria outbound 端口区间语法。
5. **测速语义多次反转**：OwnBox 的 urlTest 经历"双 HEAD → 单次握手 → 两阶段 Keep-Alive"三次变化，引用其代码时务必以 v2.6.0（`4372435`）为准。
6. OwnBox 删除了 T4A 的部分能力（如 `ThroneDesktopBackupImporter`、`SpeedTestRunner`、`SingBoxOutboundParser`、`XhttpExtraConverter` 在其仓库中不存在——是移植时丢弃的），**不能反向假设 OwnBox HEAD 是 T4A 超集**；移植必须按功能点摘取。
7. **默认 UA 变更**：OwnBox 默认订阅 UA 为 `NekoBox/Android/1.4.2 (Prefer ClashMeta Format)`（伪装 NekoBox 以获得 clash-meta 格式回退），且 `Nets.USER_AGENT` 全局跟随该设置——移植时注意这会影响所有走 `USER_AGENT` 的请求。

---

## 6. 关键提交索引（OwnBox 侧）

### 上版报告（基线 `3769c07` 之前）

| 提交 | 主题 |
|---|---|
| `e541277` | 分叉后首个大提交（OwnBox 化 + 部分前置功能） |
| `b6652ab` | 整体移植 T4A 2026-09-04 状态（基线对齐点） |
| `f231a46` | Room defaultValue NULL 迁移修复 |
| `3b3ce03` | 并发拨号、AMOLED、国旗、剪贴板自动导入、TCP 优化 |
| `fa9b523` | AWG 兼容、URLTest 选项、DNS leak 面板、widget |
| `18f73dc` | 实时流量图表（Clash API）+ widget |
| `35e2a7f` | 落地 IP + 网络工具（IP 纯度/媒体解锁） |
| `5c14552` | 订阅 UA 设置、Snell 协议、XHTTP alias |
| `9160df5` | **sing-box 1.14 升级**、原地测速、连接图标 |
| `a5c0685` | **1.14 DNS schema 适配** + MaterialCardView 崩溃修复 |
| `c6142f6` | 订阅多 UA 回退 + base64 健壮化 |
| `74e97e7` | 节点解析修复 + TLS fragment + 负载均衡（初版） |
| `4d73b6c` | assets.sh 限流修复 |

### v2.4.0 → v2.6.0 增量（`3769c07..4372435`，29 提交）

| 提交 | 主题 |
|---|---|
| `dceb83f` | v2.4.0：urlTest 延迟修复、Hysteria2 端口跳跃、负载均衡 |
| `9e8613a` | loadbalance Close() interrupt 修复 |
| `6773574` | v2.4.1：EOF、URL 重置、落地 IP 开关、全链路测试、balancer |
| `411b088` | v2.4.2 pre-release（7 项修复 + 连通性测试） |
| `fd47168` | v2.4.3：机场自动改名、动态 UA 同步、软隐藏不可用节点 |
| `2df318d` | v2.4.4：连通性 outbound 测试 204 修复、路由绕过、Google CN 检测 |
| `f956cf7` | v2.4.5：流媒体/AI 解锁检测大改、订阅节点重复修复 |
| `393f4bd` | v2.5.0 正式版 |
| `5efd05b` | v2.5.6：UI 布局、URL test 延迟、hy2/tuic EOF、trojan、**IPv6 泄露**、去重 |
| `44f8e3d` | v2.5.7：HY2 连通性、URL test 延迟、队列卡死、落地 IP 按钮对比度 |
| `6fe79c4` | v2.5.8：动态图标、磁贴统一、清理冗余图标、**warm probe URL test（测速方案反转）**、落地延迟校准 |
| `e1d6009` | v2.5.9：磁贴图标缩放、**设置页折叠 UI**、静默电池优化、**更名 Own** |
| `2c698b7`/`7a9341d` | v2.6.0 正式发布 + RELEASE_NOTES 同步 |
| `4f9ff37` | versionName 从 metadata 动态读取、导出前缀 OWN |
