/* SPDX-License-Identifier: MIT
 *
 * Copyright (C) 2017-2025 WireGuard LLC. All Rights Reserved.
 */

package conn

import (
	"net"
	"syscall"

	"github.com/sagernet/sing/common/control"
)

// UDP socket read/write buffer size (7MB). The value of 7MB is chosen as it is
// the max supported by a default configuration of macOS. Some platforms will
// silently clamp the value to other maximums, such as linux clamping to
// net.core.{r,w}mem_max (see _linux.go for additional implementation that works
// around this limitation)
const socketBufferSize = 7 << 20

// ControlFunc is the callback function signature from net.ListenConfig.Control.
// It is used to apply platform specific configuration to the socket prior to
// bind.
type ControlFunc = control.Func

// controlFn is an alias for backwards compatibility
type controlFn = ControlFunc

// ControlFns is a list of functions that are called from the listen config
// that can apply socket options. Exported for external configuration.
var ControlFns []ControlFunc

// controlFns is an alias for backwards compatibility
var controlFns = ControlFns

// listenConfig returns a net.ListenConfig that applies the ControlFns to the
// socket prior to bind. This is used to apply socket buffer sizing and packet
// information OOB configuration for sticky sockets.
func listenConfig() *net.ListenConfig {
	return &net.ListenConfig{
		Control: func(network, address string, c syscall.RawConn) error {
			for _, fn := range ControlFns {
				if err := fn(network, address, c); err != nil {
					return err
				}
			}
			return nil
		},
	}
}
