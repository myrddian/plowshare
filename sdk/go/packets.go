package plowshare

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/coder/websocket"
)

const packetProtocol = "plowshare-segments-v1"
const packetChunk = 65536
const packetMessage = 320 * 1024 * 1024
const packetWire = 128 * 1024

var packetIdentity = regexp.MustCompile(`^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$`)
var packetHash = regexp.MustCompile(`^[a-f0-9]{64}$`)
var packetCapacity = errors.New("packet capacity exceeded before submission")
var processPackets struct {
	sync.Mutex
	held int
}

type messageSegment struct {
	Kind       string `json:"kind"`
	Version    int    `json:"version"`
	TransferID string `json:"transferId"`
	Number     int    `json:"segmentNumber"`
	Count      int    `json:"segmentCount"`
	Offset     int    `json:"byteOffset"`
	Total      int    `json:"totalBytes"`
	Hash       string `json:"sha256"`
	Data       string `json:"data"`
}
type segmentCredit struct {
	Kind       string `json:"kind"`
	Version    int    `json:"version"`
	TransferID string `json:"transferId"`
	Number     int    `json:"segmentNumber"`
}
type packetAssembly struct {
	first   messageSegment
	bytes   []byte
	ranges  map[int]bool
	started time.Time
	touched time.Time
}

// A socket owns its assemblies. Credits bypass the logical writer; no mutation is ever retried.
type packets struct {
	socket    *websocket.Conn
	mu        sync.Mutex
	sender    sync.Mutex
	writer    sync.Mutex
	incoming  map[string]*packetAssembly
	completed map[string]bool
	held      int
	closed    bool
	sending   string
	expected  int
	credited  int
	credit    chan struct{}
	done      chan struct{}
}

func newPackets(socket *websocket.Conn) *packets {
	p := &packets{socket: socket, incoming: map[string]*packetAssembly{}, completed: map[string]bool{}, credit: make(chan struct{}, 1), done: make(chan struct{})}
	go p.expire()
	return p
}
func (p *packets) reserve(size int) error {
	processPackets.Lock()
	defer processPackets.Unlock()
	if size > 640*1024*1024-p.held || size*3 > 2*1024*1024*1024-processPackets.held {
		return packetCapacity
	}
	p.held += size
	processPackets.held += size * 3
	return nil
}
func (p *packets) release(size int) {
	processPackets.Lock()
	p.held -= size
	processPackets.held -= size * 3
	processPackets.Unlock()
}
func (p *packets) write(ctx context.Context, value any) error {
	wire, err := json.Marshal(value)
	if err != nil {
		return err
	}
	p.writer.Lock()
	defer p.writer.Unlock()
	return p.socket.Write(ctx, websocket.MessageText, wire)
}
func (p *packets) send(ctx context.Context, wire []byte) error {
	if len(wire) == 0 || len(wire) > packetMessage || !utf8.Valid(wire) {
		return packetCapacity
	}
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return packetCapacity
	}
	err := p.reserve(len(wire))
	p.mu.Unlock()
	if err != nil {
		return err
	}
	defer func() { p.mu.Lock(); p.release(len(wire)); p.mu.Unlock() }()
	p.sender.Lock()
	defer p.sender.Unlock()
	defer func() { p.mu.Lock(); p.sending = ""; p.mu.Unlock() }()
	ctx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	raw := identity()
	id := fmt.Sprintf("%s-%s-%s-%s-%s", raw[:8], raw[8:12], raw[12:16], raw[16:20], raw[20:])
	hash := sha256.Sum256(wire)
	count := (len(wire) + packetChunk - 1) / packetChunk
	p.mu.Lock()
	p.sending = id
	p.credited = 0
	p.mu.Unlock()
	for n := 1; n <= count; n++ {
		offset := (n - 1) * packetChunk
		end := min(len(wire), offset+packetChunk)
		p.mu.Lock()
		p.expected = n
		p.mu.Unlock()
		if err = p.write(ctx, messageSegment{"transport.segment", 1, id, n, count, offset, len(wire), hex.EncodeToString(hash[:]), base64.StdEncoding.EncodeToString(wire[offset:end])}); err != nil {
			p.close()
			_ = p.socket.CloseNow()
			return err
		}
		timer := time.NewTimer(15 * time.Second)
		select {
		case <-p.credit:
			timer.Stop()
		case <-p.done:
			timer.Stop()
			return errors.New("packet connection closed")
		case <-ctx.Done():
			timer.Stop()
			p.close()
			_ = p.socket.CloseNow()
			return ctx.Err()
		case <-timer.C:
			p.close()
			_ = p.socket.CloseNow()
			return errors.New("packet credit deadline expired")
		}
	}
	return nil
}

