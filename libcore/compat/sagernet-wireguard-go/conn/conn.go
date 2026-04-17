package conn

import (
	"net/netip"
	"syscall"

	amz "github.com/amnezia-vpn/amneziawg-go/conn"
)

type ReceiveFunc = amz.ReceiveFunc

type Endpoint = amz.Endpoint

type Listener interface {
	WireGuardControl() func(network, address string, c syscall.RawConn) error
}

type Bind interface {
	Open(port uint16) (fns []ReceiveFunc, actualPort uint16, err error)
	Close() error
	SetMark(mark uint32) error
	Send(bufs [][]byte, ep Endpoint) error
	ParseEndpoint(s string) (Endpoint, error)
	BatchSize() int
	SetReservedForEndpoint(destination netip.AddrPort, reserved [3]byte)
}

type bindCompat struct {
	inner amz.Bind
}

func WrapAmneziaBind(bind amz.Bind) Bind {
	if bind == nil {
		return nil
	}
	return &bindCompat{inner: bind}
}

func UnwrapBind(bind Bind) amz.Bind {
	if bind == nil {
		return nil
	}
	if wrapped, ok := bind.(*bindCompat); ok {
		return wrapped.inner
	}
	return &bindAdapter{inner: bind}
}

func NewDefaultBind() Bind {
	return WrapAmneziaBind(amz.NewDefaultBind())
}

// Keep control callback in signature for compatibility; amneziawg-go ignores it.
func NewStdNetBind(_ ...func(network, address string, c syscall.RawConn) error) Bind {
	return WrapAmneziaBind(amz.NewStdNetBind())
}

func (b *bindCompat) Open(port uint16) ([]ReceiveFunc, uint16, error) {
	return b.inner.Open(port)
}

func (b *bindCompat) Close() error {
	return b.inner.Close()
}

func (b *bindCompat) SetMark(mark uint32) error {
	return b.inner.SetMark(mark)
}

func (b *bindCompat) Send(bufs [][]byte, ep Endpoint) error {
	return b.inner.Send(bufs, ep)
}

func (b *bindCompat) ParseEndpoint(s string) (Endpoint, error) {
	return b.inner.ParseEndpoint(s)
}

func (b *bindCompat) BatchSize() int {
	return b.inner.BatchSize()
}

func (b *bindCompat) SetReservedForEndpoint(_ netip.AddrPort, _ [3]byte) {
}

type bindAdapter struct {
	inner Bind
}

func (b *bindAdapter) Open(port uint16) ([]amz.ReceiveFunc, uint16, error) {
	return b.inner.Open(port)
}

func (b *bindAdapter) Close() error {
	return b.inner.Close()
}

func (b *bindAdapter) SetMark(mark uint32) error {
	return b.inner.SetMark(mark)
}

func (b *bindAdapter) Send(bufs [][]byte, ep amz.Endpoint) error {
	return b.inner.Send(bufs, ep)
}

func (b *bindAdapter) ParseEndpoint(s string) (amz.Endpoint, error) {
	return b.inner.ParseEndpoint(s)
}

func (b *bindAdapter) BatchSize() int {
	return b.inner.BatchSize()
}
