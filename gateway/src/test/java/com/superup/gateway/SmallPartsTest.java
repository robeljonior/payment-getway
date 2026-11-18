package com.superup.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.superup.gateway.common.Money;
import com.superup.gateway.gateway.TokenBucket;
import org.junit.jupiter.api.Test;

class SmallPartsTest {

    @Test
    void moneyIsWholeSantim() {
        assertThat(Money.parseSantim("1500.00")).isEqualTo(150_000L);
        assertThat(Money.parseSantim("0.05")).isEqualTo(5L);
        assertThat(Money.parseSantim("15.5")).isNull();
        assertThat(Money.parseSantim("-1.00")).isNull();
        assertThat(Money.parseSantim("1e3")).isNull();
        assertThat(Money.format(98_500)).isEqualTo("985.00");
        assertThat(Money.format(7)).isEqualTo("0.07");
    }

    @Test
    void feesRoundHalfUp() {
        assertThat(Money.fee(100_000, 150)).isEqualTo(1_500);   // 1,000.00 ETB -> 15.00
        assertThat(Money.fee(100, 150)).isEqualTo(2);          // 1.5 santim -> 2
        assertThat(Money.fee(99, 150)).isEqualTo(1);           // 1.485 -> 1
    }

    @Test
    void theRateLimitBucketEmptiesAndRefills() {
        long start = 0;
        TokenBucket bucket = new TokenBucket(3, 1, start);
        assertThat(bucket.tryTake(start)).isTrue();
        assertThat(bucket.tryTake(start)).isTrue();
        assertThat(bucket.tryTake(start)).isTrue();
        assertThat(bucket.tryTake(start)).isFalse();
        assertThat(bucket.tryTake(start + 1_000_000_000L)).isTrue();   // one second later, one more
    }
}
