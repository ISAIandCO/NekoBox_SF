package device

import (
	"context"

	amz "github.com/amnezia-vpn/amneziawg-go/device"
	"github.com/sagernet/wireguard-go/conn"
	"github.com/sagernet/wireguard-go/tun"
)

type Device = amz.Device
type Logger = amz.Logger
type Peer = amz.Peer
type AllowedIPs = amz.AllowedIPs

func NewDevice(_ context.Context, tunDevice tun.Device, bind conn.Bind, logger *Logger, _ int) *Device {
	return amz.NewDevice(tunDevice, conn.UnwrapBind(bind), logger)
}
