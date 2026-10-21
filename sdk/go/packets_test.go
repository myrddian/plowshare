package plowshare

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
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

// Completion bookkeeping must not disconnect a healthy long-lived stream.
func TestLongPacketSessionKeepsBoundedRecentCompletionIDs(t *testing.T) {
	p := newPackets(nil)
	defer p.close()
	payload := []byte("small reply")
	hash := sha256.Sum256(payload)
	var part messageSegment
	for n := 0; n < 4200; n++ {
		part = messageSegment{TransferID: fmt.Sprintf("00000000-0000-0000-0000-%012x", n), Number: 1, Count: 1, Total: len(payload), Hash: hex.EncodeToString(hash[:])}
		p.mu.Lock()
		message, err := p.assemble(part, payload)
		p.mu.Unlock()
		if err != nil || string(message) != "small reply" {
			t.Fatal("long session failed", n, err)
		}
	}
	if len(p.completed) != 4096 || len(p.completionOrder) != 4096 || p.held != 4096*256 {
		t.Fatal("unbounded completion state")
	}
	p.mu.Lock()
	_, err := p.assemble(part, payload)
	p.mu.Unlock()
	if err == nil {
		t.Fatal("recent duplicate accepted")
	}
	p.close()
	if p.held != 0 {
		t.Fatal("completion reservations leaked")
	}
}
