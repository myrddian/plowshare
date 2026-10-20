package plowshare

import (
	"context"
	"errors"
	"runtime"
	"testing"
	"time"
)

// A request waiting behind another logical transfer must detach without marking
// a possible write or closing the connection used by the earlier transfer.
func TestCancelledQueuedSenderDoesNotSubmitOrCloseOtherWork(t *testing.T) {
	p := newPackets(nil)
	defer p.close()
	p.sender <- struct{}{}
	defer func() { <-p.sender }()
	ctx, cancel := context.WithCancel(context.Background())
	submitted := false
	entered := make(chan struct{})
	result := make(chan error, 1)
	go func() {
		close(entered)
		result <- p.send(ctx, []byte(`{"request":"queued"}`), func() { submitted = true })
	}()
	<-entered
	cancel()
	if err := <-result; !errors.Is(err, context.Canceled) {
		t.Fatal("queued cancellation did not detach", err)
	}
	p.mu.Lock()
	closed := p.closed
	p.mu.Unlock()
	if submitted || closed {
		t.Fatal("queued cancellation affected physical work")
	}
	processPackets.Lock()
	held := p.held
	processPackets.Unlock()
	if held != 0 {
		t.Fatal("queued bytes were not released", held)
	}
}

// Credits and requests share a physical writer. Cancellation while waiting for
// that writer cannot be mislabeled UNKNOWN or abort the credit-owning transfer.
func TestCancelledPhysicalWriterWaitIsNotSubmission(t *testing.T) {
	p := newPackets(nil)
	defer p.close()
	p.writer <- struct{}{}
	defer func() { <-p.writer }()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	submitted := false
	result := make(chan error, 1)
	go func() { result <- p.send(ctx, []byte(`{"request":"queued"}`), func() { submitted = true }) }()
	end := time.Now().Add(time.Second)
	for {
		p.mu.Lock()
		selected := p.sending != ""
		p.mu.Unlock()
		if selected {
			break
		}
		if time.Now().After(end) {
			t.Fatal("logical sender did not reach the held physical writer")
		}
		runtime.Gosched()
	}
	cancel()
	if err := <-result; !errors.Is(err, context.Canceled) {
		t.Fatal("physical writer cancellation did not detach", err)
	}
	p.mu.Lock()
	closed := p.closed
	p.mu.Unlock()
	if submitted || closed {
		t.Fatal("unsent writer cancellation affected another transfer")
	}
}

// Duplicate credits cannot leave a buffered permit for an unsent future range.
func TestDuplicateCreditCannotAdvanceAnotherSegment(t *testing.T) {
	id := "11111111-1111-1111-1111-111111111111"
	p := &packets{sending: id, expected: 1, credit: make(chan struct{}, 1)}
	wire := []byte(`{"kind":"transport.credit","version":1,"transferId":"` + id + `","segmentNumber":1}`)
	if _, err := p.receive(context.Background(), wire); err != nil {
		t.Fatal(err)
	}
	<-p.credit
	if _, err := p.receive(context.Background(), wire); err != nil {
		t.Fatal(err)
	}
	select {
	case <-p.credit:
		t.Fatal("duplicate credit released another packet")
	default:
	}
}

func TestProcessBudgetIncludesQueuedOutgoingBuffers(t *testing.T) {
	first, second, third := &packets{}, &packets{}, &packets{}
	if err := first.reserve(packetMessage); err != nil {
		t.Fatal(err)
	}
	defer first.release(packetMessage)
	if err := second.reserve(packetMessage); err != nil {
		t.Fatal(err)
	}
	defer second.release(packetMessage)
	if err := third.reserve(packetMessage); err != packetCapacity {
		t.Fatal("aggregate capacity was not refused", err)
	}
}

func TestPacketFieldsRefuseDuplicatesAndTrailingMessages(t *testing.T) {
	for _, wire := range []string{`{"version":1,"version":1}`, `{"version":1} {}`, `[]`} {
		if _, err := packetFields([]byte(wire)); err == nil {
			t.Fatal("invalid packet accepted", wire)
		}
	}
}
