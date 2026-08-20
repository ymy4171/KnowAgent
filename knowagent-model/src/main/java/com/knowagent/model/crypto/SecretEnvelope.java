package com.knowagent.model.crypto;

import java.util.Base64;

/**
 * Serializes an encrypted secret to a self-describing, parseable envelope and back.
 *
 * <p>Format: {@code aesgcm.v<keyVersion>.<base64url(nonce)>.<base64url(ciphertext)>}.
 * The {@code aesgcm} segment pins the algorithm so a future algorithm change is
 * detectable at decrypt time, the {@code v<keyVersion>} segment is the key version,
 * and the nonce/ciphertext use unpadded base64url so the value is safe to store in a
 * {@code text} column.
 */
final class SecretEnvelope {

    private static final String ALGORITHM = "aesgcm";
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BYTES = 16;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private SecretEnvelope() {
    }

    static String compose(int keyVersion, byte[] nonce, byte[] ciphertext) {
        return ALGORITHM + ".v" + keyVersion + "." + ENCODER.encodeToString(nonce) + "."
                + ENCODER.encodeToString(ciphertext);
    }

    static Parsed parse(String envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("secret envelope is null");
        }
        String[] parts = envelope.split("\\.", -1);
        if (parts.length != 4 || !ALGORITHM.equals(parts[0])) {
            throw new IllegalArgumentException("secret envelope is not an aesgcm value");
        }
        if (!parts[1].startsWith("v")) {
            throw new IllegalArgumentException("secret envelope key version segment is malformed");
        }
        int version;
        try {
            version = Integer.parseInt(parts[1].substring(1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("secret envelope key version is not a number", exception);
        }
        if (version <= 0) {
            throw new IllegalArgumentException("secret envelope key version must be > 0");
        }
        byte[] nonce;
        byte[] ciphertext;
        try {
            nonce = DECODER.decode(parts[2]);
            ciphertext = DECODER.decode(parts[3]);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("secret envelope is not valid base64url", exception);
        }
        if (nonce.length != NONCE_BYTES) {
            throw new IllegalArgumentException("secret envelope nonce must contain 12 bytes");
        }
        if (ciphertext.length < GCM_TAG_BYTES) {
            throw new IllegalArgumentException("secret envelope ciphertext is shorter than the GCM tag");
        }
        return new Parsed(version, nonce, ciphertext);
    }

    record Parsed(int version, byte[] nonce, byte[] ciphertext) {
    }
}
