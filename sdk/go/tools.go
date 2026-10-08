package plowshare

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"math/big"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf16"
)

// ToolValue is a closed scalar, not an arbitrary JSON value. Null and nested values are refused.
type ToolValue struct {
	text   *string
	number *float64
	flag   *bool
}

func ToolText(value string) ToolValue    { return ToolValue{text: &value} }
func ToolNumber(value float64) ToolValue { return ToolValue{number: &value} }
func ToolBoolean(value bool) ToolValue   { return ToolValue{flag: &value} }
func (v ToolValue) Text() (string, bool) {
	if v.text == nil {
		return "", false
	}
	return *v.text, true
}
func (v ToolValue) Number() (float64, bool) {
	if v.number == nil {
		return 0, false
	}
	return *v.number, true
}
func (v ToolValue) Boolean() (bool, bool) {
	if v.flag == nil {
		return false, false
	}
	return *v.flag, true
}
func (v ToolValue) MarshalJSON() ([]byte, error) {
	if v.text != nil {
		if err := toolText(*v.text, 4096, false); err != nil {
			return nil, err
		}
		return json.Marshal(*v.text)
	}
	if v.number != nil {
		if !toolNumber(*v.number) {
			return nil, errors.New("invalid tool number")
		}
		return json.Marshal(*v.number)
	}
	if v.flag != nil {
		return json.Marshal(*v.flag)
	}
	return nil, errors.New("tool scalar variant is missing")
}
func (v *ToolValue) UnmarshalJSON(data []byte) error {
	*v = ToolValue{}
	var value any
	decoder := json.NewDecoder(strings.NewReader(string(data)))
	decoder.UseNumber()
	if err := decoder.Decode(&value); err != nil {
		return err
	}
	switch typed := value.(type) {
	case string:
		*v = ToolText(typed)
	case json.Number:
		number, err := typed.Float64()
		if err != nil {
			return err
		}
		original, ok := new(big.Rat).SetString(string(typed))
		roundtrip, valid := new(big.Rat).SetString(strconv.FormatFloat(number, 'g', -1, 64))
		if !ok || !valid || original.Cmp(roundtrip) != 0 {
			return errors.New("tool number cannot round-trip across SDK languages")
		}
		*v = ToolNumber(number)
	case bool:
		*v = ToolBoolean(typed)
	default:
		return errors.New("tool arguments must be declared scalars")
	}
	_, err := v.MarshalJSON()
	return err
}

type ToolArguments map[string]ToolValue
type ToolParameter struct {
	Name        string `json:"name"`
	Type        string `json:"type"`
	Description string `json:"description"`
	Required    bool   `json:"required"`
}
type ToolDeclaration struct {
	Name           string          `json:"name"`
	Description    string          `json:"description"`
	Parameters     []ToolParameter `json:"parameters"`
	TimeoutSeconds int             `json:"timeoutSeconds"`
}
type ToolBinding struct {
	Project  string `json:"project"`
	Provider string `json:"provider"`
	Account  string `json:"account"`
}
type ToolCall struct {
	Schema       string        `json:"schema"`
	InvocationID string        `json:"invocationId"`
	Project      string        `json:"project"`
	Provider     string        `json:"provider"`
	Tool         string        `json:"tool"`
	Account      string        `json:"account"`
	Run          string        `json:"run"`
	Call         string        `json:"call"`
	Deadline     string        `json:"deadline"`
	Arguments    ToolArguments `json:"arguments"`
}
type ToolResult struct {
	State string `json:"state"`
	Text  string `json:"text"`
}
type RegisteredTool struct {
	Declaration ToolDeclaration
	Handler     func(context.Context, ToolCall) (ToolResult, error)
}
type ToolReceipt struct {
	InvocationID string
	Request      string
	Phase        string
	Result       *ToolResult
	OccurredAt   *string
}

// ToolJournal is exclusive provider-owned persistence. Save must commit durably before returning.
type ToolJournal interface {
	Identity() string
	All() ([]ToolReceipt, error)
	Save(ToolReceipt) error
}

