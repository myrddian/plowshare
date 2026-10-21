package plowshare

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

// UserSession is local identity metadata, not proof of current server authority.
type UserSession struct {
	Origin string
	Handle string
	Tokens Tokens
}

// CredentialOptions selects an optional saved human login. Directory overrides
// environment precedence; an empty account infers only an unambiguous identity.
type CredentialOptions struct {
	Directory   string
	Account     string
	LockTimeout time.Duration
}

// Credentials owns one optional shared user login and serializes renewal with
// every native SDK. Its methods never open or recover application connections.
type Credentials struct {
	origin, account, root string
	legacy                []string
	lockTimeout           time.Duration
}
type savedCredential struct {
	Version  int    `json:"version"`
	Origin   string `json:"origin"`
	Handle   string `json:"handle"`
	Tokens   Tokens `json:"tokens"`
	Renewing bool   `json:"renewing,omitempty"`
}
type credentialMigration struct {
	Version int    `json:"version"`
	Origin  string `json:"origin"`
	Account string `json:"account"`
}

// ConfigurationDirectory shares Node's configuration root precedence. XDG is
// exclusively a legacy migration source, never a new profile's fallback.
func ConfigurationDirectory() (string, error) {
	root, ok := os.LookupEnv("PLOWSHARE_CONFIG_DIR")
	if !ok {
		root, ok = os.LookupEnv("PLOWSHARE_DESKTOP_CONFIG")
	}
	if !ok {
		home, e := os.UserHomeDir()
		if e != nil {
			return "", ErrCredential
		}
		root = filepath.Join(home, ".plowshare")
	}
	if !filepath.IsAbs(root) {
		return "", ErrCredential
	}
	return root, nil
}
func digest(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}

// ConnectionKey is the immutable SHA-256 identity used by all native clients.
func ConnectionKey(origin, account string) (string, error) {
	base, e := CanonicalOrigin(origin)
	if e != nil || !accountPattern.MatchString(account) {
		return "", ErrCredential
	}
	data, _ := json.Marshal([]string{base, account})
	return digest(string(data)), nil
}

