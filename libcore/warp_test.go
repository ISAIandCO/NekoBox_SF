package libcore

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
)

type warpTestOutbound struct {
	outbound.Adapter
	dial func(context.Context, string, M.Socksaddr, string) (net.Conn, error)
}

func (o *warpTestOutbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return o.dial(ctx, network, destination, o.Tag())
}
func (o *warpTestOutbound) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, fmt.Errorf("unexpected UDP request")
}

func newWarpTestBox(t *testing.T, dial func(context.Context, string, M.Socksaddr, string) (net.Conn, error)) *BoxInstance {
	t.Helper()
	registry := nekoboxAndroidOutboundRegistry()
	outbound.Register(registry, "warp-test", func(_ context.Context, _ adapter.Router, _ log.ContextLogger, tag string, _ option.DirectOutboundOptions) (adapter.Outbound, error) {
		kind := "warp-test"
		if tag == "bypass" {
			kind = C.TypeDirect
		}
		return &warpTestOutbound{Adapter: outbound.NewAdapter(kind, tag, []string{"tcp"}, nil), dial: dial}, nil
	})
	ctx, cancel := context.WithCancel(context.Background())
	ctx = box.Context(ctx, nekoboxAndroidInboundRegistry(), registry, nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[adapter.PlatformInterface](ctx, &wireguardLifecycleTestPlatform{})
	t.Cleanup(cancel)
	var options option.Options
	if err := decodeConfigOptions(ctx, []byte(`{"log":{"disabled":true},"outbounds":[{"type":"warp-test","tag":"bypass"},{"type":"warp-test","tag":"vpn"}],"route":{"final":"bypass"}}`), &options); err != nil {
		t.Fatal(err)
	}
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		t.Fatal(err)
	}
	b := &BoxInstance{Box: instance, ctx: ctx, cancel: cancel}
	t.Cleanup(func() { _ = b.Close() })
	if err = b.Start(); err != nil {
		t.Fatal(err)
	}
	if b.Outbound().Default().Tag() != "bypass" {
		t.Fatal("fixture must have a direct default outbound")
	}
	return b
}

func TestWarpOperationsUseSelectedVPNEvenWhenDefaultIsDirect(t *testing.T) {
	var posts, gets, deletes, proxyDials, directDials atomic.Int32
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Host != "api.cloudflareclient.com" {
			t.Error("lost API host")
		}
		switch r.Method {
		case "POST":
			posts.Add(1)
		case "GET":
			gets.Add(1)
		case "DELETE":
			deletes.Add(1)
		}
		_, _ = io.WriteString(w, `{"id":"device-1","token":"test-token","config":{"client_id":"AQID","interface":{"addresses":{"v4":"172.16.0.2","v6":"2606:4700:110::2"}},"peers":[{"public_key":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","endpoint":{"v4":"162.159.192.1:0","host":"engage.cloudflareclient.com:2408"}}]}}`)
	}))
	defer server.Close()
	// Keep the real HTTP/TLS stack, trusting only this local test API certificate.
	transport := http.DefaultTransport.(*http.Transport).Clone()
	pool := x509.NewCertPool()
	pool.AddCert(server.Certificate())
	transport.TLSClientConfig = &tls.Config{RootCAs: pool, ServerName: server.Certificate().DNSNames[0]}
	original := http.DefaultTransport
	http.DefaultTransport = transport
	defer func() { http.DefaultTransport = original; transport.CloseIdleConnections() }()
	b := newWarpTestBox(t, func(ctx context.Context, network string, destination M.Socksaddr, tag string) (net.Conn, error) {
		if tag != "vpn" {
			directDials.Add(1)
			return nil, fmt.Errorf("direct bypass attempted")
		}
		proxyDials.Add(1)
		if network != "tcp" || destination.String() != "api.cloudflareclient.com:443" {
			return nil, fmt.Errorf("unexpected API destination")
		}
		return (&net.Dialer{}).DialContext(ctx, network, server.Listener.Addr().String())
	})
	for _, amnezia := range []bool{false, true} {
		identity, err := b.WarpOperation("vpn", "register", "", amnezia)
		if err != nil {
			t.Fatal(err)
		}
		if _, err = b.WarpOperation("vpn", "refresh", identity, false); err != nil {
			t.Fatal(err)
		}
		if _, err = b.WarpOperation("vpn", "revoke", identity, false); err != nil {
			t.Fatal(err)
		}
	}
	if posts.Load() != 2 || gets.Load() != 2 || deletes.Load() != 2 || proxyDials.Load() != 6 || directDials.Load() != 0 {
		t.Fatal("WARP operation bypassed the VPN or failed to reach the test API")
	}
}

func TestWarpUnavailableVPNNeverFallsBackToDirect(t *testing.T) {
	var proxyDials, directDials atomic.Int32
	b := newWarpTestBox(t, func(_ context.Context, _ string, _ M.Socksaddr, tag string) (net.Conn, error) {
		if tag == "vpn" {
			proxyDials.Add(1)
			return nil, fmt.Errorf("test VPN unavailable")
		}
		directDials.Add(1)
		return nil, fmt.Errorf("direct bypass attempted")
	})
	identity := `{"id":"device-1","token":"test-token"}`
	for _, action := range []string{"register", "refresh", "revoke"} {
		if _, err := b.WarpOperation("vpn", action, identity, false); err == nil || !strings.Contains(err.Error(), "test VPN unavailable") {
			t.Fatalf("%s did not report VPN failure: %v", action, err)
		}
	}
	for _, tag := range []string{"", "missing", "bypass"} {
		if _, err := b.WarpOperation(tag, "register", "", false); err == nil {
			t.Fatalf("accepted unavailable/direct VPN outbound %q", tag)
		}
	}
	_ = b.Close()
	if _, err := b.WarpOperation("vpn", "register", "", false); err == nil {
		t.Fatal("accepted stopped VPN")
	}
	if _, err := (*BoxInstance)(nil).WarpOperation("vpn", "register", "", false); err == nil {
		t.Fatal("accepted missing VPN")
	}
	if proxyDials.Load() != 3 || directDials.Load() != 0 {
		t.Fatal("missing or failed VPN triggered direct fallback")
	}
}

func TestWarpRequestCancelledWhenVPNCloses(t *testing.T) {
	started := make(chan struct{}, 1)
	b := newWarpTestBox(t, func(ctx context.Context, _ string, _ M.Socksaddr, tag string) (net.Conn, error) {
		if tag != "vpn" {
			return nil, fmt.Errorf("direct bypass attempted")
		}
		started <- struct{}{}
		<-ctx.Done()
		return nil, ctx.Err()
	})
	done := make(chan error, 1)
	go func() { _, err := b.WarpOperation("vpn", "register", "", false); done <- err }()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("request did not start through VPN")
	}
	closed := make(chan struct{})
	go func() { _ = b.Close(); close(closed) }()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancelled request reported success")
		}
	case <-time.After(time.Second):
		t.Fatal("request survived VPN shutdown")
	}
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("VPN shutdown blocked on WARP request")
	}
}
