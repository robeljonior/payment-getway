package com.superup.gateway.common;

import java.util.regex.Pattern;

/**
 * Money is kept as whole santim (long). On the API it is a string with two decimals, "1500.00".
 * There are no floating-point numbers anywhere.
 */
public final class Money {

    private static final Pattern AMOUNT = Pattern.compile("^[0-9]{1,13}\\.[0-9]{2}$");

    private Money() {
    }

    /** Returns santim, or null when the text is not a valid amount like "1500.00". */
    public static Long parseSantim(String text) {
        if (text == null || !AMOUNT.matcher(text).matches()) {
            return null;
        }
        String digits = text.replace(".", "");
        return Long.parseLong(digits);
    }

    public static String format(long santim) {
        long birr = santim / 100;
        long cents = santim % 100;
        return birr + "." + (cents < 10 ? "0" : "") + cents;
    }

    /** Fee in santim for a rate in basis points (150 = 1.5%), rounded half up. */
    public static long fee(long amountSantim, int basisPoints) {
        return Math.addExact(Math.multiplyExact(amountSantim, basisPoints), 5_000) / 10_000;
    }
}
