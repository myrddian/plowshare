package plowshare

import (
	"encoding/json"
	"math"
	"net/url"
	"reflect"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode/utf16"
)

// constraints are boundary metadata, not application property bags.
type constraints struct {
	Web              bool     `json:"web"`
	SafePrecision    bool     `json:"safePrecision"`
	Timestamp        bool     `json:"timestamp"`
	MinLength        int      `json:"minLength"`
	MaxLength        *int     `json:"maxLength"`
	Nonblank         bool     `json:"nonblank"`
	Trimmed          bool     `json:"trimmed"`
	NoNul            bool     `json:"noNul"`
	Pattern          string   `json:"pattern"`
	Disallow         []string `json:"disallow"`
	SafeRelativePath bool     `json:"safeRelativePath"`
	Integer          bool     `json:"integer"`
	Minimum          *float64 `json:"minimum"`
	Maximum          *float64 `json:"maximum"`
	UniqueItems      bool     `json:"uniqueItems"`
	Element          *shape   `json:"element"`
	MaxProperties    *int     `json:"maxProperties"`
	KeyPattern       string   `json:"keyPattern"`
	Values           *shape   `json:"values"`
	Rules            []any    `json:"rules"`
}

func get(row any, path string) any {
	for _, k := range strings.Split(path, ".") {
		object, ok := row.(map[string]any)
		if !ok {
			return nil
		}
		row = object[k]
	}
	return row
}
func present(v any) bool {
	if v == nil {
		return false
	}
	switch x := v.(type) {
	case bool:
		return x
	case string:
		return x != ""
	case []any:
		return len(x) > 0
	}
	return true
}
func rule(expr any, row any) any {
	expression, ok := expr.(map[string]any)
	if !ok {
		return expr
	}
	for op, args := range expression {
		switch op {
		case "get":
			return get(row, args.(string))
		case "has":
			object, ok := row.(map[string]any)
			if !ok {
				return false
			}
			_, ok = object[args.(string)]
			return ok
		case "present":
			return present(get(row, args.(string)))
		case "exactlyOne", "atMostOne":
			count := 0
			for _, path := range args.([]any) {
				if present(get(row, path.(string))) {
					count++
				}
			}
			if op == "exactlyOne" {
				return count == 1
			}
			return count <= 1
		case "not":
			return !present(rule(args, row))
		}
		values := []any{}
		for _, a := range args.([]any) {
			values = append(values, rule(a, row))
		}
		switch op {
		case "and":
			for _, v := range values {
				if !present(v) {
					return false
				}
			}
			return true
		case "or":
			for _, v := range values {
				if present(v) {
					return true
				}
			}
			return false
		case "eq":
			return reflect.DeepEqual(values[0], values[1])
		case "gt":
			a, aok := values[0].(json.Number)
			b, bok := values[1].(json.Number)
			if !aok || !bok {
				return false
			}
			an, e1 := a.Float64()
			bn, e2 := b.Float64()
			return e1 == nil && e2 == nil && an > bn
		}
	}
	return false
}
func pattern(p, s string) bool { valid, err := regexp.MatchString(p, s); return err == nil && valid }
func check(s shape, value any) error {
	if value == nil {
		return nil
	}
	switch v := value.(type) {
	case string:
		if s.Web {
			address, err := url.Parse(v)
			if err != nil {
				return contractError
			}
			if address.Port() != "" {
				port, err := strconv.Atoi(address.Port())
				if err != nil || port < 1 || port > 65535 {
					return contractError
				}
			}
		}
		if s.Timestamp {
			if _, err := time.Parse(time.RFC3339Nano, v); err != nil {
				return contractError
			}
		}
		n := len(utf16.Encode([]rune(v)))
		maximum := 8 * 1024 * 1024
		if s.MaxLength != nil {
			maximum = *s.MaxLength
		}
		if n < s.MinLength || n > maximum || s.Nonblank && strings.TrimSpace(v) == "" || s.Trimmed && strings.TrimSpace(v) != v || s.NoNul && strings.ContainsRune(v, 0) || s.Pattern != "" && !pattern(s.Pattern, v) || slices.Contains(s.Disallow, v) || s.SafeRelativePath && (strings.HasPrefix(v, "/") || strings.Contains(v, "\\") || slices.Contains(strings.Split(v, "/"), "..")) {
			return contractError
		}
	case json.Number:
		n, err := v.Float64()
		if s.SafePrecision {
			if math.Trunc(n) == n && math.Abs(n) > 9007199254740991 {
				return contractError
			}
			literal := strconv.FormatFloat(n, 'g', -1, 64)
			if i := strings.IndexAny(literal, "eE"); i >= 0 {
				exponent, _ := strconv.Atoi(literal[i+1:])
				if exponent < -18 || exponent > 18 {
					return contractError
				}
			} else if i := strings.Index(literal, "."); i >= 0 && len(literal[i+1:]) > 18 {
				return contractError
			}
		}
		if err != nil || s.Integer && (math.IsInf(n, 0) || math.IsNaN(n) || math.Trunc(n) != n) || s.Minimum != nil && n < *s.Minimum || s.Maximum != nil && n > *s.Maximum {
			return contractError
		}
	case []any:
		maximum := 10000
		if s.MaxItems != nil {
			maximum = *s.MaxItems
		}
		if len(v) < s.MinItems || len(v) > maximum {
			return contractError
		}
		seen := map[string]bool{}
		for _, entry := range v {
			if s.UniqueItems {
				wire, err := json.Marshal(entry)
				if err != nil || seen[string(wire)] {
					return contractError
				}
				seen[string(wire)] = true
			}
			if s.Element != nil {
				if err := check(*s.Element, entry); err != nil {
					return err
				}
			}
		}
	case map[string]any:
		maximum := 10000
		if s.MaxProperties != nil {
			maximum = *s.MaxProperties
		}
		if len(v) > maximum {
			return contractError
		}
		for key, entry := range v {
			if s.KeyPattern != "" && !pattern(s.KeyPattern, key) {
				return contractError
			}
			if s.Values != nil {
				if err := check(*s.Values, entry); err != nil {
					return err
				}
			}
		}
	}
	for _, r := range s.Rules {
		if !present(rule(r, value)) {
			return contractError
		}
	}
	return nil
}
