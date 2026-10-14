package plowshare

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"math/big"
	"reflect"
	"sort"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf16"
	"unicode/utf8"
)

const ToolSchemaDialect = "https://json-schema.org/draft/2020-12/schema"

// SchemaTypes preserves scalar versus union spelling in the shared vocabulary.
type SchemaTypes struct {
	Values []string
	Union  bool
}

func SchemaType(name string) *SchemaTypes { return &SchemaTypes{Values: []string{name}} }
func (s SchemaTypes) MarshalJSON() ([]byte, error) {
	if !s.Union && len(s.Values) == 1 {
		return json.Marshal(s.Values[0])
	}
	return json.Marshal(s.Values)
}
func (s *SchemaTypes) UnmarshalJSON(data []byte) error {
	if bytes.HasPrefix(bytes.TrimSpace(data), []byte("[")) {
		s.Union = true
		return json.Unmarshal(data, &s.Values)
	}
	var value string
	if err := json.Unmarshal(data, &value); err != nil {
		return err
	}
	s.Values = []string{value}
	s.Union = false
	return nil
}

// SchemaRule selects exactly one boolean or schema variant.
type SchemaRule struct {
	Allowed *bool
	Schema  *SchemaDefinition
}

func (r SchemaRule) MarshalJSON() ([]byte, error) {
	if (r.Allowed == nil) == (r.Schema == nil) {
		return nil, errors.New("invalid schema rule")
	}
	if r.Allowed != nil {
		return json.Marshal(*r.Allowed)
	}
	return json.Marshal(r.Schema)
}
func (r *SchemaRule) UnmarshalJSON(data []byte) error {
	var flag bool
	if json.Unmarshal(data, &flag) == nil {
		r.Allowed = &flag
		r.Schema = nil
		return nil
	}
	r.Allowed = nil
	r.Schema = &SchemaDefinition{}
	return json.Unmarshal(data, r.Schema)
}

// MarshalJSON omits absent fields while preserving empty maps/lists and explicit null literals.
func (s SchemaDefinition) MarshalJSON() ([]byte, error) {
	row := map[string]any{}
	value := reflect.ValueOf(s)
	typ := value.Type()
	for i := 0; i < value.NumField(); i++ {
		field := value.Field(i)
		if !field.IsNil() {
			key := strings.Split(typ.Field(i).Tag.Get("json"), ",")[0]
			row[key] = field.Interface()
		}
	}
	return json.Marshal(row)
}

// UnmarshalJSON is a private boundary; null constants are explicit variants, never omitted fields.
func (s *SchemaDefinition) UnmarshalJSON(data []byte) error {
	type fields SchemaDefinition
	var row map[string]json.RawMessage
	if err := json.Unmarshal(data, &row); err != nil {
		return err
	}
	if row == nil {
		return errors.New("schema must be an object")
	}
	nulls := map[string]bool{}
	for key, value := range row {
		if bytes.Equal(bytes.TrimSpace(value), []byte("null")) {
			if key != "const" && key != "default" {
				return errors.New("null schema keyword")
			}
			nulls[key] = true
			row[key] = json.RawMessage("0")
		}
	}
	fixed, err := json.Marshal(row)
	if err != nil {
		return err
	}
	var decoded fields
	d := json.NewDecoder(bytes.NewReader(fixed))
	d.DisallowUnknownFields()
	if err = d.Decode(&decoded); err != nil {
		return err
	}
	*s = SchemaDefinition(decoded)
	if nulls["const"] {
		value := ToolNull()
		s.Const = &value
	}
	if nulls["default"] {
		value := ToolNull()
		s.Default = &value
	}
	return nil
}

// DecodeToolSchema rejects duplicate/unknown fields before constructing a typed schema.
func DecodeToolSchema(source string) (*SchemaDefinition, error) {
	if len(source) > 50*1024*1024 {
		return nil, errors.New("schema exceeds byte bound")
	}
	d := json.NewDecoder(strings.NewReader(source))
	d.UseNumber()
	if err := toolJSONValue(d, 0); err != nil {
		return nil, err
	}
	if _, err := d.Token(); err != io.EOF {
		return nil, errors.New("trailing schema JSON")
	}
	var schema SchemaDefinition
	if err := json.Unmarshal([]byte(source), &schema); err != nil {
		return nil, err
	}
	if err := ValidateToolSchema(&schema); err != nil {
		return nil, err
	}
	return &schema, nil
}

