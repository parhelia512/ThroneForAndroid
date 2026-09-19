# Spec Delta

## MODIFIED Requirements

### Requirement: sing-box 配置符合官方 schema

配置生成 MUST 以当前 `SINGBOX_VERSION`（sing-box v1.14.1）对应的官方 schema 为准。入站嗅探与目标解析 MUST 使用位于路由规则前部的 `sniff`/`resolve` 动作；TUN 地址 MUST 使用合并后的 `address` 字段；双网络加速 MUST 映射为 `default_network_strategy: "hybrid"`。已被官方移除的 legacy 字段 MUST NOT 被发射，包括但不限于：顶层 `dns.fakeip`（改为 typed fakeip DNS server）、`dns-block` server 类型（改为 `action: "reject"` 规则）、独立 TLS fragment outbound（改为主出站 detour 注入）、XHTTP extra 中的 `encryption` 字段。DNS 服务器 MUST 按 1.14 typed schema（`type`/`server`/`server_port`/`path`/`domain_resolver`/`domain_strategy`）发射，而非旧式 `address/address_resolver/address_strategy/address_fallback_delay` 字符串字段。

#### Scenario: 生成正式连接配置

- **GIVEN** 用户启用流量嗅探、IPv6 或双网络加速等设置
- **WHEN** `ConfigBuilder` 生成 sing-box 配置
- **THEN** 输出可被 sing-box v1.14.1 官方内核 schema 接受
- **AND** 不包含已移除的入站 sniff、legacy TUN 地址或 `route.concurrent_dial` 字段

#### Scenario: 1.14 移除字段不出现

- **GIVEN** 用户启用 FakeDNS、DNS 拦截或 TLS 分片
- **WHEN** 生成连接配置
- **THEN** FakeDNS 以 `type: "fakeip"` 的 DNS server 形式出现且无顶层 `dns.fakeip`
- **AND** DNS 拦截以 `action: "reject"` 的 DNS 规则形式出现且无 `dns-block` server 类型
- **AND** TLS 分片不生成独立 outbound

### Requirement: 协议连通性兼容修复

Hysteria 节点的 SNI 为空时 MUST 回退使用服务器地址作为 TLS server_name；h2 协议版本的 ALPN MUST 从节点配置解析而非硬编码；Hysteria 与 TUIC 的 outbound TLS MUST 启用 UDP 分片；TUIC 的 SNI 回退 MUST 尊重用户的禁用 SNI 设置。Hysteria 多端口首端口提取 MUST 兼容 `port-range`（`-` 分隔）格式；端口跳跃列表为空时 MUST 回退取多端口配置的首端口，且端口跳跃生效时 `hop_interval` MUST 不低于 15 秒（未配置时默认 30 秒）。Shadowsocks v2ray-plugin 的 plugin_opts MUST 显式包含 `mux=0`（用户未显式配置 mux 时），空 plugin_opts MUST 置空而非发射空串。vmess/vless 链接 query 中包含未编码特殊字符（花括号、引号、空格、竖线、反斜杠、尖括号等）时 MUST 经百分号编码重试解析并回退 URI 解析，解析失败报告可读错误；kcp headerType 非法值 MUST 回退 `none` 而非中断解析；xhttp extra 参数转换失败 MUST 保留原文。REALITY 节点在用户未配置有效 uTLS 指纹（空、`none`、`random`、`randomized`）时 MUST 默认使用 `chrome` 指纹；REALITY 的 public_key 与 short_id MUST 经 trim 与规范化后发射。xhttp/splithttp 传输在未配置 ALPN 时 MUST 发射 `h2` 与 `http/1.1`，并 MUST 发射 `no_grpc_header`。Hysteria outbound MUST 发射 QUIC 韧性参数：keep-alive 周期 15 秒、idle 超时 30 秒、按节点配置发射接收窗口与路径 MTU 探测禁用项，并使用标准 BBR 配置。

#### Scenario: SNI 为空的 Hysteria2 节点

- **GIVEN** 一条未填写 SNI 的 Hysteria2 节点
- **WHEN** 应用生成连接配置
- **THEN** TLS server_name 使用服务器地址
- **AND** 配置可被官方内核接受

#### Scenario: v2ray-plugin 未配置 mux

- **GIVEN** 一条使用 v2ray-plugin 且未显式配置 mux 的 Shadowsocks 节点
- **WHEN** 应用生成连接配置
- **THEN** plugin_opts 包含 `mux=0`
- **AND** 握手不因缺失 mux 参数而异常

#### Scenario: 带特殊字符 query 的 vless 链接

- **GIVEN** 一条 query 参数包含未编码花括号或引号的 vless 分享链接
- **WHEN** 应用解析该链接
- **THEN** 经百分号编码重试后成功解析出节点参数
- **AND** 不产生未处理异常

#### Scenario: 非法 kcp headerType

- **GIVEN** 一条 kcp 传输且 headerType 值不受支持的分享链接
- **WHEN** 应用解析该链接
- **THEN** headerType 回退为 `none`
- **AND** 节点其余参数正常解析

#### Scenario: REALITY 节点未配置 uTLS 指纹

- **GIVEN** 一条启用 REALITY 且 uTLS 指纹为空或 `none` 的 vless 节点
- **WHEN** 应用生成连接配置
- **THEN** uTLS 指纹默认发射 `chrome`
- **AND** REALITY 的 public_key 与 short_id 经 trim 后发射

