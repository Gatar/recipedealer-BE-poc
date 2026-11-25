package com.gatar.recipedealer.auth;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import com.gatar.recipedealer.web.ApiError;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * CSRF guard of the admin chain: a browser keeps the admin's Basic credentials for the dashboard and would send them
 * along with a request another site makes. Browsers mark such requests with an {@code Origin} of that site, so a
 * changing request whose {@code Origin} is not this server answers 403. Requests without {@code Origin} (curl,
 * Postman, the dashboard's own GETs) pass.
 */
public class SameOriginFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !SAFE.contains(request.getMethod()) && !sameHost(origin, request)) {
            AdminAuthRateLimiter.write(response, HttpStatus.FORBIDDEN,
                    ApiError.of("cross_origin", "admin requests from another site are not allowed"));
            return;
        }
        chain.doFilter(request, response);
    }

    /** The host (and port) of {@code Origin} is the one the request was sent to; behind Caddy that is its Host. */
    private static boolean sameHost(String origin, HttpServletRequest request) {
        try {
            URI uri = URI.create(origin);
            String host = request.getServerName().toLowerCase(Locale.ROOT);
            if (uri.getHost() == null || !uri.getHost().toLowerCase(Locale.ROOT).equals(host)) {
                return false;
            }
            int port = uri.getPort() >= 0 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
            return port == request.getServerPort();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
