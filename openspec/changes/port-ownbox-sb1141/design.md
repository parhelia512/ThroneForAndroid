# Design

## Context

T4A 当前内核 v1.13.16（`nb4a.properties` 唯一版本来源），libcore 经 `replace ../../sing-box` 指向 CI 检出的官方 tag。OwnBoxForAndroid（Own v2.6.0，sing-box v1.14.0）已完成 1.14 适配全套（提交 `9160df5d`、`a5c0685`），且本轮增量扫描（`ROO_OWN_RESEARCH.md` 第 0B 章）确认其 1.15 迁移提交 `8bb680f8` 适配面极小，反向证明其 1.14 适配层完整可用；OwnBox 从未使用过 1.14.1，但 1.14.1 是 1.14 稳定线的补丁版本，1.14.0 的适配结论直接适用。OwnBox 在 1.14.0 内核期间又累积了 sc_\* 范围对象、urlTest HTTP/2、loadbalance 粘性等实测修复，这些与 1.14.1 兼容。

本地无 Go/Android 编译环境，编译与真机验证全部交给 GitHub Actions；实现采用快速滚动验证（一个批次 → CI/真机反馈 → 下一批次）。

## Goals / Non-Goals

**Goals:**

- `SINGBOX_VERSION` 提升到 `v1.14.1`，libcore 与官方 1.14.1 编译通过、真机可建立连接。
- 配置生成按 1.14 schema 输出（typed DNS、fakeip server 化、reject 动作、fragment detour）。
- 移植 OwnBox 1.14 期间的必改项（sc_\* 范围对象等）与低风险增强（urlTest HTTP/2、protect 加固）。
- 保持 T4A 自有能力（XHTTP 移植层、Throne 测速、WireGuard endpoint 迁移成果）在 1.14 下语义不变。

**Non-Goals:**

- 不引入 OwnBox 1.15 专属改动（`ConnectionOwner.PackageNames` 改名、AutoRedirect、Sing-Tun 官方栈、1.15 依赖族、xhttp 2.8.0 重写）。
- 不移植 OwnBox 的品牌/主题/UI 产品向改动（主题重构、仪表盘、局域网共享、Docs 等）。
- 不移植 OwnBox 的 DB 迁移（Room v10）、BalancerBean 独立节点、测速方案替换（与本升级无依赖关系的另行立项）。
- 不改动 `SINGBOX_VERSION` 以外的版本体系（versionCode 等维持 T4A 自己的规则）。

## Decisions

1. **以 OwnBox 最终状态 diff 为参考、逐功能点摘取，而非 cherry-pick 提交**。OwnBox 大量提交是"修自己上一个提交引入的编译错误"，且其仓库不含 T4A 的 SpeedTestRunner、XhttpExtraConverter 等（分叉丢失），直接 cherry-pick 会带入编译破坏。参考基线：1.14 适配取 `9160df5d`+`a5c0685` 的最终态，1.14 内核期间修复取 `4372435..8bb680f8` 区间的最终态 diff。
2. **go.mod 依赖族以官方 v1.14.1 tag 的 go.mod 为唯一权威**，OwnBox v2.6.0 的 go.mod（sing v0.9.0-beta.4、quic-go v0.61.0-sing-box-mod.7、sing-tun v0.9.0-beta.4 等）仅作交叉校验；T4A 特有依赖（speedtest-go、sing-juicity v0.1.6、stun 等）保持现状，编译冲突时按官方 tag 的版本约束解决。
3. **DNS schema 迁移在 Kotlin 侧一次性完成**：`SingBoxOptions.java` 的 `DNSServerOptions` 重写为 typed 字段，`ConfigBuilder` 新增 `buildDnsServer()` 工厂解析旧式 address 字符串；fakeip 从顶层选项迁到 `dns.servers` 中 `type:"fakeip"` server。选择 Kotlin 侧转换（而非 Go 侧兜底）是因为官方 1.14 已删除旧字段解析，配置必须在发射前就是新 schema。
4. **TLS fragment 改为主出站 detour 注入**：1.14 生态下独立 fragment outbound 的做法被参考实现废弃；fragment delay 按逗号区间首值解析，无法解析时回退默认值。
5. **sc_\* 范围对象在 `V2RayFmt`/`XhttpExtraConverter` 的合并出口统一包装**（RANGE_KEYS 集合），而不是在 `SingBoxOptions` 改字段类型为强类型 Range——用出口包装可同时覆盖 extra 合并与直接赋值两条路径，且升级 1.15 时只需移除 RANGE_KEYS 一处（OwnBox 1.15 迁移正是这么做的）。
6. **urlTest HTTP/2 与 GET 回退、protect 加固直接移植 OwnBox 最终态**：这两项与内核版本无强绑定但属低风险高价值增强，随升级批次一并做可减少一轮单独的 CI 验证。urlTest 保持 T4A 现有两阶段 Keep-Alive 语义（两边已趋同，见研究文档 0.1），只叠加 HTTP/2、GET 重试、超时拆分与节流 GC。
7. **验证靠官方 schema 单测 + CI 构建**：为 DNS typed 转换、fakeip、reject 规则、sc_\* 包装、Hysteria 字段、REALITY 指纹补 JVM 单测（不依赖内核）；Go 侧编译与真机连接验证交给 GitHub Actions 与用户真机。

