package libcore

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"testing"
	"time"

	"github.com/sagernet/sing-box/log"
	wg "github.com/sagernet/sing-box/transport/wireguard"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

type failingWARPBindDialer struct{ attempted chan struct{} }

func (d failingWARPBindDialer) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	close(d.attempted)
	return nil, errors.New("test VPN unavailable")
}
func (d failingWARPBindDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("unexpected unconnected dial")
}

// Device.Down closes the bind and then waits for send/receive workers. Those
// workers must not wait for DeviceWake, which cannot run until Down returns.
func TestWireGuardBindCloseCancelsRetryWhilePaused(t *testing.T) {
	for _, send := range []bool{true, false} {
		name := "receive"
		if send {
			name = "send"
		}
		t.Run(name, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			ctx = pause.WithDefaultManager(service.ContextWithDefaultRegistry(ctx))
			pauseManager := service.FromContext[pause.Manager](ctx)
			pauseManager.DevicePause()
			dialer := failingWARPBindDialer{make(chan struct{})}
			bind := wg.NewClientBind(ctx, log.NewNOPFactory().NewLogger("test"), dialer, true, netip.MustParseAddrPort("127.0.0.1:2408"), [3]uint8{})
			fns, _, err := bind.Open(0)
			if err != nil {
				t.Fatal(err)
			}
			defer bind.Close()
			done := make(chan struct{})
			go func() {
				defer close(done)
				if send {
					endpoint, _ := bind.ParseEndpoint("127.0.0.1:2408")
					_ = bind.Send(nil, endpoint)
				} else {
					_, _ = fns[0](nil, nil, nil)
				}
			}()
			select {
			case <-dialer.attempted:
			case <-time.After(time.Second):
				t.Fatal("bind did not attempt the VPN dial")
			}
			if err := bind.Close(); err != nil {
				t.Fatal(err)
			}
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatal("closed bind is waiting for device wake")
			}
		})
	}
}