// ToolRelayPorts uses only the generated public SDK operations; *Client implements it.
type ToolRelayPorts interface {
	RelayConsume(context.Context, RelayConsumeRequest) (Reply[RelayBatchDto], error)
	RelayAck(context.Context, RelayAckRequest) (Reply[RelayAckResultDto], error)
	RelayPublish(context.Context, RelayPublishRequest) (Reply[RelayPublishResultDto], error)
	RelayLog(context.Context, RelayLogRequest) (Reply[RelayLogResultDto], error)
}

var toolName = regexp.MustCompile(`^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$`)
var providerName = regexp.MustCompile(`^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$`)
var parameterName = regexp.MustCompile(`^[a-zA-Z_][a-zA-Z0-9_]{0,63}$`)
var toolUUID = regexp.MustCompile(`^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$`)

func toolText(v string, max int, nonblank bool) error {
	if len(utf16.Encode([]rune(v))) > max || strings.ContainsRune(v, 0) || nonblank && strings.TrimSpace(v) == "" {
		return errors.New("invalid tool text")
	}
	return nil
}
func toolIdentity(v string) error {
	if v == "" || len(utf16.Encode([]rune(v))) > 256 || strings.TrimSpace(v) != v {
		return errors.New("invalid tool identity")
	}
	for _, c := range v {
		if unicode.IsControl(c) || c == 0x2028 || c == 0x2029 {
			return errors.New("invalid tool identity")
		}
	}
	return nil
}
func toolNumber(v float64) bool {
	if math.IsNaN(v) || math.IsInf(v, 0) || math.Abs(v) > 9007199254740991 {
		return false
	}
	parts := strings.Split(strconv.FormatFloat(v, 'g', -1, 64), "e")
	exponent := 0
	if len(parts) == 2 {
		exponent, _ = strconv.Atoi(parts[1])
	}
	fraction := 0
	if point := strings.IndexByte(parts[0], '.'); point >= 0 {
		fraction = len(strings.TrimRight(parts[0][point+1:], "0"))
	}
	return fraction-exponent <= 18
}
func (t ToolDeclaration) Validate(args ToolArguments) error {
	if !toolName.MatchString(t.Name) || len(t.Name) > 64 || t.TimeoutSeconds < 1 || t.TimeoutSeconds > 300 || len(t.Parameters) > 32 {
		return errors.New("invalid tool declaration")
	}
	if err := toolText(t.Description, 4096, true); err != nil {
		return err
	}
	names := map[string]bool{}
	for _, p := range t.Parameters {
		if !parameterName.MatchString(p.Name) || names[p.Name] {
			return errors.New("invalid or duplicate parameter")
		}
		names[p.Name] = true
		if err := toolText(p.Description, 4096, false); err != nil {
			return err
		}
		if p.Type != "STRING" && p.Type != "NUMBER" && p.Type != "INTEGER" && p.Type != "BOOLEAN" {
			return errors.New("invalid parameter type")
		}
	}
	if args == nil {
		return nil
	}
	for key := range args {
		if !names[key] {
			return errors.New("unknown tool argument")
		}
	}
	for _, p := range t.Parameters {
		v, present := args[p.Name]
		if !present {
			if p.Required {
				return errors.New("required tool argument missing")
			}
			continue
		}
		if _, err := v.MarshalJSON(); err != nil {
			return err
		}
		valid := p.Type == "STRING" && v.text != nil || p.Type == "BOOLEAN" && v.flag != nil || (p.Type == "NUMBER" || p.Type == "INTEGER") && v.number != nil && (p.Type != "INTEGER" || math.Trunc(*v.number) == *v.number)
		if !valid {
			return errors.New("tool argument type mismatch")
		}
	}
	return nil
}

