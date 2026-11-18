package payments

import "fmt"

// Money is always whole santim (int64). On the API it is a string with two decimals, "1500.00".

// MaxAmount is the largest amount the API accepts: 99,999,999,999.99 ETB (13 digits before the point in santim terms).
const MaxAmount int64 = 9_999_999_999_999

// FormatSantim turns 150000 into "1500.00".
func FormatSantim(santim int64) string {
	sign := ""
	if santim < 0 {
		sign, santim = "-", -santim
	}
	return fmt.Sprintf("%s%d.%02d", sign, santim/100, santim%100)
}

// Fee returns the fee in santim for a rate in basis points (150 = 1.5%), rounded half up.
func Fee(amount, basisPoints int64) int64 {
	return (amount*basisPoints + 5_000) / 10_000
}
