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

type DispatchMode string

const (
	ConcurrentDispatch DispatchMode = "concurrent-v1"
	OrderedDispatch    DispatchMode = "ordered"
)

type Options struct {
	DispatchMode    DispatchMode
	LegacyTransport bool
	Session         string
	Timeout         time.Duration
}
type response struct {
	reply outcome
	err   error
}
type pending struct {
	operation string
	answer    chan response
	submitted atomic.Bool
}

// admission covers conversion, writer wait and response retention until one caller settles.
type admission struct {
	owner      *Client
	bytes      int
	deadline   time.Time
	converting bool
	released   atomic.Bool
}

func (c *Client) admit() (*admission, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed || c.admitted >= c.maximum {
		return nil, &TransportError{Delivery: NotSubmitted, Cause: packetCapacity}
	}
	if c.admitted == 0 {
		c.idle = make(chan struct{})
	}
	c.admitted++
	return &admission{owner: c, deadline: time.Now().Add(c.timeout)}, nil
}

// convert serializes transient codec copies before their exact wire size is known.
// It is released after encoding/byte charge, never while waiting for a reply.
func (a *admission) convert(ctx context.Context) error {
	deadline, cancel := context.WithDeadline(ctx, a.deadline)
	defer cancel()
	select {
	case a.owner.converter <- struct{}{}:
		a.converting = true
	case <-deadline.Done():
		return &TransportError{Delivery: NotSubmitted, Cause: deadline.Err()}
	case <-a.owner.done:
		return &TransportError{Delivery: NotSubmitted, Cause: errors.New("connection closed before conversion")}
	}
	if deadline.Err() != nil {
		return &TransportError{Delivery: NotSubmitted, Cause: deadline.Err()}
	}
	a.owner.mu.Lock()
	closed := a.owner.closed
	a.owner.mu.Unlock()
	if closed {
		return &TransportError{Delivery: NotSubmitted, Cause: errors.New("connection closed before conversion")}
	}
	return nil
}
func (a *admission) releaseConversion() {
	if a.converting {
		a.converting = false
		<-a.owner.converter
	}
}
func (a *admission) charge(size int) error {
	a.owner.mu.Lock()
	defer a.owner.mu.Unlock()
	if a.owner.closed || size > 640*1024*1024-a.owner.held {
		return &TransportError{Delivery: NotSubmitted, Cause: packetCapacity}
	}
	a.bytes = size
	a.owner.held += size
	return nil
}
func (a *admission) release() {
	if a.released.Swap(true) {
		return
	}
	a.releaseConversion()
	a.owner.mu.Lock()
	a.owner.admitted--
	a.owner.held -= a.bytes
	if a.owner.admitted == 0 {
		close(a.owner.idle)
	}
	a.owner.mu.Unlock()
}

type Client struct {
	Session   string
	socket    *websocket.Conn
	packets   *packets
	timeout   time.Duration
	mu        sync.Mutex
	pending   map[string]*pending
	converter chan struct{}
	admitted  int
	maximum   int
	held      int
	closed    bool
	pushes    chan ServerPush
	dropped   atomic.Uint64
	cancel    context.CancelFunc
	done      chan struct{}
	// idle belongs to one nonempty admission generation. Close fences admission
	// before observing it, so no later generation can escape the shared drain.
	idle      chan struct{}
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
	if options.DispatchMode != "" && options.DispatchMode != ConcurrentDispatch && options.DispatchMode != OrderedDispatch {
		return nil, errors.New("choose a dispatch mode")
	}
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
	var protocols []string
	if !options.LegacyTransport {
		protocols = []string{packetProtocol}
	}
	socket, _, err := websocket.Dial(opening, u.String(), &websocket.DialOptions{
		Subprotocols: protocols,
		HTTPHeader:   http.Header{"Authorization": {"Bearer " + token}},
		HTTPClient:   &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }},
	})
	if err != nil {
		return nil, &TransportError{Delivery: NotSubmitted, Cause: err}
	}
	if !options.LegacyTransport && socket.Subprotocol() != packetProtocol {
		_ = socket.CloseNow()
		return nil, &TransportError{Delivery: NotSubmitted, Cause: errors.New("server does not support segmented transport; explicitly select legacy mode")}
	}
	socket.SetReadLimit(1048576)
	lifetime, cancel := context.WithCancel(context.Background())
	c := &Client{Session: options.Session, socket: socket, timeout: options.Timeout, pending: map[string]*pending{}, converter: make(chan struct{}, 1), maximum: 64, pushes: make(chan ServerPush, 256), cancel: cancel, done: make(chan struct{})}
	if !options.LegacyTransport {
		c.packets = newPackets(socket)
	}
	go c.read(lifetime)
	if options.DispatchMode != OrderedDispatch {
		reply, err := c.request(opening, "transport.dispatch", []byte(`{"mode":"concurrent-v1"}`))
		var selected struct {
			Mode       string `json:"mode"`
			MaxPending int    `json:"maxPending"`
			MaxIDBytes int    `json:"maxIdBytes"`
		}
		if err == nil && (reply.Code != "OK" || json.Unmarshal(reply.Payload, &selected) != nil || selected.Mode != "concurrent-v1" || selected.MaxPending < 1 || selected.MaxPending > 64 || selected.MaxIDBytes != 128) {
			err = &TransportError{Delivery: NotSubmitted, Cause: errors.New("server did not acknowledge concurrent dispatch; select ordered explicitly")}
		}
		if err != nil {
			_ = c.Close()
			return nil, &TransportError{Delivery: NotSubmitted, Cause: err}
		}
		c.mu.Lock()
		c.maximum = selected.MaxPending
		c.mu.Unlock()
	}
	return c, nil
}