type schemaBudget struct{ remaining int }

func (b *schemaBudget) step(depth int) error {
	b.remaining--
	if depth > 64 || b.remaining < 0 {
		return errors.New("schema validation exceeds work bound")
	}
	return nil
}
func schemaField(name string) error {
	if name == "" || len(utf16.Encode([]rune(name))) > 1024 {
		return errors.New("invalid tool field")
	}
	for _, c := range name {
		if unicode.IsControl(c) {
			return errors.New("invalid tool field")
		}
	}
	return nil
}
func schemaReference(root *SchemaDefinition, ref string) (*SchemaDefinition, error) {
	if !strings.HasPrefix(ref, "#/$defs/") || strings.Contains(ref[8:], "/") {
		return nil, errors.New("reference must select root definition")
	}
	tail := ref[8:]
	for i := 0; i < len(tail); i++ {
		if tail[i] == '~' && (i+1 >= len(tail) || tail[i+1] != '0' && tail[i+1] != '1') {
			return nil, errors.New("invalid reference escape")
		}
	}
	key := strings.ReplaceAll(strings.ReplaceAll(tail, "~1", "/"), "~0", "~")
	value := root.Defs[key]
	if value == nil {
		return nil, errors.New("unresolved schema reference")
	}
	return value, nil
}

