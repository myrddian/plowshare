package plowshare

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"runtime"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func drainClient(t *testing.T) *Client {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		socket, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{packetProtocol}})
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
	t.Cleanup(server.Close)
	c, err := Connect(context.Background(), server.URL, "drain-fixture", Options{DispatchMode: OrderedDispatch, Timeout: time.Second})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

func awaitDrainCondition(t *testing.T, condition func() bool) {
	t.Helper()
	end := time.Now().Add(time.Second)
	for !condition() {
		if time.Now().After(end) {
			t.Fatal("transport did not reach the expected ownership state")
		}
		runtime.Gosched()
	}
}

func assertTransportIdle(t *testing.T, c *Client) {
	t.Helper()
	c.mu.Lock()
	admitted, held, pending := c.admitted, c.held, len(c.pending)
	c.mu.Unlock()
	processPackets.Lock()
	packetBytes := c.packets.held
	processPackets.Unlock()
	if admitted != 0 || held != 0 || pending != 0 || packetBytes != 0 {
		t.Fatal("close returned before transport ownership drained", admitted, held, pending, packetBytes)
	}
}

func TestCloseDrainsQueuedConverterAndPhysicalWriterBeforeReturning(t *testing.T) {
	c := drainClient(t)
	c.packets.writer <- struct{}{}
	defer func() { <-c.packets.writer }()
	first := make(chan error, 1)
	go func() {
		_, err := c.MemoryIndex(context.Background(), MemoryIndexRequest{})
		first <- err
	}()
	awaitDrainCondition(t, func() bool {
		c.packets.mu.Lock()
		defer c.packets.mu.Unlock()
		return c.packets.sending != ""
	})
	c.converter <- struct{}{}
	defer func() { <-c.converter }()
	var encoded atomic.Int32
	second := make(chan error, 1)
	go func() {
		_, err := requestTyped[[]ObservedIndexDto](context.Background(), c, "memory.index", observedEncoding{&encoded})
		second <- err
	}()
	awaitDrainCondition(t, func() bool {
		c.mu.Lock()
		defer c.mu.Unlock()
		return c.admitted == 2
	})
	closed := make(chan error, 2)
	go func() { closed <- c.Close() }()
	go func() { closed <- c.Close() }()
	for range 2 {
		select {
		case err := <-closed:
			if err != nil {
				t.Fatal(err)
			}
		case <-time.After(time.Second):
			t.Fatal("close did not cancel and drain queued owners")
		}
		assertTransportIdle(t, c)
	}
	for _, result := range []<-chan error{first, second} {
		var transport *TransportError
		if err := <-result; !errors.As(err, &transport) || transport.Delivery != NotSubmitted {
			t.Fatal("queued close lost NOT_SUBMITTED", err)
		}
	}
	if encoded.Load() != 0 {
		t.Fatal("closed queued converter reached encoding")
	}
}

type heldEncoding struct{ entered, release chan struct{} }

func (h heldEncoding) MarshalJSON() ([]byte, error) {
	close(h.entered)
	<-h.release
	return []byte(`{}`), nil
}

func TestCloseWaitsForActiveConversionOwnershipWithoutWaitingForCaller(t *testing.T) {
	c := drainClient(t)
	encoding := heldEncoding{make(chan struct{}), make(chan struct{})}
	var release sync.Once
	defer release.Do(func() { close(encoding.release) })
	result := make(chan error, 1)
	go func() {
		_, err := requestTyped[[]ObservedIndexDto](context.Background(), c, "memory.index", encoding)
		result <- err
	}()
	select {
	case <-encoding.entered:
	case <-time.After(time.Second):
		t.Fatal("converter did not start")
	}
	closed := make(chan error, 1)
	go func() { closed <- c.Close() }()
	select {
	case <-c.done:
	case <-time.After(time.Second):
		t.Fatal("reader did not stop")
	}
	select {
	case <-closed:
		t.Fatal("close returned while conversion retained an admission")
	default:
	}
	release.Do(func() { close(encoding.release) })
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("close did not observe released conversion ownership")
	}
	assertTransportIdle(t, c)
	// Consume the caller result only after close: transport drain must not depend
	// on application code collecting its reply or running a callback.
	var transport *TransportError
	if err := <-result; !errors.As(err, &transport) || transport.Delivery != NotSubmitted {
		t.Fatal("closed converter lost NOT_SUBMITTED", err)
	}
}
