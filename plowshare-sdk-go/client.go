// Package plowshare provides a header-authenticated Plowshare WebSocket client.
// No application request is automatically replayed, including after a deadline.
package plowshare

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
)

type Delivery string

const (
	NotSubmitted    Delivery = "NOT_SUBMITTED"
	Unknown         Delivery = "UNKNOWN"
	InvalidResponse Delivery = "INVALID_RESPONSE"
)

type TransportError struct {
	Delivery Delivery
	Cause    error
}

func (e *TransportError) Error() string {
	return "Plowshare delivery is " + string(e.Delivery) + "; no request was replayed"
}
func (e *TransportError) Unwrap() error { return e.Cause }

type Outcome struct {
	Code    string          `json:"code"`
	Said    *string         `json:"said,omitempty"`
	Payload json.RawMessage `json:"payload,omitempty"`
}

// Raw preserves the complete envelope, including future outcome and payload fields.
type Reply struct {
	Raw     json.RawMessage
	Outcome Outcome
}

func (r Reply) Successful() bool {
	return slices.Contains([]string{"OK", "CREATED", "ACCEPTED", "NO_CONTENT"}, r.Outcome.Code)
}

type RefusalError struct{ Reply Reply }

func (e *RefusalError) Error() string {
	if e.Reply.Outcome.Said != nil {
		return *e.Reply.Outcome.Said
	}
	return e.Reply.Outcome.Code
}
func (r Reply) RequirePayload() (json.RawMessage, error) {
	if !r.Successful() {
		return nil, &RefusalError{Reply: r}
	}
	if len(r.Outcome.Payload) == 0 || string(r.Outcome.Payload) == "null" {
		return nil, &TransportError{Delivery: InvalidResponse}
	}
	return r.Outcome.Payload, nil
}

type Options struct {
	Session string
	Timeout time.Duration
}
type response struct {
	reply Reply
	err   error
}
type pending struct {
	operation string
	answer    chan response
}
type Client struct {
	Session   string
	socket    *websocket.Conn
	timeout   time.Duration
	mu        sync.Mutex
	pending   map[string]pending
	closed    bool
	pushes    chan json.RawMessage
	dropped   atomic.Uint64
	cancel    context.CancelFunc
	done      chan struct{}
	closeOnce sync.Once
}

func identity() string {
	var bytes [16]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		panic("secure request identity unavailable")
	}
	return hex.EncodeToString(bytes[:])
}

func Connect(ctx context.Context, origin, token string, options Options) (*Client, error) {
	u, err := url.Parse(origin)
	if err != nil || u.Host == "" || (u.Scheme != "http" && u.Scheme != "https") || u.User != nil || (u.Path != "" && u.Path != "/") || u.RawQuery != "" || u.Fragment != "" {
		return nil, errors.New("an HTTP(S) origin without credentials, path, query or fragment is required")
	}
	if options.Timeout == 0 {
		options.Timeout = 30 * time.Second
	}
	if options.Session == "" {
		options.Session = identity()
	}
	if strings.TrimSpace(token) == "" || strings.TrimSpace(options.Session) == "" || options.Timeout < 0 {
		return nil, errors.New("token, session and a positive timeout are required")
	}
	if u.Scheme == "https" {
		u.Scheme = "wss"
	} else {
		u.Scheme = "ws"
	}
	u.Path = "/v1/events"
	u.RawQuery = url.Values{"session": {options.Session}}.Encode()
	opening, stopOpening := context.WithTimeout(ctx, options.Timeout)
	defer stopOpening()
	socket, _, err := websocket.Dial(opening, u.String(), &websocket.DialOptions{
		HTTPHeader: http.Header{"Authorization": {"Bearer " + token}},
		HTTPClient: &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }},
	})
	if err != nil {
		return nil, &TransportError{Delivery: NotSubmitted, Cause: err}
	}
	socket.SetReadLimit(1048576)
	lifetime, cancel := context.WithCancel(context.Background())
	c := &Client{Session: options.Session, socket: socket, timeout: options.Timeout, pending: map[string]pending{}, pushes: make(chan json.RawMessage, 256), cancel: cancel, done: make(chan struct{})}
	go c.read(lifetime)
	return c, nil
}

