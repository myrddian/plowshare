package plowshare

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

type authConnection struct {
	Key       string `json:"key"`
	Name      string `json:"name"`
	Server    string `json:"server"`
	Account   string `json:"account"`
	Reconnect *bool  `json:"reconnect"`
}
type authConfiguration struct {
	Version          int               `json:"version"`
	Connections      *[]authConnection `json:"connections"`
	Selected         json.RawMessage   `json:"selected,omitempty"`
	FileStoreDefault *struct {
		Alias string `json:"alias"`
		Root  string `json:"root"`
	} `json:"fileStoreDefault,omitempty"`
	ConnectionSetupPending *bool `json:"connectionSetupPending,omitempty"`
}

func validConnectionName(s string) bool {
	if s == "" || len([]rune(s)) > 100 || strings.TrimSpace(s) != s {
		return false
	}
	for _, c := range s {
		if c < 32 || c == 127 {
			return false
		}
	}
	return true
}
func readAuthConfiguration(root string) ([]authConnection, string, error) {
	if e := privateAuthDirectory(root); e != nil {
		return nil, "", e
	}
	data, e := readAuthFile(filepath.Join(root, "config.json"))
	if e != nil || data == nil {
		return nil, "", e
	}
	var conf authConfiguration
	if decodeAuthFile(data, &conf) != nil || conf.Version != 1 || conf.Connections == nil || len(*conf.Connections) > 100 {
		return nil, "", ErrCredential
	}
	names, keys := map[string]bool{}, map[string]bool{}
	for _, c := range *conf.Connections {
		key, e := ConnectionKey(c.Server, c.Account)
		base, _ := CanonicalOrigin(c.Server)
		if e != nil || key != c.Key || base != c.Server || !validConnectionName(c.Name) || c.Reconnect == nil || names[c.Name] || keys[c.Key] {
			return nil, "", ErrCredential
		}
		names[c.Name] = true
		keys[c.Key] = true
	}
	selected := ""
	if len(conf.Selected) > 0 {
		if json.Unmarshal(conf.Selected, &selected) != nil || !keys[selected] {
			return nil, "", ErrCredential
		}
	}
	var fields map[string]json.RawMessage
	_ = json.Unmarshal(data, &fields)
	if raw, ok := fields["fileStoreDefault"]; ok {
		f := conf.FileStoreDefault
		if string(raw) == "null" || f == nil || !regexp.MustCompile(`^[a-z][a-z0-9_-]{0,63}$`).MatchString(f.Alias) || !filepath.IsAbs(f.Root) || len(f.Root) > 4096 {
			return nil, "", ErrCredential
		}
		for _, c := range f.Root {
			if c < 32 || c == 127 {
				return nil, "", ErrCredential
			}
		}
	}
	if raw, ok := fields["connectionSetupPending"]; ok {
		if string(raw) != "true" || conf.ConnectionSetupPending == nil || !*conf.ConnectionSetupPending || len(*conf.Connections) > 0 || selected != "" || conf.FileStoreDefault == nil {
			return nil, "", ErrCredential
		}
	}
	return *conf.Connections, selected, nil
}

// ResolveCredentials reads the existing native connection registry. Explicit
// selectors override their environment counterpart; named selectors must agree
// with explicit server/account values. An explicit server bypasses selection.
// It never inspects a service token or substitutes a saved user for one.
func ResolveCredentials(origin, account, connection string, options CredentialOptions) (*Credentials, error) {
	root := options.Directory
	var e error
	if root == "" {
		root, e = ConfigurationDirectory()
		if e != nil {
			return nil, e
		}
	}
	if !filepath.IsAbs(root) {
		return nil, ErrCredential
	}
	entries, selected, e := readAuthConfiguration(root)
	if e != nil {
		return nil, e
	}
	if origin == "" {
		var present bool
		origin, present = os.LookupEnv("PLOWSHARE_URL")
		if present && origin == "" {
			return nil, ErrCredential
		}
	}
	if account == "" {
		account = options.Account
	}
	if account == "" {
		v, ok := os.LookupEnv("PLOWSHARE_ACCOUNT")
		if !ok {
			v, ok = os.LookupEnv("PLOWSHARE_HANDLE")
		}
		if ok && v == "" {
			return nil, ErrCredential
		}
		account = v
	}
	if connection == "" {
		var present bool
		connection, present = os.LookupEnv("PLOWSHARE_CONNECTION")
		if present && connection == "" {
			return nil, ErrCredential
		}
	}
	var chosen *authConnection
	for i := range entries {
		c := &entries[i]
		if connection != "" && c.Name == connection || connection == "" && origin == "" && account == "" && c.Key == selected {
			chosen = c
			break
		}
	}
	if connection != "" && (!validConnectionName(connection) || chosen == nil) {
		return nil, ErrCredential
	}
	if chosen != nil {
		if origin != "" {
			base, e := CanonicalOrigin(origin)
			if e != nil || base != chosen.Server {
				return nil, ErrCredential
			}
		}
		if account != "" && account != chosen.Account {
			return nil, ErrCredential
		}
		origin, account = chosen.Server, chosen.Account
	}
	options.Account = account
	return NewCredentials(origin, options)
}
