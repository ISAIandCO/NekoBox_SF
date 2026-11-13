package warp

import (
	"context"
	"crypto/ecdh"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func fixture() *device {
	var result device
	_ = json.Unmarshal([]byte(`{"id":"device-1","token":"secret-token","config":{"client_id":"AQID","interface":{"addresses":{"v4":"172.16.0.2","v6":"2606:4700:110::2"}},"peers":[{"public_key":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","endpoint":{"v4":"162.159.192.1:0","host":"engage.cloudflareclient.com:2408"}}]}}`), &result)
	return &result
}

func TestRegistrationRefreshAndRevoke(t *testing.T) {
	var publicKey string
	methods := []string{}
	client := &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		methods = append(methods, r.Method)
		if r.URL.Host != "api.cloudflareclient.com" {
			t.Fatal("wrong API host")
		}
		if r.Method == "POST" {
			var body map[string]string
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Fatal(err)
			}
			publicKey = body["key"]
			if body["tunnel_type"] != "wireguard" || body["tos"] == "" {
				t.Fatal("registration fields missing")
			}
		} else if r.Header.Get("Authorization") != "Bearer secret-token" || !strings.HasSuffix(r.URL.Path, "/reg/device-1") {
			t.Fatal("missing registration credentials")
		}
		content, _ := json.Marshal(fixture())
		return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(string(content)))}, nil
	})}
	identity, err := Register(context.Background(), client, true)
	if err != nil {
		t.Fatal(err)
	}
	private, _ := base64.StdEncoding.DecodeString(identity.PrivateKey)
	key, err := ecdh.X25519().NewPrivateKey(private)
	if err != nil || base64.StdEncoding.EncodeToString(key.PublicKey().Bytes()) != publicKey {
		t.Fatal("registered public key does not match private key")
	}
	if identity.LocalAddress != "172.16.0.2/32\n2606:4700:110::2/128" || identity.Reserved != "AQID" || identity.Port != 2408 {
		t.Fatal("bad network parameters")
	}
	if len(identity.Packets) == 0 || len(identity.Packets) > 5 {
		t.Fatal("missing AWG QUIC signature")
	}
	for _, packet := range identity.Packets {
		packet = strings.TrimSuffix(strings.TrimPrefix(packet, "<b 0x"), ">")
		content, err := hex.DecodeString(packet)
		if err != nil || len(content) < 1200 {
			t.Fatal("invalid QUIC first flight")
		}
	}
	originalKey := identity.PrivateKey
	if err := Refresh(context.Background(), client, identity); err != nil {
		t.Fatal(err)
	}
	if identity.PrivateKey != originalKey {
		t.Fatal("refresh replaced enrolled key")
	}
	if err := Revoke(context.Background(), client, identity); err != nil {
		t.Fatal(err)
	}
	if strings.Join(methods, ",") != "POST,GET,DELETE" {
		t.Fatal(methods)
	}
}

func TestInvalidResponseDoesNotReplaceSavedParameters(t *testing.T) {
	for _, mutate := range []func(*device){
		func(d *device) { d.Config.ClientID = "AA==" },
		func(d *device) { d.Config.Peers = nil },
		func(d *device) { d.Config.Peers[0].PublicKey = "bad" },
		func(d *device) { d.Config.Peers[0].Endpoint.V4 = "127.0.0.1:2408" },
		func(d *device) { d.Config.Interface.Addresses.V4 = "invalid" },
		func(d *device) { d.ID = "another-device" },
	} {
		d := fixture()
		mutate(d)
		identity := &Identity{ID: "device-1", Token: "secret", PrivateKey: "unchanged", Server: "old"}
		if fill(d, identity) == nil {
			t.Fatal("accepted invalid registration")
		}
		if identity.Server != "old" || identity.PrivateKey != "unchanged" {
			t.Fatal("partially overwrote identity")
		}
	}
}

