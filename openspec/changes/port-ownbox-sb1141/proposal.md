# Proposal

## Why

T4A 当前内核锁定 sing-box v1.13.16（`nb4a.properties` 的 `SINGBOX_VERSION`），官方 1.14 稳定线已推进到 v1.14.1，且参考仓库 OwnBoxForAndroid（Own v2.6.0，sing-box v1.14.0）已趟平整套 1.14 适配路径（内核 API、DNS schema、fakeip、ruleset、TLS fragment、XHTTP、平台接口）。本轮对 OwnBox origin/main（v2.8.0，`7bccca0b`）的增量扫描（见 `ROO_OWN_RESEARCH.md` 第 0B 章）确认：OwnBox 从 v1.14.0 直接跳到 1.15 alpha，其 1.14 适配层加上 1.14 内核期间（`4372435..8bb680f8`）的修复即是 T4A 升级 1.14.1 的完整参考实现；其中 sc_\* XHTTP 字段的 `{"from","to"}` 范围对象要求是 1.14 特有坑，T4A 现有代码未处理，不升级则永远踩不到、升级时不改则必炸。

## What Changes

- **内核升级**：`nb4a.properties` 的 `SINGBOX_VERSION` 从 `v1.13.16` 提升到 `v1.14.1`（唯一版本真实来源）；`libcore/go.mod` 依赖族随官方 1.14.1 tag 上移（sing 0.8.x→0.9.0-beta.x 系、quic-go、sing-tun、sing-mux 等，以官方 tag 的 go.mod 为准）。
- **libcore 内核适配**（对照 OwnBox `9160df5d`/`a5c0685` 及第 2.A 章清单）：`box.go`（ResetNetwork 新签名、CertificateProviderRegistry 注册、selector 兜底扫描）、`platform_box.go`（1.14 平台接口补齐：ProcessPlatformOptions、CancelNotification、ReadWIFIState、Neighbor/Shell/Bridge 空实现）、`ruleset.go`（RuleSet.Tag 类型变化）、xhttp `qtls.Dial` 新签名、`dns_box.go` 小幅适配。T4A 移植层（libcore/protocol/vless/**）同步适配 1.14 API。
- **1.14 特有必改项**（OwnBox `249ca740` 实测踩坑，1.15 已取消）：
  - XHTTP `sc_max_each_post_bytes`/`sc_min_posts_interval_ms`/`sc_stream_up_server_secs` 等字段发射为 `{"from":N,"to":N}` 范围对象（纯数字在 1.14 内核报 `cannot unmarshal number into Go struct field`）；
  - extra 合并时静默丢弃 `encryption` 字段（1.12+ 已移除）；
  - xhttp transport 强制 `no_grpc_header=true`，ALPN 为空时补 `["h2","http/1.1"]`。
- **DNS schema 适配**（Android 配置生成，对照 OwnBox `a5c0685`）：`DNSServerOptions` 重写为 typed servers（`type/server/server_port/path/domain_resolver/domain_strategy`）、新增 `buildDnsServer()` 工厂、fakeip 由顶层 `dns.fakeip` 迁移为 `type:"fakeip"` 的 DNS server、`dns-block` server 改为 `action:"reject"` 规则、`DNSRule_DefaultOptions` 增加 `action/rcode`。
- **TLS fragment 重构**：不再生成独立 fragment outbound，改为主代理 outbound 挂 `detour` + 按区间首值计算 delay（OwnBox `74e97e7`）。
- **协议连通性修复（随升级一并移植）**：REALITY 下 uTLS fingerprint 默认 `chrome`（排除 none/random/randomized）并 trim/小写、security 判定接受 reality、Hysteria hopPorts 空列表回退 `getFirstPort` + `hop_interval` 下限 15s 默认 30s + QUIC 韧性字段（`keep_alive_period`/`idle_timeout`/receive windows/`disable_path_mtu_discovery`/`bbr_profile`，配套 `SingBoxOptions.Outbound_HysteriaOptions` 新增 6 字段）、xhttp extra 转换失败保留原文。
- **libcore 增强（随升级一并移植，OwnBox 0B.1）**：urlTest 两阶段 Keep-Alive 探测启用 HTTP/2（`ForceAttemptHTTP2` + `NextProtos`）与 GET 重试回退、5xx 判失败、primary/fallback 超时拆分、urltest 实例节流 GC、protect 通道加固（绝对路径、chmod 0666、3 次重试、超时 2s）、`ForceGc` 双阶段回收。
- **明确不做**（OwnBox 1.15 专属，T4A 上 1.14.1 不适用）：`ConnectionOwner.AndroidPackageNames→PackageNames` 改名、`UsePlatformAutoRedirect`/`CreateAutoRedirect`、Sing-Tun 官方栈接入、1.15 依赖族、xhttp 2.8.0 重写（`4ef86a0a`）；以及 OwnBox 的品牌/主题/UI 产品向改动。

## Capabilities

### New Capabilities

（无 —— 本 change 是既有能力在 1.14.1 内核下的要求更新，不引入新能力。）

### Modified Capabilities

- `libcore-integration`: 内核版本提升至 sing-box v1.14.1 后的 Go/JNI 接驳要求——平台接口集合、ruleset Tag 处理、XHTTP 移植层 1.14 API 适配、sc_* 范围对象序列化契约、urlTest HTTP/2 探测与 protect 通道加固要求。
- `android-application`: 配置生成 MUST 按 1.14 官方 schema 发射——typed DNS servers、fakeip 服务器化、dns-block→reject、TLS fragment detour、REALITY uTLS 默认指纹、Hysteria QUIC 韧性字段与 hopPorts/hop_interval 规范化。

## Impact

- **构建链**：`nb4a.properties`（SINGBOX_VERSION=v1.14.1）、`libcore/go.mod`/`go.sum`（依赖族上移，构建时由 `buildScript/lib/core/get_source.sh` 检出官方 tag 到 `../../sing-box`）；本地不执行 Go 编译，构建验证交 GitHub Actions。
- **libcore（Go）**：`box.go`、`platform_box.go`、`ruleset.go`、`dns_box.go`、`protect.go`、`nb4a.go`、`speedtest.go`、`protocol/vless/**`（xhttp 移植层）。
- **Android（Kotlin/Java）**：`SingBoxOptions.java`（DNS server options 重写、Outbound_HysteriaOptions 新增字段、XHTTPOptions sc_* 处理）、`ConfigBuilder.kt`（buildDnsServer、fakeip、dns 规则、TLS fragment）、`V2RayFmt.kt`、`HysteriaFmt.kt`、`SingBoxOptionsUtil.kt` 及相关单测。
- **外部依赖官方来源**：`SagerNet/sing-box` v1.14.1 tag（schema 与 API 的唯一权威），参考实现 `Own716/OwnBoxForAndroid`（1.14 适配提交 `9160df5d`、`a5c0685`、`249ca740`，内核期间修复 `2d395981`、`dcd78681`、`0c604172`；1.15 迁移提交 `8bb680f8` 用于反向确认边界）。
- **风险**：1.14 是破坏性升级（DNS schema、fakeip、平台接口全变）；OwnBox 的 Room/DB、测速语义等无关分叉不得带入；T4A 的 XHTTP 移植层需在 1.14 API 下重新验证。
