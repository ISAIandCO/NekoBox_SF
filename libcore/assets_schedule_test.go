package libcore

import "testing"

func TestGeoAssetUpdateIntervalUsesRuleSetDurations(t *testing.T) {
	for interval, expected := range map[string]int64{
		"72h":   259200000,
		"1h30m": 5400000,
		"3600s": 3600000,
		"0":     0,
		"1ns":   1,
	} {
		got, err := GeoAssetUpdateIntervalMillis(interval)
		if got != expected || err != nil {
			t.Errorf("%s: got %d, %v; want %d", interval, got, err, expected)
		}
	}
	if _, err := GeoAssetUpdateIntervalMillis("72"); err == nil {
		t.Fatal("accepted a nonzero duration without a unit")
	}
}
