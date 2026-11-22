package libcore

import (
	"encoding/json"
	"fmt"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/protocol/group"
)

// ActiveOutboundTags resolves the current path without probing or dialing.
func (b *BoxInstance) ActiveOutboundTags(root string) (string, error) {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 1 || b.Box == nil {
		return "", fmt.Errorf("core is not running")
	}

	seen := make(map[string]bool)
	tags := make([]string, 0)
	var visit func(string) error
	visit = func(tag string) error {
		if tag == "" || seen[tag] {
			return nil
		}
		seen[tag] = true
		outbound, found := b.Outbound().Outbound(tag)
		if !found {
			return fmt.Errorf("active outbound is unavailable")
		}
		tags = append(tags, tag)
		switch selected := outbound.(type) {
		case *group.URLTest:
			tcp, udp := selected.SelectedOutboundTags()
			if err := visit(tcp); err != nil {
				return err
			}
			return visit(udp)
		case adapter.OutboundGroup:
			return visit(selected.Now())
		default:
			for _, dependency := range outbound.Dependencies() {
				if err := visit(dependency); err != nil {
					return err
				}
			}
			return nil
		}
	}
	if err := visit(root); err != nil {
		return "", err
	}
	result, err := json.Marshal(tags)
	return string(result), err
}
