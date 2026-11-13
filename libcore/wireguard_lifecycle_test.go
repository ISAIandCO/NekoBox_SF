package libcore

import (
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/group"
	wg "github.com/sagernet/sing-box/transport/wireguard"
	"github.com/sagernet/sing-tun"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

// Use the actual Android adapter, replacing only the Android fd-protection call.
type wireguardLifecycleTestPlatform struct {
	boxPlatformInterfaceWrapper
	protected atomic.Int32
}

func (p *wireguardLifecycleTestPlatform) AutoDetectInterfaceControl(int) error {
	p.protected.Add(1)
	return nil
}

func TestAndroidPlatformWireGuardSurvivesFullServiceStartup(t *testing.T) {
	for _, mode := range []string{"WireGuard", "WARP-style AWG2", "WireGuard via SOCKS", "WARP-style AWG2 via SOCKS"} {
		t.Run(mode, func(t *testing.T) {
			var keys [2]*ecdh.PrivateKey
			for i := range keys {
				var err error
				keys[i], err = ecdh.X25519().GenerateKey(rand.Reader)
				if err != nil {
					t.Fatal(err)
				}
			}
			key := func(value []byte) string { return base64.StdEncoding.EncodeToString(value) }
			socket, err := net.ListenPacket("udp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = socket.Close() })
			serverCtx := pause.WithDefaultManager(service.ContextWithDefaultRegistry(context.Background()))
			server, err := wg.NewEndpoint(wg.EndpointOptions{
				Context: serverCtx, Logger: log.NewNOPFactory().NewLogger("server"),
				Dialer: wgTestDialer{socket}, MTU: 1280,
				Address:    []netip.Prefix{netip.MustParsePrefix("10.0.0.1/32")},
				PrivateKey: key(keys[0].Bytes()),
				Handler:    warpEchoHandler{},
				UDPTimeout: time.Minute,
				Peers:      []wg.PeerOptions{{PublicKey: key(keys[1].PublicKey().Bytes()), AllowedIPs: []netip.Prefix{netip.MustParsePrefix("10.0.0.2/32")}}},
			})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = server.Close() })
			if err = server.Start(false); err != nil {
				t.Fatal(err)
			}

			outbound := map[string]any{
				"type": "wireguard", "tag": "proxy", "server": "127.0.0.1",
				"server_port":   socket.LocalAddr().(*net.UDPAddr).Port,
				"local_address": []string{"10.0.0.2/32"}, "mtu": 1280,
				"private_key": key(keys[1].Bytes()), "peer_public_key": key(keys[0].PublicKey().Bytes()),
			}
			if strings.HasPrefix(mode, "WARP") {
				outbound["jc"], outbound["jmin"], outbound["jmax"] = 4, 40, 70
				outbound["h1"], outbound["h2"], outbound["h3"], outbound["h4"] = "1", "2", "3", "4"
				// Synthetic CPS data with the same size as a 1250-byte WARP preface.
				outbound["i1"] = "<b 0x" + strings.Repeat("ab", 1250) + ">"
			}
			outbounds := []any{outbound}
			if strings.Contains(mode, "via SOCKS") {
				for _, tag := range []string{"vpn-a", "vpn-b"} {
					port := startWarpSocksRelay(t)
					outbounds = append(outbounds, map[string]any{"type": "socks", "tag": tag, "server": "127.0.0.1", "server_port": port})
				}
				outbounds = append(outbounds, map[string]any{"type": "selector", "tag": "main-vpn", "outbounds": []string{"vpn-a", "vpn-b"}, "default": "vpn-a"})
				outbound["detour"] = "main-vpn"
			}
			content, _ := json.Marshal(map[string]any{
				"outbounds": outbounds, "route": map[string]any{"auto_detect_interface": true, "final": "proxy"},
				"log": map[string]any{"disabled": true},
			})
			ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateRegistry())
			ctx = service.ContextWithDefaultRegistry(ctx)
			platform := &wireguardLifecycleTestPlatform{}
			service.MustRegister[adapter.PlatformInterface](ctx, platform)
			var options option.Options
			if err = decodeConfigOptions(ctx, content, &options); err != nil {
				t.Fatal(err)
			}
			instance, err := box.New(box.Options{Context: ctx, Options: options})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = instance.Close() })
			if err = instance.Start(); err != nil {
				t.Fatal(err)
			}
			manager := service.FromContext[pause.Manager](ctx)
			if manager.IsNetworkPaused() {
				t.Fatal("Android platform falsely paused the network after full service startup")
			}
			if platform.protected.Load() == 0 {
				t.Fatal("WireGuard socket bypassed platform fd protection")
			}
			if instance.Network().DefaultNetworkInterface() != nil {
				t.Fatal("Android adapter must not fabricate a default interface")
			}
			proxy, _ := instance.Outbound().Outbound("proxy")
			tcpCtx, tcpCancel := context.WithTimeout(ctx, 8*time.Second)
			tcpConn, err := proxy.DialContext(tcpCtx, "tcp", M.ParseSocksaddr("10.0.0.1:443"))
			tcpCancel()
			if err != nil {
				t.Fatal("TCP through WARP exit: ", err)
			}
			_ = tcpConn.SetDeadline(time.Now().Add(8 * time.Second))
			if _, err = tcpConn.Write([]byte("tcp-through-warp")); err != nil {
				t.Fatal(err)
			}
			response := make([]byte, len("tcp-through-warp"))
			if _, err = io.ReadFull(tcpConn, response); err != nil || string(response) != "tcp-through-warp" {
				t.Fatal("TCP echo failed: ", err)
			}
			_ = tcpConn.Close()
			listener, err := server.ListenPacket(serverCtx, M.ParseSocksaddr("10.0.0.1:5353"))
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			destination := M.Socksaddr{Addr: netip.MustParseAddr("10.0.0.1"), Port: M.SocksaddrFromNet(listener.LocalAddr()).Port}
			for _, afterWake := range []bool{false, true} {
				if afterWake {
					manager.DevicePause()
					manager.DeviceWake()
					if strings.Contains(mode, "via SOCKS") {
						mainVPN, _ := instance.Outbound().Outbound("main-vpn")
						if !mainVPN.(*group.Selector).SelectOutbound("vpn-b") {
							t.Fatal("main VPN switch failed")
						}
					}
				}
				conn, err := proxy.DialContext(ctx, "udp", destination)
				if err != nil {
					t.Fatal(err)
				}
				_ = listener.SetDeadline(time.Now().Add(8 * time.Second))
				_ = conn.SetDeadline(time.Now().Add(8 * time.Second))
				if _, err = conn.Write([]byte("query")); err != nil {
					t.Fatal(err)
				}
				buffer := make([]byte, 128)
				n, source, err := listener.ReadFrom(buffer)
				if err != nil || string(buffer[:n]) != "query" {
					t.Fatalf("tunnel request: n=%d err=%v", n, err)
				}
				if _, err = listener.WriteTo([]byte("answer"), source); err != nil {
					t.Fatal(err)
				}
				n, err = conn.Read(buffer)
				if err != nil || string(buffer[:n]) != "answer" {
					t.Fatalf("tunnel response: n=%d err=%v", n, err)
				}
				_ = conn.Close()
			}
		})
	}
}

