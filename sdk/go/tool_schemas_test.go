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
			Name, SchemaSource, ArgumentsSource, Fingerprint, LegacyFingerprint string
			ValidSchema, ValidArguments                                         bool
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
			if matched, err := schemaFingerprintMatches(schema, row.Fingerprint); err != nil || !matched {
				t.Fatal("canonical fingerprint refused", err)
			}
			if matched, err := schemaFingerprintMatches(schema, "0000000000000000000000000000000000000000000000000000000000000000"); err != nil || matched {
				t.Fatal("unrelated fingerprint accepted", err)
			}
			if row.LegacyFingerprint != "" {
				if row.LegacyFingerprint == fingerprint {
					t.Fatal("legacy fixture does not distinguish ordering")
				}
				if matched, err := schemaFingerprintMatches(schema, row.LegacyFingerprint); err != nil || !matched {
					t.Fatal("legacy fingerprint refused", err)
				}
				changed := *schema
				description := "changed"
				changed.Description = &description
				if matched, err := schemaFingerprintMatches(&changed, row.LegacyFingerprint); err != nil || matched {
					t.Fatal("changed schema accepted", err)
				}
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
