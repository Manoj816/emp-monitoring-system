package com.empverify.api;

import com.empverify.service.VerificationService;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Browser-facing capture callback. Called by the interactive capture page after
 * the employee clicks "Capture". Public (no device token): authorized by the
 * one-time check nonce, which is single-use because the check leaves PENDING.
 */
@RestController
@RequestMapping("/api/v1/capture")
public class CaptureController {

    private final VerificationService verificationService;

    public CaptureController(VerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @PostMapping("/{checkId}/submit")
    public CheckController.CheckResultResponse submit(
            @PathVariable UUID checkId,
            @RequestParam @NotBlank String token,
            @RequestParam("image") MultipartFile image,
            @RequestParam(required = false) String cameraDeviceName) {
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return CheckController.toResult(
                verificationService.captureFromBrowser(checkId, token, bytes, cameraDeviceName));
    }
}