// A local peer echoes TCP after WireGuard decryption; UDP uses the bound socket above.
type warpEchoHandler struct{}

func (warpEchoHandler) JudgeFlow(uint8, netip.AddrPort, netip.AddrPort, []byte) tun.FlowVerdict {
	return tun.FlowVerdict{Action: tun.ActionAccept}
}
func (warpEchoHandler) NewDNSPacket([]byte, M.Socksaddr, M.Socksaddr, N.PacketWriter) {}
func (warpEchoHandler) NewConnectionEx(_ context.Context, conn net.Conn, _, _ M.Socksaddr, onClose N.CloseHandlerFunc) {
	go func() {
		_, err := io.Copy(conn, conn)
		conn.Close()
		if onClose != nil {
			onClose(err)
		}
	}()
}
func (warpEchoHandler) NewPacketConnectionEx(_ context.Context, conn N.PacketConn, _, _ M.Socksaddr, onClose N.CloseHandlerFunc) {
	conn.Close()
	if onClose != nil {
		onClose(nil)
	}
}

func startWarpSocksRelay(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	content, _ := json.Marshal(map[string]any{
		"inbounds":  []any{map[string]any{"type": "socks", "listen": "127.0.0.1", "listen_port": port}},
		"outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}},
		"route":     map[string]any{"final": "direct"}, "log": map[string]any{"disabled": true},
	})
	ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[adapter.PlatformInterface](ctx, &wireguardLifecycleTestPlatform{})
	var options option.Options
	if err := decodeConfigOptions(ctx, content, &options); err != nil {
		t.Fatal(err)
	}
	relay, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { relay.Close() })
	if err = relay.Start(); err != nil {
		t.Fatal(err)
	}
	return port
}