// ToolDeploymentConfig exports operator-installed bindings; it never grants an agent a tool.
func ToolDeploymentConfig(binding ToolBinding, tools []ToolDeclaration) (string, error) {
	if toolIdentity(binding.Project) != nil || toolIdentity(binding.Account) != nil || !providerName.MatchString(binding.Provider) || len(binding.Provider) > 48 || len(tools) < 1 || len(tools) > 256 {
		return "", errors.New("invalid tool binding")
	}
	type installed struct {
		ToolBinding
		ToolDeclaration
	}
	bindings := make([]installed, 0, len(tools))
	names := map[string]bool{}
	for _, t := range tools {
		if names[t.Name] {
			return "", errors.New("duplicate tool")
		}
		names[t.Name] = true
		if err := t.Validate(nil); err != nil {
			return "", err
		}
		if t.Parameters == nil {
			t.Parameters = []ToolParameter{}
		}
		bindings = append(bindings, installed{binding, t})
	}
	config := struct {
		Plowshare struct {
			Relay struct {
				Tools struct {
					Bindings []installed `json:"bindings"`
				} `json:"tools"`
			} `json:"relay"`
		} `json:"plowshare"`
	}{}
	config.Plowshare.Relay.Tools.Bindings = bindings
	data, err := json.MarshalIndent(config, "", "  ")
	return string(data), err
}
func toolHash(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}
func ToolResultRequestID(id string) (string, error) {
	if !toolUUID.MatchString(id) {
		return "", errors.New("invalid tool UUID")
	}
	h := toolHash("tool-result:" + id)[:32]
	return h[:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:], nil
}

// Validate duplicates and depth at the owning envelope boundary, before JSON deserialization.
func toolJSONValue(d *json.Decoder, depth int) error {
	if depth > 8 {
		return errors.New("tool JSON exceeds depth bound")
	}
	token, err := d.Token()
	if err != nil {
		return err
	}
	delimiter, ok := token.(json.Delim)
	if !ok {
		return nil
	}
	if delimiter == '{' {
		seen := map[string]bool{}
		for d.More() {
			key, err := d.Token()
			if err != nil {
				return err
			}
			name, ok := key.(string)
			if !ok || seen[name] {
				return errors.New("duplicate tool field")
			}
			seen[name] = true
			if err := toolJSONValue(d, depth+1); err != nil {
				return err
			}
		}
	} else if delimiter == '[' {
		for d.More() {
			if err := toolJSONValue(d, depth+1); err != nil {
				return err
			}
		}
	} else {
		return errors.New("invalid tool JSON")
	}
	_, err = d.Token()
	return err
}
func DecodeToolCall(source string) (ToolCall, error) {
	var call ToolCall
	if toolText(source, 32768, true) != nil {
		return call, errors.New("invalid tool envelope bound")
	}
	d := json.NewDecoder(strings.NewReader(source))
	if err := toolJSONValue(d, 0); err != nil {
		return call, err
	}
	if _, err := d.Token(); err != io.EOF {
		return call, errors.New("trailing tool JSON")
	}
	d = json.NewDecoder(strings.NewReader(source))
	d.DisallowUnknownFields()
	if err := d.Decode(&call); err != nil {
		return call, err
	}
	if call.Schema != "plowshare-tool/1" || !toolUUID.MatchString(call.InvocationID) || call.Arguments == nil {
		return call, errors.New("invalid tool envelope")
	}
	for _, v := range []string{call.Project, call.Provider, call.Tool, call.Account, call.Run, call.Call} {
		if err := toolIdentity(v); err != nil {
			return call, err
		}
	}
	if !providerName.MatchString(call.Provider) || len(call.Provider) > 48 || !toolName.MatchString(call.Tool) || len(call.Tool) > 64 || len(call.Arguments) > 32 {
		return call, errors.New("invalid tool binding or arguments")
	}
	for name := range call.Arguments {
		if !parameterName.MatchString(name) {
			return call, errors.New("invalid tool argument name")
		}
	}
	if !strings.HasSuffix(call.Deadline, "Z") {
		return call, errors.New("tool deadline must be UTC")
	}
	if _, err := time.Parse(time.RFC3339Nano, call.Deadline); err != nil {
		return call, err
	}
	return call, nil
}
func toolResultText(call ToolCall, result *ToolResult) (string, error) {
	if result == nil || result.State != "COMPLETED" && result.State != "REJECTED" && result.State != "UNKNOWN" || toolText(result.Text, 16384, true) != nil {
		return "", errors.New("invalid tool result")
	}
	envelope := struct {
		Schema       string `json:"schema"`
		InvocationID string `json:"invocationId"`
		Project      string `json:"project"`
		Provider     string `json:"provider"`
		Tool         string `json:"tool"`
		ToolResult
	}{"plowshare-tool/1", call.InvocationID, call.Project, call.Provider, call.Tool, *result}
	data, err := json.Marshal(envelope)
	if err != nil {
		return "", err
	}
	if err := toolText(string(data), 32768, true); err != nil {
		return "", err
	}
	return string(data), nil
}