## Risks / Trade-offs

- [1.14 破坏性 API 变更面大（DNS schema、fakeip、ruleset Tag、平台接口、qtls 签名）] → 分批次推进：先 libcore 编译适配批次，再配置生成批次，再协议修复批次；每批次过一次 CI。
- [T4A XHTTP 移植层基于 1.13 API，1.14 下行为未知] → `qtls.Dial` 签名修复 + sc_\* 包装 + no_grpc_header 后，用 `libcore/vless_xhttp_test.go` 与真机 xhttp 节点验证；不移植 OwnBox 的 2.8.0 xhttp 重写（1.15 基线）。
- [OwnBox 1.14 依赖族与 T4A 现有依赖冲突（如 speedtest-go、gvisor 版本）] → 以官方 1.14.1 tag 为准解析依赖；冲突时优先保官方一致性，speedtest-go 升级单独验证测速语义。
- [1.14 内核对 sc_\* 纯数字的拒绝只在实际连接时暴露] → JVM 单测断言发射 JSON 形态 + 真机 xhttp 节点连接验证双保险。
- [hopPorts 输出格式在 1.13/1.14 语义间的差异（`start-end` vs `start:end`）] → 实现前以官方 1.14.1 源码的 hysteria outbound 解析为准（联网核对），单测锁定格式。
- [Doze/自动恢复等 BaseService 改动超出本 change spec 范围] → 本 change 不含 0B.3 的 cache.db 自动恢复与 Doze 行为修改（未列入 specs），如需要另立 change，避免一次 CI 批次混入无法独立验证的行为。

## Migration Plan

1. 批次一（libcore 编译适配）：`nb4a.properties`、`go.mod`、`box.go`、`platform_box.go`、`ruleset.go`、`dns_box.go`、xhttp 移植层 1.14 API → CI 构建 AAR 通过。
2. 批次二（配置生成 schema）：`SingBoxOptions.java` DNS/Hysteria 字段、`ConfigBuilder` typed DNS/fakeip/reject/fragment detour → JVM 单测 + CI 构建 APK 通过。
3. 批次三（协议与增强）：sc_\* 包装、REALITY 指纹、Hysteria 韧性字段、urlTest HTTP/2、protect 加固 → JVM 单测 + CI 构建。
4. 真机验证：正式连接（含 FakeDNS、DNS 拦截、TLS 分片、xhttp、Hysteria2 端口跳跃）、URL 延迟测试、订阅更新回归。
5. 回滚：`SINGBOX_VERSION` 回退 `v1.13.16` 并 revert libcore/配置层提交即可，无数据迁移。

## Open Questions

（无 —— 已在批次一任务 1.1 核对完毕，见附录 A。）

## 附录 A：官方 sing-box v1.14.1 核对结论（任务 1.1 证据）

来源：`https://raw.githubusercontent.com/SagerNet/sing-box/v1.14.1/go.mod`、`protocol/hysteria2/outbound.go`、`SagerNet/sing-quic v0.7.0`（`hysteria/client.go` 的 `ParsePorts`、`hysteria2/client.go`）。

