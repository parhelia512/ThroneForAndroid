# Tasks

## 1. 批次一：libcore 1.14.1 编译适配

- [x] 1.1 联网核对 `SagerNet/sing-box` 官方 `v1.14.1` tag 的 go.mod 依赖族与 hysteria 端口区间语法（`start:end`/`start-end`），将结论记录到本 change 的 design.md 附录（修正 Open Questions），证据：tag go.mod 关键行摘录（已写入 design.md 附录 A）
- [x] 1.2 `nb4a.properties` 的 `SINGBOX_VERSION` 改为 `v1.14.1`，`libcore/go.mod` 依赖族按官方 tag 对齐（sing/quic-go/sing-tun/sing-mux 等），保留 T4A 特有依赖；验证：`go.mod` 注释与 `nb4a.properties` 一致、无 fork 来源（sing v0.9.4 / quic-go mod.7 / sing-tun v0.9.3 / sing-quic v0.7.0 / sing-vmess v0.2.8 / sing-juicity v0.3.0 / x/net v0.57.0 / x/sys v0.47.0，go 1.25.5）
- [x] 1.3 适配 `libcore/box.go`：ResetNetwork 新签名、CertificateProviderRegistry 注册、selector 兜底扫描（对照 OwnBox 最终态），保持 T4A 既有 urlTest 两阶段语义；验证：代码走查对照 spec「内核升级至 sing-box v1.14.1 保持官方接入契约」通过
- [x] 1.4 适配 `libcore/platform_box.go`（1.14 平台回调补齐 + 不支持项空实现）、`libcore/ruleset.go`（RuleSet.Tag []string 取首元素）、`libcore/dns_box.go`（ExchangeAsync）；验证：对照 spec「平台接口按 box 实例隔离」MODIFIED 增量通过，与 OwnBox 1.14 适配 diff 逐项一致
- [x] 1.5 适配 `libcore/protocol/vless/**` XHTTP 移植层（qtls.Dial 四参新签名、移除 UnbindPacketConn 包装、Go 侧 Range 反序列化兼容纯数字/单值字符串）与测试文件 certificate registry 注册；验证：静态检查无 1.13 遗留 API 引用（NewUnbindPacketConn/无参 ReadWIFIState/无参 ResetNetwork 均已清除）
- [x] 1.6 提交批次一并推送，触发 GitHub Actions 内核构建 workflow；验证：libcore AAR 构建成功，回传 CI run 链接与结论；失败则在本批次内修复后重推（提交 865151a 已推送，用户确认 CI 通过）

## 2. 批次二：配置生成 1.14 schema（DNS/fakeip/reject/fragment）

- [x] 2.1 `SingBoxOptions.java`：`DNSServerOptions` 重写为 typed 字段（type/server/server_port/path/domain_resolver/domain_strategy/inet4_range/inet6_range）、DNS 规则增加 action/rcode、移除顶层 `dns.fakeip` 字段；验证：字段与官方 1.14.1 schema 及 OwnBox 适配一致
- [x] 2.2 `ConfigBuilder.kt` 新增顶层 `buildDnsServer()` 工厂：解析 `tls://`、`quic://`、`h3://`、`https://`、`tcp://`、`udp://`、`local`、`hosts` 为 typed server，非 IP 地址补 domain_resolver/domain_strategy；验证：新增 `ConfigBuilderDnsTest`（12 用例覆盖各 scheme/端口/IPv6 方括号/IP 跳过引导）
- [x] 2.3 fakeip 迁移（dns.servers 中 type:"fakeip" + 规则 server 引用）、hosts server 改 typed `type:"hosts"`、dns-block→`action:"reject"` 规则转换（用户规则出口统一转换）；验证：静态检查无 `dns.fakeip`/`rcode://`/legacy 字段残留，单测断言 typed 序列化无 address/address_resolver/strategy 键
- [x] 2.4 TLS fragment 验证：T4A 现状已是主出站 TLS 内联 fragment（无独立 fragment outbound），`tlsFragmentFallbackDelay` 重构出纯函数 `parseFragmentFallbackDelay`（区间首值 + 回退默认）并纳入单测；验证：单测覆盖首值/多段/空串/非法输入
- [x] 2.5 跑全量 JVM 单测（`gradlew :app:testDebugUnitTest` 或等价本地可跑子集）+ 提交推送触发 CI 构建；验证：本地单测通过、CI APK 构建成功，回传证据（本地无 Android SDK，单测交由 CI 执行；两轮修复 action 字段重复声明与 fragment delay 测试断言后，用户确认 CI 通过，122 单测全绿）

## 3. 批次三：1.14 必改项与协议增强

- [x] 3.1 `V2RayFmt.kt`：extra 合并出口 RANGE_KEYS sc_\* 字段纯数字包装为 `{"from": N, "to": N}` 对象、BLOCKED_KEYS 丢弃 `encryption`、xhttp baseConfig 恒发射 `no_grpc_header=true`、无 ALPN 时 TLS 补 `["h2","http/1.1"]`；验证：新增 JVM 单测 `xhttpConfigWrapsPlainScFieldsAndDropsEncryption`（sing-box 原生格式 extra 透传路径）
- [x] 3.2 `V2RayFmt.kt` TLS/REALITY：security 判定扩展接受 `reality`/`realityPubKey` 非空、REALITY 下 uTLS 默认 `chrome`（排除 none/random/randomized）、sni trim、public_key/short_id trim+小写规范化；验证：代码走查对照 spec「协议连通性兼容修复」REALITY 场景
- [x] 3.3 `HysteriaFmt.kt` + `SingBoxOptions.Outbound_Hysteria2Options`：hopPorts 空回退 `getFirstPort`、`resolveHopInterval` 下限 15s 默认 30s、keep_alive_period=15s/idle_timeout=30s/stream+connection_receive_window/disable_path_mtu_discovery/bbr_profile="standard" 发射；验证：新增 JVM 单测（hopPorts 冒号格式、hopInterval 决策）
- [x] 3.4 libcore urlTest 增强：HTTP/2 transport（NextProtos h2+http/1.1 + ForceAttemptHTTP2 + ConfigureTransport）、HEAD 不兼容 GET 重试预热、>=500 判失败、primary/fallback 超时拆分 + cloudflare↔gstatic 互备、urltest 关闭节流 GC（2s 窗口）；protect 加固（GetProtectSocketPath 绝对路径、chmod 0666、3 次立即重试——按用户反馈去除 sleep 等待，保留原 100ms socket 超时不引入 2s 阻塞）；验证：Go 侧代码走查 + CI 编译
- [ ] 3.5 跑全量 JVM 单测 + 提交推送触发 CI 构建；验证：本地单测通过、CI 构建 AAR+APK 成功，回传证据

## 4. 真机回归与规范同步

- [ ] 4.1 真机场景验证：正式连接（普通节点/链式）、FakeDNS、DNS 拦截、TLS 分片、xhttp 节点连接（含 extra 纯数字 sc 字段）、Hysteria2 端口跳跃、URL 延迟测试与备用 URL 回退；证据：连接成功截图/日志 + 异常场景的内核报错为空
- [ ] 4.2 回归订阅更新与节点去重（确认 1.14 升级未破坏既有订阅链路）；证据：批量更新成功汇总
- [ ] 4.3 运行 `openspec validate --change port-ownbox-sb1141 --strict` 并按仓库流程同步/归档 spec 增量；证据：validate 通过、archive 记录
