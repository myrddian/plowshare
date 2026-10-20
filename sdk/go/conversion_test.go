package plowshare

import (
	"context"
	"errors"
	"runtime"
	"sync/atomic"
	"testing"
	"time"
)

type observedEncoding struct{ count *atomic.Int32 }

func (o observedEncoding) MarshalJSON() ([]byte, error) {
	o.count.Add(1)
	return []byte(`{}`), nil
}

// Exercise the request codec entry, not just semaphore accounting: a cancelled
// waiter cannot invoke a user value's encoder or reach the (absent) wire.
func TestQueuedConversionCancellationDoesNotEncodeOrSubmit(t *testing.T) {
	c := &Client{timeout: time.Second, maximum: 64, converter: make(chan struct{}, 1), done: make(chan struct{}), pending: map[string]*pending{}}
	c.converter <- struct{}{}
	var encoded atomic.Int32
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	result := make(chan error, 1)
	go func() {
		_, err := requestTyped[[]ObservedIndexDto](ctx, c, "memory.index", observedEncoding{&encoded})
		result <- err
	}()
	end := time.Now().Add(time.Second)
	for {
		c.mu.Lock()
		admitted := c.admitted
		c.mu.Unlock()
		if admitted == 1 {
			break
		}
		if time.Now().After(end) {
			t.Fatal("request did not enter conversion admission")
		}
		runtime.Gosched()
	}
	cancel()
	var delivery *TransportError
	if err := <-result; !errors.As(err, &delivery) || delivery.Delivery != NotSubmitted {
		t.Fatal("queued converter lost NOT_SUBMITTED", err)
	}
	if encoded.Load() != 0 {
		t.Fatal("cancelled converter encoded a request")
	}
	<-c.converter
	lease, err := c.admit()
	if err != nil {
		t.Fatal(err)
	}
	defer lease.release()
	if err = lease.convert(context.Background()); err != nil {
		t.Fatal("converter permit leaked", err)
	}
	lease.releaseConversion()
	c.mu.Lock()
	held := c.held
	admitted := c.admitted
	c.mu.Unlock()
	if held != 0 || admitted != 1 {
		t.Fatal("cancelled request capacity leaked", held, admitted)
	}
}

func TestQueuedConversionUsesAdmissionDeadlineBeforeEncoding(t *testing.T) {
	c := &Client{timeout: 20 * time.Millisecond, maximum: 64, converter: make(chan struct{}, 1), done: make(chan struct{}), pending: map[string]*pending{}}
	c.converter <- struct{}{}
	defer func() { <-c.converter }()
	var encoded atomic.Int32
	_, err := requestTyped[[]ObservedIndexDto](context.Background(), c, "memory.index", observedEncoding{&encoded})
	var delivery *TransportError
	if !errors.As(err, &delivery) || delivery.Delivery != NotSubmitted || !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal("converter wait ignored invocation deadline", err)
	}
	if encoded.Load() != 0 {
		t.Fatal("expired converter encoded a request")
	}
	c.mu.Lock()
	held := c.held
	admitted := c.admitted
	c.mu.Unlock()
	if held != 0 || admitted != 0 {
		t.Fatal("expired request capacity leaked", held, admitted)
	}
}
