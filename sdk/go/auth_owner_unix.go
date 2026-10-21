//go:build !windows

package plowshare

import (
	"os"
	"syscall"
)

func ownedAuthFile(info os.FileInfo) bool {
	stat, ok := info.Sys().(*syscall.Stat_t)
	return ok && stat.Uid == uint32(os.Getuid())
}

func openAuthFile(path string) (*os.File, error) {
	descriptor, err := syscall.Open(path, syscall.O_RDONLY|syscall.O_NOFOLLOW|syscall.O_NONBLOCK, 0)
	if err != nil {
		return nil, err
	}
	return os.NewFile(uintptr(descriptor), path), nil
}
