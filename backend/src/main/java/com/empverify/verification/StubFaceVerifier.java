package com.empverify.verification;

import com.empverify.domain.Enums.CheckStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local development verifier — lets the full end-to-end flow run without an AWS
 * account. Returns a fixed similarity (default 95). An empty capture simulates
 * NO_FACE so the error path is testable.
 */
@Component
@ConditionalOnProperty(name = "empverify.verifier", havingValue = "stub", matchIfMissing = true)
public class StubFaceVerifier implements FaceVerifier {

    private final double stubSimilarity;

    public StubFaceVerifier(@Value("${empverify.stub-similarity:95.0}") double stubSimilarity) {
        this.stubSimilarity = stubSimilarity;
    }

    @Override
    public Outcome verify(byte[] capturedJpeg, byte[] referenceJpeg) {
        if (capturedJpeg == null || capturedJpeg.length == 0) {
            return Outcome.failure(CheckStatus.NO_FACE, "stub: empty capture");
        }
        return Outcome.similarityOf(stubSimilarity);
    }
}
