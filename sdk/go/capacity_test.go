package plowshare

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"runtime"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
)

// capacityClient is a transport with no socket: admission accounting is reached
// without a server, which is what these cases are about.
func capacityClient(window int) *Client {
	return &Client{timeout: time.Second, maximum: window, converter: make(chan struct{}, 1),
		done: make(chan struct{}), pending: map[string]*pending{}, random: func() float64 { return 0 }}
}

func awaitCapacity(t *testing.T, condition func() bool, what string) {
	t.Helper()
	end := time.Now().Add(2 * time.Second)
	for !condition() {
		if time.Now().After(end) {
			t.Fatal(what)
		}
		runtime.Gosched()
	}
}

func (c *Client) observe() (admitted int, dispatched int, parked int) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.admitted, c.dispatched, len(c.slotWaiters)
}

// A full window is flow control, so the default form waits for a slot and is
// granted one when a settling request frees it.
func TestWaitingFormParksForASlotAndIsGrantedOne(t *testing.T) {
	c := capacityClient(1)
	first, err := c.admit(context.Background(), true)
	if err != nil {
		t.Fatal(err)
	}
	granted := make(chan *admission, 1)
	go func() {
		slot, err := c.admit(context.Background(), true)
		if err != nil {
			t.Error("waiting form refused a full window", err)
		}
		granted <- slot
	}()
	awaitCapacity(t, func() bool { _, _, parked := c.observe(); return parked == 1 }, "the waiting form did not park")
	select {
	case <-granted:
		t.Fatal("the waiting form was granted a slot the window did not have")
	case <-time.After(50 * time.Millisecond):
	}
	first.release()
	select {
	case slot := <-granted:
		if slot == nil {
			t.Fatal("a parked caller was released without a slot")
		}
		if admitted, _, parked := c.observe(); admitted != 1 || parked != 0 {
			t.Fatal("granting a parked caller lost the window accounting", admitted, parked)
		}
		slot.release()
	case <-time.After(2 * time.Second):
		t.Fatal("a parked caller was not granted the freed slot")
	}
	if admitted, _, _ := c.observe(); admitted != 0 {
		t.Fatal("admission leaked", admitted)
	}
}

// The fail-fast form is still reachable, explicitly, and reports in the same
// outcome channel a caller already handles.
func TestNonBlockingFormRefusesAFullWindowInTheSameCall(t *testing.T) {
	c := capacityClient(1)
	first, err := c.admit(context.Background(), true)
	if err != nil {
		t.Fatal(err)
	}
	defer first.release()
	settled := make(chan error, 1)
	go func() {
		_, err := requestTyped[[]ProjectViewDto](WithoutCapacityWait(context.Background()), c, "project.list", ProjectListRequest{})
		settled <- err
	}()
	select {
	case err := <-settled:
		var delivery *TransportError
		if !errors.As(err, &delivery) || delivery.Delivery != NotSubmitted {
			t.Fatal("the non-blocking form lost NOT_SUBMITTED", err)
		}
	case <-time.After(time.Second):
		t.Fatal("the non-blocking form waited for a slot")
	}
	if _, _, parked := c.observe(); parked != 0 {
		t.Fatal("the non-blocking form parked a caller", parked)
	}
}

// Cancellation while waiting for capacity returns promptly, reports
// NOT_SUBMITTED in Go's own cancellation currency, and leaves the accounting
// exactly as it was.
func TestCancelledCapacityWaitReportsCancellationAndLeaksNoSlot(t *testing.T) {
	c := capacityClient(1)
	first, err := c.admit(context.Background(), true)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	settled := make(chan error, 1)
	go func() {
		_, err := c.admit(ctx, true)
		settled <- err
	}()
	awaitCapacity(t, func() bool { _, _, parked := c.observe(); return parked == 1 }, "the waiting form did not park")
	cancel()
	select {
	case err := <-settled:
		var delivery *TransportError
		if !errors.As(err, &delivery) || delivery.Delivery != NotSubmitted || !errors.Is(err, context.Canceled) {
			t.Fatal("a cancelled capacity wait lost its cancellation", err)
		}
	case <-time.After(time.Second):
		t.Fatal("a cancelled capacity wait did not return")
	}
	if admitted, _, parked := c.observe(); admitted != 1 || parked != 0 {
		t.Fatal("a cancelled wait disturbed the accounting", admitted, parked)
	}
	first.release()
	// The slot the cancelled caller did not take is still there to be taken.
	second, err := c.admit(context.Background(), false)
	if err != nil {
		t.Fatal("a cancelled wait leaked the window slot", err)
	}
	second.release()
}

