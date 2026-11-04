package com.empverify.service;

import com.empverify.domain.Enums.ReferenceImageSource;
import com.empverify.domain.ReferenceImage;
import com.empverify.repo.ReferenceImageRepository;
import com.empverify.storage.ImageStorage;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Writes the ID/photo an administrator supplied as the employee's reference.
 *
 * <p>Shared by the "Add employee" form, the "Replace ID / photo" action and the
 * CSV import, so what an uploaded reference <em>is</em> — ACTIVE, ADMIN_UPLOAD,
 * stored under the employee — is stated once. A second copy of these four lines
 * is a reference that silently stops being found by the portal when one of them
 * drifts.
 */
@Service
public class ReferenceImageService {

    private final ReferenceImageRepository referenceImages;
    private final ImageStorage storage;

    public ReferenceImageService(ReferenceImageRepository referenceImages, ImageStorage storage) {
        this.referenceImages = referenceImages;
        this.storage = storage;
    }

    /** Stores the upload and records it as the employee's ACTIVE ADMIN_UPLOAD reference. */
    public ReferenceImage storeAdminUpload(UUID employeeId, byte[] bytes) {
        var reference = new ReferenceImage();
        reference.setEmployeeId(employeeId);
        reference.setSource(ReferenceImageSource.ADMIN_UPLOAD);
        reference.setCreatedBy("admin");
        reference.setS3Key(storage.store(
                "reference/" + employeeId + "/" + reference.getId() + ".jpg", bytes));
        referenceImages.save(reference);
        return reference;
    }

    /**
     * JPEG (FF D8 FF) or PNG (89 50 4E 47) magic bytes; anything else is not a
     * picture a browser can show or a face matcher can read. A PDF, a Word file
     * or a renamed .txt all fail here, whatever the extension claims.
     */
    public static boolean looksLikeImage(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            return false;
        }
        boolean jpeg = (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
        boolean png = (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
        return jpeg || png;
    }
}
