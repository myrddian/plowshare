package plowshare

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"time"
)

const MaxRelayBlobBytes = 50 * 1024 * 1024

// RelayBlobs owns an explicit-origin, caller-bearer HTTP transport. It never follows
// redirects, refreshes credentials or replays a transfer. It is safe for concurrent use.
type RelayBlobs struct {
	origin, bearer string
	http           *http.Client
}

// NewRelayBlobs requires a bounded timeout; Close releases idle connections.
func NewRelayBlobs(origin, bearer string, timeout time.Duration) (*RelayBlobs, error) {
	base, err := CanonicalOrigin(origin)
	if err != nil {
		return nil, err
	}
	if (len(bearer) > 8192 || !regexp.MustCompile(`^[\x21-\x7e]+$`).MatchString(bearer)) || timeout <= 0 || timeout > 5*time.Minute {
		return nil, errors.New("invalid binary bearer or timeout")
	}
	baseTransport, ok := http.DefaultTransport.(*http.Transport)
	if !ok {
		return nil, errors.New("default HTTP transport is not a native transport")
	}
	transport := baseTransport.Clone()
	return &RelayBlobs{origin: base, bearer: bearer, http: &http.Client{Transport: transport, Timeout: timeout, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}, nil
}
func (c *RelayBlobs) String() string   { return "RelayBlobs{redacted}" }
func (c *RelayBlobs) GoString() string { return c.String() }
func (c *RelayBlobs) Close()           { c.http.CloseIdleConnections() }

func blobContract(name string, value any, input bool) ([]byte, error) {
	raw, err := json.Marshal(value)
	if err != nil {
		return nil, err
	}
	return project(catalog.HTTP[name], raw, input)
}
func (c *RelayBlobs) transfer(ctx context.Context, method, path, contentType string, body []byte, limit int, expected *RelayBlobDescriptorDto) ([]byte, error) {
	if err := ctx.Err(); err != nil {
		return nil, &TransportError{Delivery: NotSubmitted, Cause: err}
	}
	request, err := http.NewRequestWithContext(ctx, method, c.origin+path, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Authorization", "Bearer "+c.bearer)
	request.Header.Set("Accept-Encoding", "identity")
	if contentType != "" {
		request.Header.Set("Content-Type", contentType)
	}
	response, err := c.http.Do(request)
	if err != nil {
		return nil, &TransportError{Delivery: Unknown, Cause: errors.New("binary HTTP transfer failed; reconcile original request")}
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return nil, &TransportError{Delivery: Unknown, Cause: fmt.Errorf("binary HTTP transfer refused (%d); reconcile original request", response.StatusCode)}
	}
	invalid := func() ([]byte, error) {
		return nil, &TransportError{Delivery: InvalidResponse, Cause: errors.New("binary response differs from descriptor")}
	}
	if expected != nil {
		if response.Header.Get("Content-Type") != "application/octet-stream" {
			return invalid()
		}
		for key, value := range map[string]string{"Content-Length": strconv.Itoa(limit), "X-Plowshare-Blob-Sha256": expected.Sha256, "X-Plowshare-Blob-Media-Type": expected.MediaType} {
			if actual, found := response.Header[http.CanonicalHeaderKey(key)]; found && (len(actual) != 1 || actual[0] != value) {
				return invalid()
			}
		}
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, int64(limit)+1))
	if err != nil {
		return nil, &TransportError{Delivery: Unknown, Cause: errors.New("binary response interrupted")}
	}
	if len(data) > limit {
		return invalid()
	}
	return data, nil
}

// Publish submits once. Retain the original request UUID and bytes before calling;
// cancellation after submission has unknown delivery and does not cancel server work.
func (c *RelayBlobs) Publish(ctx context.Context, request RelayBlobPublishDto, data []byte) (RelayBlobPublishedDto, error) {
	var result RelayBlobPublishedDto
	raw, err := blobContract("RelayBlobPublish", request, true)
	if err != nil {
		return result, err
	}
	if len(data) < 1 || len(data) > MaxRelayBlobBytes {
		return result, errors.New("binary upload requires 1..50 MiB")
	}
	snapshot := bytes.Clone(data)
	digest := sha256.Sum256(snapshot)
	// Validated generated metadata is projected inside this HTTP codec boundary.
	var metadata map[string]string
	if err = json.Unmarshal(raw, &metadata); err != nil {
		return result, err
	}
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	for key, value := range metadata {
		if err = writer.WriteField(key, value); err != nil {
			return result, err
		}
	}
	file, err := writer.CreateFormFile("file", "blob.bin")
	if err != nil {
		return result, err
	}
	if _, err = file.Write(snapshot); err != nil {
		return result, err
	}
	if err = writer.Close(); err != nil {
		return result, err
	}
	response, err := c.transfer(ctx, "POST", "/v1/relay/blobs", writer.FormDataContentType(), body.Bytes(), 16384, nil)
	if err != nil {
		return result, err
	}
	response, err = project(catalog.HTTP["RelayBlobPublished"], response, false)
	if err == nil {
		err = json.Unmarshal(response, &result)
	}
	if err != nil {
		return RelayBlobPublishedDto{}, &TransportError{Delivery: InvalidResponse, Cause: errors.New("invalid binary receipt")}
	}
	pub := result.Publication
	if pub.RequestId != request.RequestId || pub.Project != request.Project || pub.Topic != request.Topic || result.Blob.MediaType != request.MediaType || result.Blob.ByteLength != float64(len(snapshot)) || result.Blob.Sha256 != hex.EncodeToString(digest[:]) {
		return RelayBlobPublishedDto{}, &TransportError{Delivery: InvalidResponse, Cause: errors.New("foreign binary receipt")}
	}
	return result, nil
}

// Read checks current server authority and verifies the descriptor before exposing bytes.
func (c *RelayBlobs) Read(ctx context.Context, request RelayBlobReadDto, expected RelayBlobDescriptorDto) ([]byte, error) {
	raw, err := blobContract("RelayBlobRead", request, true)
	if err != nil {
		return nil, err
	}
	if _, err = blobContract("RelayBlobDescriptor", expected, true); err != nil {
		return nil, err
	}
	var metadata map[string]string
	if err = json.Unmarshal(raw, &metadata); err != nil {
		return nil, err
	}
	query := url.Values{}
	for key, value := range metadata {
		query.Set(key, value)
	}
	data, err := c.transfer(ctx, "GET", "/v1/relay/blobs?"+query.Encode(), "", nil, int(expected.ByteLength), &expected)
	if err != nil {
		return nil, err
	}
	digest := sha256.Sum256(data)
	if len(data) != int(expected.ByteLength) || hex.EncodeToString(digest[:]) != expected.Sha256 {
		return nil, &TransportError{Delivery: InvalidResponse, Cause: errors.New("binary download failed integrity verification")}
	}
	return data, nil
}
