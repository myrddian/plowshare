package plowshare

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Authentication is an optional HTTP bootstrap capability. It never reconnects
// application sockets or replays application operations. Implementations must
// bind Origin to the server to which credentials will be sent.
type Authentication interface {
	Origin() string
	Login(context.Context, string, string) (SignIn, error)
	Standing(context.Context, Tokens) (Standing, error)
	Refresh(context.Context, Tokens) (Tokens, error)
	Logout(context.Context, Tokens) error
}

// Tokens contains private bearer material. Its diagnostic forms are redacted.
// Refresh is empty only for a restricted/nonrenewable session.
type Tokens struct {
	Access  string `json:"access"`
	Refresh string `json:"refresh,omitempty"`
}

func (Tokens) String() string   { return "Tokens{redacted}" }
func (Tokens) GoString() string { return "Tokens{redacted}" }

// SignIn retains restricted/setup state independently of token renewability.
type SignIn struct {
	Tokens             Tokens
	MustChangePassword bool
	SetupRequired      bool
}

// Standing is the public server status, not a local credential-file inference.
type Standing struct {
	SignedIn           bool
	MustChangePassword bool
}

var ErrAuthentication = errors.New("authentication refused, unavailable or invalid")
var ErrLoginRequired = errors.New("sign in explicitly; saved session unavailable or renewal interrupted")
var ErrCredential = errors.New("invalid or unavailable private authentication state")

var accountPattern = regexp.MustCompile(`^[A-Za-z0-9_.-]{1,64}$`)
var hostPattern = regexp.MustCompile(`^[a-zA-Z0-9._-]+$`)

func validSecret(s string) bool {
	if len(s) < 1 || len(s) > 4096 {
		return false
	}
	for _, c := range s {
		if c < 33 || c > 126 || strings.ContainsRune(";,\"\\", c) {
			return false
		}
	}
	return true
}
func validTokens(t Tokens, renewable bool) bool {
	return validSecret(t.Access) && (t.Refresh == "" && !renewable || validSecret(t.Refresh))
}

// CanonicalOrigin produces Node-compatible origin keys. Ambiguous IPv4 and
// Unicode DNS spellings are refused; supply explicit ASCII/IDNA names instead.
func CanonicalOrigin(value string) (string, error) {
	if len(value) > 2048 || strings.ContainsAny(value, "\\?#") {
		return "", ErrCredential
	}
	for _, c := range value {
		if c <= 32 || c >= 127 {
			return "", ErrCredential
		}
	}
	u, e := url.Parse(value)
	if e != nil || (u.Scheme != "http" && u.Scheme != "https") || u.User != nil || u.Hostname() == "" || (u.Path != "" && u.Path != "/") || u.RawQuery != "" || u.Fragment != "" {
		return "", ErrCredential
	}
	host := strings.ToLower(u.Hostname())
	if strings.Contains(host, ":") {
		ip := net.ParseIP(host)
		if ip == nil {
			return "", ErrCredential
		}
		address := netip.MustParseAddr(host)
		canonical := address.String()
		if address.Is4In6() {
			bytes := address.As16()
			canonical = fmt.Sprintf("::ffff:%x:%x", uint16(bytes[12])<<8|uint16(bytes[13]), uint16(bytes[14])<<8|uint16(bytes[15]))
		}
		host = "[" + canonical + "]"
	} else {
		if !hostPattern.MatchString(host) {
			return "", ErrCredential
		}
		last := strings.Split(strings.TrimSuffix(host, "."), ".")
		part := last[len(last)-1]
		if regexp.MustCompile(`^(?:[0-9]+|0[xX][0-9a-fA-F]+)$`).MatchString(part) {
			ip := net.ParseIP(host)
			if ip == nil || ip.To4() == nil || ip.String() != host {
				return "", ErrCredential
			}
		}
	}
	port := u.Port()
	if strings.HasSuffix(u.Host, ":") {
		return "", ErrCredential
	}
	if port != "" {
		n, e := strconv.Atoi(port)
		if e != nil || n < 1 || n > 65535 {
			return "", ErrCredential
		}
		port = strconv.Itoa(n)
		if (u.Scheme == "http" && port == "80") || (u.Scheme == "https" && port == "443") {
			port = ""
		}
	}
	if port != "" {
		host += ":" + port
	}
	return u.Scheme + "://" + host, nil
}

// HttpAuthentication owns a redirect-free, non-retrying native HTTP boundary.
// Responses are bounded. Refresh failure always stays fenced by Credentials;
// no injected error can claim that single-use credentials were not submitted.
type HttpAuthentication struct {
	origin                      string
	client                      *http.Client
	accessCookie, refreshCookie string
}