// The grant and the cancellation can land together, and a slot charged to a
// caller that is already leaving must be handed back rather than leaked. Both
// outcomes are legal; losing the slot is not.
func TestCapacityWaitCancelledAsItIsGrantedLeaksNothing(t *testing.T) {
	for range 200 {
		c := capacityClient(1)
		first, err := c.admit(context.Background(), true)
		if err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithCancel(context.Background())
		settled := make(chan *admission, 1)
		go func() {
			slot, _ := c.admit(ctx, true)
			settled <- slot
		}()
		awaitCapacity(t, func() bool { _, _, parked := c.observe(); return parked == 1 }, "the waiting form did not park")
		var racing sync.WaitGroup
		racing.Add(2)
		go func() { defer racing.Done(); cancel() }()
		go func() { defer racing.Done(); first.release() }()
		racing.Wait()
		if slot := <-settled; slot != nil {
			slot.release()
		}
		cancel()
		if admitted, _, parked := c.observe(); admitted != 0 || parked != 0 {
			t.Fatal("a slot granted to a cancelling caller leaked", admitted, parked)
		}
	}
}

// refusingServer answers the first application frame of a connection
// CAPACITY_EXHAUSTED the given number of times and then OK, recording the frame
// ids it saw. refused is signalled after each refusal is written.
func refusingServer(t *testing.T, refusals int, refused chan<- struct{}) (*httptest.Server, func() []string) {
	t.Helper()
	var guard sync.Mutex
	var ids []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		socket, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer func() { _ = socket.CloseNow() }()
		seen := 0
		for {
			_, wire, err := socket.Read(context.Background())
			if err != nil {
				return
			}
			var frame struct {
				ID   string `json:"id"`
				Type string `json:"type"`
			}
			if json.Unmarshal(wire, &frame) != nil {
				return
			}
			guard.Lock()
			ids = append(ids, frame.ID)
			guard.Unlock()
			seen++
			body := `{"code":"OK","payload":[]}`
			if seen <= refusals {
				body = `{"code":"CAPACITY_EXHAUSTED","said":"queue is full"}`
			}
			reply := `{"id":"` + frame.ID + `","type":"` + frame.Type + `","protocol_version":"` + ProtocolVersion + `","payload":` + body + `}`
			if socket.Write(context.Background(), websocket.MessageText, []byte(reply)) != nil {
				return
			}
			if seen <= refusals && refused != nil {
				refused <- struct{}{}
			}
		}
	}))
	t.Cleanup(server.Close)
	return server, func() []string {
		guard.Lock()
		defer guard.Unlock()
		return append([]string(nil), ids...)
	}
}

func refusingClient(t *testing.T, server *httptest.Server, timeout time.Duration) *Client {
	t.Helper()
	c, err := Connect(context.Background(), server.URL, "capacity-fixture",
		Options{DispatchMode: OrderedDispatch, LegacyTransport: true, Timeout: timeout})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	// Deterministic pacing: the whole jittered half, so the pause is the ceiling.
	// Written under mu because the field is read under mu by backoff.
	c.mu.Lock()
	c.random = func() float64 { return 1 }
	c.mu.Unlock()
	return c
}

