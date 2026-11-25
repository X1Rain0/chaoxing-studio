//go:build !windows

package store

import "encoding/base64"

// 非 Windows 平台的占位实现：仅做编码，不提供机器绑定加密。
func EncryptSecret(plain string) (string, error) {
	if plain == "" {
		return "", nil
	}
	return base64.StdEncoding.EncodeToString([]byte(plain)), nil
}

func DecryptSecret(encoded string) (string, error) {
	if encoded == "" {
		return "", nil
	}
	raw, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return "", err
	}
	return string(raw), nil
}
