package plowshare

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestSharedToolSchemaProfile(t *testing.T) {
	data, err := os.ReadFile(filepath.Join(filepath.Dir(os.Getenv("PLOWSHARE_SDK_DTO_FIXTURES")), "tool-schemas.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Cases []struct {
			Name, SchemaSource, ArgumentsSource, Fingerprint string
			ValidSchema, ValidArguments                      bool
		}
	}
	if err = json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	for _, row := range fixture.Cases {
		t.Run(row.Name, func(t *testing.T) {
			schema, err := DecodeToolSchema(row.SchemaSource)
			if !row.ValidSchema {
				if err == nil {
					t.Fatal("invalid schema accepted")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			fingerprint, err := SchemaFingerprint(schema)
			if err != nil || fingerprint != row.Fingerprint {
				t.Fatalf("fingerprint %s != %s: %v", fingerprint, row.Fingerprint, err)
			}
			var value ToolValue
			err = json.Unmarshal([]byte(row.ArgumentsSource), &value)
			if err == nil {
				err = ValidateSchemaArguments(schema, value)
			}
			if (err == nil) != row.ValidArguments {
				t.Fatalf("argument acceptance differs: %v", err)
			}
		})
	}
}
