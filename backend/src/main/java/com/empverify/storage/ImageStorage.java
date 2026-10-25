package com.empverify.storage;

/**
 * Storage for reference images and CHECK-event evidence.
 * Implementations: local disk (dev) and S3 with SSE-KMS (deployment).
 */
public interface ImageStorage {

    /** Stores the bytes under the given key and returns the key. */
    String store(String key, byte[] bytes);

    byte[] load(String key);

    void delete(String key);
}
