// WARP API fields and AWG camouflage follow throneproj/Throne (GPL-3.0),
// core/internal/warp and core/internal/amnezia at the revision in docs/WARP.md.
package warp

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const apiURL = "https://api.cloudflareclient.com/v0a4471"

type Identity struct {
	ID            string   `json:"id"`
	Token         string   `json:"token"`
	PrivateKey    string   `json:"privateKey"`
	PeerPublicKey string   `json:"peerPublicKey"`
	Server        string   `json:"server"`
	Port          int      `json:"port"`
	LocalAddress  string   `json:"localAddress"`
	Reserved      string   `json:"reserved"`
	Packets       []string `json:"packets"`
}

type device struct {
	ID     string `json:"id"`
	Token  string `json:"token"`
	Config struct {
		ClientID  string `json:"client_id"`
		Interface struct {
			Addresses struct {
				V4 string `json:"v4"`
				V6 string `json:"v6"`
			} `json:"addresses"`
		} `json:"interface"`
		Peers []struct {
			PublicKey string `json:"public_key"`
			Endpoint  struct {
				V4   string `json:"v4"`
				Host string `json:"host"`
			} `json:"endpoint"`
		} `json:"peers"`
	} `json:"config"`
}

// NewClient never falls back to a direct connection after a supplied dial fails.
func NewClient(dial func(context.Context, string, string) (net.Conn, error)) *http.Client {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	if dial != nil {
		transport.DialContext = dial
	}
	transport.TLSHandshakeTimeout = 15 * time.Second
	return &http.Client{Transport: transport, Timeout: 45 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return fmt.Errorf("WARP API redirect refused") }}
}

func Register(ctx context.Context, client *http.Client, amnezia bool) (*Identity, error) {
	packets := make([]string, 0)
	var err error
	if amnezia {
		packets, err = QUICSignature(ctx, "www.cloudflare.com")
		if err != nil {
			return nil, fmt.Errorf("WARP QUIC camouflage: %w", err)
		}
	}
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	serial := make([]byte, 8)
	if _, err = rand.Read(serial); err != nil {
		return nil, err
	}
	response, err := call(ctx, client, http.MethodPost, "/reg", "", map[string]string{
		"key": base64.StdEncoding.EncodeToString(key.PublicKey().Bytes()),
		"tos": time.Now().UTC().Format(time.RFC3339Nano), "model": "Android",
		"serial_number": hex.EncodeToString(serial), "key_type": "curve25519",
		"tunnel_type": "wireguard", "locale": "en_US", "install_id": "", "fcm_token": "",
	})
	if err != nil {
		return nil, err
	}
	identity := &Identity{ID: response.ID, Token: response.Token, Packets: packets,
		PrivateKey: base64.StdEncoding.EncodeToString(key.Bytes())}
	if err = fill(response, identity); err != nil {
		return nil, err
	}
	return identity, nil
}

func Refresh(ctx context.Context, client *http.Client, identity *Identity) error {
	if err := validateCredentials(identity); err != nil {
		return err
	}
	response, err := call(ctx, client, http.MethodGet, "/reg/"+url.PathEscape(identity.ID), identity.Token, nil)
	if err != nil {
		return err
	}
	return fill(response, identity)
}

func Revoke(ctx context.Context, client *http.Client, identity *Identity) error {
	if err := validateCredentials(identity); err != nil {
		return err
	}
	_, err := call(ctx, client, http.MethodDelete, "/reg/"+url.PathEscape(identity.ID), identity.Token, nil)
	return err
}

func validateCredentials(identity *Identity) error {
	if identity == nil || identity.ID == "" || identity.Token == "" || strings.ContainsAny(identity.ID, "/?#") {
		return fmt.Errorf("missing or invalid WARP registration credentials")
	}
	return nil
}

