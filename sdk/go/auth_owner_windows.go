package plowshare

import "os"

// Windows state uses the caller's private profile ACLs; no POSIX mode claims.
func ownedAuthFile(info os.FileInfo) bool { return true }

func openAuthFile(path string) (*os.File, error) { return os.Open(path) }
