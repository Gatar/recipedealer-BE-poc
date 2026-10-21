package com.gatar.recipedealer.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

import com.gatar.recipedealer.web.ApiError;

@Configuration
@EnableConfigurationProperties(AdminProperties.class)
public class SecurityConfig {

    /** {@code /v1/admin/**}: HTTP Basic with the admin credentials from the environment, rate limited. */
    @Bean
    @Order(1)
    SecurityFilterChain adminChain(HttpSecurity http, AdminAuthRateLimiter rateLimiter) throws Exception {
        return http
                .securityMatcher("/v1/admin/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
                .httpBasic(basic -> basic.authenticationEntryPoint(rateLimiter.countingEntryPoint()))
                .addFilterBefore(rateLimiter.blockingFilter(), BasicAuthenticationFilter.class)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    /** Everything else: device bearer tokens. */
    @Bean
    @Order(2)
    SecurityFilterChain deviceChain(HttpSecurity http, DeviceTokenService tokens) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, e) ->
                AdminAuthRateLimiter.write(response, HttpStatus.UNAUTHORIZED, ApiError.of("unauthorized", "valid bearer token required"));
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new BearerTokenFilter(tokens), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorized))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** The single admin account; empty (admin disabled) when no credentials are configured. */
    @Bean
    UserDetailsService adminUserDetailsService(AdminProperties admin) {
        if (!admin.configured()) {
            return new InMemoryUserDetailsManager();
        }
        return new InMemoryUserDetailsManager(User.withUsername(admin.username())
                .password(admin.passwordHash())
                .roles("ADMIN")
                .build());
    }
}
