package tun

import (
	"os"

	amz "github.com/amnezia-vpn/amneziawg-go/tun"
)

type Event = amz.Event

const (
	EventUp        = amz.EventUp
	EventDown      = amz.EventDown
	EventMTUUpdate = amz.EventMTUUpdate
)

type Device = amz.Device

var ErrTooManySegments = amz.ErrTooManySegments

func CreateTUN(name string, mtu int) (Device, error) {
	return amz.CreateTUN(name, mtu)
}

func CreateTUNFromFile(file *os.File, mtu int) (Device, error) {
	return amz.CreateTUNFromFile(file, mtu)
}
