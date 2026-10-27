package com.empverify.verification;

import java.time.Instant;

/**
 * Interactive liveness (live-person) detection. Implementations:
 * {@link StubLivenessProvider} (local dev, no AWS) and
 * {@link RekognitionLivenessProvider} (Amazon Rekognition Face Liveness).
 */
public interface LivenessProvider {

    /** Creates a liveness session the browser component will run. */
    Session createSession();

    /** Fetches the completed session's result (confidence + a captured frame). */
    Result getResult(String sessionId);

    record Session(String sessionId, Instant expiresAt) {}

    /**
     * @param confidence    live-person confidence (0-100)
     * @param referenceJpeg a frame captured during the liveness check for 1:1
     *                      comparison, or null when the provider returns none
     */
    record Result(double confidence, byte[] referenceJpeg) {}
}
