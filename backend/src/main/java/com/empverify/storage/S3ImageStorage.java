package com.empverify.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * S3-backed image storage for deployment. Encryption at rest via SSE-KMS (or
 * SSE-S3 when no KMS key is configured). Credentials come from the default
 * provider chain — in ECS that is the task role, never static IAM user keys.
 *
 * Enabled with empverify.storage=s3. Retention is enforced primarily by the
 * bucket's lifecycle rules; the application-level retention job is a backstop.
 */
@Component
@ConditionalOnProperty(name = "empverify.storage", havingValue = "s3")
public class S3ImageStorage implements ImageStorage {

    private final S3Client client;
    private final String bucket;
    private final String kmsKeyId;

    public S3ImageStorage(
            @Value("${empverify.aws.region}") String region,
            @Value("${empverify.aws.s3-bucket}") String bucket,
            @Value("${empverify.aws.kms-key-id:}") String kmsKeyId) {
        this.client = S3Client.builder().region(Region.of(region)).build();
        this.bucket = bucket;
        this.kmsKeyId = kmsKeyId;
    }

    @Override
    public String store(String key, byte[] bytes) {
        var builder = PutObjectRequest.builder().bucket(bucket).key(key).contentType("image/jpeg");
        if (kmsKeyId != null && !kmsKeyId.isBlank()) {
            builder.serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(kmsKeyId);
        } else {
            builder.serverSideEncryption(ServerSideEncryption.AES256);
        }
        client.putObject(builder.build(), RequestBody.fromBytes(bytes));
        return key;
    }

    @Override
    public byte[] load(String key) {
        ResponseBytes<GetObjectResponse> object =
                client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build());
        return object.asByteArray();
    }

    @Override
    public void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
