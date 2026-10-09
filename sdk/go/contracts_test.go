package plowshare

import (
	"encoding/json"
	"os"
	"strings"
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

func TestDeploymentReceiptCoordinates(t *testing.T) {
	asked := []byte(`{"project":"app","requestId":"11111111-1111-1111-1111-111111111111","revision":"22222222-2222-2222-2222-222222222222"}`)
	valid := []byte(`{"project":"app","requestId":"11111111-1111-1111-1111-111111111111","release":{"revision":"22222222-2222-2222-2222-222222222222"}}`)
	if !deploymentMatches("application.activate", asked, valid) {
		t.Fatal("matching receipt refused")
	}
	for _, reply := range []string{
		`{"project":"foreign","requestId":"11111111-1111-1111-1111-111111111111","release":{"revision":"22222222-2222-2222-2222-222222222222"}}`,
		`{"project":"app","requestId":"33333333-3333-3333-3333-333333333333","release":{"revision":"22222222-2222-2222-2222-222222222222"}}`,
		`{"project":"app","requestId":"11111111-1111-1111-1111-111111111111","release":{"revision":"33333333-3333-3333-3333-333333333333"}}`,
	} {
		if deploymentMatches("application.activate", asked, []byte(reply)) {
			t.Fatal("foreign receipt accepted")
		}
	}
}

func TestRelayPublishRawUtf8Limit(t *testing.T) {
	const maximum = 50 * 1024 * 1024
	for _, text := range []string{strings.Repeat("x", maximum), strings.Repeat("é", maximum/2)} {
		asked := RelayPublishRequest{Project: "fixture", Topic: "large.events", RequestId: "11111111-1111-1111-1111-111111111111", OccurredAt: "2026-10-09T00:00:00Z", Text: text}
		data, err := json.Marshal(asked)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := project(catalog.Inputs["relay.publish"], data, true); err != nil {
			t.Fatal(err)
		}
		asked.Text += "x"
		data, err = json.Marshal(asked)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := project(catalog.Inputs["relay.publish"], data, true); err == nil {
			t.Fatal("Oversized UTF-8 text accepted")
		}
	}
}
