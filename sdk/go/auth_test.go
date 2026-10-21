package plowshare

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type authFixture struct {
	rotations atomic.Int32
	refresh   func(context.Context, Tokens) (Tokens, error)
	status    func(Tokens) (Standing, error)
}

func (*authFixture) Origin() string { return "https://auth.example" }
func (*authFixture) Login(context.Context, string, string) (SignIn, error) {
	return SignIn{Tokens: Tokens{"access0", "refresh0"}}, nil
}
func (a *authFixture) Standing(_ context.Context, t Tokens) (Standing, error) {
	if a.status != nil {
		return a.status(t)
	}
	return Standing{SignedIn: t.Access != "access0"}, nil
}
func (a *authFixture) Refresh(ctx context.Context, t Tokens) (Tokens, error) {
	a.rotations.Add(1)
	if a.refresh != nil {
		return a.refresh(ctx, t)
	}
	return Tokens{"access1", "refresh1"}, nil
}
func (*authFixture) Logout(context.Context, Tokens) error { return nil }
func authStore(t *testing.T) (*Credentials, *authFixture) {
	t.Helper()
	s, e := NewCredentials("https://auth.example", CredentialOptions{Directory: t.TempDir(), Account: "alice", LockTimeout: 2 * time.Second})
	if e != nil {
		t.Fatal(e)
	}
	a := &authFixture{}
	if _, e = s.Login(context.Background(), "alice", "fixture-password", a); e != nil {
		t.Fatal(e)
	}
	return s, a
}
func TestAuthConcurrentStatusRefusalRotatesOnce(t *testing.T) {
	s, a := authStore(t)
	var group sync.WaitGroup
	for range 6 {
		group.Add(1)
		go func() {
			defer group.Done()
			session, e := s.Authenticated(context.Background(), a)
			if e != nil || session.Tokens.Access != "access1" {
				t.Errorf("wrong authentication result: %v", e)
			}
		}()
	}
	group.Wait()
	if a.rotations.Load() != 1 {
		t.Fatal("replayed refresh")
	}
}
func TestAuthCancelledAndFailedRefreshStayFenced(t *testing.T) {
	for _, cancelled := range []bool{false, true} {
		t.Run(map[bool]string{true: "cancelled", false: "failed"}[cancelled], func(t *testing.T) {
			s, a := authStore(t)
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			a.refresh = func(context.Context, Tokens) (Tokens, error) {
				if cancelled {
					cancel()
					return Tokens{"new", "new-refresh"}, nil
				}
				return Tokens{}, errors.New("private diagnostic")
			}
			_, e := s.Renew(ctx, a, "")
			if e == nil {
				t.Fatal("renewal succeeded")
			}
			if _, e = s.Renew(context.Background(), a, "older"); !errors.Is(e, ErrLoginRequired) {
				t.Fatal("renewal fence lost")
			}
			if a.rotations.Load() != 1 {
				t.Fatal("replayed fenced refresh")
			}
		})
	}
}
func TestAuthPersistenceFailureRetainsSharedLock(t *testing.T) {
	s, a := authStore(t)
	a.refresh = func(context.Context, Tokens) (Tokens, error) {
		if e := os.Remove(s.file()); e != nil {
			t.Fatal(e)
		}
		if e := os.Mkdir(s.file(), 0700); e != nil {
			t.Fatal(e)
		}
		return Tokens{"access1", "refresh1"}, nil
	}
	if _, e := s.Renew(context.Background(), a, ""); !errors.Is(e, errPersistenceFenced) {
		t.Fatal("missing persistence failure")
	}
	if _, e := os.Stat(s.file() + ".lock"); e != nil {
		t.Fatal("failed persistence released fence")
	}
}
func TestAuthStatusFailureDoesNotRenew(t *testing.T) {
	s, a := authStore(t)
	a.status = func(Tokens) (Standing, error) { return Standing{}, ErrAuthentication }
	if _, e := s.Authenticated(context.Background(), a); e == nil {
		t.Fatal("status failure hidden")
	}
	if a.rotations.Load() != 0 {
		t.Fatal("refreshed without explicit rejection")
	}
}
func TestAuthRejectsForeignAccountServiceStateAndSymlink(t *testing.T) {
	s, a := authStore(t)
	if _, e := s.Login(context.Background(), "bob", "password", a); e == nil {
		t.Fatal("foreign account accepted")
	}
	if e := writeAuthFile(s.file(), savedCredential{1, s.origin, "alice", Tokens{"pss_service", "refresh"}, false}); e != nil {
		t.Fatal(e)
	}
	if _, e := s.Session(context.Background()); e == nil {
		t.Fatal("service fallback accepted")
	}
	if e := os.Remove(s.file()); e != nil {
		t.Fatal(e)
	}
	target := filepath.Join(t.TempDir(), "state")
	if e := os.WriteFile(target, []byte(`{}`), 0600); e != nil {
		t.Fatal(e)
	}
	if e := os.Symlink(target, s.file()); e != nil {
		t.Fatal(e)
	}
	if _, e := s.Session(context.Background()); e == nil {
		t.Fatal("symlink accepted")
	}
}
func TestAuthCanonicalKeysAndSecretDiagnostics(t *testing.T) {
	key, e := ConnectionKey("https://EXAMPLE.com:443/", "alice")
	if e != nil {
		t.Fatal(e)
	}
	expected, _ := ConnectionKey("https://example.com", "alice")
	if key != expected {
		t.Fatal("identity normalization differs")
	}
	for _, origin := range []string{"https://user@example.com", "http://127.1", "https://example.com/path", "https://é.example", "https://example.com?"} {
		if _, e := CanonicalOrigin(origin); e == nil {
			t.Fatalf("invalid origin %s", origin)
		}
	}
	if (Tokens{Access: "private"}).String() != "Tokens{redacted}" {
		t.Fatal("secret diagnostic")
	}
}
