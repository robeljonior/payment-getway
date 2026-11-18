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
