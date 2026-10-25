package com.empverify.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Short-lived HMAC-signed bearer tokens for devices: "deviceId.expiryEpoch.signature".
 * Deliberately minimal for Phase 1 — no external JWT dependency; swapped for
 * Cognito-issued tokens when admin auth moves to Cognito.
 */
@Component
public class DeviceTokenService {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] secret;
    private final long ttlSeconds;

    public DeviceTokenService(
            @Value("${empverify.security.device-token-secret}") String secret,
            @Value("${empverify.security.device-token-ttl-seconds}") long ttlSeconds) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(UUID deviceId) {
        long expiry = Instant.now().plusSeconds(ttlSeconds).getEpochSecond();
        String payload = deviceId + "." + expiry;
        return payload + "." + B64.encodeToString(hmac(payload));
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    /** Returns the deviceId when the token is authentic and unexpired. */
    public Optional<UUID> validate(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        String payload = parts[0] + "." + parts[1];
        byte[] expected = hmac(payload);
        byte[] provided;
        try {
            provided = B64D.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(expected, provided)) {
            return Optional.empty();
        }
        try {
            if (Instant.ofEpochSecond(Long.parseLong(parts[1])).isBefore(Instant.now())) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(parts[0]));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }
}
