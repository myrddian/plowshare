package plowshare

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

var fixtureBinding = ToolBinding{"fixture", "scanner", "provider"}
var fixtureDeclaration = ToolDeclaration{"inspect", "Inspect fixture", []ToolParameter{{"value", "NUMBER", "", true}}, 30}

const fixtureInvocation = "11111111-1111-1111-1111-111111111111"

func toolFixture(t *testing.T) (string, []struct {
	Name   string
	Source string
	Valid  bool
}, string) {
	t.Helper()
	path := filepath.Join(filepath.Dir(os.Getenv("PLOWSHARE_SDK_DTO_FIXTURES")), "relay-tools.json")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		ResultRequestID string
		Cases           []struct {
			Name   string
			Source string
			Valid  bool
		}
	}
	if err = json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture.Cases[0].Source, fixture.Cases, fixture.ResultRequestID
}
func TestToolSharedBoundaries(t *testing.T) {
	_, cases, expected := toolFixture(t)
	actual, err := ToolResultRequestID(fixtureInvocation)
	if err != nil || actual != expected {
		t.Fatal("result identity differs")
	}
	for _, sample := range cases {
		t.Run(sample.Name, func(t *testing.T) {
			call, err := DecodeToolCall(sample.Source)
			if err == nil {
				err = fixtureDeclaration.Validate(call.Arguments)
			}
			if (err == nil) != sample.Valid {
				t.Fatalf("acceptance differs: %v", err)
			}
		})
	}
}

type fixtureJournal struct{ rows map[string]ToolReceipt }

func (j *fixtureJournal) Identity() string {
	config, _ := ToolDeploymentConfig(fixtureBinding, []ToolDeclaration{fixtureDeclaration})
	return toolHash(config)
}
func (j *fixtureJournal) All() ([]ToolReceipt, error) {
	result := []ToolReceipt{}
	for _, row := range j.rows {
		result = append(result, row)
	}
	return result, nil
}
func (j *fixtureJournal) Save(row ToolReceipt) error { j.rows[row.InvocationID] = row; return nil }

type fixtureToolPorts struct {
	request      string
	publications int
	loseReply    bool
	published    RelayPublishRequest
}

func strVariant(value string) TierDtoProject { return TierDtoProject{Variant2: &value} }
func (p *fixtureToolPorts) RelayConsume(_ context.Context, q RelayConsumeRequest) (Reply[RelayBatchDto], error) {
	event := RelayEventDto{Position: "1", EventId: fixtureInvocation, Publisher: "tool-runtime", CorrelationId: strVariant(fixtureInvocation), Payload: RelayEventDtoPayloadDto{Kind: "TEXT", Text: strVariant(p.request)}}
	return Reply[RelayBatchDto]{Code: "OK", payload: RelayBatchDto{Project: q.Project, Topic: q.Topic, Group: q.Group, ConsumerId: q.ConsumerId, Status: "DATA", BatchId: strVariant(fixtureInvocation), Fence: strVariant("1"), Through: "1", Events: []RelayEventDto{event}}}, nil
}
func (p *fixtureToolPorts) RelayAck(_ context.Context, q RelayAckRequest) (Reply[RelayAckResultDto], error) {
	return Reply[RelayAckResultDto]{Code: "OK", payload: RelayAckResultDto{Project: q.Project, Topic: q.Topic, Group: q.Group, BatchId: q.BatchId, Through: "1", Gap: false}}, nil
}
func (p *fixtureToolPorts) RelayPublish(_ context.Context, q RelayPublishRequest) (Reply[RelayPublishResultDto], error) {
	p.publications++
	p.published = q
	if p.loseReply {
		return Reply[RelayPublishResultDto]{}, &TransportError{Delivery: Unknown, Cause: errors.New("lost reply")}
	}
	return Reply[RelayPublishResultDto]{Code: "OK", payload: RelayPublishResultDto{Project: q.Project, Topic: q.Topic, RequestId: q.RequestId, Position: "1", PublishedAt: time.Now().UTC().Format(time.RFC3339Nano)}}, nil
}
func (p *fixtureToolPorts) RelayLog(_ context.Context, q RelayLogRequest) (Reply[RelayLogResultDto], error) {
	event := RelayEventDto{Position: "1", EventId: p.published.RequestId, Publisher: "sdk:" + toolHash(fixtureBinding.Account), OccurredAt: p.published.OccurredAt, CorrelationId: strVariant(fixtureInvocation), CausationId: strVariant(fixtureInvocation), Payload: RelayEventDtoPayloadDto{Kind: "TEXT", Text: strVariant(p.published.Text)}}
	return Reply[RelayLogResultDto]{Code: "OK", payload: RelayLogResultDto{Scope: RelayLogResultDtoScopeDto{Project: strVariant(fixtureBinding.Project)}, Topic: RelayTopicDto{Name: q.Variant1.Topic, Through: "1"}, After: "0", Next: "1", Events: []RelayEventDto{event}}}, nil
}
func TestToolLostResultAndRestartDoNotRepeatEffects(t *testing.T) {
	source, _, _ := toolFixture(t)
	journal := &fixtureJournal{rows: map[string]ToolReceipt{}}
	ports := &fixtureToolPorts{request: source, loseReply: true}
	executions := 0
	tools := []RegisteredTool{{fixtureDeclaration, func(_ context.Context, call ToolCall) (ToolResult, error) {
		executions++
		return ToolResult{"COMPLETED", "observed"}, nil
	}}}
	provider, err := NewToolProvider(ports, fixtureBinding, tools, journal)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = provider.Poll(context.Background()); err == nil {
		t.Fatal("expected lost reply")
	}
	restarted, err := NewToolProvider(ports, fixtureBinding, tools, journal)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = restarted.Poll(context.Background()); err == nil {
		t.Fatal("uncertainty allowed another pass")
	}
	if count, err := restarted.Reconcile(context.Background()); err != nil || count != 1 {
		t.Fatalf("reconcile: %d %v", count, err)
	}
	if _, err = restarted.Poll(context.Background()); err != nil {
		t.Fatal(err)
	}
	if executions != 1 || ports.publications != 1 || journal.rows[fixtureInvocation].Phase != "done" {
		t.Fatal("effects were repeated")
	}
}
func TestToolInterruptedIntentNeverExecutes(t *testing.T) {
	source, _, _ := toolFixture(t)
	journal := &fixtureJournal{rows: map[string]ToolReceipt{fixtureInvocation: {InvocationID: fixtureInvocation, Request: source, Phase: "executing"}}}
	ports := &fixtureToolPorts{request: source}
	executions := 0
	provider, err := NewToolProvider(ports, fixtureBinding, []RegisteredTool{{fixtureDeclaration, func(context.Context, ToolCall) (ToolResult, error) {
		executions++
		return ToolResult{"COMPLETED", "unexpected"}, nil
	}}}, journal)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = provider.Poll(context.Background()); err != nil {
		t.Fatal(err)
	}
	if executions != 0 || ports.publications != 1 || journal.rows[fixtureInvocation].Result.State != "UNKNOWN" {
		t.Fatal("interrupted work was repeated")
	}
}
