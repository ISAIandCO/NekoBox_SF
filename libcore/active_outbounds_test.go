package libcore

import (
	"context"
	"encoding/json"
	"slices"
	"testing"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/group"
	"github.com/sagernet/sing/service"
)

func TestActiveOutboundTagsFollowSelectorAndDetour(t *testing.T) {
	ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(),
		nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(),
		nekoboxAndroidCertificateRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[adapter.PlatformInterface](ctx, &dnsTestPlatform{})
	var options option.Options
	config := `{
		"outbounds": [
			{"type":"direct","tag":"first"},
			{"type":"direct","tag":"second"},
			{"type":"selector","tag":"proxy","outbounds":["first","second"]},
			{"type":"socks","tag":"root","server":"127.0.0.1","server_port":1080,"detour":"proxy"}
		]
	}`
	if err := decodeConfigOptions(ctx, []byte(config), &options); err != nil {
		t.Fatal(err)
	}
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	core := BoxInstance{Box: instance, state: 1}
	checkTags := func(want []string) {
		t.Helper()
		result, err := core.ActiveOutboundTags("root")
		if err != nil {
			t.Fatal(err)
		}
		var tags []string
		if err := json.Unmarshal([]byte(result), &tags); err != nil || !slices.Equal(tags, want) {
			t.Fatalf("got active tags %v (%v), want %v", tags, err, want)
		}
	}
	checkTags([]string{"root", "proxy", "first"})

	selector, _ := instance.Outbound().Outbound("proxy")
	if !selector.(*group.Selector).SelectOutbound("second") {
		t.Fatal("failed to select second outbound")
	}
	checkTags([]string{"root", "proxy", "second"})
}
