package libcore

import (
	"context"
	"encoding/json"
	"fmt"
	"libcore/warp"
	"net"
	"time"

	C "github.com/sagernet/sing-box/constant"
	M "github.com/sagernet/sing/common/metadata"
)

// WarpOperation pins the entire request to this running VPN, even when route.final
// is direct (for example, with imported WireGuard AllowedIPs). Closing the VPN
// cancels the request; neither a missing outbound nor a dial error permits bypass.
func (b *BoxInstance) WarpOperation(tag, action, identityJSON string, amnezia bool) (string, error) {
	if b == nil {
		return "", fmt.Errorf("VPN core is unavailable")
	}
	b.access.Lock()
	if b.state != 1 || b.Box == nil || tag == "" {
		b.access.Unlock()
		return "", fmt.Errorf("VPN core or outbound is unavailable")
	}
	outbound, exists := b.Outbound().Outbound(tag)
	if !exists || outbound.Type() == C.TypeDirect {
		b.access.Unlock()
		return "", fmt.Errorf("VPN outbound is unavailable")
	}
	b.probeWG.Add(1)
	parent := b.ctx
	b.access.Unlock()
	defer b.probeWG.Done()
	return runWarp(parent, func(ctx context.Context, network, address string) (net.Conn, error) {
		return outbound.DialContext(ctx, network, M.ParseSocksaddr(address))
	}, identityJSON, action, amnezia)
}

func runWarp(parent context.Context, dial func(context.Context, string, string) (net.Conn, error), identityJSON string, action string, amnezia bool) (string, error) {
	ctx, cancel := context.WithTimeout(parent, 45*time.Second)
	defer cancel()
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
	default:
		return "", fmt.Errorf("unknown WARP operation")
	}
	if err != nil {
		return "", err
	}
	content, err := json.Marshal(identity)
	return string(content), err
}

// These direct operations are only for a stopped VPN. A connected service must
// use BoxInstance.WarpOperation. Obtain consent to Cloudflare's terms first.
func WarpRegister(amnezia bool) (string, error) {
	return runWarp(context.Background(), nil, "", "register", amnezia)
}
func WarpRefresh(identityJSON string) (string, error) {
	return runWarp(context.Background(), nil, identityJSON, "refresh", false)
}
func WarpRevoke(identityJSON string) error {
	_, err := runWarp(context.Background(), nil, identityJSON, "revoke", false)
	return err
}