### A.1 官方 v1.14.1 go.mod 关键依赖族（go 1.25.5）

| 依赖 | 官方 v1.14.1 | T4A 现状（1.13.16） | OwnBox v2.6.0（1.14.0） |
|---|---|---|---|
| `sagernet/sing` | **v0.9.4** | v0.8.12-* | v0.9.0-beta.4 |
| `sagernet/quic-go` | **v0.61.0-sing-box-mod.7** | v0.59.0-sing-box-mod.4 | v0.61.0-sing-box-mod.7 |
| `sagernet/sing-tun` | **v0.9.3** | v0.8.12-* | v0.9.0-beta.4 |
| `sagernet/sing-quic` | **v0.7.0** | （未直接 require） | v0.7.0 |
| `sagernet/sing-mux` | **v0.3.6** | — | v0.3.5 |
| `sagernet/sing-vmess` | **v0.2.8** | — | v0.2.8-* |
| `sagernet/sing-anytls` | **v0.0.11** | — | v0.0.11 |
| `sagernet/sing-snell` | v0.0.0-20260829071736-20f2eaec77c3 | — | 同 |
| `sagernet/wireguard-go` | **v0.0.6** | — | v0.0.6 |
| `sagernet/gvisor` | v0.0.0-20260727.0-sing-box-mod.1 | — | 同 |
| `sagernet/netlink` | v0.0.0-20260814022025-64455d367bbf | — | 同 |
| `sagernet/nftables` | v0.3.0-mod.4 | — | 同 |
| `sagernet/smux` | v1.5.50-sing-box-mod.1 | — | 同 |
| `miekg/dns` | v1.1.72 | v1.1.72 | v1.1.72 |
| `oschwald/maxminddb-golang` | v1.13.1 | v1.13.1 | v1.13.1 |
| `golang.org/x/net` | v0.57.0 | v0.50.0 | — |
| `golang.org/x/sys` | v0.47.0 | v0.41.0 | — |
| `golang.org/x/crypto` | v0.54.0 | — | v0.54.0 |
| `google.golang.org/grpc` | v1.79.1 | — | — |
| `sagernet/tailscale` | v1.102.1-sing-box-1.14-mod.5 | — | — |

结论：T4A 的 go.mod 依赖族按官方 v1.14.1 上移（sing 0.8.x→0.9.4、quic-go→mod.7、sing-tun→0.9.3、x/net→0.57.0、x/sys→0.47.0），并需补 `sing-quic v0.7.0`（1.14 的 hysteria/hysteria2/tuic 已迁入 sing-quic 包）。OwnBox v2.6.0 的 beta 版本号（sing v0.9.0-beta.4、sing-tun v0.9.0-beta.4）仅为其当时快照，以官方 tag 为准。

### A.2 Hysteria 端口区间语法（1.14.1 实测源码）

- `sing-quic/hysteria/client.go` 的 `ParsePorts`：**必须包含 `:`**（`strings.Contains(portRange, ":")`，否则报 `bad port range`），即 `start:end` 格式（如 `20820:20830`）；`start` 缺省为 0、`end` 缺省为 65535。
- `hysteria2.NewClient` 同样经 `hysteria.ParsePorts` 解析 `ServerPorts`。
- **hop interval 下限为 5 秒**（`hop.go`：`interval < 5*time.Second` 报错；`interval==0 && intervalMax==0` 时默认 30s）。OwnBox 的 15s 下限/30s 默认满足且更保守，采用之。
- 1.14.1 `Hysteria2OutboundOptions` 确认支持：`IdleTimeout`、`KeepAlivePeriod`、`StreamReceiveWindow`、`ConnectionReceiveWindow`、`MaxConcurrentStreams`、`InitialPacketSize`、`DisablePathMTUDiscovery`、`BBRProfile`（`protocol/hysteria2/outbound.go` 直接映射到 `qtls.QUICOptions`）——与 OwnBox 新增的 6 个 SingBoxOptions 字段一一对应。
- T4A 的 `hopPortsToSingboxList` 输出格式 MUST 为 `start:end`（与 OwnBox v2.6.0 一致；上版报告记录的 `start-end` 为旧内核语义，已废弃）。
