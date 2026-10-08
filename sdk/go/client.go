// Package plowshare provides a header-authenticated Plowshare WebSocket client.
// No application request is automatically replayed, including after a deadline.
package plowshare

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
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

// Reply contains only an outcome and the operation's validated result DTO.
// Refusals carry no arbitrary diagnostic payload.
type Reply[T any] struct {
	Code    string
	Said    *string
	payload T
}

func (r Reply[T]) Successful() bool {
	return slices.Contains([]string{"OK", "CREATED", "ACCEPTED", "NO_CONTENT"}, r.Code)
}
func (r Reply[T]) RequirePayload() (T, error) {
	if !r.Successful() {
		var zero T
		return zero, &RefusalError{Code: r.Code, Said: r.Said}
	}
	return r.payload, nil
}

type RefusalError struct {
	Code string
	Said *string
}

func (e *RefusalError) Error() string {
	if e.Said != nil {
		return *e.Said
	}
	return e.Code
}

type outcome struct {
	Code    string          `json:"code"`
	Said    *string         `json:"said,omitempty"`
	Payload json.RawMessage `json:"payload,omitempty"`
}

type Options struct {
	Session string
	Timeout time.Duration
}
type response struct {
	reply outcome
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
	pushes    chan ServerPush
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
	c := &Client{Session: options.Session, socket: socket, timeout: options.Timeout, pending: map[string]pending{}, pushes: make(chan ServerPush, 256), cancel: cancel, done: make(chan struct{})}
	go c.read(lifetime)
	return c, nil
}

// request owns correlation and the raw envelope; generated methods own DTO conversion.
func (c *Client) request(ctx context.Context, operation string, body []byte) (outcome, error) {
	id := identity()
	wire, err := json.Marshal(map[string]any{"id": id, "type": operation, "protocol_version": ProtocolVersion, "payload": json.RawMessage(body)})
	if err != nil {
		return outcome{}, err
	}
	deadline, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()
	waiting := pending{operation: operation, answer: make(chan response, 1)}
	c.mu.Lock()
	if c.closed || len(c.pending) >= 64 || deadline.Err() != nil {
		c.mu.Unlock()
		return outcome{}, &TransportError{Delivery: NotSubmitted, Cause: deadline.Err()}
	}
	c.pending[id] = waiting
	c.mu.Unlock()
	defer func() { c.mu.Lock(); delete(c.pending, id); c.mu.Unlock() }()
	if err = c.socket.Write(deadline, websocket.MessageText, wire); err != nil {
		return outcome{}, &TransportError{Delivery: Unknown, Cause: err}
	}
	select {
	case answer := <-waiting.answer:
		if answer.err == nil && slices.Contains([]string{"OK", "CREATED", "ACCEPTED", "NO_CONTENT"}, answer.reply.Code) && !deploymentMatches(operation, body, answer.reply.Payload) {
			return outcome{}, &TransportError{Delivery: InvalidResponse, Cause: contractError}
		}
		return answer.reply, answer.err
	case <-deadline.Done():
		return outcome{}, &TransportError{Delivery: Unknown, Cause: deadline.Err()}
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
			hint, err := decodePush(wire)
			if err != nil {
				continue
			}
			select {
			case c.pushes <- hint:
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
		var body outcome
		valid := version == ProtocolVersion && operation == waiting.operation && json.Unmarshal(frame["payload"], &body) == nil && knownCodes[body.Code]
		answer := response{reply: body}
		if !valid {
			answer = response{err: &TransportError{Delivery: InvalidResponse}}
		}
		waiting.answer <- answer
	}
}

func (c *Client) Pushes() <-chan ServerPush { return c.pushes }
func (c *Client) DroppedPushes() uint64     { return c.dropped.Load() }
func (c *Client) Close() error {
	c.closeOnce.Do(func() { c.cancel(); _ = c.socket.CloseNow() })
	<-c.done
	return nil
}
