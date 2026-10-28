package com.empverify.config;

import com.empverify.security.ApiAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Two auth planes (both handled by {@link ApiAuthFilter} in Phase 1):
 *  - Desktop agents: short-lived HMAC device tokens from /api/v1/devices/auth.
 *  - Admins: X-Admin-Key header (dev only) — replaced by Cognito JWTs before
 *    production.
 *
 * Registration and auth endpoints are open; registration itself is gated by a
 * one-time enrollment token validated in the service layer.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ApiAuthFilter apiAuthFilter)
            throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(apiAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/actuator/health",
                    "/api/v1/devices/register",
                    "/api/v1/devices/auth",
                    "/api/v1/devices/provision",
                    "/api/v1/liveness/**",
                    "/api/v1/capture/**"
                ).permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().hasAnyRole("DEVICE", "ADMIN"));
        return http.build();
    }
}
