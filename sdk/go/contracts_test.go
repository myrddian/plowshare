package plowshare

import (
	"encoding/json"
	"os"
	"testing"
)

func TestSharedBoundaryCases(t *testing.T) {
	data, err := os.ReadFile(os.Getenv("PLOWSHARE_SDK_DTO_FIXTURES"))
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct {
		Boundary  string          `json:"boundary"`
		Operation string          `json:"operation"`
		Value     json.RawMessage `json:"value"`
		Valid     bool            `json:"valid"`
	}
	if err = json.Unmarshal(data, &cases); err != nil {
		t.Fatal(err)
	}
	for _, test := range cases {
		t.Run(test.Boundary+"/"+test.Operation, func(t *testing.T) {
			var err error
			switch test.Boundary {
			case "input":
				_, err = project(catalog.Inputs[test.Operation], test.Value, true)
			case "result":
				_, err = project(catalog.Results[test.Operation], test.Value, false)
			case "push":
				_, err = decodePush(test.Value)
			default:
				t.Fatal("unknown fixture boundary")
			}
			if (err == nil) != test.Valid {
				t.Fatalf("accepted=%v expected=%v: %s", err == nil, test.Valid, test.Value)
			}
		})
	}
}
func TestOmittedAndExplicitNull(t *testing.T) {
	request := ConversationOpenRequest{}
	data, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "{}" {
		t.Fatal("absent fields encoded")
	}
	request.Project = &TierDtoProject{Variant1: &Unit{}}
	data, err = json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	var object map[string]json.RawMessage
	if err = json.Unmarshal(data, &object); err != nil {
		t.Fatal(err)
	}
	if string(object["project"]) != "null" {
		t.Fatal("explicit null lost")
	}
}
