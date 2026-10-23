// Exercise the published typed API against the shared WebSocket fixture.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	plowshare "io.aeyer/plowshare/sdk"
	"os"
	"reflect"
	"strings"
	"time"
)

func check(ok bool, message string) {
	if !ok {
		panic(message)
	}
}

// scenario is one case of the shared conformance fixture.
//
// The capacity fields belong to the dispatch backpressure cases. Those need a
// negotiated window and several requests, so the generic loop below skips every
// case carrying Capacity and backpressure() drives them deliberately. Their
// numbers are read from the fixture rather than restated here; the fixture's
// capacityContract block documents what each one means.
type scenario struct {
	Name               string             `json:"name"`
	Delivery           plowshare.Delivery `json:"delivery"`
	DeliveryAlternates []string           `json:"deliveryAlternates"`
	Capacity           bool               `json:"capacity"`
	WaitingTimeoutMs   int                `json:"waitingTimeoutMs"`
	WaitingProbeMs     int                `json:"waitingProbeMs"`
	WaitingDelivery    plowshare.Delivery `json:"waitingDelivery"`
}

// result is one settled request, carried on a channel so a driver can observe
// that a request is still pending without collecting it.
type result struct {
	reply plowshare.Reply[[]plowshare.ProjectViewDto]
	err   error
}

// submit starts a request and reports its settlement once.
func submit(ctx context.Context, client *plowshare.Client) <-chan result {
	done := make(chan result, 1)
	go func() {
		reply, err := client.ProjectList(ctx, plowshare.ProjectListRequest{})
		done <- result{reply, err}
	}()
	return done
}

// settled waits up to pause for a request to settle. A false second return is
// what distinguishes a parked request from a refused one: the fail-fast form
// settles in the same instant, so a request that is still pending after a pause
// taken from inside the fixture's hold cannot have been refused locally.
func settled(done <-chan result, pause time.Duration) (result, bool) {
	timer := time.NewTimer(pause)
	defer timer.Stop()
	select {
	case settlement := <-done:
		return settlement, true
	case <-timer.C:
		return result{}, false
	}
}

// pendingNow reads a request's state without waiting. Used only after another
// request has already settled, so it is a state check rather than a race.
func pendingNow(done <-chan result) bool {
	select {
	case <-done:
		return false
	default:
		return true
	}
}

func milliseconds(value int) time.Duration { return time.Duration(value) * time.Millisecond }

