package com.empverify.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeviceTokenServiceTest {

    private static final String SECRET = "unit-test-device-token-secret";

    @Test
    void issuedTokenValidatesBackToItsDeviceId() {
        var service = new DeviceTokenService(SECRET, 3600);
        var deviceId = UUID.randomUUID();

        var token = service.issue(deviceId);
        var resolved = service.validate(token);

        assertTrue(resolved.isPresent());
        assertEquals(deviceId, resolved.get());
    }

    @Test
    void tamperedSignatureIsRejected() {
        var service = new DeviceTokenService(SECRET, 3600);
        var token = service.issue(UUID.randomUUID());

        // Flip the last character of the signature segment.
        var tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");

        assertFalse(service.validate(tampered).isPresent());
    }

    @Test
    void tokenSignedWithADifferentSecretIsRejected() {
        var issuer = new DeviceTokenService(SECRET, 3600);
        var attacker = new DeviceTokenService("some-other-secret", 3600);

        var token = issuer.issue(UUID.randomUUID());

        assertFalse(attacker.validate(token).isPresent());
    }

    @Test
    void expiredTokenIsRejected() {
        // Negative TTL => the issued token is already expired.
        var service = new DeviceTokenService(SECRET, -10);
        var token = service.issue(UUID.randomUUID());

        assertFalse(service.validate(token).isPresent());
    }

    @Test
    void malformedTokensAreRejectedNotCrashing() {
        var service = new DeviceTokenService(SECRET, 3600);

        assertFalse(service.validate("not-a-token").isPresent());
        assertFalse(service.validate("a.b").isPresent());
        assertFalse(service.validate("").isPresent());
        assertFalse(service.validate("a.b.c.d").isPresent());
    }
}
