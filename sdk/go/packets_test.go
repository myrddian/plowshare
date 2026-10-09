package plowshare

import (
	"context"
	"testing"
)

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