#### Scenario: 端口跳跃列表为空的 Hysteria 节点

- **GIVEN** 一条 serverPorts 规范化后端口跳跃列表为空、但多端口配置非空的 Hysteria 节点
- **WHEN** 应用生成连接配置
- **THEN** outbound 使用多端口配置的首端口作为 server_port
- **AND** 不发射空的 server_ports 与无效的 hop_interval

#### Scenario: 端口跳跃生效时的 QUIC 韧性参数

- **GIVEN** 一条端口跳跃列表非空且未自定义 hop_interval 的 Hysteria2 节点
- **WHEN** 应用生成连接配置
- **THEN** `hop_interval` 为 30 秒且 `keep_alive_period` 为 15 秒、`idle_timeout` 为 30 秒
- **AND** 按节点设置发射接收窗口、路径 MTU 探测与标准 BBR 配置字段

#### Scenario: xhttp 传输缺省 ALPN

- **GIVEN** 一条 type 为 xhttp 且未配置 ALPN 的节点
- **WHEN** 应用生成连接配置
- **THEN** TLS ALPN 为 `["h2", "http/1.1"]`
- **AND** XHTTP transport 发射 `no_grpc_header`

## ADDED Requirements

### Requirement: DNS 服务器按 typed schema 生成

配置生成 MUST 将用户与系统 DNS 设置解析为 sing-box v1.14 typed DNS server 对象：`tls://`、`quic://`、`h3://`、`https://`、`tcp://`、`local`、`hosts` 等旧式 address 字符串 MUST 分别映射为对应 `type` 的 DNS server（含端口与路径字段）。DNS server 地址为非 IP 域名时 MUST 生成该 server 的 `domain_resolver`/`domain_strategy` 引用，保证无循环引导。FakeDNS MUST 以 `type: "fakeip"` 的 DNS server 形式发射（含 `inet4_range`，IPv6 启用时含 `inet6_range`），对应的 DNS 规则 MUST 以 server 引用指向该 fakeip server。原 `dns-block` server 语义 MUST 以 `action: "reject"` 的 DNS 规则实现。被拦截或拒绝类 DNS 规则 MUST 携带显式 `action` 字段。

#### Scenario: 远程 DoH 服务器设置

- **GIVEN** 用户远程 DNS 设置为 `https://dns.example/dns-query`（域名为非 IP）
- **WHEN** 生成连接配置
- **THEN** 该 DNS 以 `type: "https"` 的 typed server 出现，携带 server 与 path 字段
- **AND** 该 server 携带指向可用引导解析的 `domain_resolver` 引用
- **AND** 不出现旧式 `address_resolver`/`address_strategy` 字段

#### Scenario: FakeDNS 启用

- **GIVEN** 用户启用 FakeDNS 且 IPv6 模式为自动
- **WHEN** 生成连接配置
- **THEN** `dns.servers` 中存在 `type: "fakeip"` 的 server 且含 `inet4_range` 与 `inet6_range`
- **AND** fakeip DNS 规则以 server 名引用该 server，无顶层 `dns.fakeip`

#### Scenario: DNS 拦截规则

- **GIVEN** 用户的路由/DNS 设置中存在广告拦截类 DNS 规则
- **WHEN** 生成连接配置
- **THEN** 拦截以带 `action: "reject"` 的 DNS 规则发射
- **AND** 不发射 `dns-block` 类型的 DNS server

### Requirement: TLS 分片经主出站 detour 注入

启用 TLS 分片时，配置生成 MUST 将分片选项注入到启用分流的代理出站链路上（经由 detour 关联），MUST NOT 再生成独立的 fragment outbound。分片延迟 MUST 按用户配置的区间字符串（逗号分隔多段时取首段）解析出确定的 delay 值发射；无法解析时 MUST 回退默认值而非生成非法配置。

#### Scenario: 启用 TLS 分片生成配置

- **GIVEN** 用户启用 TLS 分片并配置区间如 `10-20,30-40`
- **WHEN** 生成连接配置
- **THEN** 分片选项挂在启用分流的出站 detour 链路上且 delay 取首段解析值
- **AND** 配置中不存在独立的 fragment outbound

### Requirement: XHTTP 范围字段按 1.14 内核序列化

XHTTP 配置中的 `sc_max_each_post_bytes`、`sc_min_posts_interval_ms`、`sc_stream_up_server_secs` 等范围类字段 MUST 以 `{"from": N, "to": N}` 对象形式发射；用户配置为纯数字时 MUST 自动包装为等值范围对象，已是范围对象的 MUST 保持原样。sing-box 1.12 起已移除的 `encryption` 字段 MUST 在合并 extra 时静默丢弃。范围类字段 MUST NOT 以纯数字形式发射到最终配置。

#### Scenario: 用户 extra 配置纯数字范围字段

- **GIVEN** 一条 xhttp 节点的 extra 配置中 `sc_max_each_post_bytes` 为纯数字
- **WHEN** 应用生成连接配置
- **THEN** 该字段发射为 `{"from": N, "to": N}` 形式
- **AND** sing-box v1.14.1 内核解析不报 `cannot unmarshal number` 错误

#### Scenario: extra 携带已废弃 encryption 字段

- **GIVEN** 一条 xhttp 节点的 extra 配置中包含 `encryption` 字段
- **WHEN** 应用生成连接配置
- **THEN** `encryption` 被静默丢弃
- **AND** 其余合法 extra 字段正常合并
