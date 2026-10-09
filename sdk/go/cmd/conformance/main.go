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
func main() {
	ctx := context.Background()
	origin := os.Args[1]
	bytes, err := os.ReadFile(os.Getenv("PLOWSHARE_SDK_FIXTURES"))
	check(err == nil, "fixture unavailable")
	var fixture struct {
		Cases []struct {
			Name     string             `json:"name"`
			Delivery plowshare.Delivery `json:"delivery"`
		} `json:"cases"`
	}
	check(json.Unmarshal(bytes, &fixture) == nil, "invalid fixture")
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
	client := open("multiplex")
	type result struct {
		reply plowshare.Reply[[]plowshare.ProjectViewDto]
		err   error
	}
	first := make(chan result, 1)
	go func() {
		reply, err := client.ProjectList(ctx, plowshare.ProjectListRequest{})
		first <- result{reply, err}
	}()
	// The fixture labels requests by arrival, so assert the pair independently of goroutine scheduling.
	two, err := client.ProjectList(ctx, plowshare.ProjectListRequest{})
	one := <-first
	check(err == nil && one.err == nil, "multiplex failed")
	a, err := one.reply.RequirePayload()
	check(err == nil, "missing first")
	b, err := two.RequirePayload()
	check(err == nil, "missing second")
	check(a[0].Name != b[0].Name && (a[0].Name == "first" || a[0].Name == "second"), "correlation failed")
	client.Close()
	for _, extra := range []string{"malformed-nested", "missing-payload"} {
		fixture.Cases = append(fixture.Cases, struct {
			Name     string             `json:"name"`
			Delivery plowshare.Delivery `json:"delivery"`
		}{extra, plowshare.InvalidResponse})
	}
	for _, test := range fixture.Cases {
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
			check(envelope.Variant11 != nil && envelope.Variant11.Type == "usage.closed", "enveloped hint missing")
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
	fmt.Println("Go typed SDK conformance passed")
}
