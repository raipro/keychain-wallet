package com.keychain.wallet.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Canonical request fingerprint for idempotency conflict detection: same key with a
 * different hash means the caller reused an idempotency key for a different request,
 * which must fail loudly (409) rather than silently replay the original result.
 */
final class RequestHasher {

    private RequestHasher() {
    }

    static String hash(Object... parts) {
        // Length-prefix each part ("<len>:<value>|") so the encoding is unambiguous:
        // no choice of delimiter inside a value (e.g. an orderId containing '|') can
        // make two distinct inputs produce the same canonical string.
        StringBuilder canonical = new StringBuilder();
        for (Object part : parts) {
            String value = String.valueOf(part);
            canonical.append(value.length()).append(':').append(value).append('|');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
