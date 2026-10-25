package com.empverify.verification;

import com.empverify.domain.Enums.CheckStatus;

/**
 * One-to-one face verification: captured frame vs. the enrolled reference image.
 * Implementations: {@link StubFaceVerifier} (local dev, no AWS account needed)
 * and {@link RekognitionFaceVerifier} (DetectFaces + CompareFaces).
 */
public interface FaceVerifier {

    /**
     * @param capturedJpeg  frame uploaded by the agent
     * @param referenceJpeg the employee's active reference image
     * @return raw outcome; threshold mapping to MATCH/CHECK happens in the service
     */
    Outcome verify(byte[] capturedJpeg, byte[] referenceJpeg);

    /**
     * @param similarity null when no comparison could be made; then {@code status}
     *                   carries the reason (NO_FACE, MULTIPLE_FACES, VERIFICATION_ERROR)
     */
    record Outcome(CheckStatus status, Double similarity, String errorDetail) {

        public static Outcome similarityOf(double similarity) {
            // status is provisional; the service maps it against policy thresholds
            return new Outcome(CheckStatus.PENDING, similarity, null);
        }

        public static Outcome failure(CheckStatus status, String detail) {
            return new Outcome(status, null, detail);
        }
    }
}