type ToolProvider struct {
	ports    ToolRelayPorts
	binding  ToolBinding
	tools    []RegisteredTool
	journal  ToolJournal
	consumer string
	gate     sync.Mutex
}

func NewToolProvider(ports ToolRelayPorts, binding ToolBinding, tools []RegisteredTool, journal ToolJournal) (*ToolProvider, error) {
	declarations := make([]ToolDeclaration, 0, len(tools))
	for _, t := range tools {
		if t.Handler == nil {
			return nil, errors.New("tool handler is required")
		}
		declarations = append(declarations, t.Declaration)
	}
	config, err := ToolDeploymentConfig(binding, declarations)
	if err != nil {
		return nil, err
	}
	if journal == nil || ports == nil || journal.Identity() != toolHash(config) {
		return nil, errors.New("foreign tool journal or missing ports")
	}
	return &ToolProvider{ports: ports, binding: binding, tools: cloneTools(tools), journal: journal, consumer: identity()}, nil
}

// PublishCatalog publishes/renews declarations with a retained caller UUID; never auto-replays.
func (p *ToolProvider) PublishCatalog(ctx context.Context, requestID string) error {
	tools := make([]ToolDeclaration, 0, len(p.tools))
	for _, tool := range p.tools {
		declaration := tool.Declaration
		if declaration.Parameters == nil {
			declaration.Parameters = []ToolParameter{}
		}
		tools = append(tools, declaration)
	}
	return p.catalog(ctx, requestID, tools)
}