// NewCredentials binds an explicit origin and optional account/configuration root.
// A missing account is inferred only when local identity is unambiguous.
func NewCredentials(origin string, options CredentialOptions) (*Credentials, error) {
	base, e := CanonicalOrigin(origin)
	if e != nil {
		return nil, e
	}
	if options.Account != "" && !accountPattern.MatchString(options.Account) {
		return nil, ErrCredential
	}
	root := options.Directory
	if root == "" {
		root, e = ConfigurationDirectory()
		if e != nil {
			return nil, e
		}
	}
	if !filepath.IsAbs(root) {
		return nil, ErrCredential
	}
	wait := options.LockTimeout
	if wait == 0 {
		wait = 10 * time.Second
	}
	if wait < 0 {
		return nil, ErrCredential
	}
	c := &Credentials{base, options.Account, root, []string{filepath.Join(root, "credentials")}, wait}
	_, explicit := os.LookupEnv("PLOWSHARE_CONFIG_DIR")
	_, old := os.LookupEnv("PLOWSHARE_DESKTOP_CONFIG")
	if options.Directory == "" && !explicit && !old {
		xdg, ok := os.LookupEnv("XDG_CONFIG_HOME")
		if !ok {
			home, e := os.UserHomeDir()
			if e != nil {
				return nil, ErrCredential
			}
			xdg = filepath.Join(home, ".config")
		}
		if !filepath.IsAbs(xdg) {
			return nil, ErrCredential
		}
		c.legacy = append(c.legacy, filepath.Join(xdg, "plowshare", "credentials"))
	}
	return c, nil
}
func (c *Credentials) Origin() string { return c.origin }
func (c *Credentials) forAccount(account string) *Credentials {
	copy := *c
	copy.account = account
	return &copy
}
func (c *Credentials) scope() string {
	key, _ := ConnectionKey(c.origin, c.account)
	return filepath.Join(c.root, "connections", key)
}
func (c *Credentials) file() string {
	return filepath.Join(c.scope(), "credentials", digest(c.origin)+".json")
}
func privateAuthDirectory(path string) error {
	if e := os.MkdirAll(path, 0700); e != nil {
		return ErrCredential
	}
	info, e := os.Lstat(path)
	if e != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 || !ownedAuthFile(info) {
		return ErrCredential
	}
	if e = os.Chmod(path, 0700); e != nil {
		return ErrCredential
	}
	return nil
}
func readAuthFile(path string) ([]byte, error) {
	info, e := os.Lstat(path)
	if errors.Is(e, os.ErrNotExist) {
		return nil, nil
	}
	if e != nil || !info.Mode().IsRegular() || info.Size() > 2000000 || !ownedAuthFile(info) {
		return nil, ErrCredential
	}
	f, e := openAuthFile(path)
	if e != nil {
		return nil, ErrCredential
	}
	defer f.Close()
	opened, e := f.Stat()
	if e != nil || !os.SameFile(info, opened) {
		return nil, ErrCredential
	}
	if e = f.Chmod(0600); e != nil {
		return nil, ErrCredential
	}
	data, e := io.ReadAll(io.LimitReader(f, 2000001))
	if e != nil || len(data) > 2000000 {
		return nil, ErrCredential
	}
	return data, nil
}
func syncAuthDirectory(path string) error {
	if os.PathSeparator == '\\' {
		return nil
	}
	f, e := os.Open(path)
	if e != nil {
		return ErrCredential
	}
	defer f.Close()
	if f.Sync() != nil {
		return ErrCredential
	}
	return nil
}
func writeAuthFile(path string, value interface{}) error {
	if info, e := os.Lstat(path); e == nil && (!info.Mode().IsRegular() || !ownedAuthFile(info)) {
		return ErrCredential
	} else if e != nil && !errors.Is(e, os.ErrNotExist) {
		return ErrCredential
	}
	data, e := json.Marshal(value)
	if e != nil {
		return ErrCredential
	}
	nonce := make([]byte, 16)
	if _, e = rand.Read(nonce); e != nil {
		return ErrCredential
	}
	temporary := path + "." + hex.EncodeToString(nonce)
	f, e := os.OpenFile(temporary, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if e != nil {
		return ErrCredential
	}
	defer os.Remove(temporary)
	_, e = f.Write(data)
	if e == nil {
		e = f.Sync()
	}
	closed := f.Close()
	if e != nil || closed != nil {
		return ErrCredential
	}
	if os.Rename(temporary, path) != nil {
		return ErrCredential
	}
	return syncAuthDirectory(filepath.Dir(path))
}

// File JSON stays at this persistence boundary. Unknown/null fields never get
// silently coerced into successful domain values.
func decodeAuthFile(data []byte, target interface{}) error {
	if !uniqueAuthJSON(data) {
		return ErrCredential
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if decoder.Decode(target) != nil || decoder.Decode(new(interface{})) != io.EOF {
		return ErrCredential
	}
	return nil
}
func decodeSaved(data []byte) (savedCredential, error) {
	var s savedCredential
	if decodeAuthFile(data, &s) != nil || s.Version != 1 || !accountPattern.MatchString(s.Handle) || !validTokens(s.Tokens, true) || strings.HasPrefix(s.Tokens.Access, "pss_") {
		return s, ErrCredential
	}
	origin, e := CanonicalOrigin(s.Origin)
	if e != nil || origin != s.Origin {
		return s, ErrCredential
	}
	var fields map[string]json.RawMessage
	if json.Unmarshal(data, &fields) != nil {
		return s, ErrCredential
	}
	if v, ok := fields["renewing"]; ok && string(v) == "null" {
		return s, ErrCredential
	}
	return s, nil
}
func decodeMigration(data []byte) (credentialMigration, error) {
	var m credentialMigration
	if decodeAuthFile(data, &m) != nil || m.Version != 1 || !accountPattern.MatchString(m.Account) {
		return m, ErrCredential
	}
	base, e := CanonicalOrigin(m.Origin)
	if e != nil || base != m.Origin {
		return m, ErrCredential
	}
	return m, nil
}
func (c *Credentials) directories() error {
	for _, path := range []string{c.root, filepath.Join(c.root, "connections"), c.scope(), filepath.Dir(c.file())} {
		if e := privateAuthDirectory(path); e != nil {
			return e
		}
	}
	return nil
}
func (c *Credentials) lock(ctx context.Context, file string) (func() error, error) {
	deadline := time.Now().Add(c.lockTimeout)
	for {
		if e := ctx.Err(); e != nil {
			return nil, e
		}
		e := os.Mkdir(file+".lock", 0700)
		if e == nil {
			return func() error { return os.Remove(file + ".lock") }, nil
		}
		if !errors.Is(e, os.ErrExist) {
			return nil, ErrCredential
		}
		if !time.Now().Before(deadline) {
			return nil, errors.New("credential store busy; inspect interrupted lock, never steal it")
		}
		timer := time.NewTimer(25 * time.Millisecond)
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil, ctx.Err()
		case <-timer.C:
		}
	}
}
func (c *Credentials) mark() error {
	return writeAuthFile(filepath.Join(c.scope(), "credential-migration.json"), credentialMigration{1, c.origin, c.account})
}
func (c *Credentials) migrate(ctx context.Context) error {
	marker, e := readAuthFile(filepath.Join(c.scope(), "credential-migration.json"))
	if e != nil {
		return e
	}
	if marker != nil {
		m, e := decodeMigration(marker)
		if e != nil || m.Origin != c.origin || m.Account != c.account {
			return ErrCredential
		}
		return nil
	}
	current, e := readAuthFile(c.file())
	if e != nil {
		return e
	}
	if current != nil {
		return nil
	}
	for _, folder := range c.legacy {
		if _, e := os.Lstat(folder); errors.Is(e, os.ErrNotExist) {
			continue
		}
		if privateAuthDirectory(folder) != nil {
			return ErrCredential
		}
		file := filepath.Join(folder, filepath.Base(c.file()))
		release, e := c.lock(ctx, file)
		if e != nil {
			return e
		}
		e = func() error {
			defer release()
			raw, e := readAuthFile(file)
			if e != nil || raw == nil {
				return e
			}
			saved, e := decodeSaved(raw)
			if e != nil || saved.Origin != c.origin {
				return ErrCredential
			}
			if saved.Handle != c.account {
				return nil
			}
			if saved.Renewing {
				return ErrLoginRequired
			}
			if os.Rename(file, c.file()) != nil {
				return ErrCredential
			}
			if syncAuthDirectory(filepath.Dir(c.file())) != nil {
				return ErrCredential
			}
			if c.mark() != nil {
				return ErrCredential
			}
			return syncAuthDirectory(folder)
		}()
		if e != nil {
			return e
		}
		if current, e := readAuthFile(c.file()); e != nil {
			return e
		} else if current != nil {
			return nil
		}
	}
	return nil
}
func (c *Credentials) load() (savedCredential, error) {
	raw, e := readAuthFile(c.file())
	if e != nil {
		return savedCredential{}, e
	}
	if raw == nil {
		return savedCredential{}, ErrLoginRequired
	}
	s, e := decodeSaved(raw)
	if e != nil || s.Origin != c.origin || s.Handle != c.account {
		return s, ErrCredential
	}
	if s.Renewing {
		return s, ErrLoginRequired
	}
	return s, nil
}
func (c *Credentials) chosen() (*Credentials, error) {
	if c.account != "" {
		return c, nil
	}
	if privateAuthDirectory(c.root) != nil {
		return nil, ErrCredential
	}
	accounts := map[string]bool{}
	parent := filepath.Join(c.root, "connections")
	if _, e := os.Lstat(parent); !errors.Is(e, os.ErrNotExist) {
		if privateAuthDirectory(parent) != nil {
			return nil, ErrCredential
		}
		entries, e := os.ReadDir(parent)
		if e != nil || len(entries) > 1024 {
			return nil, ErrCredential
		}
		for _, entry := range entries {
			if !regexp.MustCompile(`^[a-f0-9]{64}$`).MatchString(entry.Name()) {
				continue
			}
			path := filepath.Join(parent, entry.Name())
			if privateAuthDirectory(path) != nil {
				return nil, ErrCredential
			}
			raw, e := readAuthFile(filepath.Join(path, "credential-migration.json"))
			if e != nil {
				return nil, e
			}
			if raw != nil {
				m, e := decodeMigration(raw)
				key, _ := ConnectionKey(m.Origin, m.Account)
				if e != nil || key != entry.Name() {
					return nil, ErrCredential
				}
				if m.Origin == c.origin {
					accounts[m.Account] = true
				}
			}
			folder := filepath.Join(path, "credentials")
			if _, e := os.Lstat(folder); !errors.Is(e, os.ErrNotExist) {
				if privateAuthDirectory(folder) != nil {
					return nil, ErrCredential
				}
				raw, e := readAuthFile(filepath.Join(folder, digest(c.origin)+".json"))
				if e != nil {
					return nil, e
				}
				if raw != nil {
					s, e := decodeSaved(raw)
					key, _ := ConnectionKey(s.Origin, s.Handle)
					if e != nil || s.Origin != c.origin || key != entry.Name() {
						return nil, ErrCredential
					}
					accounts[s.Handle] = true
				}
			}
		}
	}
	if len(accounts) > 1 {
		return nil, errors.New("multiple saved accounts; select an explicit account or named connection")
	}
	for account := range accounts {
		return c.forAccount(account), nil
	}
	for _, folder := range c.legacy {
		if _, e := os.Lstat(folder); errors.Is(e, os.ErrNotExist) {
			continue
		}
		if privateAuthDirectory(folder) != nil {
			return nil, ErrCredential
		}
		raw, e := readAuthFile(filepath.Join(folder, digest(c.origin)+".json"))
		if e != nil {
			return nil, e
		}
		if raw != nil {
			s, e := decodeSaved(raw)
			if e != nil || s.Origin != c.origin {
				return nil, ErrCredential
			}
			return c.forAccount(s.Handle), nil
		}
	}
	return nil, ErrLoginRequired
}
func (c *Credentials) authentication(auth Authentication) (Authentication, error) {
	if auth == nil {
		return NewHttpAuthentication(c.origin, 30*time.Second, "", "")
	}
	if auth.Origin() != c.origin {
		return nil, ErrCredential
	}
	return auth, nil
}

var errPersistenceFenced = errors.New("credential persistence failed; inspect retained lock and sign in again")

func (c *Credentials) locked(ctx context.Context, work func() error) (err error) {
	if err = c.directories(); err != nil {
		return
	}
	release, e := c.lock(ctx, c.file())
	if e != nil {
		return e
	}
	defer func() {
		// A replace may have succeeded before its directory sync failed. Keep
		// the shared lock so no client can consume an unproven pair.
		if errors.Is(err, errPersistenceFenced) {
			return
		}
		if e := release(); err == nil && e != nil {
			err = ErrCredential
		}
	}()
	return work()
}

// Session reloads local credentials under the shared lock; it never renews.
func (c *Credentials) Session(ctx context.Context) (result UserSession, err error) {
	chosen, e := c.chosen()
	if e != nil {
		return result, e
	}
	err = chosen.locked(ctx, func() error {
		if e := chosen.migrate(ctx); e != nil {
			return e
		}
		s, e := chosen.load()
		if e == nil {
			result = UserSession{s.Origin, s.Handle, s.Tokens}
		}
		return e
	})
	return
}

// Login stores only ordinary renewable sessions. Restricted login is returned
// explicitly without saving a password or overwriting the existing session.
func (c *Credentials) Login(ctx context.Context, handle, password string, auth Authentication) (result SignIn, err error) {
	if !accountPattern.MatchString(handle) || (c.account != "" && handle != c.account) {
		return result, ErrCredential
	}
	a, e := c.authentication(auth)
	if e != nil {
		return result, e
	}
	chosen := c.forAccount(handle)
	err = chosen.locked(ctx, func() error {
		signed, e := a.Login(ctx, handle, password)
		if e != nil {
			return e
		}
		if !validTokens(signed.Tokens, !signed.MustChangePassword) || strings.HasPrefix(signed.Tokens.Access, "pss_") {
			return ErrAuthentication
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if !signed.MustChangePassword && signed.Tokens.Refresh != "" {
			if e = writeAuthFile(chosen.file(), savedCredential{1, c.origin, handle, signed.Tokens, false}); e != nil {
				return e
			}
			if e = chosen.mark(); e != nil {
				return e
			}
		}
		result = signed
		return nil
	})
	return
}
func (c *Credentials) rotate(ctx context.Context, a Authentication, s savedCredential) (Tokens, error) {
	intent := s
	intent.Renewing = true
	if e := writeAuthFile(c.file(), intent); e != nil {
		return Tokens{}, e
	}
	pair, e := a.Refresh(ctx, s.Tokens)
	if ctx.Err() != nil {
		return Tokens{}, ctx.Err()
	}
	if e != nil || !validTokens(pair, true) || strings.HasPrefix(pair.Access, "pss_") {
		return Tokens{}, ErrLoginRequired
	}
	s.Tokens = pair
	if e = writeAuthFile(c.file(), s); e != nil {
		return Tokens{}, errPersistenceFenced
	}
	return pair, nil
}

// Renew explicitly rotates once. When rejectedAccess is supplied it reuses a
// newer saved pair. All failed/ambiguous refreshes remain fenced until login.
func (c *Credentials) Renew(ctx context.Context, auth Authentication, rejectedAccess string) (result Tokens, err error) {
	if rejectedAccess != "" && !validSecret(rejectedAccess) {
		return result, ErrCredential
	}
	a, e := c.authentication(auth)
	if e != nil {
		return result, e
	}
	chosen, e := c.chosen()
	if e != nil {
		return result, e
	}
	err = chosen.locked(ctx, func() error {
		if e := chosen.migrate(ctx); e != nil {
			return e
		}
		s, e := chosen.load()
		if e != nil {
			return e
		}
		if rejectedAccess != "" && s.Tokens.Access != rejectedAccess {
			result = s.Tokens
			return nil
		}
		result, e = chosen.rotate(ctx, a, s)
		return e
	})
	return
}

// Authenticated checks the public status endpoint. Only an explicit negative
// status (HTTP 401 in the native implementation) permits renewal, once.
func (c *Credentials) Authenticated(ctx context.Context, auth Authentication) (UserSession, error) {
	a, e := c.authentication(auth)
	if e != nil {
		return UserSession{}, e
	}
	chosen, e := c.chosen()
	if e != nil {
		return UserSession{}, e
	}
	s, e := chosen.Session(ctx)
	if e != nil {
		return s, e
	}
	status, e := a.Standing(ctx, s.Tokens)
	if e != nil {
		return UserSession{}, e
	}
	if status.SignedIn {
		if status.MustChangePassword {
			return UserSession{}, ErrLoginRequired
		}
		return s, nil
	}
	pair, e := chosen.Renew(ctx, a, s.Tokens.Access)
	if e != nil {
		return UserSession{}, e
	}
	status, e = a.Standing(ctx, pair)
	if e != nil {
		return UserSession{}, e
	}
	if !status.SignedIn || status.MustChangePassword {
		return UserSession{}, ErrLoginRequired
	}
	return UserSession{s.Origin, s.Handle, pair}, nil
}

// Logout rotates and persists before revocation. A failed revoke retains the
// current pair; a successful revoke writes the migration fence before deletion.
func (c *Credentials) Logout(ctx context.Context, auth Authentication) error {
	a, e := c.authentication(auth)
	if e != nil {
		return e
	}
	chosen, e := c.chosen()
	if e != nil {
		return e
	}
	return chosen.locked(ctx, func() error {
		if e := chosen.migrate(ctx); e != nil {
			return e
		}
		s, e := chosen.load()
		if e != nil {
			return e
		}
		pair, e := chosen.rotate(ctx, a, s)
		if e != nil {
			return e
		}
		if e = a.Logout(ctx, pair); e != nil {
			return e
		}
		if e = chosen.mark(); e != nil {
			return e
		}
		if os.Remove(chosen.file()) != nil {
			return ErrCredential
		}
		return syncAuthDirectory(filepath.Dir(chosen.file()))
	})
}
