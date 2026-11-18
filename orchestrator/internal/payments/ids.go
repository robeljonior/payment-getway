package payments

import (
	"crypto/rand"
	"math/big"
)

// IDs have a type prefix, for example ps_8fK2mPq7... (docs/08-data-model.md).
const (
	prefixSession = "ps_"
	prefixAttempt = "att_"
	prefixSuccess = "suc_"
	prefixFailure = "fail_"
	prefixJournal = "jr_"
	prefixEvent   = "evt_"
)

const alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

func newID(prefix string) string { return prefix + RandomText(20) }

// RandomText returns n random letters and digits from a secure source.
func RandomText(n int) string {
	out := make([]byte, n)
	max := big.NewInt(int64(len(alphabet)))
	for i := range out {
		v, err := rand.Int(rand.Reader, max)
		if err != nil {
			panic(err) // the system has no secure random source; nothing safe can be done
		}
		out[i] = alphabet[v.Int64()]
	}
	return string(out)
}
