package com.issuepilot.security;

import com.issuepilot.config.AppProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring Security & OAuth2 Resource Server configuration for Issue Pilot.
 *
 * Implements Stage 1 & Phase 2 Security Architecture:
 * - Stateless session policy
 * - OAuth2 Resource Server Bearer JWT validation with real HMAC-SHA256 decoder
 * - Strict claims validation (issuer, expiration, user_id/sub)
 * - Fails closed: Rejects tokens if APP_JWT_SECRET is absent or under-strength
 * - Public routes: /api/health/**, /api/issues/**, /api/simulator/**, static assets
 * - Protected routes: /api/votes/**, /api/feedback/**, /api/users/**, /api/recommendations/**, /api/ingestion/**
 * - Custom JSON responses for 401 Unauthorized and 403 Forbidden
 */
@Configuration
@ConditionalOnWebApplication
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public JwtDecoder jwtDecoder(AppProperties properties) {
        String secret = properties.getAppJwtSecret();
        if (secret == null || secret.trim().getBytes(StandardCharsets.UTF_8).length < 32) {
            logger.warn("APP_JWT_SECRET is not configured or shorter than 32 bytes. JWT authentication will fail closed.");
            return token -> {
                throw new BadJwtException("Invalid token: JWT signing key verification failed or signing secret not configured.");
            };
        }

        byte[] secretBytes = secret.trim().getBytes(StandardCharsets.UTF_8);
        SecretKey secretKey = new SecretKeySpec(secretBytes, "HmacSHA256");

        ConfigurableJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
        JWSKeySelector<SecurityContext> keySelector = new JWSVerificationKeySelector<>(
                JWSAlgorithm.HS256,
                new ImmutableSecret<>(secretKey)
        );
        jwtProcessor.setJWSKeySelector(keySelector);

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(jwtProcessor);

        // Required validators: Timestamp, Issuer, and identity claim presence
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator());

        String issuer = properties.getAppJwtIssuer();
        if (issuer != null && !issuer.isBlank()) {
            validators.add(new OAuth2TokenValidator<Jwt>() {
                @Override
                public OAuth2TokenValidatorResult validate(Jwt token) {
                    if (token.getIssuer() != null && issuer.equals(token.getIssuer().toString())) {
                        return OAuth2TokenValidatorResult.success();
                    }
                    OAuth2Error err = new OAuth2Error("invalid_issuer", "The token issuer is not recognized.", null);
                    return OAuth2TokenValidatorResult.failure(err);
                }
            });
        }

        // Required Identity Claim Validator: Must possess either "user_id" or "sub"
        validators.add(new OAuth2TokenValidator<Jwt>() {
            @Override
            public OAuth2TokenValidatorResult validate(Jwt token) {
                String userId = token.getClaimAsString("user_id");
                String sub = token.getSubject();
                if ((userId != null && !userId.isBlank()) || (sub != null && !sub.isBlank())) {
                    return OAuth2TokenValidatorResult.success();
                }
                OAuth2Error err = new OAuth2Error("invalid_claims", "Token must contain a valid 'user_id' or 'sub' claim.", null);
                return OAuth2TokenValidatorResult.failure(err);
            }
        });

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder
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
                    "/api/auth/github/login",
                    "/api/auth/github/callback",
                    "/api/auth/exchange",
                    "/",
                    "/index.html",
                    "/error",
                    "/favicon.ico",
                    "/static/**"
                ).permitAll()
                .requestMatchers(
                    "/api/auth/me",
                    "/api/auth/logout",
                    "/api/auth/github/disconnect",
                    "/api/profile/**",
                    "/api/votes/**",
                    "/api/feedback/**",
                    "/api/users/**",
                    "/api/recommendations/**",
                    "/api/ingestion/**"
                ).authenticated()
                .anyRequest().authenticated()
            );

        http.oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt.decoder(jwtDecoder))
            .authenticationEntryPoint((request, response, authException) -> {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"" + authException.getMessage() + "\"}");
            })
        );

        return http.build();
    }
}
