package com.empverify.security;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Validates Cognito user-pool ID tokens presented by the admin portal: signature
 * (via the pool's JWKS), issuer, expiry, and that it is an ID token issued to our
 * app client. Inactive (every token rejected) until an issuer is configured, so
 * local dev keeps using the admin key.
 */
@Component
public class CognitoJwtValidator {

    private final JwtDecoder decoder; // null when not configured

    public CognitoJwtValidator(
            @Value("${empverify.security.cognito.issuer-uri:}") String issuerUri,
            @Value("${empverify.security.cognito.client-id:}") String clientId) {
        if (issuerUri == null || issuerUri.isBlank()) {
            this.decoder = null;
            return;
        }
        var nimbus = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
        OAuth2TokenValidator<Jwt> forThisClient = jwt ->
                jwt.getAudience().contains(clientId) && "id".equals(jwt.getClaimAsString("token_use"))
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(
                                new OAuth2Error("invalid_token", "not an ID token for this client", null));
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri), forThisClient));
        this.decoder = nimbus;
    }

    /** The admin's email (falling back to the subject) when the token is valid. */
    public Optional<String> validate(String token) {
        if (decoder == null) {
            return Optional.empty();
        }
        try {
            Jwt jwt = decoder.decode(token);
            String email = jwt.getClaimAsString("email");
            return Optional.of(email != null ? email : jwt.getSubject());
        } catch (JwtException e) {
            return Optional.empty();
        }
    }
}
