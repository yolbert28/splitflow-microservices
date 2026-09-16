package dev.yolbert.auth_service.config;

import dev.yolbert.auth_service.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtTokenProvider jwtTokenProvider, ObjectMapper objectMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper     = objectMapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    objectMapper.writeValue(response.getWriter(),
                            ApiErrorResponse.builder()
                                    .message("No autorizado.")
                                    .build());
                }))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/user/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/auth/verify-email").permitAll()
                .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/auth/login/verify-2fa").permitAll()
                .requestMatchers(HttpMethod.POST, "/auth/refresh").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(
                    new JwtAuthenticationFilter(jwtTokenProvider),
                    UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public LoginRateLimitFilter loginRateLimitFilter(ObjectMapper objectMapper) {
        return new LoginRateLimitFilter(objectMapper);
    }

    @Bean
    public FilterRegistrationBean<LoginRateLimitFilter> loginRateLimitFilterRegistration(
            LoginRateLimitFilter loginRateLimitFilter) {
        FilterRegistrationBean<LoginRateLimitFilter> registration =
                new FilterRegistrationBean<>(loginRateLimitFilter);
        registration.addUrlPatterns("/*");
        registration.setOrder(-101);
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}