// Flat packet fields are strictly unique. The normal application codec runs only after assembly.
func packetFields(wire []byte) (map[string]json.RawMessage, error) {
	decoder := json.NewDecoder(bytes.NewReader(wire))
	token, err := decoder.Token()
	if err != nil || token != json.Delim('{') {
		return nil, contractError
	}
	fields := map[string]json.RawMessage{}
	for decoder.More() {
		token, err = decoder.Token()
		if err != nil {
			return nil, err
		}
		key, ok := token.(string)
		if !ok {
			return nil, contractError
		}
		if _, exists := fields[key]; exists {
			return nil, contractError
		}
		var value json.RawMessage
		if err = decoder.Decode(&value); err != nil {
			return nil, err
		}
		fields[key] = value
	}
	if _, err = decoder.Token(); err != nil {
		return nil, err
	}
	if _, err = decoder.Token(); err != io.EOF {
		return nil, contractError
	}
	return fields, nil
}
func (p *packets) receive(ctx context.Context, wire []byte) ([]byte, error) {
	if len(wire) > packetWire {
		return nil, contractError
	}
	fields, err := packetFields(wire)
	if err != nil {
		return nil, err
	}
	var kind string
	if json.Unmarshal(fields["kind"], &kind) != nil {
		return nil, contractError
	}
	if kind == "transport.credit" {
		var ack segmentCredit
		if len(fields) != 4 || json.Unmarshal(wire, &ack) != nil || ack.Version != 1 || !packetIdentity.MatchString(ack.TransferID) || ack.Number < 1 || ack.Number > packetMessage/packetChunk {
			return nil, contractError
		}
		for _, key := range []string{"kind", "version", "transferId", "segmentNumber"} {
			if _, ok := fields[key]; !ok {
				return nil, contractError
			}
		}
		p.mu.Lock()
		defer p.mu.Unlock()
		if p.closed || ack.TransferID != p.sending || ack.Number != p.expected {
			return nil, contractError
		}
		if p.credited == ack.Number {
			return nil, nil
		}
		p.credited = ack.Number
		select {
		case p.credit <- struct{}{}:
		default:
			return nil, contractError
		}
		return nil, nil
	}
	var part messageSegment
	if kind != "transport.segment" || len(fields) != 9 || json.Unmarshal(wire, &part) != nil || part.Version != 1 || !packetIdentity.MatchString(part.TransferID) || part.Total <= 0 || part.Total > packetMessage || part.Count != (part.Total+packetChunk-1)/packetChunk || part.Number < 1 || part.Number > part.Count || part.Offset != (part.Number-1)*packetChunk || bytes.Equal(fields["byteOffset"], []byte("null")) || !packetHash.MatchString(part.Hash) || len(part.Data) > 87384 {
		return nil, contractError
	}
	for _, key := range []string{"kind", "version", "transferId", "segmentNumber", "segmentCount", "byteOffset", "totalBytes", "sha256", "data"} {
		if _, ok := fields[key]; !ok {
			return nil, contractError
		}
	}
	decoded, err := base64.StdEncoding.Strict().DecodeString(part.Data)
	if err != nil || len(decoded) != min(packetChunk, part.Total-part.Offset) || base64.StdEncoding.EncodeToString(decoded) != part.Data {
		return nil, contractError
	}
	p.mu.Lock()
	message, err := p.assemble(part, decoded)
	p.mu.Unlock()
	if err != nil {
		return nil, err
	}
	deadline, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	if err = p.write(deadline, segmentCredit{"transport.credit", 1, part.TransferID, part.Number}); err != nil {
		return nil, err
	}
	return message, nil
}
func (p *packets) assemble(part messageSegment, decoded []byte) ([]byte, error) {
	if p.closed || p.completed[part.TransferID] {
		return nil, contractError
	}
	assembly := p.incoming[part.TransferID]
	if assembly == nil {
		if len(p.incoming) >= 4 || len(p.completed)+len(p.incoming) >= 4096 {
			return nil, contractError
		}
		if err := p.reserve(part.Total); err != nil {
			return nil, err
		}
		assembly = &packetAssembly{first: part, bytes: make([]byte, part.Total), ranges: map[int]bool{}, started: time.Now()}
		p.incoming[part.TransferID] = assembly
	}
	if part.Total != assembly.first.Total || part.Count != assembly.first.Count || part.Hash != assembly.first.Hash {
		return nil, contractError
	}
	if assembly.ranges[part.Number] {
		if !bytes.Equal(assembly.bytes[part.Offset:part.Offset+len(decoded)], decoded) {
			return nil, contractError
		}
	} else {
		copy(assembly.bytes[part.Offset:], decoded)
		assembly.ranges[part.Number] = true
	}
	assembly.touched = time.Now()
	if len(assembly.ranges) != part.Count {
		return nil, nil
	}
	hash := sha256.Sum256(assembly.bytes)
	if hex.EncodeToString(hash[:]) != part.Hash || !utf8.Valid(assembly.bytes) {
		return nil, contractError
	}
	delete(p.incoming, part.TransferID)
	p.release(part.Total)
	if err := p.reserve(256); err != nil {
		return nil, err
	}
	p.completed[part.TransferID] = true
	return assembly.bytes, nil
}
func (p *packets) expire() {
	timer := time.NewTicker(time.Second)
	defer timer.Stop()
	for {
		select {
		case <-p.done:
			return
		case <-timer.C:
			p.mu.Lock()
			stale := false
			for _, a := range p.incoming {
				if time.Since(a.started) >= 60*time.Second || time.Since(a.touched) >= 15*time.Second {
					stale = true
					break
				}
			}
			p.mu.Unlock()
			if stale {
				p.close()
				_ = p.socket.CloseNow()
				return
			}
		}
	}
}
func (p *packets) close() {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		return
	}
	p.closed = true
	for _, a := range p.incoming {
		p.release(a.first.Total)
	}
	clear(p.incoming)
	p.release(len(p.completed) * 256)
	clear(p.completed)
	close(p.done)
}
