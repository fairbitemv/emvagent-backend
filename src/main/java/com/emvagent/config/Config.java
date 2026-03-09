package com.emvagent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.filter.OncePerRequestFilter;

// ══════════════════════════════════════════════════════════════════
// JWT FILTER  (package-private — only used by SecurityConfig)
// JwtService    → JwtService.java
// SecurityConfig → SecurityConfig.java
// ══════════════════════════════════════════════════════════════════

class JwtFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtFilter.class);

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    JwtFilter(JwtService jwtService, UserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws java.io.IOException, jakarta.servlet.ServletException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            log.warn("JWT filter: no Bearer token on {} {}", request.getMethod(), request.getRequestURI());
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        if (!jwtService.isValid(token)) {
            log.warn("JWT filter: invalid/expired token on {} {}", request.getMethod(), request.getRequestURI());
            chain.doFilter(request, response);
            return;
        }

        String username = jwtService.extractUsername(token);
        var userDetails = userDetailsService.loadUserByUsername(username);

        var auth = new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
        org.springframework.security.core.context.SecurityContextHolder
                .getContext().setAuthentication(auth);

        chain.doFilter(request, response);
    }
}
