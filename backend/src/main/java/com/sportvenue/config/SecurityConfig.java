package com.sportvenue.config;

import com.sportvenue.security.JwtAuthenticationFilter;
import com.sportvenue.security.RateLimitingFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final Environment env;

    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/hello",
            "/api/v1/ping",
            "/api/v1/auth/**",
            "/api/v1/public/stadiums/**",
            "/api/v1/public/complexes/**",
            "/api/v1/public/facilities/**",
            "/api/v1/public/amenities/**",
            "/api/v1/public/locations/**",
            "/api/v1/sport-types/**",
            // UC-CUS-01: guests need to view slots by date and weekly slots
            "/api/v1/stadiums/*/slots",
            "/api/v1/stadiums/*/weekly-slots",
            // UC-CUS-02: VNPay redirect về /api/v1/payments/vnpay-return — public vì
            // VNPay gọi browser redirect tới đây (không có Bearer token).
            "/api/v1/payments/vnpay-return",
            // AI chat: tool chỉ đọc dữ liệu public (tìm sân/giờ trống/kèo ghép) — guest dùng được
            // như tìm kiếm thường. JwtAuthenticationFilter vẫn set UserPrincipal nếu request có
            // Bearer token hợp lệ, nên user đã đăng nhập vẫn được nhận diện trong controller.
            "/api/v1/ai/chat",
            // VNPay IPN — gọi server-to-server, cũng không có Bearer token.
            "/api/v1/payments/vnpay-ipn",

            "/actuator/health",
            "/actuator/info",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            // WebSocket STOMP endpoint — auth happens at STOMP CONNECT level via JWT header
            "/ws/**"
    };

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder);
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(authenticationProvider())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        // MatchRequestController.getActiveMatches() has no @PreAuthorize (public by
                        // design — guests can browse the community match list), but that alone
                        // doesn't exempt it from the base .anyRequest().authenticated() below.
                        // Exact path only (no wildcard) — /my-created, /my-joined, /{id}/participants
                        // etc. under the same base path must stay authenticated.
                        .requestMatchers(HttpMethod.GET, "/api/v1/matchmaking").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/files/avatars/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/files/stadiums/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/files/documents/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/files/document").permitAll()
                        .requestMatchers("/api/v1/files/**").authenticated()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
                        })
                )
                .addFilterBefore(new RateLimitingFilter(
                        env.acceptsProfiles(Profiles.of("dev", "test"))
                ), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
                "http://localhost:3000",
                "http://localhost:3001",
                // Production domains (DuckDNS)
                "https://sportsbookswp391vn.duckdns.org",
                "http://sportsbookswp391vn.duckdns.org",
                // Fallback IP trực tiếp
                "http://103.69.85.35:3000"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/ws/**", config);
        return source;
    }
}
