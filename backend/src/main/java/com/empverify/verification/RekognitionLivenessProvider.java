package com.empverify.verification;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.AuditImage;
import software.amazon.awssdk.services.rekognition.model.CreateFaceLivenessSessionRequest;
import software.amazon.awssdk.services.rekognition.model.CreateFaceLivenessSessionRequestSettings;
import software.amazon.awssdk.services.rekognition.model.GetFaceLivenessSessionResultsRequest;
import software.amazon.awssdk.services.rekognition.model.GetFaceLivenessSessionResultsResponse;

/**
 * Amazon Rekognition Face Liveness. The backend creates the session; the React
 * component runs the challenge with Cognito credentials; the backend then fetches
 * the result (status, confidence + a reference frame) for 1:1 comparison.
 *
 * Enabled with empverify.liveness=rekognition.
 */
@Component
@ConditionalOnProperty(name = "empverify.liveness", havingValue = "rekognition")
public class RekognitionLivenessProvider implements LivenessProvider {

    private static final Logger log = LoggerFactory.getLogger(RekognitionLivenessProvider.class);

    private final RekognitionClient client;

    public RekognitionLivenessProvider(@Value("${empverify.aws.region}") String region) {
        this.client = RekognitionClient.builder().region(Region.of(region)).build();
    }

    /** Audit frames requested per session: the fallback picture for a reviewer when no reference frame comes back. */
    private static final int AUDIT_IMAGES = 2;

    @Override
    public Session createSession() {
        var response = client.createFaceLivenessSession(
                CreateFaceLivenessSessionRequest.builder()
                        .settings(CreateFaceLivenessSessionRequestSettings.builder()
                                .auditImagesLimit(AUDIT_IMAGES)
                                .build())
                        .build());
        return new Session(response.sessionId(), Instant.now().plusSeconds(300));
    }

    @Override
    public Result getResult(String sessionId) {
        GetFaceLivenessSessionResultsResponse response = client.getFaceLivenessSessionResults(
                GetFaceLivenessSessionResultsRequest.builder().sessionId(sessionId).build());
        // The reference frame is the high-quality face picture meant for comparison. A
        // low-confidence session may come back without one; then the first audit
        // frame stands in, so a reviewer still gets to see what the camera saw.
        byte[] frame = null;
        String frameSource = "none";
        if (response.referenceImage() != null && response.referenceImage().bytes() != null) {
            frame = response.referenceImage().bytes().asByteArray();
            frameSource = "reference";
        } else if (response.hasAuditImages()) {
            for (AuditImage audit : response.auditImages()) {
                if (audit.bytes() != null && audit.bytes().asByteArray().length > 0) {
                    frame = audit.bytes().asByteArray();
                    frameSource = "audit";
                    break;
                }
            }
        }
        if (frame != null && frame.length == 0) {
            frame = null;
            frameSource = "none";
        }
        double confidence = response.confidence() == null ? 0.0 : response.confidence();
        String status = response.statusAsString();
        // Operational visibility for tuning the threshold: status, score and which frame came back.
        log.info("Face Liveness session {}: status={} confidence={} frame={}",
                sessionId, status, confidence, frameSource);
        return new Result(confidence, frame, status);
    }
}
