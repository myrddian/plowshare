// Package plowshare provides a header-authenticated Plowshare WebSocket client.
// No application request is automatically replayed, including after a deadline.
package plowshare

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	mathrand "math/rand/v2"
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
	converting bool
	released   atomic.Bool
}

// grantAdmissionLocked charges one slot of the advertised window to a new owner.
// The caller holds c.mu. The idle channel belongs to one nonempty admission
// generation, so it is created with the generation's first slot and closed when
// its last is released.
func (c *Client) grantAdmissionLocked() {
	if c.admitted == 0 {
		c.idle = make(chan struct{})
	}
	c.admitted++
}

// pumpLocked hands freed admission slots to parked callers in FIFO order. It
// runs from every release, so a failure frees capacity exactly like a reply
// does. The slot is charged to the waiter before it is woken, so a caller
// arriving later cannot steal what was granted, and FIFO order means work that
// arrived later never overtakes a caller already parked. Nothing is granted once
// the connection is closed; callers parked then are released through c.done.
//
// Only admission is queued. Dispatch counts need no queue because a caller
// holding an admission is already guaranteed one; see acquireDispatch.
func (c *Client) pumpLocked() {
	for len(c.slotWaiters) > 0 && c.admitted < c.maximum && !c.closed {
		next := c.slotWaiters[0]
		c.slotWaiters = c.slotWaiters[1:]
		c.grantAdmissionLocked()
		close(next)
	}
}

// abandonLocked removes a parked waiter from its queue. A false return means the
// capacity was granted as the waiter was leaving, and the caller must hand it
// back: a cancelled or closed-out wait leaves the accounting exactly as it was.
func abandonLocked(queue *[]chan struct{}, parked chan struct{}) bool {
	for at, waiter := range *queue {
		if waiter == parked {
			*queue = append((*queue)[:at], (*queue)[at+1:]...)
			return true
		}
	}
	return false
}

// admit reserves one slot of the advertised window.
//
// The waiting form is the default: a full window is flow control, so the caller
// parks until a slot frees rather than failing. WithoutCapacityWait selects the
// fail-fast form, which reports NOT_SUBMITTED in the same call for a caller that
// would rather shed load than be held.
//
// Nothing here is charged against the request deadline, which is armed later,
// when the frame is written. The wait is bounded by the caller's context and by
// the connection closing, and a cancelled wait returns NOT_SUBMITTED with
// ctx.Err() as its cause while leaving the accounting untouched: cancellation is
// never a way to leak capacity.
func (c *Client) admit(ctx context.Context, wait bool) (*admission, error) {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil, &TransportError{Delivery: NotSubmitted, Cause: errors.New("connection closed before admission")}
	}
	// Strict `<`, and only with an empty queue, so a slot is never taken ahead of
	// a caller already parked for one.
	if c.admitted < c.maximum && len(c.slotWaiters) == 0 {
		c.grantAdmissionLocked()
		c.mu.Unlock()
		return &admission{owner: c}, nil
	}
	if !wait {
		c.mu.Unlock()
		return nil, &TransportError{Delivery: NotSubmitted, Cause: packetCapacity}
	}
	parked := make(chan struct{})
	c.slotWaiters = append(c.slotWaiters, parked)
	c.mu.Unlock()
	var cause error
	select {
	case <-parked:
		return &admission{owner: c}, nil
	case <-ctx.Done():
		cause = ctx.Err()
	case <-c.done:
		cause = errors.New("connection closed while waiting for an admission slot")
	}
	c.mu.Lock()
	granted := !abandonLocked(&c.slotWaiters, parked)
	c.mu.Unlock()
	if granted {
		// The slot was charged to us as we were leaving. Hand it straight back so
		// the next parked caller receives it.
		(&admission{owner: c}).release()
	}
	return nil, &TransportError{Delivery: NotSubmitted, Cause: cause}
}