// request owns correlation and the raw envelope; generated methods own DTO conversion.
func (c *Client) request(ctx context.Context, operation string, body []byte) (outcome, error) {
	reservation, err := c.admit()
	if err != nil {
		return outcome{}, err
	}
	defer reservation.release()
	if err = reservation.convert(ctx); err != nil {
		return outcome{}, err
	}
	return c.requestReserved(ctx, operation, body, reservation)
}
func (c *Client) requestReserved(ctx context.Context, operation string, body []byte, reservation *admission) (outcome, error) {
	id := identity()
	envelope := map[string]any{"id": id, "type": operation, "protocol_version": ProtocolVersion, "payload": json.RawMessage(body)}
	if selection, ok := ctx.Value(actingContextKey{}).(string); ok && operation != "transport.dispatch" {
		envelope["actingFor"] = map[string]string{"context": selection}
	}
	wire, err := json.Marshal(envelope)
	if err != nil {
		return outcome{}, err
	}
	if len(wire) > 1048576 && c.packets == nil || len(wire) > packetMessage {
		return outcome{}, &TransportError{Delivery: NotSubmitted, Cause: packetCapacity}
	}
	if err = reservation.charge(len(wire)); err != nil {
		return outcome{}, err
	}
	reservation.releaseConversion()
	deadline, cancel := context.WithDeadline(ctx, reservation.deadline)
	defer cancel()
	waiting := &pending{operation: operation, answer: make(chan response, 1)}
	c.mu.Lock()
	if c.closed || deadline.Err() != nil {
		c.mu.Unlock()
		return outcome{}, &TransportError{Delivery: NotSubmitted, Cause: deadline.Err()}
	}
	c.pending[id] = waiting
	c.mu.Unlock()
	defer func() { c.mu.Lock(); delete(c.pending, id); c.mu.Unlock() }()
	if c.packets != nil {
		err = c.packets.send(deadline, wire, func() { waiting.submitted.Store(true) })
	} else {
		if deadline.Err() != nil {
			return outcome{}, &TransportError{Delivery: NotSubmitted, Cause: deadline.Err()}
		}
		waiting.submitted.Store(true)
		err = c.socket.Write(deadline, websocket.MessageText, wire)
	}
	if err != nil {
		if !waiting.submitted.Load() || errors.Is(err, packetCapacity) {
			return outcome{}, &TransportError{Delivery: NotSubmitted, Cause: err}
		}
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
		c.cancel()
		if c.packets != nil {
			c.packets.close()
		}
		_ = c.socket.CloseNow()
		c.mu.Lock()
		c.closed = true
		stranded := make([]*pending, 0, len(c.pending))
		for _, waiting := range c.pending {
			stranded = append(stranded, waiting)
		}
		clear(c.pending)
		c.mu.Unlock()
		for _, waiting := range stranded {
			state := NotSubmitted
			if waiting.submitted.Load() {
				state = Unknown
			}
			waiting.answer <- response{err: &TransportError{Delivery: state}}
		}
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
		if c.packets != nil {
			wire, err = c.packets.receive(ctx, wire)
			if err != nil {
				return
			}
			if wire == nil {
				continue
			}
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

// Close stops admission, settles outstanding requests and waits for all admitted
// conversion/writer and byte ownership to drain. Concurrent closes observe the
// same shutdown; callers need not collect their results for it to finish.
func (c *Client) Close() error {
	c.closeOnce.Do(func() {
		c.mu.Lock()
		c.closed = true
		c.mu.Unlock()
		c.cancel()
		_ = c.socket.CloseNow()
	})
	<-c.done
	// Reader completion only settles replies. Converters and physical writers
	// still own their admissions until their defers release all transport bytes.
	c.mu.Lock()
	idle := c.idle
	c.mu.Unlock()
	if idle != nil {
		<-idle
	}
	return nil
}

// actingContextKey is private so request metadata can only be selected through validation.
type actingContextKey struct{}

// WithActingContext returns an immutable per-request selection. It never mutates Client state.
func WithActingContext(ctx context.Context, id string) (context.Context, error) {
	if !regexp.MustCompile(`^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$`).MatchString(id) {
		return nil, errors.New("choose a canonical acting context UUID")
	}
	return context.WithValue(ctx, actingContextKey{}, id), nil
}
