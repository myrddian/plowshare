package plowshare

import (
	"bytes"
	"context"
	_ "embed"
	"encoding/json"
	"errors"
	"math"
	"reflect"
	"slices"
	"strings"
)

// Unit represents a protocol null (including operations without a reply body).
type Unit struct{}

func (Unit) MarshalJSON() ([]byte, error) { return []byte("null"), nil }
func (*Unit) UnmarshalJSON(data []byte) error {
	if string(data) != "null" {
		return errors.New("expected protocol null")
	}
	return nil
}

//go:embed schemas.json
var schemaBytes []byte
var catalog struct {
	Inputs      map[string]shape `json:"inputs"`
	Results     map[string]shape `json:"results"`
	Pushes      shape            `json:"pushes"`
	Definitions map[string]shape `json:"$defs"`
}

type shape struct {
	constraints
	Ref         string           `json:"$ref"`
	AnyOf       []shape          `json:"anyOf"`
	Const       json.RawMessage  `json:"const"`
	Type        string           `json:"type"`
	Items       *shape           `json:"items"`
	PrefixItems []shape          `json:"prefixItems"`
	MinItems    int              `json:"minItems"`
	MaxItems    *int             `json:"maxItems"`
	Properties  map[string]shape `json:"properties"`
	Required    []string         `json:"required"`
	Additional  json.RawMessage  `json:"additionalProperties"`
	Forbidden   bool             `json:"forbidden"`
	Optional    bool             `json:"optional"`
}