// ValidateToolSchema checks unused branches too; patterns and external resources are refused.
func ValidateToolSchema(root *SchemaDefinition) error {
	if root == nil || root.Type == nil || len(root.Type.Values) != 1 || root.Type.Values[0] != "object" {
		return errors.New("tool schema must have object type")
	}
	budget := &schemaBudget{10000}
	var check func(*SchemaDefinition, int) error
	check = func(s *SchemaDefinition, depth int) error {
		if err := budget.step(depth); err != nil {
			return err
		}
		if s == nil {
			return errors.New("missing schema child")
		}
		if s.Schema != nil && *s.Schema != ToolSchemaDialect || s.Id != nil || s.Anchor != nil || s.Pattern != nil || s.PatternProperties != nil {
			return errors.New("unsupported executable schema feature")
		}
		if s.Ref != nil {
			resolved, err := schemaReference(root, *s.Ref)
			if err != nil {
				return err
			}
			if err := check(resolved, depth+1); err != nil {
				return err
			}
		}
		if s.Type != nil {
			seen := map[string]bool{}
			if len(s.Type.Values) == 0 || !s.Type.Union && len(s.Type.Values) != 1 {
				return errors.New("invalid schema types")
			}
			for _, t := range s.Type.Values {
				if seen[t] || !strings.Contains("|object|array|string|number|integer|boolean|null|", "|"+t+"|") {
					return errors.New("invalid schema type")
				}
				seen[t] = true
			}
		}
		for _, text := range []*string{s.Title, s.Description, s.Schema, s.Ref, s.Format} {
			if text != nil {
				limit := 4096
				if text == s.Description {
					limit = 32768
				}
				if err := toolText(*text, limit, false); err != nil {
					return err
				}
			}
		}
		for _, n := range []*float64{s.Minimum, s.Maximum, s.ExclusiveMinimum, s.ExclusiveMaximum, s.MultipleOf} {
			if n != nil && !toolNumber(*n) {
				return errors.New("nonportable schema number")
			}
		}
		if s.MultipleOf != nil && *s.MultipleOf <= 0 {
			return errors.New("invalid multipleOf")
		}
		for _, n := range []*int{s.MinLength, s.MaxLength, s.MinItems, s.MaxItems, s.MinProperties, s.MaxProperties} {
			if n != nil && (*n < 0 || *n > 2147483647) {
				return errors.New("invalid schema bound")
			}
		}
		for _, pair := range [][2]*int{{s.MinLength, s.MaxLength}, {s.MinItems, s.MaxItems}, {s.MinProperties, s.MaxProperties}} {
			if pair[0] != nil && pair[1] != nil && *pair[0] > *pair[1] {
				return errors.New("inverted schema range")
			}
		}
		if s.Minimum != nil && s.Maximum != nil && *s.Minimum > *s.Maximum {
			return errors.New("inverted schema range")
		}
		for _, m := range []map[string]*SchemaDefinition{s.Defs, s.Properties} {
			if len(m) > 10000 {
				return errors.New("too many schemas")
			}
			for key, child := range m {
				if err := schemaField(key); err != nil {
					return err
				}
				if err := check(child, depth+1); err != nil {
					return err
				}
			}
		}
		for index, list := range [][]*SchemaDefinition{s.PrefixItems, s.AllOf, s.AnyOf, s.OneOf} {
			if len(list) > 10000 || index > 0 && list != nil && len(list) == 0 {
				return errors.New("invalid schema composition")
			}
			for _, child := range list {
				if err := check(child, depth+1); err != nil {
					return err
				}
			}
		}
		for _, child := range []*SchemaDefinition{s.Items, s.Contains, s.Not, s.If, s.Then, s.Else} {
			if child != nil {
				if err := check(child, depth+1); err != nil {
					return err
				}
			}
		}
		if s.AdditionalProperties != nil {
			r := s.AdditionalProperties
			if (r.Allowed == nil) == (r.Schema == nil) {
				return errors.New("invalid schema rule")
			}
			if r.Schema != nil {
				if err := check(r.Schema, depth+1); err != nil {
					return err
				}
			}
		}
		names := map[string]bool{}
		if len(s.Required) > 10000 {
			return errors.New("too many required fields")
		}
		for _, name := range s.Required {
			if names[name] {
				return errors.New("duplicate required field")
			}
			names[name] = true
			if err := schemaField(name); err != nil {
				return err
			}
		}
		for _, literal := range []*ToolValue{s.Const, s.Default} {
			if literal != nil {
				if err := schemaLiteral(*literal); err != nil {
					return err
				}
			}
		}
		for i, list := range [][]ToolValue{s.Enum, s.Examples} {
			if len(list) > 10000 || i == 0 && list != nil && len(list) == 0 {
				return errors.New("invalid schema literals")
			}
			for j, value := range list {
				if err := schemaLiteral(value); err != nil {
					return err
				}
				if i == 0 {
					for _, other := range list[:j] {
						same, err := schemaEqual(value, other, budget, 0)
						if err != nil {
							return err
						}
						if same {
							return errors.New("duplicate enum literal")
						}
					}
				}
			}
		}
		return nil
	}
	return check(root, 0)
}
func schemaLiteral(v ToolValue) error {
	if v.array != nil || v.object != nil {
		return errors.New("schema constants must be scalar")
	}
	_, err := v.MarshalJSON()
	return err
}
func schemaBound(n float64, min, max *float64) bool {
	return (min == nil || n >= *min) && (max == nil || n <= *max)
}
func schemaIntBound(n int, min, max *int) bool {
	return (min == nil || n >= *min) && (max == nil || n <= *max)
}
func schemaType(t string, v ToolValue) bool {
	switch t {
	case "null":
		return v.null
	case "string":
		return v.text != nil
	case "boolean":
		return v.flag != nil
	case "number":
		return v.number != nil
	case "integer":
		return v.number != nil && math.Trunc(*v.number) == *v.number
	case "array":
		return v.array != nil
	case "object":
		return v.object != nil
	}
	return false
}
func schemaEqual(a, b ToolValue, budget *schemaBudget, depth int) (bool, error) {
	if err := budget.step(depth); err != nil {
		return false, err
	}
	if a.number != nil && b.number != nil {
		return *a.number == *b.number, nil
	}
	if a.text != nil && b.text != nil {
		return *a.text == *b.text, nil
	}
	if a.flag != nil && b.flag != nil {
		return *a.flag == *b.flag, nil
	}
	if a.null || b.null {
		return a.null == b.null, nil
	}
	if a.array != nil && b.array != nil {
		if len(*a.array) != len(*b.array) {
			return false, nil
		}
		for i, value := range *a.array {
			equal, err := schemaEqual(value, (*b.array)[i], budget, depth+1)
			if err != nil || !equal {
				return equal, err
			}
		}
		return true, nil
	}
	if a.object != nil && b.object != nil {
		if len(a.object) != len(b.object) {
			return false, nil
		}
		for k, v := range a.object {
			other, present := b.object[k]
			if !present {
				return false, nil
			}
			equal, err := schemaEqual(v, other, budget, depth+1)
			if err != nil || !equal {
				return equal, err
			}
		}
		return true, nil
	}
	return false, nil
}

