//go:build !linux

package device

import (
	"github.com/pulsarvpn/amneziawg-go/conn"
	"github.com/pulsarvpn/amneziawg-go/rwcancel"
)

func (device *Device) startRouteListener(_ conn.Bind) (*rwcancel.RWCancel, error) {
	return nil, nil
}
