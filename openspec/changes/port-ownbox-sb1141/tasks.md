# Tasks

## 1. 批次一：libcore 1.14.1 编译适配

- [ ] 1.1 联网核对 `SagerNet/sing-box` 官方 `v1.14.1` tag 的 go.mod 依赖族与 hysteria 端口区间语法（`start:end`/`start-end`），将结论记录到本 change 的 design.md 附录（修正 Open Questions），证据：tag go.mod 关键行摘录
- [ ] 1.2 `nb4a.properties` 的 `SINGBOX_VERSION` 改为 `v1.14.1`，`libcore/go.mod` 依赖族按官方 tag 对齐（sing/quic-go/sing-tun/sing-mux 等），保留 T4A 特有依赖；验证：`go.mod` 注释与 `nb4a.properties` 一致、无 fork 来源
- [ ] 1.3 适配 `libcore/box.go`：ResetNetwork 新签名、CertificateProviderRegistry 注册、selector 兜底扫描（对照 OwnBox 最终态），保持 T4A 既有 urlTest 两阶段语义；验证：代码走查对照 spec「内核升级至 sing-box v1.14.1 保持官方接入契约」
- [ ] 1.4 适配 `libcore/platform_box.go`（1.14 平台回调补齐 + 不支持项空实现）、`libcore/ruleset.go`（RuleSet.Tag 类型）、`libcore/dns_box.go`；验证：对照 spec「平台接口按 box 实例隔离」MODIFIED 增量
- [ ] 1.5 适配 `libcore/protocol/vless/**` XHTTP 移植层（qtls.Dial 新签名等）与自定义 outbound（负载均衡等）在 1.14 registry 的注册；验证：代码走查无 1.13 遗留 API 引用
- [ ] 1.6 提交批次一并推送，触发 GitHub Actions 内核构建 workflow；验证：libcore AAR 构建成功，回传 CI run 链接与结论；失败则在本批次内修复后重推

## 2. 批次二：配置生成 1.14 schema（DNS/fakeip/reject/fragment）

- [ ] 2.1 `SingBoxOptions.java`：`DNSServerOptions` 重写为 typed 字段（type/server/server_port/path/domain_resolver/domain_strategy）、DNS 规则增加 action/rcode、fakeip server 选项（inet4/inet6_range）；验证：字段与官方 1.14.1 schema 一致
- [ ] 2.2 `ConfigBuilder.kt` 新增 `buildDnsServer()` 工厂：解析 `tls://`、`quic://`、`h3://`、`https://`、`tcp://`、`local`、`hosts` 为 typed server，非 IP 地址补 domain_resolver/strategy；验证：新增 JVM 单测覆盖每种 scheme 与引导解析
- [ ] 2.3 fakeip 迁移（dns.servers 中 type:"fakeip" + 规则 server 引用）与 dns-block→`action:"reject"` 规则改造；验证：JVM 单测断言无顶层 `dns.fakeip`、无 dns-block server
- [ ] 2.4 TLS fragment 改为主出站 detour 注入（区间首值解析 delay，失败回退默认）；验证：JVM 单测断言无独立 fragment outbound 且 delay 取首段
- [ ] 2.5 跑全量 JVM 单测（`gradlew :app:testDebugUnitTest` 或等价本地可跑子集）+ 提交推送触发 CI 构建；验证：本地单测通过、CI APK 构建成功，回传证据

## 3. 批次三：1.14 必改项与协议增强

- [ ] 3.1 `V2RayFmt.kt`/`XhttpExtraConverter.kt`：RANGE_KEYS sc_\* 字段纯数字包装为 `{"from": N, "to": N}` 对象、丢弃 `encryption`、xhttp 无 ALPN 时发射 `["h2","http/1.1"]` 与 `no_grpc_header`；验证：JVM 单测断言发射 JSON 形态（对应 spec「XHTTP 范围字段按 1.14 内核序列化」）
- [ ] 3.2 `V2RayFmt.kt` TLS/REALITY：security/realityPubKey 判定扩展、REALITY 下 uTLS 默认 `chrome`（排除 none/random/randomized）、sni/short_id trim 规范化；验证：JVM 单测（对应 spec「协议连通性兼容修复」REALITY 场景）
- [ ] 3.3 `HysteriaFmt.kt` + `SingBoxOptions.Outbound_HysteriaOptions`：hopPorts 空回退 `getFirstPort`、hop_interval 下限 15s 默认 30s、keep_alive_period/idle_timeout/receive windows/disable_path_mtu_discovery/bbr_profile 字段发射（格式按任务 1.1 核对结论）；验证：JVM 单测（对应 spec Hysteria 两个场景）
- [ ] 3.4 libcore urlTest 增强：HTTP/2 transport、HEAD 不兼容 GET 重试、5xx 判失败、primary/fallback 超时拆分、urltest 关闭节流 GC；protect 加固（绝对路径、chmod、3 次重试、2s 超时）；验证：Go 侧代码走查 + CI 编译通过（对应 spec「URL 延迟预热探测启用 HTTP/2 并具备回退」）
- [ ] 3.5 跑全量 JVM 单测 + 提交推送触发 CI 构建；验证：本地单测通过、CI 构建 AAR+APK 成功，回传证据

## 4. 真机回归与规范同步

- [ ] 4.1 真机场景验证：正式连接（普通节点/链式）、FakeDNS、DNS 拦截、TLS 分片、xhttp 节点连接（含 extra 纯数字 sc 字段）、Hysteria2 端口跳跃、URL 延迟测试与备用 URL 回退；证据：连接成功截图/日志 + 异常场景的内核报错为空
- [ ] 4.2 回归订阅更新与节点去重（确认 1.14 升级未破坏既有订阅链路）；证据：批量更新成功汇总
- [ ] 4.3 运行 `openspec validate --change port-ownbox-sb1141 --strict` 并按仓库流程同步/归档 spec 增量；证据：validate 通过、archive 记录