func fill(response *device, identity *Identity) error {
	if err := validateCredentials(identity); err != nil {
		return err
	}
	if response.ID != identity.ID {
		return fmt.Errorf("WARP API returned another device")
	}
	if len(response.Config.Peers) == 0 {
		return fmt.Errorf("WARP API returned no peer")
	}
	peer := response.Config.Peers[0]
	key, err := base64.StdEncoding.DecodeString(peer.PublicKey)
	if err != nil || len(key) != 32 {
		return fmt.Errorf("WARP API returned an invalid peer key")
	}
	reserved, err := base64.StdEncoding.DecodeString(response.Config.ClientID)
	if err != nil || len(reserved) != 3 {
		return fmt.Errorf("WARP API returned an invalid client_id")
	}
	addresses := []string{}
	for _, value := range []string{response.Config.Interface.Addresses.V4, response.Config.Interface.Addresses.V6} {
		if value == "" {
			continue
		}
		address, err := netip.ParseAddr(value)
		if err != nil || address.IsUnspecified() || address.IsMulticast() {
			return fmt.Errorf("WARP API returned an invalid interface address")
		}
		addresses = append(addresses, netip.PrefixFrom(address, address.BitLen()).String())
	}
	if len(addresses) == 0 {
		return fmt.Errorf("WARP API returned no interface address")
	}
	// The literal endpoint avoids a resolver dependency on the tunnel it creates.
	server, port, err := net.SplitHostPort(peer.Endpoint.V4)
	if err != nil {
		// Some API responses provide a bare IP; it is validated below.
		server, port = peer.Endpoint.V4, ""
	}
	ip, err := netip.ParseAddr(server)
	if err != nil || !ip.Is4() || !ip.IsGlobalUnicast() || ip.IsPrivate() {
		return fmt.Errorf("WARP API returned an invalid endpoint IP")
	}
	if port == "" || port == "0" {
		// Cloudflare commonly returns v4 as IP:0, with the service port in
		// host (e.g. engage.cloudflareclient.com:2408). Keep the literal IP
		// to avoid a bootstrap DNS dependency, and borrow only that port.
		port = "2408"
		if _, hostPort, hostErr := net.SplitHostPort(peer.Endpoint.Host); hostErr == nil && hostPort != "" && hostPort != "0" {
			port = hostPort
		}
	}
	portNumber, err := strconv.Atoi(port)
	if err != nil || portNumber < 1 || portNumber > 65535 {
		return fmt.Errorf("WARP API returned an invalid endpoint port")
	}
	identity.Server, identity.Port = server, portNumber
	identity.PeerPublicKey, identity.Reserved = peer.PublicKey, response.Config.ClientID
	identity.LocalAddress = strings.Join(addresses, "\n")
	return nil
}

func call(ctx context.Context, client *http.Client, method, path, token string, body any) (*device, error) {
	var reader io.Reader
	if body != nil {
		content, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		reader = bytes.NewReader(content)
	}
	request, err := http.NewRequestWithContext(ctx, method, apiURL+path, reader)
	if err != nil {
		return nil, err
	}
	request.Header.Set("User-Agent", "WARP for Android")
	request.Header.Set("CF-Client-Version", "a-6.35-4471")
	request.Header.Set("Content-Type", "application/json; charset=UTF-8")
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	response, err := client.Do(request)
	if err != nil {
		return nil, fmt.Errorf("WARP API request failed: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode == 429 {
		return nil, fmt.Errorf("Cloudflare WARP rate limit; try again later")
	}
	if method == http.MethodDelete && response.StatusCode == 404 {
		return &device{}, nil
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		// Do not expose a response body that could include registration secrets.
		return nil, fmt.Errorf("WARP API: HTTP %d", response.StatusCode)
	}
	if method == http.MethodDelete {
		return &device{}, nil
	}
	content, err := io.ReadAll(io.LimitReader(response.Body, (1<<20)+1))
	if err != nil {
		return nil, err
	}
	if len(content) > 1<<20 {
		return nil, fmt.Errorf("WARP API response too large")
	}
	var result device
	if err = json.Unmarshal(content, &result); err != nil {
		return nil, fmt.Errorf("invalid WARP API response")
	}
	return &result, nil
}
