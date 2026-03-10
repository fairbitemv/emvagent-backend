package com.emvagent.auth;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// ══════════════════════════════════════════════════════════════════
// ENTITY
// ══════════════════════════════════════════════════════════════════

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    private OrganizationType organizationType;

    private String organizationName;

    @Column(nullable = false)
    private String role; // USER, EMV_EXPERT, ADMIN

    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    private Instant createdAt = Instant.now();

    // ── Billing fields ────────────────────────────────────────
    private String stripeCustomerId;
    private String stripeSubscriptionId;

    @Column(nullable = false)
    @Builder.Default
    private String subscriptionStatus = "INACTIVE";

    private Instant subscriptionExpiresAt;

    @Column(nullable = false)
    @Builder.Default
    private int dailyChatCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Instant dailyResetAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private int weeklyChatCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Instant weeklyResetAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private int bonusCredits = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean cancelAtPeriodEnd = false;
}

enum OrganizationType {
    BANK, ACQUIRER, PROCESSOR, POS_GATEWAY, NATIONAL_SWITCH, FAIRBIT_INTERNAL
}

// ══════════════════════════════════════════════════════════════════
// REPOSITORY
// ══════════════════════════════════════════════════════════════════

@Repository
interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    Optional<User> findByStripeSubscriptionId(String stripeSubscriptionId);
}

// ══════════════════════════════════════════════════════════════════
// DTOs
// ══════════════════════════════════════════════════════════════════

@Data
class LoginRequest {
    private String username;
    private String password;
}

@Data
class RegisterRequest {
    private String username;
    private String password;
    private String email;
    private String organizationType;
    private String organizationName;
}

@Data
@Builder
class AuthResponse {
    private String token;
    private String username;
    private String role;
    private String organizationType;
    private String subscriptionStatus;
}

// ══════════════════════════════════════════════════════════════════
// SERVICE
// ══════════════════════════════════════════════════════════════════

@Service
@RequiredArgsConstructor
class AuthService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final com.emvagent.config.JwtService jwtService;

    @Lazy
    @Autowired
    private AuthenticationManager authenticationManager;

    /**
     * Login — validates credentials, returns JWT token.
     */
    public AuthResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );
        } catch (org.springframework.security.core.AuthenticationException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        String token = jwtService.generateToken(user.getUsername(), user.getRole());

        return AuthResponse.builder()
                .token(token)
                .username(user.getUsername())
                .role(user.getRole())
                .organizationType(user.getOrganizationType() != null
                        ? user.getOrganizationType().name() : null)
                .subscriptionStatus(user.getSubscriptionStatus())
                .build();
    }

    /**
     * Register new user.
     */
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Username already exists");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Email already registered");
        }

        OrganizationType orgType;
        try {
            orgType = OrganizationType.valueOf(request.getOrganizationType());
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid organization type");
        }

        User user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .email(request.getEmail())
                .organizationType(orgType)
                .organizationName(request.getOrganizationName())
                .role("USER")
                .build();

        userRepository.save(user);

        String token = jwtService.generateToken(user.getUsername(), user.getRole());

        return AuthResponse.builder()
                .token(token)
                .username(user.getUsername())
                .role(user.getRole())
                .organizationType(request.getOrganizationType())
                .subscriptionStatus(user.getSubscriptionStatus())
                .build();
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPassword())
                .roles(user.getRole())
                .build();
    }
}

// ══════════════════════════════════════════════════════════════════
// CONTROLLER
// ══════════════════════════════════════════════════════════════════

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }
}