// ValidateSchemaArguments enforces the offered contract with a shared, fail-closed work budget.
func ValidateSchemaArguments(root *SchemaDefinition, value ToolValue) error {
	if err := ValidateToolSchema(root); err != nil {
		return err
	}
	if err := validateToolValue(value, 0, &schemaBudget{10000}); err != nil {
		return err
	}
	budget := &schemaBudget{10000}
	var match func(*SchemaDefinition, ToolValue, int) (bool, error)
	match = func(s *SchemaDefinition, v ToolValue, depth int) (bool, error) {
		if err := budget.step(depth); err != nil {
			return false, err
		}
		apply := func(child *SchemaDefinition) (bool, error) { return match(child, v, depth+1) }
		if s.Ref != nil {
			child, err := schemaReference(root, *s.Ref)
			if err != nil {
				return false, err
			}
			ok, err := apply(child)
			if err != nil || !ok {
				return ok, err
			}
		}
		if s.Type != nil {
			ok := false
			for _, t := range s.Type.Values {
				if schemaType(t, v) {
					ok = true
				}
			}
			if !ok {
				return false, nil
			}
		}
		if s.Const != nil {
			ok, err := schemaEqual(*s.Const, v, budget, depth+1)
			if err != nil || !ok {
				return ok, err
			}
		}
		if s.Enum != nil {
			found := false
			for _, item := range s.Enum {
				ok, err := schemaEqual(item, v, budget, depth+1)
				if err != nil {
					return false, err
				}
				if ok {
					found = true
				}
			}
			if !found {
				return false, nil
			}
		}
		for _, child := range s.AllOf {
			ok, err := apply(child)
			if err != nil || !ok {
				return ok, err
			}
		}
		if s.AnyOf != nil {
			found := false
			for _, child := range s.AnyOf {
				ok, err := apply(child)
				if err != nil {
					return false, err
				}
				if ok {
					found = true
					break
				}
			}
			if !found {
				return false, nil
			}
		}
		if s.OneOf != nil {
			count := 0
			for _, child := range s.OneOf {
				ok, err := apply(child)
				if err != nil {
					return false, err
				}
				if ok {
					count++
				}
			}
			if count != 1 {
				return false, nil
			}
		}
		if s.Not != nil {
			ok, err := apply(s.Not)
			if err != nil || ok {
				return false, err
			}
		}
		if s.If != nil {
			ok, err := apply(s.If)
			if err != nil {
				return false, err
			}
			child := s.Else
			if ok {
				child = s.Then
			}
			if child != nil {
				ok, err = apply(child)
				if err != nil || !ok {
					return ok, err
				}
			}
		}
		if v.text != nil && !schemaIntBound(utf8.RuneCountInString(*v.text), s.MinLength, s.MaxLength) {
			return false, nil
		}
		if v.number != nil {
			n := *v.number
			if !schemaBound(n, s.Minimum, s.Maximum) || s.ExclusiveMinimum != nil && n <= *s.ExclusiveMinimum || s.ExclusiveMaximum != nil && n >= *s.ExclusiveMaximum {
				return false, nil
			}
			if s.MultipleOf != nil {
				a, _ := new(big.Rat).SetString(strconv.FormatFloat(n, 'g', -1, 64))
				b, _ := new(big.Rat).SetString(strconv.FormatFloat(*s.MultipleOf, 'g', -1, 64))
				if !new(big.Rat).Quo(a, b).IsInt() {
					return false, nil
				}
			}
		}
		if v.object != nil {
			if !schemaIntBound(len(v.object), s.MinProperties, s.MaxProperties) {
				return false, nil
			}
			for _, key := range s.Required {
				if _, ok := v.object[key]; !ok {
					return false, nil
				}
			}
			for key, item := range v.object {
				child := s.Properties[key]
				if child == nil && s.AdditionalProperties != nil {
					if s.AdditionalProperties.Allowed != nil && !*s.AdditionalProperties.Allowed {
						return false, nil
					}
					child = s.AdditionalProperties.Schema
				}
				if child != nil {
					ok, err := match(child, item, depth+1)
					if err != nil || !ok {
						return ok, err
					}
				}
			}
		}
		if v.array != nil {
			items := *v.array
			if !schemaIntBound(len(items), s.MinItems, s.MaxItems) {
				return false, nil
			}
			contained := false
			for i, item := range items {
				child := s.Items
				if i < len(s.PrefixItems) {
					child = s.PrefixItems[i]
				}
				if child != nil {
					ok, err := match(child, item, depth+1)
					if err != nil || !ok {
						return ok, err
					}
				}
				if s.Contains != nil {
					ok, err := match(s.Contains, item, depth+1)
					if err != nil {
						return false, err
					}
					if ok {
						contained = true
					}
				}
				if s.UniqueItems != nil && *s.UniqueItems {
					for _, other := range items[:i] {
						ok, err := schemaEqual(item, other, budget, depth+1)
						if err != nil || ok {
							return false, err
						}
					}
				}
			}
			if s.Contains != nil && !contained {
				return false, nil
			}
		}
		return true, nil
	}
	ok, err := match(root, value, 0)
	if err != nil {
		return err
	}
	if !ok {
		return errors.New("tool arguments do not satisfy offered schema")
	}
	return nil
}

