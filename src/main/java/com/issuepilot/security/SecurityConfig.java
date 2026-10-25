package com.issuepilot.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security & OAuth2 Resource Server configuration for Issue Pilot.
 *
 * Implements Stage 1 Security Architecture:
 * - Stateless session policy
 * - OAuth2 Resource Server Bearer JWT validation
 * - Public routes: /api/health/**, /api/issues/**, /api/simulator/**, static assets
 * - Protected routes: /api/votes/**, /api/feedback/**, /api/users/**, /api/recommendations/**
 * - Custom JSON responses for 401 Unauthorized and 403 Forbidden
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    @SuppressWarnings("null")
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectProvider<JwtDecoder> jwtDecoderProvider
    ) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Authentication credentials are required to access this resource.\"}");
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"status\":403,\"error\":\"Forbidden\",\"message\":\"Access denied: insufficient permissions or cross-user operation.\"}");
                })
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/health/**",
                    "/api/simulator/**",
                    "/api/issues/**",
                    "/",
                    "/index.html",
                    "/error",
                    "/favicon.ico",
                    "/static/**"
                ).permitAll()
                .requestMatchers(
                    "/api/votes/**",
                    "/api/feedback/**",
                    "/api/users/**",
                    "/api/recommendations/**"
                ).authenticated()
                .anyRequest().authenticated()
            );

        // Configure OAuth2 Resource Server with available or fallback JwtDecoder
        JwtDecoder decoder = jwtDecoderProvider.getIfAvailable();
        if (decoder == null) {
            decoder = token -> {
                throw new BadJwtException("Invalid token: JWT signing key verification failed or token is malformed");
            };
        }
        final JwtDecoder effectiveDecoder = decoder;

        http.oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt.decoder(effectiveDecoder))
            .authenticationEntryPoint((request, response, authException) -> {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"" + authException.getMessage() + "\"}");
            })
        );

        return http.build();
    }
}
