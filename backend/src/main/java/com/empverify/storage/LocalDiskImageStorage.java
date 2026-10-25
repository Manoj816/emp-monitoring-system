package com.empverify.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Dev-only storage under a local directory; replaced by S3 (SSE-KMS) at deployment. */
@Component
@ConditionalOnProperty(name = "empverify.storage", havingValue = "local", matchIfMissing = true)
public class LocalDiskImageStorage implements ImageStorage {

    private final Path baseDir;

    public LocalDiskImageStorage(@Value("${empverify.local-storage-dir}") String baseDir) {
        this.baseDir = Path.of(baseDir);
    }

    @Override
    public String store(String key, byte[] bytes) {
        try {
            Path target = resolve(key);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] load(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path resolve(String key) {
        Path target = baseDir.resolve(key).normalize();
        if (!target.startsWith(baseDir.normalize())) {
            throw new IllegalArgumentException("key escapes storage directory");
        }
        return target;
    }
}
