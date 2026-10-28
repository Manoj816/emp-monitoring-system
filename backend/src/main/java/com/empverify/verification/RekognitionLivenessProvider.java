package com.empverify.verification;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.CreateFaceLivenessSessionRequest;
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

    @Override
    public Session createSession() {
        var response = client.createFaceLivenessSession(
                CreateFaceLivenessSessionRequest.builder().build());
        return new Session(response.sessionId(), Instant.now().plusSeconds(300));
    }

    @Override
    public Result getResult(String sessionId) {
        GetFaceLivenessSessionResultsResponse response = client.getFaceLivenessSessionResults(
                GetFaceLivenessSessionResultsRequest.builder().sessionId(sessionId).build());
        byte[] reference = null;
        if (response.referenceImage() != null && response.referenceImage().bytes() != null) {
            reference = response.referenceImage().bytes().asByteArray();
        }
        double confidence = response.confidence() == null ? 0.0 : response.confidence();
        String status = response.statusAsString();
        // Operational visibility for tuning the threshold: status, score and whether a frame came back.
        log.info("Face Liveness session {}: status={} confidence={} referenceFrame={}",
                sessionId, status, confidence, reference != null);
        return new Result(confidence, reference, status);
    }
}
