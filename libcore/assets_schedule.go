package libcore

import (
	"encoding/json"

	"github.com/sagernet/sing/common/json/badoption"
)

// GeoAssetUpdateIntervalMillis accepts the same durations as remote rule-sets.
func GeoAssetUpdateIntervalMillis(interval string) (int64, error) {
	encoded, _ := json.Marshal(interval)
	var duration badoption.Duration
	if err := duration.UnmarshalJSON(encoded); err != nil {
		return 0, err
	}
	if duration <= 0 {
		return 0, nil
	}
	return max(1, duration.Build().Milliseconds()), nil
}
