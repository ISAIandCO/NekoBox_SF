package libcore

import (
	"context"
	"encoding/json"
	"libcore/warp"
	"net"
	"time"

	M "github.com/sagernet/sing/common/metadata"
)

func runWarp(identityJSON string, action string, amnezia bool) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	var dial func(context.Context, string, string) (net.Conn, error)
	if instance := mainInstance; instance != nil {
		dial = func(ctx context.Context, network, address string) (net.Conn, error) {
			return instance.Outbound().Default().DialContext(ctx, network, M.ParseSocksaddr(address))
		}
	}
	client := warp.NewClient(dial)
	defer client.CloseIdleConnections()
	var identity warp.Identity
	if action != "register" {
		if err := json.Unmarshal([]byte(identityJSON), &identity); err != nil {
			return "", err
		}
	}
	var err error
	switch action {
	case "register":
		var registered *warp.Identity
		registered, err = warp.Register(ctx, client, amnezia)
		if registered != nil {
			identity = *registered
		}
	case "refresh":
		err = warp.Refresh(ctx, client, &identity)
	case "revoke":
		err = warp.Revoke(ctx, client, &identity)
	}
	if err != nil {
		return "", err
	}
	content, err := json.Marshal(identity)
	return string(content), err
}

// The caller must obtain consent to Cloudflare's terms before registration.
func WarpRegister(amnezia bool) (string, error)       { return runWarp("", "register", amnezia) }
func WarpRefresh(identityJSON string) (string, error) { return runWarp(identityJSON, "refresh", false) }
func WarpRevoke(identityJSON string) error {
	_, err := runWarp(identityJSON, "revoke", false)
	return err
}
