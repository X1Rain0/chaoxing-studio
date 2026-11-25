//go:build windows

package store

import (
	"encoding/base64"
	"syscall"
	"unsafe"
)

type dataBlob struct {
	cbData uint32
	pbData *byte
}

var (
	modCrypt32             = syscall.NewLazyDLL("crypt32.dll")
	procCryptProtectData   = modCrypt32.NewProc("CryptProtectData")
	procCryptUnprotectData = modCrypt32.NewProc("CryptUnprotectData")
	modKernel32            = syscall.NewLazyDLL("kernel32.dll")
	procLocalFree          = modKernel32.NewProc("LocalFree")
)

const cryptProtectUIForbidden = 0x1

func blobFromBytes(b []byte) *dataBlob {
	if len(b) == 0 {
		return &dataBlob{}
	}
	return &dataBlob{cbData: uint32(len(b)), pbData: &b[0]}
}

// EncryptSecret 用 Windows DPAPI 加密，密文只能在本机本用户下解开。
func EncryptSecret(plain string) (string, error) {
	if plain == "" {
		return "", nil
	}
	in := blobFromBytes([]byte(plain))
	var out dataBlob
	ret, _, err := procCryptProtectData.Call(
		uintptr(unsafe.Pointer(in)),
		0,
		0,
		0,
		0,
		uintptr(cryptProtectUIForbidden),
		uintptr(unsafe.Pointer(&out)),
	)
	if ret == 0 {
		return "", err
	}
	defer procLocalFree.Call(uintptr(unsafe.Pointer(out.pbData)))
	buf := unsafe.Slice(out.pbData, int(out.cbData))
	return base64.StdEncoding.EncodeToString(buf), nil
}

func DecryptSecret(encoded string) (string, error) {
	if encoded == "" {
		return "", nil
	}
	raw, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return "", err
	}
	in := blobFromBytes(raw)
	var out dataBlob
	ret, _, callErr := procCryptUnprotectData.Call(
		uintptr(unsafe.Pointer(in)),
		0, 0, 0, 0, 0,
		uintptr(unsafe.Pointer(&out)),
	)
	if ret == 0 {
		return "", callErr
	}
	defer procLocalFree.Call(uintptr(unsafe.Pointer(out.pbData)))
	buf := unsafe.Slice(out.pbData, int(out.cbData))
	return string(buf), nil
}
