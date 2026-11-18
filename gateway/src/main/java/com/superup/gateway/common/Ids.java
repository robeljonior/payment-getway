package com.superup.gateway.common;

import java.security.SecureRandom;

/** IDs with a type prefix, for example ps_8fK2mPq7... (docs/08-data-model.md). */
public final class Ids {

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String newId(String prefix) {
        return prefix + random(20);
    }

    public static String random(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }

    public static final String SESSION = "ps_";
    public static final String ATTEMPT = "att_";
    public static final String SUCCESS = "suc_";
    public static final String FAILURE = "fail_";
    public static final String JOURNAL = "jr_";
    public static final String EVENT = "evt_";
    public static final String REQUEST = "req_";
}
