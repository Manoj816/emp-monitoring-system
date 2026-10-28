package com.empverify.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CognitoJwtValidatorTest {

    @Test
    void rejectsEveryTokenWhenNoIssuerIsConfigured() {
        // Local dev: no Cognito issuer -> JWT admin auth is off, the admin key is used instead.
        var validator = new CognitoJwtValidator("", "");

        assertTrue(validator.validate("eyJhbGciOiJSUzI1NiJ9.e30.c2ln").isEmpty());
    }
}
