package com.empverify.verification;

import com.empverify.domain.Enums.CheckStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.CompareFacesRequest;
import software.amazon.awssdk.services.rekognition.model.CompareFacesResponse;
import software.amazon.awssdk.services.rekognition.model.DetectFacesRequest;
import software.amazon.awssdk.services.rekognition.model.Image;
import software.amazon.awssdk.services.rekognition.model.RekognitionException;

/**
 * Real verifier: DetectFaces for face count, then CompareFaces for 1:1 similarity.
 * Stateless — AWS does not retain faces from these calls. Enabled with
 * empverify.verifier=rekognition; credentials come from the default provider
 * chain (locally: SSO/env; in ECS: the task role — never IAM user keys).
 */
@Component
@ConditionalOnProperty(name = "empverify.verifier", havingValue = "rekognition")
public class RekognitionFaceVerifier implements FaceVerifier {

    private final RekognitionClient client;

    public RekognitionFaceVerifier(@Value("${empverify.aws.region}") String region) {
        this.client = RekognitionClient.builder().region(Region.of(region)).build();
    }

    @Override
    public Outcome verify(byte[] capturedJpeg, byte[] referenceJpeg) {
        try {
            var captured = Image.builder().bytes(SdkBytes.fromByteArray(capturedJpeg)).build();
            var reference = Image.builder().bytes(SdkBytes.fromByteArray(referenceJpeg)).build();

            var faces = client.detectFaces(
                    DetectFacesRequest.builder().image(captured).build()).faceDetails();
            if (faces.isEmpty()) {
                return Outcome.failure(CheckStatus.NO_FACE, "no face detected");
            }
            if (faces.size() > 1) {
                return Outcome.failure(CheckStatus.MULTIPLE_FACES, faces.size() + " faces detected");
            }

            CompareFacesResponse comparison = client.compareFaces(CompareFacesRequest.builder()
                    .sourceImage(reference)
                    .targetImage(captured)
                    // threshold 0: always return the similarity; policy mapping is ours
                    .similarityThreshold(0f)
                    .build());

            if (comparison.faceMatches().isEmpty()) {
                return Outcome.similarityOf(0.0);
            }
            return Outcome.similarityOf(comparison.faceMatches().get(0).similarity());
        } catch (RekognitionException e) {
            return Outcome.failure(CheckStatus.VERIFICATION_ERROR,
                    "rekognition: " + e.awsErrorDetails().errorCode());
        } catch (Exception e) {
            return Outcome.failure(CheckStatus.VERIFICATION_ERROR, "verifier failure: " + e.getMessage());
        }
    }
}
