package com.empverify.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit tests for the tamper-evident audit hash computation. */
class AuditHashTest {

    private static final Instant T = Instant.parse("2026-10-26T12:00:00Z");

    @Test
    void sameInputsProduceSameHash() {
        String a = AuditService.computeEntryHash(null, "admin", "LOGIN", "user", "u1", "{}", T);
        String b = AuditService.computeEntryHash(null, "admin", "LOGIN", "user", "u1", "{}", T);
        assertEquals(a, b);
    }

    @Test
    void changingAnyFieldChangesTheHash() {
        String base = AuditService.computeEntryHash(null, "admin", "LOGIN", "user", "u1", "{}", T);
        assertNotEquals(base, AuditService.computeEntryHash(null, "attacker", "LOGIN", "user", "u1", "{}", T));
        assertNotEquals(base, AuditService.computeEntryHash(null, "admin", "LOGOUT", "user", "u1", "{}", T));
        assertNotEquals(base, AuditService.computeEntryHash(null, "admin", "LOGIN", "device", "u1", "{}", T));
        assertNotEquals(base, AuditService.computeEntryHash(null, "admin", "LOGIN", "user", "u2", "{}", T));
        assertNotEquals(base, AuditService.computeEntryHash(null, "admin", "LOGIN", "user", "u1", "{\"x\":1}", T));
        assertNotEquals(base, AuditService.computeEntryHash(null, "admin", "LOGIN", "user", "u1", "{}", T.plusSeconds(1)));
    }

    @Test
    void chainLinksThroughPreviousHash() {
        String first = AuditService.computeEntryHash(null, "admin", "A", "e", "1", "{}", T);
        String second = AuditService.computeEntryHash(first, "admin", "B", "e", "2", "{}", T);
        // A different predecessor yields a different hash for the same entry -> tamper detectable.
        String secondIfFirstAltered =
                AuditService.computeEntryHash(first + "x", "admin", "B", "e", "2", "{}", T);
        assertNotEquals(second, secondIfFirstAltered);
    }

    @Test
    void hashIsSha256HexLength() {
        String h = AuditService.computeEntryHash(null, "a", "b", "c", "d", "{}", T);
        assertEquals(64, h.length());
    }
}
