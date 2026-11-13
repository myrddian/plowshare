package plowshare

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

var blobRequest = RelayBlobPublishDto{RequestId: "11111111-1111-1111-1111-111111111111", Project: "project", Topic: "event", OccurredAt: "2026-11-13T00:00:00Z", MediaType: "application/octet-stream"}
var blobRead = RelayBlobReadDto{Project: "project", Topic: "event", Group: "workers", EventId: "22222222-2222-2222-2222-222222222222"}

func blobDescriptor(data []byte) RelayBlobDescriptorDto {
	sum := sha256.Sum256(data)
	return RelayBlobDescriptorDto{Sha256: hex.EncodeToString(sum[:]), ByteLength: float64(len(data)), MediaType: "application/octet-stream"}
}
func blobReceipt(data []byte) RelayBlobPublishedDto {
	return RelayBlobPublishedDto{Blob: blobDescriptor(data), Publication: RelayBlobPublishedDtoPublicationDto{RequestId: blobRequest.RequestId, Project: "project", Topic: "event", Position: "9007199254740993", PublishedAt: "2026-11-13T00:00:00Z"}}
}
func blobClient(t *testing.T, handler http.HandlerFunc) *RelayBlobs {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	client, err := NewRelayBlobs(server.URL, "fixture", time.Second)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(client.Close)
	return client
}
func TestBinaryPublishAndRead(t *testing.T) {
	data := []byte{0, 1, 2, 255}
	var calls atomic.Int32
	client := blobClient(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Header.Get("Authorization") != "Bearer fixture" || r.URL.Path != "/v1/relay/blobs" {
			t.Error("wrong binary route or authorization")
		}
		if r.Method == "POST" {
			if err := r.ParseMultipartForm(1024); err != nil {
				t.Error(err)
				return
			}
			defer r.MultipartForm.RemoveAll()
			if r.FormValue("requestId") != blobRequest.RequestId || r.FormValue("mediaType") != blobRequest.MediaType {
				t.Error("missing metadata")
			}
			file, _, err := r.FormFile("file")
			if err != nil {
				t.Error(err)
				return
			}
			defer file.Close()
			got, _ := io.ReadAll(file)
			if !bytes.Equal(got, data) {
				t.Error("bytes changed")
			}
			_ = json.NewEncoder(w).Encode(blobReceipt(data))
			return
		}
		if r.URL.Query().Get("eventId") != blobRead.EventId {
			t.Error("wrong event")
		}
		w.Header().Set("Content-Type", "application/octet-stream")
		_, _ = w.Write(data)
	})
	receipt, err := client.Publish(context.Background(), blobRequest, data)
	if err != nil || receipt.Publication.Position != "9007199254740993" {
		t.Fatalf("receipt: %v", err)
	}
	got, err := client.Read(context.Background(), blobRead, receipt.Blob)
	if err != nil || !bytes.Equal(got, data) {
		t.Fatalf("read: %v", err)
	}
	if calls.Load() != 2 {
		t.Fatal("transfer replayed")
	}
}
func TestBinaryRefusesInvalidResponsesAndNeverFollowsRedirect(t *testing.T) {
	for _, kind := range []string{"redirect", "foreign", "short", "overflow", "hash", "headers", "malformed", "refused"} {
		t.Run(kind, func(t *testing.T) {
			var calls atomic.Int32
			data := []byte("binary")
			client := blobClient(t, func(w http.ResponseWriter, r *http.Request) {
				calls.Add(1)
				switch kind {
				case "redirect":
					w.Header().Set("Location", "/leak")
					w.WriteHeader(307)
				case "foreign":
					receipt := blobReceipt(data)
					receipt.Publication.Project = "other"
					_ = json.NewEncoder(w).Encode(receipt)
				case "malformed":
					_, _ = io.WriteString(w, `{"publication":{}}`)
				case "refused":
					w.WriteHeader(403)
				default:
					w.Header().Set("Content-Type", "application/octet-stream")
					switch kind {
					case "short":
						data = data[:2]
					case "overflow":
						data = append(data, 1)
					case "hash":
						data = []byte("broken")
					case "headers":
						w.Header().Set("X-Plowshare-Blob-Media-Type", "text/plain")
					}
					_, _ = w.Write(data)
				}
			})
			var err error
			if kind == "foreign" || kind == "malformed" {
				_, err = client.Publish(context.Background(), blobRequest, []byte("binary"))
			} else {
				_, err = client.Read(context.Background(), blobRead, blobDescriptor([]byte("binary")))
			}
			if err == nil || calls.Load() != 1 {
				t.Fatalf("accepted response or replayed: %v calls %d", err, calls.Load())
			}
		})
	}
}
func TestBinaryValidatesBeforeSubmissionAndCancels(t *testing.T) {
	var calls atomic.Int32
	client := blobClient(t, func(w http.ResponseWriter, r *http.Request) { calls.Add(1) })
	for _, bad := range []RelayBlobPublishDto{{}, {RequestId: blobRequest.RequestId, Project: "project", Topic: "event", OccurredAt: "bad", MediaType: "text/plain"}} {
		if _, err := client.Publish(context.Background(), bad, []byte("x")); err == nil {
			t.Fatal("invalid request accepted")
		}
	}
	expected := blobDescriptor([]byte("x"))
	expected.ByteLength = 1.5
	if _, err := client.Read(context.Background(), blobRead, expected); err == nil {
		t.Fatal("fractional length accepted")
	}
	if _, err := client.Publish(context.Background(), blobRequest, nil); err == nil {
		t.Fatal("empty upload accepted")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := client.Publish(ctx, blobRequest, []byte("x"))
	var failure *TransportError
	if !errors.As(err, &failure) || failure.Delivery != NotSubmitted || calls.Load() != 0 {
		t.Fatal("pre-cancelled request submitted")
	}
	for _, origin := range []string{"", "http://user:secret@example.test", "http://example.test/path"} {
		if _, err = NewRelayBlobs(origin, "fixture", time.Second); err == nil {
			t.Fatal("invalid origin accepted")
		}
	}
	if strings.Contains(client.String(), "fixture") {
		t.Fatal("bearer leaked in diagnostic")
	}
}
func TestBinaryCancellationAfterSubmissionIsUnknown(t *testing.T) {
	started := make(chan struct{})
	release := make(chan struct{})
	client := blobClient(t, func(w http.ResponseWriter, r *http.Request) { close(started); <-release })
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { _, err := client.Publish(ctx, blobRequest, []byte("x")); done <- err }()
	<-started
	cancel()
	err := <-done
	close(release)
	var failure *TransportError
	if !errors.As(err, &failure) || failure.Delivery != Unknown {
		t.Fatal("lost unknown delivery")
	}
}