func NewHttpAuthentication(origin string, timeout time.Duration, accessCookie, refreshCookie string) (*HttpAuthentication, error) {
	base, e := CanonicalOrigin(origin)
	if e != nil {
		return nil, e
	}
	if timeout <= 0 {
		return nil, ErrAuthentication
	}
	if accessCookie == "" {
		accessCookie = "ps_access"
	}
	if refreshCookie == "" {
		refreshCookie = "ps_refresh"
	}
	cookieName := regexp.MustCompile(`^[A-Za-z0-9_]{1,64}$`)
	if !cookieName.MatchString(accessCookie) || !cookieName.MatchString(refreshCookie) || accessCookie == refreshCookie {
		return nil, ErrAuthentication
	}
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.DisableKeepAlives = true
	return &HttpAuthentication{base, &http.Client{Timeout: timeout, Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}, accessCookie, refreshCookie}, nil
}
func (a *HttpAuthentication) Origin() string { return a.origin }
func (a *HttpAuthentication) request(ctx context.Context, method, path string, headers map[string]string, body []byte) (*http.Response, []byte, error) {
	req, e := http.NewRequestWithContext(ctx, method, a.origin+path, bytes.NewReader(body))
	if e != nil {
		return nil, nil, ErrAuthentication
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	res, e := a.client.Do(req)
	if e != nil {
		if ctx.Err() != nil {
			return nil, nil, ctx.Err()
		}
		return nil, nil, ErrAuthentication
	}
	defer res.Body.Close()
	data, e := io.ReadAll(io.LimitReader(res.Body, 65537))
	if e != nil || len(data) > 65536 {
		return nil, nil, ErrAuthentication
	}
	return res, data, nil
}
func (a *HttpAuthentication) Login(ctx context.Context, handle, password string) (SignIn, error) {
	if !accountPattern.MatchString(handle) || len(password) == 0 || len(password) > 4096 {
		return SignIn{}, ErrAuthentication
	}
	body, _ := json.Marshal(struct {
		Handle   string `json:"handle"`
		Password string `json:"password"`
	}{handle, password})
	res, data, e := a.request(ctx, "POST", "/v1/auth/login", map[string]string{"Content-Type": "application/json", "X-Plowshare-Token-Delivery": "body"}, body)
	if e != nil {
		return SignIn{}, e
	}
	if res.StatusCode != 200 {
		return SignIn{}, ErrAuthentication
	}
	var raw struct {
		Access     string `json:"access"`
		Refresh    string `json:"refresh"`
		MustChange *bool  `json:"mustChangePassword"`
	}
	if !uniqueAuthJSON(data) || json.Unmarshal(data, &raw) != nil || raw.MustChange == nil {
		return SignIn{}, ErrAuthentication
	}
	t := Tokens{raw.Access, raw.Refresh}
	if !validTokens(t, !*raw.MustChange) {
		return SignIn{}, ErrAuthentication
	}
	return SignIn{t, *raw.MustChange, res.Header.Get("X-Plowshare-Setup-Required") == "true"}, nil
}
func (a *HttpAuthentication) Standing(ctx context.Context, t Tokens) (Standing, error) {
	if !validTokens(t, false) {
		return Standing{}, ErrAuthentication
	}
	res, _, e := a.request(ctx, "GET", "/v1/auth/session", map[string]string{"Authorization": "Bearer " + t.Access}, nil)
	if e != nil {
		return Standing{}, e
	}
	if res.StatusCode == 401 {
		return Standing{}, nil
	}
	if res.StatusCode != 204 {
		return Standing{}, ErrAuthentication
	}
	return Standing{true, res.Header.Get("X-Plowshare-Must-Change-Password") == "true"}, nil
}
func (a *HttpAuthentication) Refresh(ctx context.Context, t Tokens) (Tokens, error) {
	if !validTokens(t, true) {
		return Tokens{}, ErrAuthentication
	}
	res, _, e := a.request(ctx, "POST", "/v1/auth/refresh", map[string]string{"Cookie": a.refreshCookie + "=" + t.Refresh}, nil)
	if e != nil {
		return Tokens{}, e
	}
	if res.StatusCode != 204 {
		return Tokens{}, ErrAuthentication
	}
	jar := map[string]string{}
	for _, header := range res.Header.Values("Set-Cookie") {
		parts := strings.Split(header, ";")
		first := strings.SplitN(parts[0], "=", 2)
		if len(first) != 2 {
			continue
		}
		name := strings.TrimSpace(first[0])
		if name != a.accessCookie && name != a.refreshCookie {
			continue
		}
		for _, attr := range parts[1:] {
			key := strings.TrimSpace(strings.SplitN(attr, "=", 2)[0])
			if key == a.accessCookie || key == a.refreshCookie {
				return Tokens{}, ErrAuthentication
			}
		}
		if _, exists := jar[name]; exists || !validSecret(first[1]) {
			return Tokens{}, ErrAuthentication
		}
		jar[name] = first[1]
	}
	pair := Tokens{jar[a.accessCookie], jar[a.refreshCookie]}
	if !validTokens(pair, true) {
		return Tokens{}, ErrAuthentication
	}
	return pair, nil
}
func (a *HttpAuthentication) Logout(ctx context.Context, t Tokens) error {
	if !validTokens(t, false) {
		return ErrAuthentication
	}
	res, _, e := a.request(ctx, "POST", "/v1/auth/logout", map[string]string{"Authorization": "Bearer " + t.Access}, nil)
	if e != nil {
		return e
	}
	if res.StatusCode != 204 {
		return ErrAuthentication
	}
	return nil
}
func (t SignIn) String() string {
	return fmt.Sprintf("SignIn{restricted:%t setup:%t}", t.MustChangePassword, t.SetupRequired)
}
