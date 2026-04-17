package device

import (
	"context"
	"reflect"
	"unsafe"

	amz "github.com/amnezia-vpn/amneziawg-go/device"
	"github.com/sagernet/wireguard-go/conn"
	"github.com/sagernet/wireguard-go/tun"
)

type Logger = amz.Logger
type Peer = amz.Peer
type AllowedIPs = amz.AllowedIPs

type Device struct {
	*amz.Device
	allowedips AllowedIPs
}

func NewDevice(_ context.Context, tunDevice tun.Device, bind conn.Bind, logger *Logger, _ int) *Device {
	inner := amz.NewDevice(tunDevice, conn.UnwrapBind(bind), logger)
	wrapper := &Device{Device: inner}
	allowed := reflect.Indirect(reflect.ValueOf(inner)).FieldByName("allowedips")
	if allowed.IsValid() {
		wrapper.allowedips = *(*AllowedIPs)(unsafe.Pointer(allowed.UnsafeAddr()))
	}
	return wrapper
}

// InputPacket existed in sagernet/wireguard-go; amneziawg-go does not expose
// an equivalent API. Keep a no-op method for build compatibility.
func (d *Device) InputPacket(_ []byte, _ [][]byte) {
}