func init() {
	// Rule constants and frame numbers must share the same representation for comparisons.
	decoder := json.NewDecoder(bytes.NewReader(schemaBytes))
	decoder.UseNumber()
	if err := decoder.Decode(&catalog); err != nil {
		panic("invalid generated SDK catalog")
	}
}
func parse(data []byte) (any, error) {
	if len(data) == 0 {
		return nil, nil
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber()
	var value any
	if err := decoder.Decode(&value); err != nil {
		return nil, err
	}
	return value, nil
}

var contractError = errors.New("value does not match the owned protocol contract")

// decode copies known fields after validating every nested value. Unknown output
// fields are omitted for forward compatibility; input fields are strictly allowlisted.
func decode(s shape, value any, input bool, depth int) (any, error) {
	if err := check(s, value); err != nil {
		return nil, err
	}
	if depth > 64 || s.Forbidden {
		return nil, contractError
	}
	// Count JSON nesting, not trusted generated graph indirections.
	if s.Ref != "" {
		target, ok := catalog.Definitions[strings.TrimPrefix(s.Ref, "#/$defs/")]
		if !ok {
			return nil, contractError
		}
		return decode(target, value, input, depth)
	}
	if len(s.AnyOf) > 0 {
		for _, v := range s.AnyOf {
			if result, err := decode(v, value, input, depth); err == nil {
				return result, nil
			}
		}
		return nil, contractError
	}
	if s.Const != nil {
		literal, err := parse(s.Const)
		if err != nil {
			return nil, err
		}
		a, _ := json.Marshal(value)
		b, _ := json.Marshal(literal)
		if !bytes.Equal(a, b) {
			return nil, contractError
		}
		return value, nil
	}
	if s.Optional || s.Type == "null" {
		if value != nil {
			return nil, contractError
		}
		return nil, nil
	}
	switch s.Type {
	case "string":
		v, ok := value.(string)
		if !ok || len(v) > 50*1024*1024 {
			return nil, contractError
		}
		return v, nil
	case "boolean":
		v, ok := value.(bool)
		if !ok {
			return nil, contractError
		}
		return v, nil
	case "number":
		v, ok := value.(json.Number)
		if !ok {
			return nil, contractError
		}
		n, err := v.Float64()
		if err != nil || math.IsInf(n, 0) || math.IsNaN(n) {
			return nil, contractError
		}
		return v, nil
	case "array":
		entries, ok := value.([]any)
		max := 10000
		if s.MaxItems != nil {
			max = *s.MaxItems
		}
		if !ok || len(entries) < s.MinItems || len(entries) > max {
			return nil, contractError
		}
		result := make([]any, len(entries))
		for i, v := range entries {
			item := s.Items
			if i < len(s.PrefixItems) {
				item = &s.PrefixItems[i]
			}
			if item == nil {
				return nil, contractError
			}
			r, err := decode(*item, v, input, depth+1)
			if err != nil {
				return nil, err
			}
			result[i] = r
		}
		return result, nil
	case "object":
		row, ok := value.(map[string]any)
		if !ok || len(row) > 10000 {
			return nil, contractError
		}
		for _, key := range s.Required {
			if _, ok := row[key]; !ok {
				return nil, contractError
			}
		}
		result := map[string]any{}
		for key, v := range row {
			if key == "__proto__" || key == "constructor" || key == "prototype" {
				return nil, contractError
			}
			field, known := s.Properties[key]
			if !known {
				if len(s.Additional) > 0 && s.Additional[0] == '{' {
					if json.Unmarshal(s.Additional, &field) != nil {
						return nil, contractError
					}
				} else if input {
					return nil, contractError
				} else {
					continue
				}
			}
			r, err := decode(field, v, input, depth+1)
			if err != nil {
				return nil, err
			}
			result[key] = r
		}
		return result, nil
	}
	return nil, contractError
}
func project(s shape, data []byte, input bool) ([]byte, error) {
	value, err := parse(data)
	if err != nil {
		return nil, err
	}
	result, err := decode(s, value, input, 0)
	if err != nil {
		return nil, err
	}
	return json.Marshal(result)
}
func matches(key string, data []byte) bool {
	_, err := project(catalog.Definitions[key], data, false)
	return err == nil
}
func requestTyped[T any](ctx context.Context, c *Client, operation string, request any) (Reply[T], error) {
	var result Reply[T]
	reservation, err := c.admit()
	if err != nil {
		return result, err
	}
	defer reservation.release()
	if err = reservation.convert(ctx); err != nil {
		return result, err
	}
	data, err := json.Marshal(request)
	if err != nil {
		return result, err
	}
	data, err = project(catalog.Inputs[operation], data, true)
	if err != nil {
		return result, err
	}
	outcome, err := c.requestReserved(ctx, operation, data, reservation)
	if err != nil {
		return result, err
	}
	result.Code, result.Said = outcome.Code, outcome.Said
	if result.Successful() {
		data, err = project(catalog.Results[operation], outcome.Payload, false)
		if err == nil {
			err = json.Unmarshal(data, &result.payload)
		}
		if err != nil {
			return Reply[T]{}, &TransportError{Delivery: InvalidResponse, Cause: err}
		}
	}
	return result, nil
}
func decodePush(data []byte) (ServerPush, error) {
	var result ServerPush
	value, err := parse(data)
	if err != nil {
		return result, err
	}
	row, ok := value.(map[string]any)
	if !ok {
		return result, contractError
	}
	if version, ok := row["protocol_version"]; ok {
		if version != ProtocolVersion || row["id"] != nil {
			return result, contractError
		}
		payload, ok := row["payload"].(map[string]any)
		if !ok {
			return result, contractError
		}
		payload["type"] = row["type"]
		row = payload
	}
	projected, err := decode(catalog.Pushes, row, false, 0)
	if err != nil {
		return result, err
	}
	wire, err := json.Marshal(projected)
	if err != nil {
		return result, err
	}
	err = json.Unmarshal(wire, &result)
	return result, err
}

// Transport boundary correlation supplements schema validation for retained deployment receipts.
func deploymentMatches(operation string, request, reply []byte) bool {
	if strings.HasPrefix(operation, "tool.scope.host.") || strings.HasPrefix(operation, "tool.scope.worker.") {
		return hostedMatches(operation, request, reply)
	}
	if slices.Contains([]string{"application.identity.status", "application.token.issue", "application.token.receipt"}, operation) {
		type identityCoordinates struct {
			Project   string `json:"project"`
			RequestID string `json:"requestId"`
			TokenID   string `json:"tokenId"`
			Principal string `json:"principal"`
		}
		var asked identityCoordinates
		if json.Unmarshal(request, &asked) != nil {
			return false
		}
		if operation == "application.identity.status" {
			var status struct {
				Project  string               `json:"project"`
				Identity *identityCoordinates `json:"identity"`
			}
			if json.Unmarshal(reply, &status) != nil || status.Project != asked.Project {
				return false
			}
			return status.Identity == nil || status.Identity.Project == status.Project && status.Identity.Principal == "@service/"+status.Identity.TokenID
		}
		var answered identityCoordinates
		if operation == "application.token.issue" {
			var credential struct {
				Receipt identityCoordinates `json:"receipt"`
			}
			if json.Unmarshal(reply, &credential) != nil {
				return false
			}
			answered = credential.Receipt
		} else if json.Unmarshal(reply, &answered) != nil {
			return false
		}
		return answered.Project == asked.Project && strings.EqualFold(answered.RequestID, asked.RequestID) && answered.Principal == "@service/"+answered.TokenID
	}
	if !slices.Contains([]string{"application.deploy", "application.activate", "application.deployment.status", "application.deployment.receipt"}, operation) {
		return true
	}
	type coordinates struct {
		Project   string `json:"project"`
		RequestID string `json:"requestId"`
		Revision  string `json:"revision"`
		Release   struct {
			Revision string `json:"revision"`
		} `json:"release"`
	}
	var asked, answered coordinates
	if json.Unmarshal(request, &asked) != nil || json.Unmarshal(reply, &answered) != nil || asked.Project != answered.Project {
		return false
	}
	return operation == "application.deployment.status" || strings.EqualFold(asked.RequestID, answered.RequestID) && (operation != "application.activate" || strings.EqualFold(asked.Revision, answered.Release.Revision))
}

// Closed hosted reply correlation occurs at the JSON boundary before typed replies escape.
func hostedMatches(operation string, request, reply []byte) bool {
	var asked map[string]any
	if json.Unmarshal(request, &asked) != nil {
		return false
	}
	var value any
	if json.Unmarshal(reply, &value) != nil {
		return false
	}
	if operation == "tool.scope.host.list" {
		rows, ok := value.([]any)
		if !ok {
			return false
		}
		for _, entry := range rows {
			row, ok := entry.(map[string]any)
			if !ok || row["project"] != asked["project"] {
				return false
			}
		}
		return true
	}
	row, ok := value.(map[string]any)
	if !ok {
		return false
	}
	if operation == "tool.scope.worker.credential" || operation == "tool.scope.worker.read" {
		keys := []string{"binding", "registration", "invocation"}
		if strings.HasSuffix(operation, "credential") {
			keys = append(keys, "purpose")
		}
		for _, k := range keys {
			if !reflect.DeepEqual(row[k], asked[k]) {
				return false
			}
		}
		return true
	}
	if operation == "tool.scope.worker.status" {
		return reflect.DeepEqual(row["binding"], asked["binding"])
	}
	nested := map[string]string{"tool.scope.worker.publish": "publication", "tool.scope.worker.consume": "consumption", "tool.scope.worker.ack": "acknowledgement"}[operation]
	if nested != "" {
		port, ok := asked[nested].(map[string]any)
		if !ok {
			return false
		}
		for _, k := range []string{"project", "topic", "group", "consumerId", "requestId", "batchId"} {
			a, known := port[k]
			b, returned := row[k]
			if known && returned && !reflect.DeepEqual(a, b) {
				return false
			}
		}
		if operation == "tool.scope.worker.consume" {
			events, ok := row["events"].([]any)
			limit, bounded := port["limit"].(float64)
			if !ok || !bounded || float64(len(events)) > limit {
				return false
			}
		}
		return true
	}
	binding := row
	if operation != "tool.scope.host.status" {
		binding, ok = row["binding"].(map[string]any)
		if !ok {
			return false
		}
	}
	for _, k := range []string{"project", "binding"} {
		if a, known := asked[k]; known && !reflect.DeepEqual(a, binding[k]) {
			return false
		}
	}
	if operation == "tool.scope.worker.renew" {
		return reflect.DeepEqual(row["registration"], asked["registration"])
	}
	if operation == "tool.scope.worker.register" {
		return reflect.DeepEqual(row["registration"], asked["requestId"]) && reflect.DeepEqual(binding["generation"], asked["expectedGeneration"])
	}
	if id, known := asked["requestId"]; known && !reflect.DeepEqual(id, row["requestId"]) {
		return false
	}
	if operation == "tool.scope.host.receipt" || operation == "tool.scope.host.status" {
		return true
	}
	generation := float64(1)
	if asked["expectedGeneration"] != nil {
		expected, ok := asked["expectedGeneration"].(float64)
		if !ok {
			return false
		}
		generation = expected + 1
	}
	if row["operation"] != operation || binding["generation"] != generation {
		return false
	}
	if operation == "tool.scope.host.save" {
		return reflect.DeepEqual(binding["definition"], asked["definition"])
	}
	return binding["enabled"] == (asked["action"] == "ENABLE") && binding["deleted"] == (asked["action"] == "DELETE")
}
