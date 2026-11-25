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
@EnableConfigurationProperties({AdminProperties.class, RegistrationProperties.class})
public class SecurityConfig {

    /**
     * {@code /v1/admin/**} and the dashboard {@code /admin/**}: HTTP Basic with the admin credentials from the
     * environment, rate limited, changing requests only from this server's own pages.
     */
    @Bean
    @Order(1)
    SecurityFilterChain adminChain(HttpSecurity http, AdminAuthRateLimiter rateLimiter) throws Exception {
        return http
                .securityMatcher("/v1/admin/**", "/admin", "/admin/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
                .httpBasic(basic -> basic.authenticationEntryPoint(rateLimiter.countingEntryPoint()))
                .addFilterBefore(rateLimiter.blockingFilter(), BasicAuthenticationFilter.class)
                .addFilterBefore(new SameOriginFilter(), BasicAuthenticationFilter.class)
                // the dashboard must never be framed by another site
                .headers(headers -> headers.frameOptions(frame -> frame.deny())
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; img-src 'self' data:; frame-ancestors 'none'")))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    /** Everything else: device bearer tokens, except registration and login. */
    @Bean
    @Order(2)
    SecurityFilterChain deviceChain(HttpSecurity http, DeviceTokenService tokens) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, e) ->
                AdminAuthRateLimiter.write(response, HttpStatus.UNAUTHORIZED, ApiError.of("unauthorized", "valid bearer token required"));
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error", "/v1/auth/**").permitAll()
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
