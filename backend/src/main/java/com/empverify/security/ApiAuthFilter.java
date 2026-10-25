package com.empverify.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Phase 1 authentication:
 *  - "Authorization: Bearer <device token>" -> ROLE_DEVICE, principal = deviceId
 *  - "X-Admin-Key: <key>"                   -> ROLE_ADMIN (dev-only; Cognito later)
 */
@Component
public class ApiAuthFilter extends OncePerRequestFilter {

    private final DeviceTokenService deviceTokens;
    private final byte[] adminKey;

    public ApiAuthFilter(
            DeviceTokenService deviceTokens,
            @Value("${empverify.security.admin-api-key}") String adminKey) {
        this.deviceTokens = deviceTokens;
        this.adminKey = adminKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String providedAdminKey = request.getHeader("X-Admin-Key");
        if (providedAdminKey != null
                && MessageDigest.isEqual(adminKey, providedAdminKey.getBytes(StandardCharsets.UTF_8))) {
            var auth = new UsernamePasswordAuthenticationToken(
                    "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        } else {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                deviceTokens.validate(header.substring(7)).ifPresent(deviceId -> {
                    var auth = new UsernamePasswordAuthenticationToken(
                            deviceId.toString(), null,
                            List.of(new SimpleGrantedAuthority("ROLE_DEVICE")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
            }
        }
        chain.doFilter(request, response);
    }
}