// WithdrawCatalog removes declarations; uncertain effects still require reconciliation.
func (p *ToolProvider) WithdrawCatalog(ctx context.Context, requestID string) error {
	return p.catalog(ctx, requestID, []ToolDeclaration{})
}
func (p *ToolProvider) catalog(ctx context.Context, requestID string, tools []ToolDeclaration) error {
	if !toolUUID.MatchString(requestID) || len(tools) > 128 {
		return errors.New("invalid tool catalogue identity or size")
	}
	data, err := json.Marshal(struct {
		Version string            `json:"version"`
		Tools   []ToolDeclaration `json:"tools"`
	}{"plowshare-tool-catalog/1", tools})
	if err != nil {
		return err
	}
	if err := toolText(string(data), 65536, false); err != nil {
		return err
	}
	topic := "tool." + p.binding.Provider + ".catalog"
	reply, err := p.ports.RelayPublish(ctx, RelayPublishRequest{Project: p.binding.Project, Topic: topic, RequestId: requestID, OccurredAt: time.Now().UTC().Format(time.RFC3339Nano), Text: string(data)})
	if err != nil {
		return err
	}
	receipt, err := reply.RequirePayload()
	if err != nil {
		return err
	}
	if receipt.RequestId != requestID || receipt.Project != p.binding.Project || receipt.Topic != topic {
		return errors.New("foreign tool catalogue publication receipt")
	}
	return nil
}
func cloneTools(tools []RegisteredTool) []RegisteredTool {
	result := append([]RegisteredTool(nil), tools...)
	for i := range result {
		result[i].Declaration.Parameters = append([]ToolParameter(nil), result[i].Declaration.Parameters...)
	}
	return result
}
func (p *ToolProvider) topic(name, kind string) string {
	return "tool." + p.binding.Provider + "." + name + "." + kind
}
func (p *ToolProvider) receipts() ([]ToolReceipt, error) {
	rows, err := p.journal.All()
	if err != nil {
		return nil, err
	}
	if len(rows) > 1000 {
		return nil, errors.New("tool journal exceeds receipt bound")
	}
	seen := map[string]bool{}
	for _, row := range rows {
		call, err := DecodeToolCall(row.Request)
		if err != nil {
			return nil, err
		}
		if seen[row.InvocationID] || call.InvocationID != row.InvocationID || call.Project != p.binding.Project || call.Provider != p.binding.Provider {
			return nil, errors.New("foreign or duplicate tool receipt")
		}
		seen[row.InvocationID] = true
		matched := false
		for _, tool := range p.tools {
			if tool.Declaration.Name == call.Tool {
				if err = tool.Declaration.Validate(call.Arguments); err != nil {
					return nil, err
				}
				matched = true
			}
		}
		if !matched {
			return nil, errors.New("foreign tool receipt declaration")
		}
		if row.Phase != "executing" && row.Phase != "ready" && row.Phase != "publishing" && row.Phase != "done" || (row.Phase == "executing") != (row.Result == nil) || (row.Phase == "publishing" || row.Phase == "done") != (row.OccurredAt != nil) {
			return nil, errors.New("invalid tool receipt phase")
		}
		if row.Result != nil {
			if _, err = toolResultText(call, row.Result); err != nil {
				return nil, err
			}
		}
		if row.OccurredAt != nil {
			if _, err = time.Parse(time.RFC3339Nano, *row.OccurredAt); err != nil {
				return nil, err
			}
		}
	}
	return rows, nil
}

