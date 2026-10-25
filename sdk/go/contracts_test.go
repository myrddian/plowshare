package plowshare

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
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

func TestContainedRepliesAreAboutTheChildAsked(t *testing.T) {
	const child, foreign = "11111111-1111-1111-1111-111111111111", "33333333-3333-3333-3333-333333333333"
	asked := []byte(`{"conversation":"c","child":"` + child + `"}`)
	for operation, cases := range map[string]map[string]bool{
		"application.child.ceiling": {
			`{"child":"` + child + `"}`:   true,
			`{"child":"` + foreign + `"}`: false,
		},
		"application.child.destroy": {
			`{"child":"` + child + `","collected":["` + foreign + `","` + child + `"],"sources":2}`: true,
			`{"child":"` + foreign + `","collected":["` + foreign + `"],"sources":1}`:               false,
			`{"child":"` + child + `","collected":["` + foreign + `"],"sources":1}`:                 false,
			`{"child":"` + child + `","collected":["` + child + `"],"sources":2}`:                   false,
		},
	} {
		for reply, want := range cases {
			if got := deploymentMatches(operation, asked, []byte(reply)); got != want {
				t.Fatalf("%s %s: got %v want %v", operation, reply, got, want)
			}
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

func TestApplicationCredentialsStayRedactedAndCorrelated(t *testing.T) {
	secret := "pss_" + strings.Repeat("1", 48)
	receipt := ApplicationTokenReceiptDto{Project: "app", RequestId: "11111111-1111-1111-1111-111111111111", TokenId: "22222222-2222-2222-2222-222222222222", Principal: "@service/22222222-2222-2222-2222-222222222222", ExpiresAt: "2026-11-01T00:00:00Z"}
	value := ApplicationCredentialDto{Receipt: receipt, Credential: secret}
	for _, diagnostic := range []string{fmt.Sprint(value), fmt.Sprintf("%+v", value), fmt.Sprintf("%#v", value)} {
		if strings.Contains(diagnostic, secret) {
			t.Fatal("Credential leaked in diagnostic")
		}
	}
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	asked, err := json.Marshal(map[string]string{"project": "app", "requestId": receipt.RequestId})
	if err != nil {
		t.Fatal(err)
	}
	if !deploymentMatches("application.token.issue", asked, data) {
		t.Fatal("Own receipt refused")
	}
	asked, err = json.Marshal(map[string]string{"project": "foreign", "requestId": receipt.RequestId})
	if err != nil {
		t.Fatal(err)
	}
	if deploymentMatches("application.token.issue", asked, data) {
		t.Fatal("Foreign receipt accepted")
	}
}

func TestHostedCoordinatesAndSecretDiagnostics(t *testing.T) {
	data, err := os.ReadFile(filepath.Join(filepath.Dir(os.Getenv("PLOWSHARE_SDK_DTO_FIXTURES")), "hosted-scope-correlations.json"))
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct {
		Operation string          `json:"operation"`
		Request   json.RawMessage `json:"request"`
		Reply     json.RawMessage `json:"reply"`
		Valid     bool            `json:"valid"`
	}
	if err = json.Unmarshal(data, &cases); err != nil {
		t.Fatal(err)
	}
	for _, row := range cases {
		if deploymentMatches(row.Operation, row.Request, row.Reply) != row.Valid {
			t.Fatalf("Hosted coordinate mismatch: %s", row.Operation)
		}
	}
	value := HostedToolCredentialDto{Value: "fixture-private-secret"}
	for _, diagnostic := range []string{fmt.Sprint(value), fmt.Sprintf("%+v", value), fmt.Sprintf("%#v", value)} {
		if strings.Contains(diagnostic, value.Value) {
			t.Fatal("Hosted secret leaked")
		}
	}
}
