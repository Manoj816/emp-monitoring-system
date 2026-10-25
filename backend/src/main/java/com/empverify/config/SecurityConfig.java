package com.empverify.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Two auth planes share this API:
 *  - Admin portal users: Cognito-issued JWTs (resource-server validation to be wired in
 *    once the Cognito user pool exists).
 *  - Desktop agents: per-device credentials issued at registration; exchanged for a
 *    short-lived device token on /api/v1/devices/auth.
 *
 * Registration and auth endpoints are open (registration itself is gated by a one-time
 * enrollment token validated in the service layer).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/actuator/health",
                    "/api/v1/devices/register",
                    "/api/v1/devices/auth"
                ).permitAll()
                .anyRequest().authenticated());
        // TODO: add JWT resource-server config (Cognito issuer) + device-token filter.
        return http.build();
    }
}