// A refusal is flow control the caller never sees: the same request id is
// resubmitted and the caller gets the answer to the resubmission. While the
// request paces its resubmission it has released its dispatch count -- capacity
// the server said it cannot take -- and kept its admission slot, which is what
// stops work that arrived later from overtaking it.
func TestCapacityRefusalIsAbsorbedKeepingAdmissionAndReleasingDispatch(t *testing.T) {
	refused := make(chan struct{}, 1)
	server, frames := refusingServer(t, 1, refused)
	c := refusingClient(t, server, 5*time.Second)
	settled := make(chan error, 1)
	go func() {
		reply, err := c.ProjectList(context.Background(), ProjectListRequest{})
		if err == nil {
			_, err = reply.RequirePayload()
		}
		settled <- err
	}()
	<-refused
	// Observed inside the backoff pause, which the injected jitter fixes at the
	// 100ms base. A missed window shows up as admitted dropping to zero, which the
	// condition below cannot satisfy, rather than as a silent pass.
	awaitCapacity(t, func() bool {
		admitted, dispatched, _ := c.observe()
		return admitted == 1 && dispatched == 0
	}, "a refused request did not release its dispatch count while keeping its admission slot")
	if err := <-settled; err != nil {
		t.Fatal("a refused request did not reach its caller as an answer", err)
	}
	sent := frames()
	if len(sent) != 2 || sent[0] != sent[1] {
		t.Fatal("a refused request was not resubmitted exactly once under its own id", sent)
	}
	if admitted, dispatched, _ := c.observe(); admitted != 0 || dispatched != 0 {
		t.Fatal("a resubmitted request leaked capacity", admitted, dispatched)
	}
}

// Refusals that outlast the deadline armed at the first transmit end in that
// deadline. Nothing re-arms it per attempt, so the caller is held for its own
// timeout and no longer, and there is no retry ceiling doing the bounding.
func TestRepeatedRefusalsEndInTheDeadlineArmedAtFirstTransmit(t *testing.T) {
	server, frames := refusingServer(t, 1<<30, nil)
	c := refusingClient(t, server, 600*time.Millisecond)
	start := time.Now()
	_, err := c.ProjectList(context.Background(), ProjectListRequest{})
	elapsed := time.Since(start)
	var delivery *TransportError
	if !errors.As(err, &delivery) || !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal("repeated refusals did not end in a deadline expiry", err)
	}
	if delivery.Delivery != NotSubmitted && delivery.Delivery != Unknown {
		t.Fatal("a refused request ended with a delivery it cannot honestly claim", delivery.Delivery)
	}
	// One request timeout, not one per attempt: a re-armed deadline would run for
	// as many timeouts as there were attempts.
	if elapsed > 1200*time.Millisecond {
		t.Fatal("the deadline was re-armed by a resubmission", elapsed)
	}
	if sent := frames(); len(sent) < 2 {
		t.Fatal("a repeatedly refused request was not resubmitted", sent)
	} else if len(sent) > 8 {
		t.Fatal("refusals were resubmitted in a hot loop rather than on a paced backoff", len(sent))
	}
}

// The non-blocking form reports a refusal as an outcome instead of absorbing it,
// which is the whole point of having it: a caller that would rather shed load
// than wait gets to see the refusal.
func TestNonBlockingFormSurfacesCapacityExhaustedToTheCaller(t *testing.T) {
	server, frames := refusingServer(t, 1, nil)
	c := refusingClient(t, server, 5*time.Second)
	reply, err := c.ProjectList(WithoutCapacityWait(context.Background()), ProjectListRequest{})
	if err != nil {
		t.Fatal("a refusal became a transport fault", err)
	}
	if reply.Code != capacityExhausted {
		t.Fatal("the non-blocking form absorbed a refusal", reply.Code)
	}
	if sent := frames(); len(sent) != 1 {
		t.Fatal("the non-blocking form resubmitted a refused request", sent)
	}
}