func (p *ToolProvider) publish(ctx context.Context, r ToolReceipt) error {
	call, err := DecodeToolCall(r.Request)
	if err != nil {
		return err
	}
	encoded, err := toolResultText(call, r.Result)
	if err != nil {
		return err
	}
	id, err := ToolResultRequestID(call.InvocationID)
	if err != nil {
		return err
	}
	occurred := time.Now().UTC().Truncate(time.Microsecond).Format(time.RFC3339Nano)
	ready := r
	r.Phase = "publishing"
	r.OccurredAt = &occurred
	if err = p.journal.Save(r); err != nil {
		return err
	}
	nullable := func(value string) *TierDtoProject { return &TierDtoProject{Variant2: &value} }
	reply, err := p.ports.RelayPublish(ctx, RelayPublishRequest{Project: p.binding.Project, Topic: p.topic(call.Tool, "result"), RequestId: id, OccurredAt: occurred, Text: encoded, CorrelationId: nullable(call.InvocationID), ParentTopic: nullable(p.topic(call.Tool, "request")), ParentEventId: nullable(call.InvocationID)})
	if err != nil {
		var delivery *TransportError
		if errors.As(err, &delivery) && delivery.Delivery == NotSubmitted {
			if saved := p.journal.Save(ready); saved != nil {
				return saved
			}
		}
		return err
	}
	body, err := reply.RequirePayload()
	if err != nil {
		return err
	}
	if body.Project != p.binding.Project || body.Topic != p.topic(call.Tool, "result") || body.RequestId != id {
		return errors.New("foreign tool result receipt")
	}
	r.Phase = "done"
	return p.journal.Save(r)
}
func (p *ToolProvider) Poll(ctx context.Context) (int, error) {
	if !p.gate.TryLock() {
		return 0, errors.New("tool provider already has an active pass")
	}
	defer p.gate.Unlock()
	rows, err := p.receipts()
	if err != nil {
		return 0, err
	}
	for _, r := range rows {
		if r.Phase == "publishing" {
			return 0, errors.New("uncertain tool result; reconcile before polling")
		}
		if r.Phase == "executing" {
			r.Phase = "ready"
			r.Result = &ToolResult{"UNKNOWN", "Provider restarted after execution intent; external effects may have occurred."}
			r.OccurredAt = nil
			if err = p.journal.Save(r); err != nil {
				return 0, err
			}
		}
		if r.Phase == "ready" {
			if err = p.publish(ctx, r); err != nil {
				return 0, err
			}
		}
	}
	count := 0
	one, zero := float64(1), float64(0)
	for _, registered := range p.tools {
		name := registered.Declaration.Name
		topic := p.topic(name, "request")
		reply, err := p.ports.RelayConsume(ctx, RelayConsumeRequest{Project: p.binding.Project, Topic: topic, Group: "tool-provider", ConsumerId: p.consumer, Start: "OLDEST_RETAINED", Limit: &one, WaitMs: &zero})
		if err != nil {
			return count, err
		}
		batch, err := reply.RequirePayload()
		if err != nil {
			return count, err
		}
		if batch.Status == "GAP" {
			return count, errors.New("tool history expired; inspect before acknowledging")
		}
		if batch.Status != "DATA" {
			continue
		}
		if batch.Project != p.binding.Project || batch.Topic != topic || batch.Group != "tool-provider" || batch.ConsumerId != p.consumer || len(batch.Events) != 1 {
			return count, errors.New("foreign tool batch")
		}
		event := batch.Events[0]
		if event.Publisher != "tool-runtime" || event.Payload.Kind != "TEXT" || event.Payload.Text.Variant2 == nil {
			return count, errors.New("invalid tool publisher or payload")
		}
		source := *event.Payload.Text.Variant2
		call, err := DecodeToolCall(source)
		if err != nil {
			return count, err
		}
		if call.Project != p.binding.Project || call.Provider != p.binding.Provider || call.Tool != name || call.InvocationID != event.EventId || event.CorrelationId.Variant2 == nil || *event.CorrelationId.Variant2 != call.InvocationID {
			return count, errors.New("foreign tool invocation")
		}
		if err = registered.Declaration.Validate(call.Arguments); err != nil {
			return count, err
		}
		rows, err = p.receipts()
		if err != nil {
			return count, err
		}
		receipt := ToolReceipt{InvocationID: call.InvocationID, Request: source, Phase: "executing"}
		fresh := true
		for _, r := range rows {
			if r.InvocationID == call.InvocationID {
				receipt = r
				fresh = false
				break
			}
		}
		if receipt.Request != source {
			return count, errors.New("conflicting tool invocation identity")
		}
		if fresh {
			if err = p.journal.Save(receipt); err != nil {
				return count, err
			}
		}
		if batch.BatchId.Variant2 == nil || batch.Fence.Variant2 == nil {
			return count, errors.New("missing tool acknowledgement authority")
		}
		acked, err := p.ports.RelayAck(ctx, RelayAckRequest{Project: p.binding.Project, Topic: topic, Group: "tool-provider", ConsumerId: p.consumer, BatchId: *batch.BatchId.Variant2, Fence: *batch.Fence.Variant2})
		if err != nil {
			return count, err
		}
		acknowledgement, err := acked.RequirePayload()
		if err != nil {
			return count, err
		}
		if acknowledgement.Project != p.binding.Project || acknowledgement.Topic != topic || acknowledgement.Group != "tool-provider" || acknowledgement.BatchId != *batch.BatchId.Variant2 || acknowledgement.Gap {
			return count, errors.New("foreign tool acknowledgement")
		}
		if fresh {
			deadline, err := time.Parse(time.RFC3339Nano, call.Deadline)
			if err != nil {
				return count, err
			}
			result := ToolResult{"REJECTED", "Tool deadline expired before execution; no handler ran."}
			if time.Now().Before(deadline) {
				limit := time.Now().Add(time.Duration(registered.Declaration.TimeoutSeconds) * time.Second)
				if limit.Before(deadline) {
					deadline = limit
				}
				handlerContext, cancel := context.WithDeadline(ctx, deadline)
				type answer struct {
					value ToolResult
					err   error
				}
				completed := make(chan answer, 1)
				go func() {
					value, failure := registered.Handler(handlerContext, call)
					completed <- answer{value, failure}
				}()
				select {
				case returned := <-completed:
					result = returned.value
					if returned.err != nil {
						result = ToolResult{"UNKNOWN", "Handler failed after execution intent; external effects may have occurred."}
					}
				case <-handlerContext.Done():
					result = ToolResult{"UNKNOWN", "Handler ended after execution intent; external effects may have occurred."}
				}
				cancel()
				if _, err = toolResultText(call, &result); err != nil {
					result = ToolResult{"UNKNOWN", "Handler result violates the tool contract; external effects may have occurred."}
				}
			}
			receipt.Phase = "ready"
			receipt.Result = &result
			if err = p.journal.Save(receipt); err != nil {
				return count, err
			}
		}
		if receipt.Phase == "ready" {
			if err = p.publish(ctx, receipt); err != nil {
				return count, err
			}
		}
		count++
	}
	return count, nil
}

