package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	plowshare "io.aeyer/plowshare/sdk"
	"os"
	"reflect"
	"time"
)

func check(value bool, message string) {
	if !value {
		panic(message)
	}
}
func main() {
	ctx := context.Background()
	wire, err := os.ReadFile(os.Getenv("PLOWSHARE_SDK_FIXTURES"))
	check(err == nil, "fixture unavailable")
	var fixture struct {
		Cases []struct {
			Name     string             `json:"name"`
			Response any                `json:"response"`
			Delivery plowshare.Delivery `json:"delivery"`
		} `json:"cases"`
	}
	check(json.Unmarshal(wire, &fixture) == nil, "fixture malformed")
	catalogWire, err := os.ReadFile(os.Getenv("PLOWSHARE_SDK_CATALOG"))
	check(err == nil, "catalog unavailable")
	var catalog struct {
		Operations []string `json:"operations"`
	}
	check(json.Unmarshal(catalogWire, &catalog) == nil, "catalog malformed")
	check(reflect.DeepEqual(plowshare.Operations(), catalog.Operations), "catalog incomplete")
	origin := os.Args[1]
	_, err = plowshare.Connect(ctx, origin+"/wrong", "sdk-fixture-token", plowshare.Options{})
	check(err != nil, "invalid origin accepted")
	_, err = plowshare.Connect(ctx, origin, "sdk-redirect-fixture", plowshare.Options{Session: "go-redirect", Timeout: 300 * time.Millisecond})
	var delivery *plowshare.TransportError
	check(errors.As(err, &delivery) && delivery.Delivery == plowshare.NotSubmitted, "redirect followed")
	client, err := plowshare.Connect(ctx, origin, "sdk-fixture-token", plowshare.Options{Session: "go", Timeout: 300 * time.Millisecond})
	check(err == nil, "connect failed")
	defer client.Close()
	first := make(chan plowshare.Reply, 1)
	go func() {
		reply, err := client.Request(ctx, "project.list", map[string]any{"scenario": "multiplex-one"})
		check(err == nil, "first request failed")
		first <- reply
	}()
	two, err := client.Request(ctx, "project.list", map[string]any{"scenario": "multiplex-two"})
	check(err == nil, "second request failed")
	one := <-first
	var a, b map[string]any
	check(json.Unmarshal(one.Outcome.Payload, &a) == nil && json.Unmarshal(two.Outcome.Payload, &b) == nil && a["sequence"] == float64(1) && b["sequence"] == float64(2), "reply correlation failed")
	for _, test := range fixture.Cases {
		reply, err := client.Request(ctx, "project.list", map[string]any{"scenario": test.Name})
		if test.Delivery != "" {
			check(errors.As(err, &delivery) && delivery.Delivery == test.Delivery, "wrong delivery: "+test.Name)
			continue
		}
		check(err == nil, "request failed: "+test.Name)
		var raw map[string]any
		check(json.Unmarshal(reply.Raw, &raw) == nil && raw["futureEnvelope"] == true, "future field lost")
		check(reflect.DeepEqual(raw["payload"], test.Response), "opaque result changed")
		if test.Name == "success" {
			body, err := reply.RequirePayload()
			check(err == nil && len(body) > 0, "payload absent")
		}
		if test.Name == "refusal" {
			_, err = reply.RequirePayload()
			var refusal *plowshare.RefusalError
			check(errors.As(err, &refusal), "refusal became success")
		}
	}
	var bare, envelope map[string]any
	check(json.Unmarshal(<-client.Pushes(), &bare) == nil && bare["kind"] == "fixture-push", "bare push lost")
	check(json.Unmarshal(<-client.Pushes(), &envelope) == nil && envelope["type"] == "usage.snapshot", "enveloped push lost")
	_, err = client.Request(ctx, "project.list", nil)
	check(errors.As(err, &delivery) && delivery.Delivery == plowshare.NotSubmitted, "closed client submitted work")
	cancelled, err := plowshare.Connect(ctx, origin, "sdk-fixture-token", plowshare.Options{Session: "go-cancel"})
	check(err == nil, "cancel connection failed")
	defer cancelled.Close()
	cancelContext, cancel := context.WithCancel(ctx)
	abandoned := make(chan error, 1)
	go func() {
		_, err := cancelled.Request(cancelContext, "project.list", map[string]any{"scenario": "cancel"})
		abandoned <- err
	}()
	var ack map[string]any
	check(json.Unmarshal(<-cancelled.Pushes(), &ack) == nil && ack["kind"] == "fixture-submitted", "cancel fixture not submitted")
	cancel()
	err = <-abandoned
	check(errors.As(err, &delivery) && delivery.Delivery == plowshare.Unknown && errors.Is(err, context.Canceled), "cancel lost delivery state")
	fmt.Println("Go SDK conformance passed")
}