// The dispatch negotiation is never resubmitted: the server treats a second
// selection on one connection as a protocol violation, so a retry would turn a
// refusal into a teardown. A refused negotiation fails honestly.
func TestRefusedDispatchNegotiationIsNotResubmitted(t *testing.T) {
	var guard sync.Mutex
	negotiations := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		socket, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer func() { _ = socket.CloseNow() }()
		for {
			_, wire, err := socket.Read(context.Background())
			if err != nil {
				return
			}
			var frame struct {
				ID   string `json:"id"`
				Type string `json:"type"`
			}
			if json.Unmarshal(wire, &frame) != nil {
				return
			}
			guard.Lock()
			negotiations++
			guard.Unlock()
			reply := `{"id":"` + frame.ID + `","type":"` + frame.Type + `","protocol_version":"` + ProtocolVersion +
				`","payload":{"code":"CAPACITY_EXHAUSTED","said":"dispatch queue is full"}}`
			if socket.Write(context.Background(), websocket.MessageText, []byte(reply)) != nil {
				return
			}
		}
	}))
	defer server.Close()
	_, err := Connect(context.Background(), server.URL, "capacity-fixture",
		Options{LegacyTransport: true, Timeout: 2 * time.Second})
	var delivery *TransportError
	if !errors.As(err, &delivery) || delivery.Delivery != NotSubmitted {
		t.Fatal("a refused dispatch negotiation did not fail honestly", err)
	}
	guard.Lock()
	defer guard.Unlock()
	if negotiations != 1 {
		t.Fatal("a refused dispatch negotiation was resubmitted", negotiations)
	}
}

// The deadline arms at the write and carries the caller's own duration, so a
// request may wait far longer than its timeout for a slot and still get the whole
// of it once transmitted.
func TestRequestTimeoutIsArmedAtTransmitAndNotBeforeTheWait(t *testing.T) {
	// A server that answers nothing: the only thing that can settle the request
	// is its own deadline.
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		socket, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer func() { _ = socket.CloseNow() }()
		for {
			if _, _, err := socket.Read(context.Background()); err != nil {
				return
			}
		}
	}))
	defer server.Close()
	c, err := Connect(context.Background(), server.URL, "capacity-fixture",
		Options{DispatchMode: OrderedDispatch, LegacyTransport: true, Timeout: 10 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()
	if _, err = WithRequestTimeout(context.Background(), 0); err == nil {
		t.Fatal("a non-positive request timeout was accepted")
	}
	// One slot, taken by a request the server never answers, so the next request
	// waits for the slot far longer than its own 200ms timeout.
	c.mu.Lock()
	c.maximum = 1
	c.mu.Unlock()
	holder := make(chan error, 1)
	go func() {
		_, err := c.ProjectList(context.Background(), ProjectListRequest{})
		holder <- err
	}()
	awaitCapacity(t, func() bool { admitted, dispatched, _ := c.observe(); return admitted == 1 && dispatched == 1 },
		"the first request did not take the only slot")
	ctx, err := WithRequestTimeout(context.Background(), 200*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	settled := make(chan error, 1)
	go func() {
		_, err := c.ProjectList(ctx, ProjectListRequest{})
		settled <- err
	}()
	// Several times the waiting request's own timeout. A deadline armed before the
	// transmit expires inside this pause, before any slot could free.
	select {
	case err := <-settled:
		t.Fatal("the waiting request was charged the time it spent waiting for a slot", err)
	case <-time.After(700 * time.Millisecond):
	}
	// Freeing the slot releases the waiting request, which transmits and only then
	// starts spending its 200ms.
	released := time.Now()
	c.mu.Lock()
	c.maximum = 2
	c.pumpLocked()
	c.mu.Unlock()
	select {
	case err := <-settled:
		elapsed := time.Since(released)
		var delivery *TransportError
		if !errors.As(err, &delivery) || delivery.Delivery != Unknown || !errors.Is(err, context.DeadlineExceeded) {
			t.Fatal("a transmitted request's expiry lost its uncertainty", err)
		}
		if elapsed < 150*time.Millisecond {
			t.Fatal("the waiting request kept only part of its timeout", elapsed)
		}
		if elapsed > 2*time.Second {
			t.Fatal("the waiting request ran on the connection's timeout, not its own", elapsed)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("the waiting request carried no deadline at all")
	}
	_ = c.Close()
	<-holder
}