// acquireDispatch takes one of the connection's dispatch counts, which a request
// holds from its transmit until its settlement or its refusal.
//
// Two counters are kept, as the reference keeps them: admission is the advertised
// window, dispatch is work actually on the wire. A capacity refusal releases only
// the dispatch count, so other already-admitted work can transmit while the
// refused request backs off, and the refused request keeps its place in the
// window instead of being overtaken by work that arrived after it.
//
// Holding an admission is always enough to be sure of a count, so this never
// waits. A request holds at most one dispatch count and only while it is
// admitted, so a caller that has not transmitted yet sees at most admitted-1
// dispatched, which is below the window. The window itself only ever changes
// while nothing is admitted: c.maximum is written at construction and once
// after the handshake, and the negotiation's own admission is released before
// that write. Nothing in the protocol lowers the window under live work.
//
// The remaining branch is therefore an invariant violation, not flow control. It
// is reported rather than assumed away so the advertised window stays
// authoritative: the request is refused without transmitting instead of pushing
// the connection past the window the server advertised. A caller seeing it has
// found a bug in this client's accounting, not a busy server.
func (c *Client) acquireDispatch() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return &TransportError{Delivery: NotSubmitted, Cause: errors.New("connection closed before submission")}
	}
	if c.dispatched >= c.maximum {
		return &TransportError{Delivery: NotSubmitted, Cause: errors.New("dispatch accounting exceeded the advertised window; no frame was written")}
	}
	c.dispatched++
	return nil
}

// releaseDispatch returns one dispatch count. It wakes nobody: admission is the
// only queued capacity, and an admission slot frees on release, not here.
func (c *Client) releaseDispatch() {
	c.mu.Lock()
	c.dispatched--
	c.mu.Unlock()
}