// honest reports whether a delivery is one the case admits. Absence of
// deliveryAlternates means the case admits exactly its delivery and nothing
// else, so nothing is assumed present.
//
// Every delivery value in the fixture, principal and alternate alike, is SDK
// vocabulary: it is compared to what the SDK reports directly and is never
// translated from a wire code. A case that would need translation is a fixture
// defect, not something for a driver to paper over.
func (s scenario) honest(observed plowshare.Delivery) bool {
	if observed == s.Delivery {
		return true
	}
	for _, alternate := range s.DeliveryAlternates {
		if observed == plowshare.Delivery(alternate) {
			return true
		}
	}
	return false
}
func main() {
	ctx := context.Background()
	origin := os.Args[1]
	bytes, err := os.ReadFile(os.Getenv("PLOWSHARE_SDK_FIXTURES"))
	check(err == nil, "fixture unavailable")
	var fixture struct {
		Cases []scenario `json:"cases"`
	}
	check(json.Unmarshal(bytes, &fixture) == nil, "invalid fixture")
	cases := map[string]scenario{}
	for _, test := range fixture.Cases {
		cases[test.Name] = test
	}
	bytes, err = os.ReadFile(os.Getenv("PLOWSHARE_SDK_CATALOG"))
	check(err == nil, "catalog unavailable")
	var catalog struct {
		Operations []string `json:"operations"`
	}
	check(json.Unmarshal(bytes, &catalog) == nil && reflect.DeepEqual(plowshare.Operations(), catalog.Operations), "catalog differs")
	_, err = plowshare.Connect(ctx, origin+"/wrong", "sdk-fixture-token", plowshare.Options{})
	check(err != nil, "invalid origin accepted")
	_, err = plowshare.Connect(ctx, origin, "sdk-redirect-fixture", plowshare.Options{Session: "go-redirect", Timeout: 300 * time.Millisecond})
	var delivery *plowshare.TransportError
	check(errors.As(err, &delivery) && delivery.Delivery == plowshare.NotSubmitted, "redirect followed")
	_, err = plowshare.Connect(ctx, origin, "sdk-legacy-fixture", plowshare.Options{Session: "go-legacy-refusal"})
	check(errors.As(err, &delivery) && delivery.Delivery == plowshare.NotSubmitted, "unsupported transport accepted")
	open := func(name string) *plowshare.Client {
		client, err := plowshare.Connect(ctx, origin, "sdk-fixture-token", plowshare.Options{Session: "typed-go/" + name, Timeout: 300 * time.Millisecond})
		check(err == nil, "connect failed")
		return client
	}
	longSession := open("long-session")
	for n := 0; n < 4200; n++ {
		reply, err := longSession.ProjectList(ctx, plowshare.ProjectListRequest{})
		check(err == nil, "long packet session failed")
		rows, err := reply.RequirePayload()
		check(err == nil && len(rows) == 1 && rows[0].Name == "long-session", "long packet response lost")
	}
	longSession.Close()
	selected := open("acting-selection")
	for _, id := range []string{"00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002"} {
		actingCtx, err := plowshare.WithActingContext(ctx, id)
		check(err == nil, "invalid acting context selection")
		_, err = selected.ConversationTurns(actingCtx, plowshare.ConversationTurnsRequest{Conversation: "fixture"})
		check(err == nil, "selected request failed")
	}
	_, err = selected.ConversationTurns(ctx, plowshare.ConversationTurnsRequest{Conversation: "fixture"})
	check(err == nil, "ordinary request inherited acting selection")
	selected.Close()
	client := open("multiplex")
	first := make(chan result, 1)
	go func() {
		reply, err := client.ProjectList(ctx, plowshare.ProjectListRequest{})
		first <- result{reply, err}
	}()
	arrived, ok := <-client.Pushes()
	check(ok && arrived.Variant3 != nil, "first request arrival hint missing")
	two, err := client.ProjectList(ctx, plowshare.ProjectListRequest{})
	check(err == nil, "second request failed")
	b, err := two.RequirePayload()
	check(err == nil && b[0].Name == "second", "second request correlation failed")
	select {
	case <-first:
		panic("A completed before its explicit release request")
	default:
	}
	third, err := client.ProjectList(ctx, plowshare.ProjectListRequest{})
	check(err == nil, "third request failed")
	c, err := third.RequirePayload()
	check(err == nil && c[0].Name == "third", "third request correlation failed")
	one := <-first
	check(one.err == nil, "first request failed")
	a, err := one.reply.RequirePayload()
	check(err == nil && a[0].Name == "first", "first request correlation failed")
	client.Close()
	capacity, err := plowshare.Connect(ctx, origin, "sdk-fixture-token", plowshare.Options{Session: "typed-go/capacity", Timeout: 5 * time.Second})
	check(err == nil, "capacity connection failed")
	calls := make(chan result, 64)
	for range 64 {
		go func() {
			reply, err := capacity.ProjectList(ctx, plowshare.ProjectListRequest{})
			calls <- result{reply, err}
		}()
	}
	for count := 1; count <= 64; count++ {
		hint, ok := <-capacity.Pushes()
		check(ok && hint.Variant3 != nil && hint.Variant3.Unread == float64(count), "capacity arrival hint missing")
	}
	// The non-blocking form, preserved and named: it sheds load in the same call
	// rather than waiting, and nothing is written, so NOT_SUBMITTED is honest.
	_, err = capacity.ProjectList(plowshare.WithoutCapacityWait(ctx), plowshare.ProjectListRequest{})
	check(errors.As(err, &delivery) && delivery.Delivery == plowshare.NotSubmitted, "65th request was submitted")
	// The waiting form is the default, so the 65th request PARKS rather than
	// failing fast. Fail-fast would settle in the same instant, so still being
	// pending after a pause is what distinguishes waiting from refusing. The pause
	// must stay well under this connection's 5s timeout: at t=5s the 64 held
	// requests expire, free their slots, and the parked request would transmit,
	// breaking both the 64-frame count the shared harness asserts and the
	// NOT_SUBMITTED below. Closing the connection is what settles it, and nothing
	// was ever written for it.
	parked := submit(ctx, capacity)
	_, decided := settled(parked, 250*time.Millisecond)
	check(!decided, "the 65th request must wait for a slot, not fail fast")
	capacity.Close()
	check(errors.As((<-parked).err, &delivery) && delivery.Delivery == plowshare.NotSubmitted, "a never-transmitted parked request lost NOT_SUBMITTED")
	for range 64 {
		call := <-calls
		check(errors.As(call.err, &delivery) && delivery.Delivery == plowshare.Unknown, "submitted close lost uncertainty")
	}
	for _, extra := range []string{"malformed-nested", "missing-payload"} {
		fixture.Cases = append(fixture.Cases, scenario{Name: extra, Delivery: plowshare.InvalidResponse})
	}
	for _, test := range fixture.Cases {
		// Dispatch backpressure cases need the negotiated window and several
		// requests; they are driven by backpressure() below.
		if test.Capacity {
			continue
		}
		client = open(test.Name)
		reply, err := client.ProjectList(ctx, plowshare.ProjectListRequest{})
		if test.Delivery != "" {
			check(errors.As(err, &delivery) && delivery.Delivery == test.Delivery, "wrong delivery: "+test.Name)
		} else {
			check(err == nil, "request failed: "+test.Name)
			payload, err := reply.RequirePayload()
			if test.Name == "refusal" {
				var refusal *plowshare.RefusalError
				check(errors.As(err, &refusal) && refusal.Code == "CONFLICT", "refusal became success")
			} else {
				check(err == nil && payload[0].Name == test.Name, "invalid DTO")
			}
		}
		if test.Name == "push" {
			bare := <-client.Pushes()
			check(bare.Variant3 != nil && bare.Variant3.Unread == 1, "invalid hints escaped")
			envelope := <-client.Pushes()
			check(envelope.Variant12 != nil && envelope.Variant12.Type == "usage.closed", "enveloped hint missing")
		}
		if test.Name == "disconnect" {
			select {
			case _, ok := <-client.Pushes():
				check(!ok, "closed stream returned hint")
			case <-time.After(time.Second):
				panic("closed stream blocked")
			}
			_, err = client.ProjectList(ctx, plowshare.ProjectListRequest{})
			check(errors.As(err, &delivery) && delivery.Delivery == plowshare.NotSubmitted, "closed client submitted")
		}
		client.Close()
	}
	client = open("bad-packet")
	_, err = client.ProjectList(ctx, plowshare.ProjectListRequest{})
	check(errors.As(err, &delivery) && delivery.Delivery == plowshare.Unknown, "bad packet lost uncertain delivery")
	client.Close()
	client = open("invalid-input")
	_, err = client.JobStatus(ctx, plowshare.JobStatusRequest{Job: "\x00bad"})
	check(err != nil, "invalid identifier submitted")
	client.Close()
	client = open("cancel")
	cancelContext, cancel := context.WithCancel(ctx)
	abandoned := make(chan error, 1)
	go func() { _, err := client.ProjectList(cancelContext, plowshare.ProjectListRequest{}); abandoned <- err }()
	ack := <-client.Pushes()
	check(ack.Variant3 != nil, "submission hint missing")
	cancel()
	err = <-abandoned
	check(errors.As(err, &delivery) && delivery.Delivery == plowshare.Unknown && errors.Is(err, context.Canceled), "cancellation lost delivery")
	client.Close()
	client = open("provider-owner-compat")
	providerReply, err := client.ProviderConnectionList(ctx, plowshare.ProviderConnectionListRequest{Project: "fixture"})
	check(err == nil, "management refusal lost its envelope")
	_, err = providerReply.RequirePayload()
	var managementRefusal *plowshare.RefusalError
	check(errors.As(err, &managementRefusal) && managementRefusal.Code == "NOT_FOUND" && managementRefusal.Said != nil && *managementRefusal.Said == `no handler is registered for type "provider.connection.list"`, "unsupported management became success")
	hostedReply, err := client.ToolScopeHostList(ctx, plowshare.ToolScopeHostListRequest{Project: "fixture"})
	check(err == nil, "hosted refusal lost its envelope")
	_, err = hostedReply.RequirePayload()
	check(errors.As(err, &managementRefusal) && managementRefusal.Code == "NOT_FOUND" && managementRefusal.Said != nil && *managementRefusal.Said == `no handler is registered for type "tool.scope.host.list"`, "unsupported hosted management became success")
	client.Close()
	large, err := plowshare.Connect(ctx, origin, "sdk-fixture-token", plowshare.Options{Session: "packet-large-go", Timeout: 30 * time.Second})
	check(err == nil, "large packet upgrade failed")
	text := strings.Repeat("\u0001", 5*1024*1024)
	id := "11111111-1111-1111-1111-111111111111"
	publication, err := large.RelayPublish(ctx, plowshare.RelayPublishRequest{RequestId: id, Project: "fixture", Topic: "large.events", Text: text, OccurredAt: "2026-10-09T00:00:00Z"})
	check(err == nil, "large publication failed")
	published, err := publication.RequirePayload()
	check(err == nil && published.Position == "1", "large publication result invalid")
	consumed, err := large.RelayConsume(ctx, plowshare.RelayConsumeRequest{Project: "fixture", Topic: "large.events", Group: "fixture", ConsumerId: id, Start: "OLDEST_RETAINED"})
	check(err == nil, "large response failed")
	batch, err := consumed.RequirePayload()
	check(err == nil && len(batch.Events) == 1 && batch.Events[0].Payload.Text.Variant2 != nil && *batch.Events[0].Payload.Text.Variant2 == text, "large typed reply lost bytes")
	large.Close()
	backpressure(ctx, origin, cases)
	fmt.Println("Go typed SDK conformance passed")
}

