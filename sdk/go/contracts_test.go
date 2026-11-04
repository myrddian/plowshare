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

// A started run is a handle and not an outcome, and a cancellation echoes the child asked of.
// The request-side semantic bounds (the agent name, the task, the turn cap) live in the
// TypeScript/Node request gate, as every other contained operation's do; this pins what the Go
// schema expresses and the correlation this SDK performs.
func TestContainedRunAndCancelRoundTrip(t *testing.T) {
	const child, foreign = "11111111-1111-1111-1111-111111111111", "33333333-3333-3333-3333-333333333333"
	turns := float64(3)
	data, err := json.Marshal(ApplicationChildRunRequest{Conversation: "c", Child: child, Agent: "worker", Task: "do it",
		MaxTurns: ScheduleDefinitionDtoLimitsDtoMaxModelCalls{Variant2: &turns}})
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != `{"agent":"worker","child":"`+child+`","conversation":"c","maxTurns":3,"task":"do it"}` {
		t.Fatalf("request encoded as %s", data)
	}
	if _, err = project(catalog.Inputs["application.child.run"], data, true); err != nil {
		t.Fatal(err)
	}
	// null is the one absence the shape allows; every other omission is refused.
	if _, err = project(catalog.Inputs["application.child.run"], []byte(`{"agent":"worker","child":"`+child+`","conversation":"c","maxTurns":null,"task":"do it"}`), true); err != nil {
		t.Fatal(err)
	}
	for _, bad := range []string{
		`{"agent":"worker","child":"` + child + `","conversation":"c","task":"do it"}`,
		`{"child":"` + child + `","conversation":"c","maxTurns":3,"task":"do it"}`,
		`{"agent":"worker","child":"` + child + `","conversation":"c","maxTurns":3}`,
		`{"agent":"worker","child":"` + child + `","conversation":"c","maxTurns":3,"task":"do it","extra":1}`,
	} {
		if _, err = project(catalog.Inputs["application.child.run"], []byte(bad), true); err == nil {
			t.Fatalf("accepted %s", bad)
		}
	}
	// The turn cap and the agent name ARE bounded by the generated schema, so this SDK refuses
	// them without the TypeScript gate's help: maxTurns carries integer/minimum/maximum and agent
	// carries minLength/maxLength/pattern/trimmed. `task` is a bare string shape and is the one
	// request-side bound that is TypeScript-only; the blank task below is accepted here on purpose.
	for _, bound := range []string{
		`"maxTurns":0`, `"maxTurns":-1`, `"maxTurns":1.5`, `"maxTurns":2147483648`,
	} {
		body := `{"agent":"worker","child":"` + child + `","conversation":"c","task":"do it",` + bound + `}`
		if _, err = project(catalog.Inputs["application.child.run"], []byte(body), true); err == nil {
			t.Fatalf("accepted %s", body)
		}
	}
	for _, blank := range []string{`"agent":""`, `"agent":" "`} {
		body := `{"child":"` + child + `","conversation":"c","task":"do it","maxTurns":3,` + blank + `}`
		if _, err = project(catalog.Inputs["application.child.run"], []byte(body), true); err == nil {
			t.Fatalf("accepted %s", body)
		}
	}
	// And the one that is not bounded here, asserted so the ADR's "one of three" is checkable.
	if _, err = project(catalog.Inputs["application.child.run"],
		[]byte(`{"agent":"worker","child":"`+child+`","conversation":"c","task":"","maxTurns":3}`), true); err != nil {
		t.Fatal("a blank task is refused by the TypeScript gate alone, so this SDK accepts it")
	}
	running := `{"child":"` + child + `","job":"job_1","conversation":"cnv_child","agent":"worker"}`
	projected, err := project(catalog.Results["application.child.run"], []byte(running), false)
	if err != nil {
		t.Fatal(err)
	}
	var started ContainedApplicationRunningDto
	if err = json.Unmarshal(projected, &started); err != nil {
		t.Fatal(err)
	}
	// The conversation answered is the child's own, never the container's that was named.
	if started.Job != "job_1" || started.Conversation != "cnv_child" || started.Agent != "worker" {
		t.Fatalf("decoded %+v", started)
	}
	for _, bad := range []string{
		`{"child":"` + child + `","job":"job_1","conversation":"cnv_child"}`,
		`{"child":"` + child + `","job":"job_1","agent":"worker"}`,
	} {
		if _, err = project(catalog.Results["application.child.run"], []byte(bad), false); err == nil {
			t.Fatalf("accepted %s", bad)
		}
	}
	asked := []byte(`{"conversation":"c","child":"` + child + `"}`)
	for operation, cases := range map[string]map[string]bool{
		"application.child.run": {
			running: true,
			`{"child":"` + foreign + `","job":"job_1","conversation":"cnv_child","agent":"worker"}`: false,
		},
		"application.child.cancel": {
			`{"child":"` + child + `"}`:   true,
			`{"child":"` + foreign + `"}`: false,
		},
	} {
		for reply, want := range cases {
			if got := deploymentMatches(operation, asked, []byte(reply)); got != want {
				t.Fatalf("%s %s: got %v want %v", operation, reply, got, want)
			}
		}
	}
	cancel, err := json.Marshal(ApplicationChildCancelRequest{Conversation: "c", Child: child})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = project(catalog.Inputs["application.child.cancel"], cancel, true); err != nil {
		t.Fatal(err)
	}
}

// The generated schema types bytes as a plain number, so the non-negative integer bound is
// enforced by the TypeScript SDK alone; this test pins only what the Go schema does express.
func TestContainedResourcesRoundTripMetadataOnly(t *testing.T) {
	const child, revision = "11111111-1111-1111-1111-111111111111", "44444444-4444-4444-4444-444444444444"
	data, err := json.Marshal(ApplicationChildResourcesRequest{Conversation: "c", Child: child})
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != `{"child":"`+child+`","conversation":"c"}` {
		t.Fatalf("request encoded as %s", data)
	}
	if _, err = project(catalog.Inputs["application.child.resources"], data, true); err != nil {
		t.Fatal(err)
	}
	for _, missing := range []string{`{"conversation":"c"}`, `{"child":"` + child + `"}`, `{"conversation":"c","child":"` + child + `","extra":1}`} {
		if _, err = project(catalog.Inputs["application.child.resources"], []byte(missing), true); err == nil {
			t.Fatalf("accepted %s", missing)
		}
	}
	row := func(fields string) []byte {
		return []byte(`{"resources":[{"path":"plowshare.json","revision":"` + revision + `",` + fields + `}]}`)
	}
	projected, err := project(catalog.Results["application.child.resources"], row(`"kind":"manifest","bytes":12`), false)
	if err != nil {
		t.Fatal(err)
	}
	var decoded ContainedApplicationResourcesDto
	if err = json.Unmarshal(projected, &decoded); err != nil {
		t.Fatal(err)
	}
	want := ContainedApplicationResourceDto{Path: "plowshare.json", Kind: "manifest", Revision: revision, Bytes: 12}
	if len(decoded.Resources) != 1 || decoded.Resources[0] != want {
		t.Fatalf("decoded %+v", decoded)
	}
	for _, bad := range []string{`"kind":"directory","bytes":12`, `"kind":"file"`} {
		if _, err = project(catalog.Results["application.child.resources"], row(bad), false); err == nil {
			t.Fatalf("accepted %s", bad)
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