func TestWireGuardEndpointPortSelection(t *testing.T) {
	for _, test := range []struct {
		name, ipv4, host string
		port             int
	}{
		{"API zero port", "162.159.192.1:0", "engage.cloudflareclient.com:2408", 2408},
		{"API alternative port", "162.159.192.1:0", "engage.cloudflareclient.com:500", 500},
		{"explicit IPv4 port", "162.159.192.1:4500", "engage.cloudflareclient.com:2408", 4500},
		{"bare IPv4 with host port", "162.159.192.1", "engage.cloudflareclient.com:1701", 1701},
		{"empty IPv4 port", "162.159.192.1:", "engage.cloudflareclient.com:2408", 2408},
		{"zero port without host", "162.159.192.1:0", "", 2408},
		{"bare IPv4 without host", "162.159.192.1", "", 2408},
		{"host without port", "162.159.192.1:0", "engage.cloudflareclient.com", 2408},
		{"host with zero port", "162.159.192.1:0", "engage.cloudflareclient.com:0", 2408},
		{"negative IPv4 port", "162.159.192.1:-1", "engage.cloudflareclient.com:2408", 0},
		{"out of range IPv4 port", "162.159.192.1:65536", "engage.cloudflareclient.com:2408", 0},
		{"malformed IPv4 port", "162.159.192.1:bad", "engage.cloudflareclient.com:2408", 0},
		{"negative host port", "162.159.192.1:0", "engage.cloudflareclient.com:-1", 0},
		{"out of range host port", "162.159.192.1:0", "engage.cloudflareclient.com:65536", 0},
		{"malformed host port", "162.159.192.1:0", "engage.cloudflareclient.com:bad", 0},
		{"private endpoint", "127.0.0.1:0", "engage.cloudflareclient.com:2408", 0},
		{"malformed endpoint", "not-an-ip:0", "engage.cloudflareclient.com:2408", 0},
	} {
		t.Run(test.name, func(t *testing.T) {
			response := fixture()
			response.Config.Peers[0].Endpoint.V4 = test.ipv4
			response.Config.Peers[0].Endpoint.Host = test.host
			identity := &Identity{ID: "device-1", Token: "secret", PrivateKey: "unchanged", Server: "old", Port: 51820}
			err := fill(response, identity)
			if test.port == 0 {
				if err == nil {
					t.Fatal("accepted invalid endpoint")
				}
				if identity.Server != "old" || identity.Port != 51820 {
					t.Fatal("invalid endpoint replaced saved parameters")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if identity.Server != "162.159.192.1" || identity.Port != test.port || identity.PrivateKey != "unchanged" {
				t.Fatalf("wrong endpoint or replaced key: server=%s port=%d", identity.Server, identity.Port)
			}
		})
	}
}

func TestAPIErrorsDoNotDiscloseSecretsOrRetryRegistration(t *testing.T) {
	for _, status := range []int{401, 429, 500} {
		calls := 0
		client := &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
			calls++
			return &http.Response{StatusCode: status, Body: io.NopCloser(strings.NewReader("secret-token"))}, nil
		})}
		_, err := Register(context.Background(), client, false)
		if err == nil || strings.Contains(err.Error(), "secret-token") || calls != 1 {
			t.Fatal("unsafe API error handling", err, calls)
		}
	}
}

func TestClientDoesNotFallBackWhenVPNDialFails(t *testing.T) {
	client := NewClient(func(context.Context, string, string) (net.Conn, error) { return nil, fmt.Errorf("VPN unavailable") })
	defer client.CloseIdleConnections()
	_, err := Register(context.Background(), client, false)
	if err == nil || !strings.Contains(err.Error(), "VPN unavailable") {
		t.Fatal(err)
	}
}

func TestSignatureCancelled(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Nanosecond)
	defer cancel()
	if _, err := QUICSignature(ctx, "www.cloudflare.com"); err == nil {
		t.Fatal("ignored cancellation")
	}
}
