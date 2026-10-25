package com.empverify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.empverify.domain.Enums.CheckStatus;
import com.empverify.service.VerificationService;
import org.junit.jupiter.api.Test;

/**
 * Threshold mapping: >= match -> MATCH; review..match -> CHECK (borderline);
 * below review -> CHECK with mismatch flag. CHECK is always a review status.
 */
class VerificationThresholdTest {

    private static final double MATCH = 90.0;
    private static final double REVIEW = 75.0;

    @Test
    void atOrAboveMatchThresholdIsMatch() {
        var result = VerificationService.mapSimilarity(90.0, MATCH, REVIEW);
        assertEquals(CheckStatus.MATCH, result.status());
        assertFalse(result.mismatchFlag());

        assertEquals(CheckStatus.MATCH, VerificationService.mapSimilarity(99.9, MATCH, REVIEW).status());
    }

    @Test
    void borderlineScoreIsCheckWithoutMismatchFlag() {
        var result = VerificationService.mapSimilarity(82.5, MATCH, REVIEW);
        assertEquals(CheckStatus.CHECK, result.status());
        assertFalse(result.mismatchFlag());
    }

    @Test
    void belowReviewThresholdIsCheckWithMismatchFlag() {
        var result = VerificationService.mapSimilarity(40.0, MATCH, REVIEW);
        assertEquals(CheckStatus.CHECK, result.status());
        assertTrue(result.mismatchFlag());
    }

    @Test
    void boundaryJustBelowMatchIsCheck() {
        assertEquals(CheckStatus.CHECK, VerificationService.mapSimilarity(89.99, MATCH, REVIEW).status());
    }

    @Test
    void boundaryAtReviewIsCheckWithoutMismatch() {
        var result = VerificationService.mapSimilarity(75.0, MATCH, REVIEW);
        assertEquals(CheckStatus.CHECK, result.status());
        assertFalse(result.mismatchFlag());
    }
}
