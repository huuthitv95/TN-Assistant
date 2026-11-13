package main

import "math"

var segmentFields = []string{"start_latitude", "start_longitude", "end_latitude", "end_longitude"}

// Optional on older point-only data. Partial or inconsistent geometry must not
// become a usable segment just because an administrator supplied a speed.
func validateManagedSegment(m Object) error {
	count := 0
	for _, key := range segmentFields {
		if _, exists := m[key]; exists {
			count++
		}
	}
	if count == 0 {
		return nil
	}
	invalid := func() error {
		return problem(400, "INVALID_ROAD_SEGMENT", "Road segment geometry, directed bearing and road_group are required")
	}
	if count != 4 || str(m, "kind") != "road" || str(m, "road_group") == "" || len(str(m, "road_group")) > 160 {
		return invalid()
	}
	values := make([]float64, 4)
	for i, key := range segmentFields {
		bound := 90.0
		if i%2 == 1 {
			bound = 180
		}
		value, err := managedNumber(m, key, -bound, bound)
		if err != nil {
			return invalid()
		}
		values[i] = value
	}
	a, b, c, d := values[0], values[1], values[2], values[3]
	length := managedDistance(a, b, c, d)
	if length < 0.1 || length > 100 || math.Abs(b-d) > 180 {
		return invalid()
	}
	// The existing point index must cover each short segment. Anchor at its
	// midpoint so a segment cannot be hidden behind unrelated coordinates.
	lat, err := managedNumber(m, "latitude", -90, 90)
	if err != nil {
		return invalid()
	}
	lon, err := managedNumber(m, "longitude", -180, 180)
	if err != nil || managedDistance(lat, lon, (a+c)/2, (b+d)/2) > 1 {
		return invalid()
	}
	angle, err := managedNumber(m, "bearing", 0, 359.999999)
	if err != nil {
		return invalid()
	}
	rad := math.Pi / 180
	heading := math.Mod(math.Atan2(math.Sin((d-b)*rad)*math.Cos(c*rad), math.Cos(a*rad)*math.Sin(c*rad)-math.Sin(a*rad)*math.Cos(c*rad)*math.Cos((d-b)*rad))*180/math.Pi+360, 360)
	diff := math.Abs(angle - heading)
	if math.Min(diff, 360-diff) > 5 {
		return invalid()
	}
	return nil
}

func managedRoadCandidate(m Object) Object {
	out := Object{}
	for _, key := range []string{"id", "road_group", "latitude", "longitude", "bearing", "name", "max_speed", "source", "source_updated_at", "license", "start_latitude", "start_longitude", "end_latitude", "end_longitude"} {
		if value, exists := m[key]; exists {
			out[key] = value
		}
	}
	return out
}
