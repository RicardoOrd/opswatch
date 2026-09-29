package io.github.ricardoord.opswatch.identity.security;

import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Deny by default: every request needs authentication except an explicit public list. See
 * docs/security/security-architecture.md.
 *
 * <p>Sprint 0 has no authentication mechanism yet (JWT arrives in OW-013), so every protected endpoint answers
 * {@code 401} in Problem Details format.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfiguration {

    static final String API_CSP = "default-src 'none'; frame-ancestors 'none'";
    static final String[] API_DOCS = {"/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"};

    /**
     * OpenAPI and Swagger UI. A separate chain because Swagger UI needs scripts and styles that the strict API
     * Content-Security-Policy forbids. Production disables springdoc entirely (application-production.yml).
     */
    @Bean
    @Order(1)
    SecurityFilterChain apiDocsSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(API_DOCS)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http, AuthenticationEntryPoint entryPoint, AccessDeniedHandler accessDeniedHandler)
            throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        // Error dispatches render Problem Details for errors raised before the controller
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        // Only health and info are exposed, on the management port (8081), which is never published
                        .requestMatchers("/actuator/**")
                        .permitAll()
                        .requestMatchers("/api/v1/auth/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                // The API authenticates with a Bearer token that browsers never attach on their own, so CSRF does
                // not apply. The only cookie (the refresh token, OW-014) is protected with SameSite=Strict and an
                // Origin check on its two endpoints.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(API_CSP))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .frameOptions(frame -> frame.deny()));
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        var source = new UrlBasedCorsConfigurationSource();
        if (properties.allowedOrigins().isEmpty()) {
            return source; // no CORS configuration: cross-origin browser requests are rejected
        }
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE"));
        configuration.setAllowedHeaders(
                List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.IF_MATCH, "X-Request-Id"));
        configuration.setExposedHeaders(
                List.of(HttpHeaders.ETAG, HttpHeaders.LOCATION, HttpHeaders.RETRY_AFTER, "X-Request-Id"));
        // Needed later for the refresh token cookie; only ever combined with an explicit origin list
        configuration.setAllowCredentials(true);
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
