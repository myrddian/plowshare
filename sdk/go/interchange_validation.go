package plowshare

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"regexp"
	"strings"
	"unicode/utf16"
)

// These private DTOs exist only at the codec boundary; they confer no foreign authority.
type portableEvidence struct {
	Revision string
	Start    int
	End      int
	Quote    string
}
type portableProvenance struct {
	SourceHash   string
	Dependencies []string
	Evidence     []portableEvidence
}
type portableDescriptor struct {
	Resource    string
	Revision    string
	Ordinal     int
	Path        string
	ContentHash string
	Provenance  portableProvenance
}
type portableDocument struct {
	Descriptor  portableDescriptor
	BytesBase64 string
}
type portableLink struct {
	Source         string
	Target         string
	PinnedRevision *string
	Relation       string
}
type portableBundle struct {
	Documents     []portableDocument
	Relationships []portableLink
}
type portableDestination struct {
	Resource string
	Name     string
}
type portableTypeMapping struct{ ForeignVersion string }
type portableReuse struct {
	ForeignRevision string
	LocalRevision   string
}
type portablePlan struct {
	Reuse                []portableReuse
	RestoreRelationships bool
	Bundle               portableBundle
	Destinations         []portableDestination
	Types                []portableTypeMapping
}

var portablePathPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._/-]*$`)
var portableReserved = regexp.MustCompile(`(?i)^(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\..*)?$`)

func portablePath(value string) bool {
	if len(value) == 0 || len(value) > 240 || !portablePathPattern.MatchString(value) {
		return false
	}
	for _, part := range strings.Split(value, "/") {
		if part == "" || part == "." || part == ".." || strings.EqualFold(part, ".git") || strings.EqualFold(part, ".hg") || strings.EqualFold(part, ".svn") || strings.HasSuffix(part, ".") || portableReserved.MatchString(part) {
			return false
		}
	}
	return true
}
func portableDescriptorCheck(d portableDescriptor) error {
	if !portablePath(d.Path) {
		return contractError
	}
	for _, e := range d.Provenance.Evidence {
		if e.Start < 0 || e.End <= e.Start || len(utf16.Encode([]rune(e.Quote))) != e.End-e.Start {
			return contractError
		}
	}
	return nil
}
func portableBundleCheck(b portableBundle) error {
	if len(b.Documents) < 1 || len(b.Documents) > 16 || len(b.Relationships) > 64 {
		return contractError
	}
	revisions := map[string]portableDescriptor{}
	resources := map[string]bool{}
	paths := map[string]bool{}
	ordinals := map[string]bool{}
	total := 0
	for _, doc := range b.Documents {
		d := doc.Descriptor
		if err := portableDescriptorCheck(d); err != nil {
			return err
		}
		filename := strings.ToLower(d.Path)
		ordinal := fmt.Sprintf("%s/%d", d.Resource, d.Ordinal)
		if _, ok := revisions[d.Revision]; ok || paths[filename] || ordinals[ordinal] {
			return contractError
		}
		if len(doc.BytesBase64) > 2796204 {
			return contractError
		}
		original, err := base64.StdEncoding.Strict().DecodeString(doc.BytesBase64)
		if err != nil || len(original) == 0 || base64.StdEncoding.EncodeToString(original) != doc.BytesBase64 {
			return contractError
		}
		sum := sha256.Sum256(original)
		if hex.EncodeToString(sum[:]) != d.ContentHash {
			return contractError
		}
		total += len(original)
		revisions[d.Revision] = d
		resources[d.Resource] = true
		paths[filename] = true
		ordinals[ordinal] = true
	}
	if total > 2097152 {
		return contractError
	}
	for filename := range paths {
		for other := range paths {
			if strings.HasPrefix(other, filename+"/") {
				return contractError
			}
		}
	}
	for _, d := range revisions {
		seen := map[string]bool{}
		for _, input := range d.Provenance.Dependencies {
			if _, ok := revisions[input]; !ok || seen[input] || input == d.Revision {
				return contractError
			}
			seen[input] = true
		}
		for _, e := range d.Provenance.Evidence {
			if _, ok := revisions[e.Revision]; !ok {
				return contractError
			}
		}
	}
	edges := map[string]bool{}
	for _, e := range b.Relationships {
		pin := ""
		if e.PinnedRevision != nil {
			pin = *e.PinnedRevision
			d, ok := revisions[pin]
			if !ok || d.Resource != e.Target {
				return contractError
			}
		}
		key := e.Source + "/" + e.Target + "/" + pin + "/" + e.Relation
		if !resources[e.Source] || !resources[e.Target] || edges[key] {
			return contractError
		}
		edges[key] = true
	}
	return nil
}
func checkPortableGraph(kind string, value any) error {
	encoded, err := json.Marshal(value)
	if err != nil {
		return err
	}
	switch kind {
	case "descriptor":
		var d portableDescriptor
		if err = json.Unmarshal(encoded, &d); err != nil {
			return err
		}
		return portableDescriptorCheck(d)
	case "bundle":
		var b portableBundle
		if err = json.Unmarshal(encoded, &b); err != nil {
			return err
		}
		return portableBundleCheck(b)
	case "plan":
		var p portablePlan
		if err = json.Unmarshal(encoded, &p); err != nil {
			return err
		}
		if err = portableBundleCheck(p.Bundle); err != nil {
			return err
		}
		all := map[string]bool{}
		for _, d := range p.Bundle.Documents {
			all[d.Descriptor.Revision] = true
		}
		reused := map[string]bool{}
		local := map[string]bool{}
		for _, r := range p.Reuse {
			if !all[r.ForeignRevision] || reused[r.ForeignRevision] || local[r.LocalRevision] {
				return contractError
			}
			reused[r.ForeignRevision] = true
			local[r.LocalRevision] = true
		}
		resources := map[string]bool{}
		for _, d := range p.Bundle.Documents {
			if !reused[d.Descriptor.Revision] {
				resources[d.Descriptor.Resource] = true
			}
		}
		if len(resources) == 0 {
			return contractError
		}
		if p.RestoreRelationships {
			for _, e := range p.Bundle.Relationships {
				if !resources[e.Source] {
					return contractError
				}
			}
		}
		if p.RestoreRelationships {
			for _, e := range p.Bundle.Relationships {
				if e.PinnedRevision != nil {
					for _, doc := range p.Bundle.Documents {
						if doc.Descriptor.Revision == *e.PinnedRevision && doc.Descriptor.ContentHash != doc.Descriptor.Provenance.SourceHash {
							return contractError
						}
					}
				}
			}
		}
		selected := map[string]bool{}
		names := map[string]bool{}
		for _, d := range p.Destinations {
			if !resources[d.Resource] || selected[d.Resource] || names[d.Name] {
				return contractError
			}
			selected[d.Resource] = true
			names[d.Name] = true
		}
		if len(selected) != len(resources) {
			return contractError
		}
		versions := map[string]bool{}
		for _, t := range p.Types {
			if versions[t.ForeignVersion] {
				return contractError
			}
			versions[t.ForeignVersion] = true
		}
		return nil
	default:
		return contractError
	}
}
