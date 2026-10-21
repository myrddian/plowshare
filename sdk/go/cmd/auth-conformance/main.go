// Isolated synthetic HTTP/file interoperability driver, never a user CLI.
package main

import (
	"context"
	"encoding/json"
	"errors"
	sdk "io.aeyer/plowshare/sdk"
	"os"
	"time"
)

type input struct{ Root, Origin, Action, Rejected, Account, Connection string }
type output struct {
	Access        string `json:"access,omitempty"`
	Key           string `json:"key,omitempty"`
	Handle        string `json:"handle,omitempty"`
	LoginRequired bool   `json:"loginRequired,omitempty"`
	Failed        bool   `json:"failed,omitempty"`
}

func main() {
	var i input
	if json.NewDecoder(os.Stdin).Decode(&i) != nil {
		os.Exit(2)
	}
	account := i.Account
	if account == "" {
		account = "alice"
	}
	options := sdk.CredentialOptions{Directory: i.Root, Account: account, LockTimeout: 2 * time.Second}
	store, e := sdk.NewCredentials(i.Origin, options)
	if i.Action == "resolve" {
		store, e = sdk.ResolveCredentials(i.Origin, i.Account, i.Connection, sdk.CredentialOptions{Directory: i.Root, LockTimeout: 2 * time.Second})
	}
	out := output{}
	if i.Action == "key" {
		out.Key, e = sdk.ConnectionKey(i.Origin, account)
		out.Failed = e != nil
		_ = json.NewEncoder(os.Stdout).Encode(out)
		return
	}
	if e == nil {
		ctx := context.Background()
		switch i.Action {
		case "login":
			_, e = store.Login(ctx, account, "fixture-password", nil)
		case "renew":
			_, e = store.Renew(ctx, nil, i.Rejected)
		case "authenticated":
			_, e = store.Authenticated(ctx, nil)
		case "logout":
			e = store.Logout(ctx, nil)
		}
		if e == nil && i.Action != "logout" {
			var s sdk.UserSession
			s, e = store.Session(ctx)
			if e == nil {
				out.Access = s.Tokens.Access
				out.Handle = s.Handle
				out.Key, _ = sdk.ConnectionKey(s.Origin, s.Handle)
			}
		}
	}
	if e != nil {
		out.LoginRequired = errors.Is(e, sdk.ErrLoginRequired)
		out.Failed = true
	}
	_ = json.NewEncoder(os.Stdout).Encode(out)
}