func (c *Client) Request(ctx context.Context, operation string, payload any) (Reply, error) {
	if !slices.Contains(operationNames, operation) {
		return Reply{}, errors.New("unknown Plowshare operation")
	}
	if payload == nil {
		payload = map[string]any{}
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return Reply{}, err
	}
	var object map[string]json.RawMessage
	if json.Unmarshal(body, &object) != nil || object == nil {
		return Reply{}, errors.New("request payload must be an object")
	}
	id := identity()
	wire, err := json.Marshal(map[string]any{"id": id, "type": operation, "protocol_version": ProtocolVersion, "payload": json.RawMessage(body)})
	if err != nil {
		return Reply{}, err
	}
	deadline, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()
	waiting := pending{operation: operation, answer: make(chan response, 1)}
	c.mu.Lock()
	if c.closed || len(c.pending) >= 64 || deadline.Err() != nil {
		c.mu.Unlock()
		return Reply{}, &TransportError{Delivery: NotSubmitted, Cause: deadline.Err()}
	}
	c.pending[id] = waiting
	c.mu.Unlock()
	defer func() { c.mu.Lock(); delete(c.pending, id); c.mu.Unlock() }()
	if err = c.socket.Write(deadline, websocket.MessageText, wire); err != nil {
		return Reply{}, &TransportError{Delivery: Unknown, Cause: err}
	}
	select {
	case answer := <-waiting.answer:
		return answer.reply, answer.err
	case <-deadline.Done():
		return Reply{}, &TransportError{Delivery: Unknown, Cause: deadline.Err()}
	}
}

func (c *Client) read(ctx context.Context) {
	defer func() {
		c.mu.Lock()
		c.closed = true
		for _, waiting := range c.pending {
			waiting.answer <- response{err: &TransportError{Delivery: Unknown}}
		}
		clear(c.pending)
		c.mu.Unlock()
		close(c.pushes)
		close(c.done)
	}()
	for {
		kind, wire, err := c.socket.Read(ctx)
		if err != nil {
			return
		}
		if kind != websocket.MessageText {
			continue
		}
		var frame map[string]json.RawMessage
		if json.Unmarshal(wire, &frame) != nil || frame == nil {
			continue
		}
		var version, id, operation string
		_, enveloped := frame["protocol_version"]
		_ = json.Unmarshal(frame["protocol_version"], &version)
		if !enveloped || string(frame["id"]) == "null" && version == ProtocolVersion {
			select {
			case c.pushes <- append(json.RawMessage(nil), wire...):
			default:
				c.dropped.Add(1)
			}
			continue
		}
		if json.Unmarshal(frame["id"], &id) != nil || id == "" {
			continue
		}
		c.mu.Lock()
		waiting, exists := c.pending[id]
		delete(c.pending, id)
		c.mu.Unlock()
		if !exists {
			continue
		}
		_ = json.Unmarshal(frame["type"], &operation)
		var outcome Outcome
		valid := version == ProtocolVersion && operation == waiting.operation && json.Unmarshal(frame["payload"], &outcome) == nil && knownCodes[outcome.Code]
		answer := response{reply: Reply{Raw: append(json.RawMessage(nil), wire...), Outcome: outcome}}
		if !valid {
			answer = response{err: &TransportError{Delivery: InvalidResponse}}
		}
		waiting.answer <- answer
	}
}

func (c *Client) Pushes() <-chan json.RawMessage { return c.pushes }
func (c *Client) DroppedPushes() uint64          { return c.dropped.Load() }
func (c *Client) Close() error {
	c.closeOnce.Do(func() { c.cancel(); _ = c.socket.CloseNow() })
	<-c.done
	return nil
}
func (c *Client) JobStatus(ctx context.Context, job string) (Reply, error) {
	return c.Request(ctx, "job.status", map[string]any{"job": job})
}
func (c *Client) CancelJob(ctx context.Context, job string) (Reply, error) {
	return c.Request(ctx, "job.cancel", map[string]any{"job": job})
}
func (c *Client) OpenConversation(ctx context.Context, scope map[string]any) (Reply, error) {
	if scope == nil {
		scope = map[string]any{}
	}
	return c.Request(ctx, "conversation.open", scope)
}
func (c *Client) RunAgent(ctx context.Context, agent, task string, scope map[string]any) (Reply, error) {
	payload := map[string]any{"session": c.Session}
	for key, value := range scope {
		payload[key] = value
	}
	payload["agent"], payload["task"] = agent, task
	return c.Request(ctx, "agent.run", payload)
}

var uuid = regexp.MustCompile(`(?i)^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$`)

func (c *Client) SendOutgoing(ctx context.Context, requestID, peer string, message map[string]any, scope map[string]any) (Reply, error) {
	if !uuid.MatchString(requestID) {
		return Reply{}, fmt.Errorf("outgoing requestId must be a retained UUID")
	}
	payload := map[string]any{}
	for key, value := range scope {
		payload[key] = value
	}
	payload["requestId"], payload["peer"], payload["message"] = requestID, peer, message
	return c.Request(ctx, "outgoing.send", payload)
}
func (c *Client) OutgoingStatus(ctx context.Context, id string) (Reply, error) {
	return c.Request(ctx, "outgoing.status", map[string]any{"id": id})
}
func (c *Client) CancelOutgoing(ctx context.Context, id string) (Reply, error) {
	return c.Request(ctx, "outgoing.cancel", map[string]any{"id": id})
}