// convert serializes transient codec copies before their exact wire size is known.
// It is released after encoding/byte charge, never while waiting for a reply.
//
// The wait is bounded by the caller's context alone. Nothing before the frame is
// written may consume the request deadline, which is the defect this fixes: the
// deadline used to be armed at admission, so a request could fail for running out
// of time it never had.
func (a *admission) convert(ctx context.Context) error {
	select {
	case a.owner.converter <- struct{}{}:
		a.converting = true
	case <-ctx.Done():
		return &TransportError{Delivery: NotSubmitted, Cause: ctx.Err()}
	case <-a.owner.done:
		return &TransportError{Delivery: NotSubmitted, Cause: errors.New("connection closed before conversion")}
	}
	if ctx.Err() != nil {
		return &TransportError{Delivery: NotSubmitted, Cause: ctx.Err()}
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
	// Every ending funnels here, so a failed request frees a slot like a reply.
	a.owner.pumpLocked()
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
	// dispatched counts requests currently on the wire. A capacity refusal
	// releases this and keeps its admission slot; see acquireDispatch.
	dispatched int
	// slotWaiters is the FIFO queue of callers parked for an admission slot,
	// guarded by mu and drained by pumpLocked. Dispatch counts have no queue:
	// an admitted caller is always owed one, so parking for one is unreachable.
	slotWaiters []chan struct{}
	// random is the resubmission jitter source, injectable so tests are
	// deterministic. Nil means math/rand/v2. Guarded by mu like every other field
	// here: a test substituting it does so while the reader goroutine is live.
	random  func() float64
	closed  bool
	pushes  chan ServerPush
	dropped atomic.Uint64
	cancel  context.CancelFunc
	done    chan struct{}
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
	c := &Client{Session: options.Session, socket: socket, timeout: options.Timeout, pending: map[string]*pending{}, converter: make(chan struct{}, 1), maximum: 64, pushes: make(chan ServerPush, 256), cancel: cancel, done: make(chan struct{}), random: mathrand.Float64}
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

// capacityExhausted is the server's dispatch refusal. It is flow control, not a
// fault: the waiting form absorbs it and resubmits, and the caller never sees it.
const capacityExhausted = "CAPACITY_EXHAUSTED"

// Resubmission pacing after a capacity refusal, matching the reference SDK's
// shipped values: the ceiling doubles with the refusal count up to the cap, and
// the pause is half of it fixed and half random, so a client always pauses and
// clients released together never retry in lockstep.
const (
	resubmitBackoffBase = 100 * time.Millisecond
	resubmitBackoffCap  = 5000 * time.Millisecond
)

// backoff waits out the pause owed for the refusals so far.
//
// The exponent is a pacing input, not a bound. The bound is the deadline armed on
// the first transmit, which ctx carries here: backoff time and the server's own
// processing time both count against the caller's original timeout, so a request
// refused over and over ends in a timeout rather than retrying forever. There is
// no retry ceiling and none is wanted.
//
// A pause ended by the deadline or by a close is NOT_SUBMITTED: the refused
// attempt released its dispatch count and the server did none of the work.
func (c *Client) backoff(ctx context.Context, refusals int) error {
	ceiling := resubmitBackoffCap
	if shifted := resubmitBackoffBase << min(refusals, 16); shifted < ceiling {
		ceiling = shifted
	}
	jitter := mathrand.Float64
	c.mu.Lock()
	if c.random != nil {
		jitter = c.random
	}
	c.mu.Unlock()
	pause := ceiling/2 + time.Duration(float64(ceiling)*jitter()/2)
	timer := time.NewTimer(pause)
	defer timer.Stop()
	select {
	case <-timer.C:
		return nil
	case <-ctx.Done():
		return &TransportError{Delivery: NotSubmitted, Cause: ctx.Err()}
	case <-c.done:
		return &TransportError{Delivery: NotSubmitted, Cause: errors.New("connection closed while pacing a resubmission")}
	}
}

// request owns correlation and the raw envelope; generated methods own DTO conversion.
func (c *Client) request(ctx context.Context, operation string, body []byte) (outcome, error) {
	reservation, err := c.admit(ctx, waitsForCapacity(ctx))
	if err != nil {
		return outcome{}, err
	}
	defer reservation.release()
	if err = reservation.convert(ctx); err != nil {
		return outcome{}, err
	}
	return c.requestReserved(ctx, operation, body, reservation)
}

// requestReserved transmits one request whose admission slot is already held, and
// owns the only loop in this client.
//
// The request deadline is armed when the frame is written, exactly once. Waiting
// for an admission slot or for the conversion gate happens before that and is
// bounded by the caller's context alone, charged to nothing. Taking a dispatch
// count never waits at all. The writer semaphore inside packets.send is the one
// wait on the far side of arming, and counts against the deadline by design.
//
// A CAPACITY_EXHAUSTED reply is absorbed: the dispatch count is released so other
// admitted work can transmit, the admission slot is kept so later arrivals cannot
// overtake this request, and the same request id is resubmitted after a jittered
// pause against the deadline already armed. The server refused the work outright,
// having done none of it, which is the only reason resubmitting a request is not
// the replay this transport otherwise forbids. The dispatch negotiation is never
// resubmitted: the server treats a second selection on one connection as a
// protocol violation, so a retry would turn a refusal into a teardown.
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
	// sending is the caller's context until the first transmit arms the request
	// deadline, and the armed context afterwards. Every resubmission reuses it, so
	// the first transmit's deadline bounds the request's whole life.
	sending, armed := ctx, false
	absorbs := operation != "transport.dispatch" && waitsForCapacity(ctx)
	waiting := &pending{operation: operation, answer: make(chan response, 1)}
	defer func() { c.mu.Lock(); delete(c.pending, id); c.mu.Unlock() }()
	// Held between a successful acquireDispatch and the attempt's end, so a
	// refusal, a failure and a reply each release it exactly once.
	dispatching := false
	defer func() {
		if dispatching {
			c.releaseDispatch()
		}
	}()
	for refusals := 0; ; refusals++ {
		if err = c.acquireDispatch(); err != nil {
			return outcome{}, err
		}
		dispatching = true
		// Cleared per attempt: a close during the next backoff is honestly
		// NOT_SUBMITTED, because the refused attempt left no work behind.
		waiting.submitted.Store(false)
		c.mu.Lock()
		if c.closed || sending.Err() != nil {
			c.mu.Unlock()
			return outcome{}, &TransportError{Delivery: NotSubmitted, Cause: sending.Err()}
		}
		c.pending[id] = waiting
		c.mu.Unlock()
		if !armed {
			// The frame is written below, so this is where the deadline arms, and the
			// only place it ever does: a resubmission finds it already armed and runs
			// against what is left of it.
			armed = true
			deadline, stop := context.WithTimeout(ctx, c.requestTimeout(ctx))
			defer stop()
			sending = deadline
		}
		if c.packets != nil {
			err = c.packets.send(sending, wire, func() { waiting.submitted.Store(true) })
		} else {
			if sending.Err() != nil {
				return outcome{}, &TransportError{Delivery: NotSubmitted, Cause: sending.Err()}
			}
			waiting.submitted.Store(true)
			err = c.socket.Write(sending, websocket.MessageText, wire)
		}
		if err != nil {
			if !waiting.submitted.Load() || errors.Is(err, packetCapacity) {
				return outcome{}, &TransportError{Delivery: NotSubmitted, Cause: err}
			}
			return outcome{}, &TransportError{Delivery: Unknown, Cause: err}
		}
		select {
		case answer := <-waiting.answer:
			if answer.err == nil && answer.reply.Code == capacityExhausted && absorbs {
				// Dispatch released, admission kept: capacity the server said it cannot
				// take is given back, the place in the window is not.
				dispatching = false
				c.releaseDispatch()
				if err = c.backoff(sending, refusals); err != nil {
					return outcome{}, err
				}
				continue
			}
			if answer.err == nil && slices.Contains([]string{"OK", "CREATED", "ACCEPTED", "NO_CONTENT"}, answer.reply.Code) && !deploymentMatches(operation, body, answer.reply.Payload) {
				return outcome{}, &TransportError{Delivery: InvalidResponse, Cause: contractError}
			}
			return answer.reply, answer.err
		case <-sending.Done():
			return outcome{}, &TransportError{Delivery: Unknown, Cause: sending.Err()}
		}
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

// Per-request dispatch options. They are carried on the caller's context, the
// same seam WithActingContext already uses, so every generated operation accepts
// them without a second method per operation.
type (
	// capacityWaitKey marks a context whose requests shed load instead of waiting.
	capacityWaitKey struct{}
	// requestTimeoutKey carries a per-request timeout, armed at transmit.
	requestTimeoutKey struct{}
)

// WithoutCapacityWait selects the non-blocking submission form for requests made
// with the returned context: a full dispatch window returns NOT_SUBMITTED in the
// same call instead of waiting for a slot, and a server CAPACITY_EXHAUSTED is
// returned to the caller as an outcome rather than absorbed and resubmitted.
//
// The default is the waiting form. This is for a caller that would rather shed
// load than be held — a batch job under its own deadline — and the choice
// belongs to the call site, not to the client.
func WithoutCapacityWait(ctx context.Context) context.Context {
	return context.WithValue(ctx, capacityWaitKey{}, false)
}

// waitsForCapacity reports whether a request waits for dispatch capacity.
func waitsForCapacity(ctx context.Context) bool {
	waits, selected := ctx.Value(capacityWaitKey{}).(bool)
	return !selected || waits
}

// WithRequestTimeout overrides the connection's timeout for requests made with
// the returned context. Like the connection's own, it is armed when the request's
// frame is written and never before, so a request may wait longer for local
// capacity than its timeout without consuming any of it.
//
// It is a context value rather than a context deadline precisely because a
// context deadline starts running immediately, which is the defect this fixes.
func WithRequestTimeout(ctx context.Context, timeout time.Duration) (context.Context, error) {
	if timeout <= 0 {
		return nil, errors.New("choose a positive request timeout")
	}
	return context.WithValue(ctx, requestTimeoutKey{}, timeout), nil
}

// requestTimeout is the duration a request's deadline is armed with at transmit.
func (c *Client) requestTimeout(ctx context.Context) time.Duration {
	if selected, ok := ctx.Value(requestTimeoutKey{}).(time.Duration); ok && selected > 0 {
		return selected
	}
	return c.timeout
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
