package plowshare

import (
	"bytes"
	"context"
	_ "embed"
	"encoding/json"
	"errors"
	"math"
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
	if err := json.Unmarshal(schemaBytes, &catalog); err != nil {
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
	if s.Ref != "" {
		target, ok := catalog.Definitions[strings.TrimPrefix(s.Ref, "#/$defs/")]
		if !ok {
			return nil, contractError
		}
		return decode(target, value, input, depth+1)
	}
	if len(s.AnyOf) > 0 {
		for _, v := range s.AnyOf {
			if result, err := decode(v, value, input, depth+1); err == nil {
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
		if !ok || len(v) > 8*1024*1024 {
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
	data, err := json.Marshal(request)
	if err != nil {
		return result, err
	}
	data, err = project(catalog.Inputs[operation], data, true)
	if err != nil {
		return result, err
	}
	outcome, err := c.request(ctx, operation, data)
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