// Reconcile performs bounded reads only. Neither execution nor result publication is repeated.
func (p *ToolProvider) Reconcile(ctx context.Context) (int, error) {
	if !p.gate.TryLock() {
		return 0, errors.New("tool provider already has an active pass")
	}
	defer p.gate.Unlock()
	rows, err := p.receipts()
	if err != nil {
		return 0, err
	}
	count := 0
	for _, r := range rows {
		if r.Phase != "publishing" {
			continue
		}
		call, err := DecodeToolCall(r.Request)
		if err != nil {
			return count, err
		}
		id, err := ToolResultRequestID(call.InvocationID)
		if err != nil {
			return count, err
		}
		expected, err := toolResultText(call, r.Result)
		if err != nil {
			return count, err
		}
		after := "0"
		settled := false
		limit := float64(100)
		system := false
		for pageNumber := 0; pageNumber < 100; pageNumber++ {
			reply, err := p.ports.RelayLog(ctx, RelayLogRequest{Variant1: &RelayLogPayloadVariant1Dto{Project: p.binding.Project, Topic: p.topic(call.Tool, "result"), System: &system, After: &after, Limit: &limit}})
			if err != nil {
				return count, err
			}
			page, err := reply.RequirePayload()
			if err != nil {
				return count, err
			}
			for _, event := range page.Events {
				if event.EventId != id {
					continue
				}
				occurred, err := time.Parse(time.RFC3339Nano, event.OccurredAt)
				if err != nil {
					return count, err
				}
				if r.OccurredAt == nil {
					return count, errors.New("missing tool publication time")
				}
				original, err := time.Parse(time.RFC3339Nano, *r.OccurredAt)
				if err != nil {
					return count, err
				}
				if event.Publisher != "sdk:"+toolHash(p.binding.Account) || event.Payload.Kind != "TEXT" || event.Payload.Text.Variant2 == nil || *event.Payload.Text.Variant2 != expected || event.CorrelationId.Variant2 == nil || *event.CorrelationId.Variant2 != call.InvocationID || event.CausationId.Variant2 == nil || *event.CausationId.Variant2 != call.InvocationID || !occurred.Equal(original) {
					return count, errors.New("conflicting retained tool result")
				}
				r.Phase = "done"
				if err = p.journal.Save(r); err != nil {
					return count, err
				}
				count++
				settled = true
				break
			}
			if settled {
				break
			}
			if len(page.Events) == 0 || page.Events[len(page.Events)-1].Position == after {
				settled = true
				break
			}
			after = page.Events[len(page.Events)-1].Position
		}
		if !settled {
			return count, fmt.Errorf("tool reconciliation exceeded retained read bound")
		}
	}
	return count, nil
}
