module libcore

go 1.25.5

require (
	// 与 Throne 2e7182b9ea99947a409fee30f74df83752ab763c 的测速基线一致。
	github.com/Mahdi-zarei/speedtest-go v1.7.13-0.20260107171856-79c565dfd83a
	github.com/exclavenetwork/sing-juicity v0.3.0
	github.com/gofrs/uuid/v5 v5.5.1
	github.com/miekg/dns v1.1.72
	github.com/oschwald/maxminddb-golang v1.13.1
	github.com/sagernet/quic-go v0.61.0-sing-box-mod.7
	github.com/sagernet/sing v0.9.4
	// 版本唯一来源是 ../nb4a.properties 的 SINGBOX_VERSION；此处仅为 Go
	// module graph 所需占位值，实际源码始终由下方 replace 指向 CI 检出的官方 tag。
	github.com/sagernet/sing-box v0.0.0
	// 1.14 起 hysteria/hysteria2/tuic 客户端迁入 sing-quic；xhttp H3 拨号亦直接依赖。
	github.com/sagernet/sing-quic v0.7.0
	github.com/sagernet/sing-tun v0.9.3
	github.com/sagernet/sing-vmess v0.2.8
	github.com/ulikunitz/xz v0.5.15
	golang.org/x/crypto v0.54.0 // indirect
	golang.org/x/mobile v0.0.0-20231108233038-35478a0c49da
	golang.org/x/net v0.57.0
	golang.org/x/sys v0.47.0
)

// 官方内核：构建时由 buildScript/lib/core/get_source.sh 按 nb4a.properties 的
// SINGBOX_VERSION 克隆并校验 SagerNet/sing-box 到仓库同级目录（../../sing-box）。
replace github.com/sagernet/sing-box => ../../sing-box