// SchemaFingerprint uses canonical tokens, not language-dependent JSON number spelling.
func SchemaFingerprint(schema *SchemaDefinition) (string, error) {
	return schemaFingerprint(schema, false)
}

// Validation-only compatibility for historical Java requests. Never rewrite a retained request
// or use the legacy identity for new calls; provider journals still fence uncertain effects.
func schemaFingerprintMatches(schema *SchemaDefinition, offered string) (bool, error) {
	current, err := SchemaFingerprint(schema)
	if err != nil || current == offered {
		return current == offered, err
	}
	legacy, err := schemaFingerprint(schema, true)
	return legacy == offered, err
}

func schemaFingerprint(schema *SchemaDefinition, legacyOrdering bool) (string, error) {
	if err := ValidateToolSchema(schema); err != nil {
		return "", err
	}
	data, err := json.Marshal(schema)
	if err != nil {
		return "", err
	}
	var value any
	d := json.NewDecoder(bytes.NewReader(data))
	d.UseNumber()
	if err = d.Decode(&value); err != nil {
		return "", err
	}
	var canonical func(any) string
	canonical = func(v any) string {
		switch x := v.(type) {
		case nil:
			return "z;"
		case bool:
			if x {
				return "b1;"
			}
			return "b0;"
		case string:
			return "s" + hex.EncodeToString([]byte(x)) + ";"
		case json.Number:
			parts := strings.Split(strings.ToLower(string(x)), "e")
			exponent := 0
			if len(parts) == 2 {
				exponent, _ = strconv.Atoi(parts[1])
			}
			negative := strings.HasPrefix(parts[0], "-")
			base := strings.TrimPrefix(parts[0], "-")
			if point := strings.IndexByte(base, '.'); point >= 0 {
				exponent -= len(base) - point - 1
				base = strings.ReplaceAll(base, ".", "")
			}
			base = strings.TrimLeft(base, "0")
			if base == "" {
				return "n0e0;"
			}
			trimmed := strings.TrimRight(base, "0")
			exponent += len(base) - len(trimmed)
			sign := ""
			if negative {
				sign = "-"
			}
			return fmt.Sprintf("n%s%se%d;", sign, trimmed, exponent)
		case []any:
			result := "["
			for _, item := range x {
				result += canonical(item)
			}
			return result + "]"
		case map[string]any:
			keys := make([]string, 0, len(x))
			for key := range x {
				keys = append(keys, key)
			}
			if legacyOrdering {
				sort.Slice(keys, func(i, j int) bool {
					return "s"+hex.EncodeToString([]byte(keys[i]))+";" < "s"+hex.EncodeToString([]byte(keys[j]))+";"
				})
			} else {
				sort.Strings(keys)
			}
			result := "{"
			for _, key := range keys {
				result += canonical(key) + canonical(x[key])
			}
			return result + "}"
		}
		panic("unreachable validated schema JSON")
	}
	hash := sha256.Sum256([]byte(canonical(value)))
	return hex.EncodeToString(hash[:]), nil
}
