package com.emvagent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.filter.OncePerRequestFilter;

// ══════════════════════════════════════════════════════════════════
// JWT FILTER  (package-private — only used by SecurityConfig)
// JwtService    → JwtService.java
// SecurityConfig → SecurityConfig.java
// ══════════════════════════════════════════════════════════════════

class JwtFilter extends OncePerRequestFilter {

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
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        if (!jwtService.isValid(token)) {
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
