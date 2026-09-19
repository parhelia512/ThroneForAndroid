# Spec Delta

## MODIFIED Requirements

### Requirement: 平台接口按 box 实例隔离

每个 sing-box 实例 MUST 创建独立的 `boxPlatformInterfaceWrapper`，其 NetworkManager、TUN 名称和接口状态 MUST NOT 在并发实例之间共享。平台实现 MUST 提供 TUN 打开、fd protect、连接属主查询、网络接口枚举和默认接口监视器，以满足官方拨号路径。针对当前 `SINGBOX_VERSION`（sing-box v1.14.1）官方平台接口的演进，平台实现 MUST 补齐 1.14 新增的平台回调（进程归属选项、通知取消、WIFI 状态读取），并对官方新增但 T4A 不支持的平台能力（Neighbor、Shell、Bridge 等）提供显式空实现，使 wrapper 完整实现官方接口而无需修改官方内核源码。

#### Scenario: 正式连接与测试实例并存

- **GIVEN** 主服务运行且多个测试 box 并发启动
- **WHEN** 各 box 初始化或收到默认接口更新
- **THEN** 更新作用于该 box 自己的 NetworkManager
- **AND** 各 box 都能选择可用物理接口

#### Scenario: 1.14 平台接口完整性

- **GIVEN** sing-box v1.14.1 官方平台接口新增了进程归属选项、通知取消、WIFI 状态读取回调，并扩展了 Neighbor/Shell/Bridge 等能力接口
- **WHEN** libcore 以官方源码编译且不修改官方内核
- **THEN** 平台 wrapper 实现全部新增回调（支持项返回真实数据，不支持项返回明确的空实现）
- **AND** 编译不因接口未实现而失败，运行不因空实现而 panic

## ADDED Requirements

### Requirement: 内核升级至 sing-box v1.14.1 保持官方接入契约

内核版本 MUST 由 `nb4a.properties` 的 `SINGBOX_VERSION=v1.14.1` 指定，构建时由既有脚本从 `SagerNet/sing-box` 官方 tag 检出源码，MUST NOT 引入 fork 或第二版本来源。`libcore/go.mod` 的依赖族（sing、quic-go、sing-tun、sing-mux 等）MUST 与官方 1.14.1 tag 声明的依赖保持一致。libcore 对 1.14 的 API 变更适配 MUST 覆盖：网络重置签名、证书提供方注册表、规则集 Tag 的类型变化、XHTTP 移植层的 QUIC 拨号签名，以及 T4A 自有协议扩展（负载均衡等自定义 outbound）在官方 1.14 registry/API 下的重新注册。

#### Scenario: 内核版本唯一来源生效

- **GIVEN** CI 按仓库构建脚本执行内核构建
- **WHEN** 脚本读取 `nb4a.properties` 并检出 sing-box 源码
- **THEN** 检出的 tag 为官方 `v1.14.1`
- **AND** `libcore/go.mod` 的本地 replace 仅指向该官方检出，无其他版本来源

#### Scenario: 自定义扩展在 1.14 下注册

- **GIVEN** libcore 含 Juicity/HTTP 覆盖、负载均衡等自定义 outbound 注册
- **WHEN** 以 sing-box v1.14.1 官方 registry API 编译并初始化 box
- **THEN** 全部自定义 outbound 注册成功且拨号路径走官方 registry
- **AND** 无 fork 内核引用

### Requirement: URL 延迟预热探测启用 HTTP/2 并具备回退

libcore 的 URL 延迟测试 MUST 在预热探测 transport 上启用 HTTP/2（ALPN 含 `h2` 与 `http/1.1` 并显式尝试 HTTP/2）。预热阶段对不兼容 HEAD 探测的目标（连接复用后测量阶段返回 EOF、405、403 或 5xx 类响应）MUST 改用 GET 重试预热；预热或测量响应状态码 >= 500 MUST 判为失败。主测 URL 失败时 MUST 以独立的、短于主测的超时回退到备用测速 URL。URL 测试实例关闭时 MUST 触发节流的内存回收（短时间内的多次关闭只执行一次），MUST NOT 阻塞调用方。

#### Scenario: 目标不兼容 HEAD 探测

- **GIVEN** 某测速 URL 经代理隧道完成预热但 HEAD 测量返回 405/EOF
- **WHEN** libcore 执行 URL 延迟测试
- **THEN** 预热阶段自动改用 GET 重试并复用连接完成测量
- **AND** 返回纯 1-RTT 延迟而非报错

#### Scenario: 主测 URL 失败回退备用 URL

- **GIVEN** 主测速 URL 在主测超时内失败
- **WHEN** libcore 执行 URL 延迟测试
- **THEN** 以备用 URL 和更短的回退超时重试
- **AND** 回退成功时返回回退测量的延迟，回退失败时保留主测错误

#### Scenario: 连续关闭多个 URL 测试实例

- **GIVEN** 批量延迟测试连续关闭多个 URL 测试实例
- **WHEN** 实例关闭动作在极短时间内连续发生
- **THEN** 内存回收按节流策略只执行一次
- **AND** 关闭调用不因此阻塞或报错