// capacityConnectionTimeout is the connection-level timeout every backpressure
// case is opened with, apart from capacity-refuse-always, whose whole point is a
// short deadline ending the resubmission loop.
//
// It is deliberately long, and 5s rather than anything shorter, because
// capacity-deadline-survives-waiting tells "the request carries its own timeout,
// armed at transmit" apart from "it silently fell back to the connection's
// timeout" by probing at waitingProbeMs. A request wrongly running on this
// connection's deadline expires at holdMs + 5000ms, well past the probe; a
// shorter connection timeout would move that expiry inside the probe window and
// the case would pass for an implementation that dropped per-request timeouts
// entirely.
const capacityConnectionTimeout = 5 * time.Second

// backpressure drives the dispatch backpressure cases the generic loop skips.
//
// CAPACITY_EXHAUSTED is flow control a caller never sees as a fault, the
// advertised window is authoritative and waiting rather than refusing, the
// request deadline is armed at transmit and spends nothing on the wait, and a
// refused dispatch negotiation is never resubmitted. The numbers come from the
// fixture; the fixture's capacityContract block says what each of them means.
func backpressure(ctx context.Context, origin string, cases map[string]scenario) {
	open := func(name string, timeout time.Duration) *plowshare.Client {
		client, err := plowshare.Connect(ctx, origin, "sdk-fixture-token",
			plowshare.Options{Session: "typed-go/" + name, Timeout: timeout})
		check(err == nil, "capacity connection failed: "+name)
		return client
	}
	var delivery *plowshare.TransportError
	// Every case is driven even when an earlier one fails, and the failures are
	// reported together at the end. A per-case report is worth the few lines: these
	// seven cases fail in related ways, and the first panic alone hides which of
	// the others the same defect also broke.
	failed := map[string]any{}
	attempt := func(name string, body func()) {
		defer func() {
			if cause := recover(); cause != nil {
				failed[name] = cause
			}
		}()
		body()
	}
	defer func() {
		for name, cause := range failed {
			fmt.Println("capacity case failed:", name, "--", cause)
		}
		check(len(failed) == 0, "dispatch backpressure cases failed")
	}()
	// A single refusal is absorbed: the caller sees the answer to the
	// resubmission, not the refusal, and not an error.
	attempt("capacity-refuse-once", func() {
		client := open("capacity-refuse-once", capacityConnectionTimeout)
		defer client.Close()
		reply, err := client.ProjectList(ctx, plowshare.ProjectListRequest{})
		check(err == nil, "a refused request became a transport fault")
		rows, err := reply.RequirePayload()
		check(err == nil && len(rows) == 0, "a refused request lost its answer")
	})
	// Refusals that outlast the deadline armed at first transmit end in the
	// caller's own timeout, never in a surfaced refusal. Both deliveries the
	// fixture admits are accepted: NOT_SUBMITTED is the usual one, because the
	// deadline almost always lands inside a backoff pause, and RESPONSE_LOST
	// (UNKNOWN here) is equally truthful in the sub-millisecond window between a
	// transmit and the refusal arriving. The invariant is a deadline expiry, which
	// is why the cause is required to be the deadline itself.
	attempt("capacity-refuse-always", func() {
		test := cases["capacity-refuse-always"]
		client := open("capacity-refuse-always", 600*time.Millisecond)
		defer client.Close()
		_, err := client.ProjectList(ctx, plowshare.ProjectListRequest{})
		check(errors.As(err, &delivery) && test.honest(delivery.Delivery),
			"repeated refusals must end in a deadline expiry, not a surfaced refusal")
		check(errors.Is(err, context.DeadlineExceeded),
			"a connection teardown must not pass as a deadline expiry")
	})
	// At an advertised window of one the second request waits for a slot and is
	// answered once the first frees it, rather than failing NOT_SUBMITTED.
	attempt("capacity-window-one", func() {
		client := open("capacity-window-one", capacityConnectionTimeout)
		defer client.Close()
		held := submit(ctx, client)
		hint, ok := <-client.Pushes()
		check(ok && hint.Variant3 != nil, "window-full hint missing")
		// Submitted after the hint, so the first request is known to hold the only
		// slot and this one can only park. The 100ms probe lives inside the
		// fixture's 250ms hold, which is what makes "still pending" meaningful.
		waiting := submit(ctx, client)
		_, decided := settled(waiting, 100*time.Millisecond)
		check(!decided, "a request must wait while the advertised window of one is full")
		first := <-held
		check(first.err == nil, "the held request failed")
		rows, err := first.reply.RequirePayload()
		check(err == nil && len(rows) == 0, "the held request lost its answer")
		second := <-waiting
		check(second.err == nil, "the parked request failed once the window freed")
		rows, err = second.reply.RequirePayload()
		check(err == nil && len(rows) == 0, "the parked request lost its answer")
	})
	// A refusal releases the dispatch count so other admitted work can transmit,
	// but keeps its admission slot, so work parked for admission behind it cannot
	// overtake it: the frame order is A, A, B. Releasing admission on refusal
	// produces A, B, A, which the harness fails.
	attempt("capacity-slot-order", func() {
		client := open("capacity-slot-order", capacityConnectionTimeout)
		defer client.Close()
		one := submit(ctx, client)
		// Enough for the first request to be transmitted and refused, so the second
		// is submitted while the first is inside its backoff pause -- the window in
		// which a client that had released its admission slot would be overtaken.
		// The first backoff is at least half of the 100ms base, so the pause below
		// cannot outlast it. The sleep buys that window and nothing more: at
		// maxPending 1 the order is A, A, B however it lands, because B parks for
		// admission until A settles and A is always the first frame. A shorter or
		// absent sleep is still correct, it just stops exercising the overtaking
		// window the case exists to close.
		time.Sleep(25 * time.Millisecond)
		two := submit(ctx, client)
		for _, each := range []struct {
			settlement result
			what       string
		}{{<-one, "a refused request lost its answer"}, {<-two, "the request behind a refused one lost its answer"}} {
			check(each.settlement.err == nil, each.what)
			rows, err := each.settlement.reply.RequirePayload()
			check(err == nil && len(rows) == 0, each.what)
		}
	})
	// The regression test for the originally reported bug. Client-side queue time
	// must not be charged against the request deadline, AND the deadline the
	// request then runs on must be its own, armed when its frame was written.
	//
	// The fixture advertises a window of one, holds the first request for holdMs
	// (900ms) and then answers nothing more (silentAfterWait), so the only thing
	// that can settle the request admitted next is its own deadline. The waiting
	// request carries waitingTimeoutMs (300ms) -- less than a third of the wait it
	// is about to serve -- and a sibling carrying only the connection's 5s parks
	// behind it. The four directions land in different places:
	//   - armed before transmit (charged for the wait): it expires at 300ms, during
	//     the hold, and is never transmitted. The harness sees two frames of three.
	//   - never armed: it never settles, and the probe below expires first.
	//   - armed with the connection's duration: it expires at 900+5000ms, and the
	//     probe below expires first.
	//   - armed at transmit with its own 300ms: it expires at 1200ms. Only this
	//     one passes.
	// The probe is waitingProbeMs (2500ms) from the window filling: 1300ms of
	// scheduler tolerance above the correct expiry, and 3400ms below the expiry of
	// a request wrongly running on the connection's deadline.
	attempt("capacity-deadline-survives-waiting", func() {
		test := cases["capacity-deadline-survives-waiting"]
		client := open("capacity-deadline-survives-waiting", capacityConnectionTimeout)
		defer client.Close()
		held := submit(ctx, client)
		hint, ok := <-client.Pushes()
		check(ok && hint.Variant3 != nil, "window-full hint missing")
		probe := time.Now().Add(milliseconds(test.WaitingProbeMs))
		waitingCtx, err := plowshare.WithRequestTimeout(ctx, milliseconds(test.WaitingTimeoutMs))
		check(err == nil, "per-request timeout rejected")
		waiting := submit(waitingCtx, client)
		// The sibling must park behind the waiting request, so it is submitted after
		// it; both pauses live inside the fixture's 900ms hold. Its own settlement
		// comes on close, which is why it is submitted rather than ignored.
		time.Sleep(100 * time.Millisecond)
		sibling := submit(ctx, client)
		first := <-held
		check(first.err == nil, "the held request failed")
		rows, err := first.reply.RequirePayload()
		check(err == nil && len(rows) == 0, "the held request lost its answer")
		expiry, decided := settled(waiting, time.Until(probe))
		check(decided, "the waiting request did not expire on a deadline of its own: it is carrying no deadline, or the connection's, not one armed at transmit")
		check(expiry.err != nil, "the fixture must answer nothing once the hold is over")
		check(errors.As(expiry.err, &delivery) && delivery.Delivery == test.WaitingDelivery,
			"the waiting request was transmitted, so its expiry may have left work done and is UNKNOWN")
		check(errors.Is(expiry.err, context.DeadlineExceeded),
			"a connection teardown must not pass as a deadline expiry")
		// Read only once the waiting request has settled, so this is a state check
		// and not a race: it is the proof that the connection was alive and the
		// fixture's silence deliberate.
		check(pendingNow(sibling),
			"the sibling carrying only the connection deadline must still be pending when the waiting request expires")
		// The sibling is admitted by the waiting request's expiry, and its frame has
		// to reach the fixture before this case closes the connection: the harness
		// counts three frames, and the third is how it knows a request that waited
		// longer than its own timeout was really transmitted. It stays unanswered
		// either way, so this both gives it the room to transmit and repeats the
		// state check above.
		_, decided = settled(sibling, 300*time.Millisecond)
		check(!decided, "the fixture must answer nothing once the hold is over")
	})
	// A refused dispatch negotiation is not flow control and is never resubmitted:
	// the server treats a second selection on one connection as a protocol
	// violation, so a retry would turn a refusal into a teardown.
	attempt("capacity-handshake-refuse", func() {
		handshake := cases["capacity-handshake-refuse"]
		_, err := plowshare.Connect(ctx, origin, "sdk-fixture-token",
			plowshare.Options{Session: "typed-go/capacity-handshake-refuse", Timeout: capacityConnectionTimeout})
		check(errors.As(err, &delivery) && delivery.Delivery == handshake.Delivery,
			"a refused dispatch negotiation must fail honestly")
	})
	// A caller parked for a slot is released when the connection closes, and its
	// delivery is unambiguous because nothing was ever written for it.
	attempt("capacity-close-while-parked", func() {
		client := open("capacity-close-while-parked", capacityConnectionTimeout)
		defer client.Close()
		test := cases["capacity-close-while-parked"]
		held := submit(ctx, client)
		hint, ok := <-client.Pushes()
		check(ok && hint.Variant3 != nil, "window-full hint missing")
		waiting := submit(ctx, client)
		// Without this the case proves nothing: one frame on the wire, UNKNOWN for
		// the held caller and NOT_SUBMITTED for the other are all equally true of a
		// client that refuses locally and immediately. The 200ms pause lives inside
		// the fixture's 1000ms hold, which ends this case by closing the socket.
		_, decided := settled(waiting, 200*time.Millisecond)
		check(!decided, "the parked request must wait for a slot, not fail fast")
		check(errors.As((<-held).err, &delivery) && delivery.Delivery == test.Delivery,
			"a transmitted request lost its uncertainty when the connection closed")
		check(errors.As((<-waiting).err, &delivery) && delivery.Delivery == plowshare.NotSubmitted,
			"a parked request was released with anything but NOT_SUBMITTED")
	})
}
