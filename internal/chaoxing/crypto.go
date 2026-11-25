package chaoxing

import (
	"crypto/des"
	"encoding/hex"
)

// desKey 是学习通网页端硬编码的 DES 密钥，长度恰好 8 字节。
const desKey = "u2oh6Vu^"

// desEncrypt 复现前端 DES/ECB/PKCS5 加密，输出小写十六进制。
func desEncrypt(plain string) (string, error) {
	block, err := des.NewCipher([]byte(desKey))
	if err != nil {
		return "", err
	}
	bs := block.BlockSize()
	src := []byte(plain)
	pad := bs - len(src)%bs
	for i := 0; i < pad; i++ {
		src = append(src, byte(pad))
	}
	out := make([]byte, len(src))
	for i := 0; i < len(src); i += bs {
		block.Encrypt(out[i:i+bs], src[i:i+bs])
	}
	return hex.EncodeToString(out), nil
}
