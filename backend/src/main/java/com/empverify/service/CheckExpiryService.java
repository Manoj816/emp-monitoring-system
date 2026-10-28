package com.empverify.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Turns checks that are still PENDING past their deadline into EXPIRED. The agent
 * normally reports a closed window or a timeout itself, but if it goes offline
 * mid-check (laptop closed, network dropped) nothing would, and the admin view
 * would show a check that never finishes. Runs every minute.
 */
@Component
public class CheckExpiryService {

    private static final Logger log = LoggerFactory.getLogger(CheckExpiryService.class);

    private final VerificationService verificationService;

    public CheckExpiryService(VerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @Scheduled(fixedDelayString = "${empverify.verification.expiry-sweep-ms:60000}")
    public void sweep() {
        try {
            int expired = verificationService.expireOverdueChecks();
            if (expired > 0) {
                log.info("Expired {} pending check(s) past their response window", expired);
            }
        } catch (Exception e) {
            log.warn("Check expiry sweep failed: {}", e.getMessage());
        }
    }
}
