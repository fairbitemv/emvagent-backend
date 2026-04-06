package com.emvagent.auth;

import jakarta.persistence.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// ══════════════════════════════════════════════════════════════════
// ENTITY
// ══════════════════════════════════════════════════════════════════

@Entity
@Table(name = "magic_link_tokens")
@Data
@NoArgsConstructor
@AllArgsConstructor
class MagicLinkToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean used = false;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}

// ══════════════════════════════════════════════════════════════════
// REPOSITORY
// ══════════════════════════════════════════════════════════════════

@Repository
interface MagicLinkTokenRepository extends JpaRepository<MagicLinkToken, UUID> {
    Optional<MagicLinkToken> findByEmailAndCodeAndUsedFalse(String email, String code);
    void deleteByEmail(String email);
}

// ══════════════════════════════════════════════════════════════════
// DTOs
// ══════════════════════════════════════════════════════════════════

@Data
class MagicLinkSendRequest {
    private String email;
}

@Data
class MagicLinkVerifyRequest {
    private String email;
    private String code;
}

@Data
@Builder
class MagicLinkVerifyResponse {
    private String type;       // "LOGIN" or "SIGNUP"
    private String token;      // JWT if LOGIN; temp UUID if SIGNUP
    private String username;   // populated on LOGIN
    private String displayName;
    private String role;
    private String organizationType;
    private String subscriptionStatus;
}

@Data
class MagicLinkCompleteRequest {
    private String tempToken;
    private String displayName;
    private String organizationType;
    private List<String> topics;
}

// ══════════════════════════════════════════════════════════════════
// SERVICE
// ══════════════════════════════════════════════════════════════════

@Service
@RequiredArgsConstructor
@Slf4j
class MagicLinkService {

    private final MagicLinkTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final JavaMailSender mailSender;
    private final com.emvagent.config.JwtService jwtService;

    @Value("${spring.mail.username:contact@fairbit.com}")
    private String fromEmail;

    // In-memory map: tempToken → PendingSignup (15 min TTL)
    private final Map<String, PendingSignup> pendingSignups = new ConcurrentHashMap<>();

    private final SecureRandom random = new SecureRandom();

    private static final Set<String> LIFETIME_EMAILS = Set.of(
        "erdal.yazmaci@gmail.com",
        "ozgur.altuntas@fairbit.com",
        "emin.bahadir@fairbit.com",
        "yusuf@fairbit.com",
        "bulent@fairbit.com"
    );

    record PendingSignup(String email, Instant expiresAt) {}

    @Transactional
    public void sendCode(String email) {
        tokenRepository.deleteByEmail(email);
        String code = String.format("%06d", random.nextInt(1_000_000));
        MagicLinkToken token = new MagicLinkToken();
        token.setEmail(email);
        token.setCode(code);
        token.setExpiresAt(Instant.now().plusSeconds(900)); // 15 minutes
        token.setCreatedAt(Instant.now());
        tokenRepository.save(token);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(email);
        message.setSubject("Your EMVAgent verification code");
        message.setText(
            "Your EMVAgent sign-in code is:\n\n" +
            "  " + code + "\n\n" +
            "This code expires in 15 minutes.\n\n" +
            "If you did not request this, please ignore this email.\n\n" +
            "— Fairbit EMVAgent Team"
        );

        try {
            mailSender.send(message);
            log.info("Magic link code sent to: {}", email);
        } catch (Exception e) {
            log.error("Failed to send magic link email to {}: {}", email, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to send verification email");
        }
    }

    @Transactional
    public MagicLinkVerifyResponse verifyCode(String email, String code) {
        MagicLinkToken token = tokenRepository.findByEmailAndCodeAndUsedFalse(email, code)
            .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired code"));

        token.setUsed(true);
        tokenRepository.save(token);

        Optional<User> existingUser = userRepository.findByEmail(email);
        if (existingUser.isPresent()) {
            User user = existingUser.get();
            if (LIFETIME_EMAILS.contains(email) && !"LIFETIME".equals(user.getSubscriptionStatus())) {
                user.setSubscriptionStatus("LIFETIME");
                userRepository.save(user);
            }
            String jwt = jwtService.generateToken(user.getUsername(), user.getRole());
            return MagicLinkVerifyResponse.builder()
                .type("LOGIN")
                .token(jwt)
                .username(user.getUsername())
                .displayName(user.getOrganizationName())
                .role(user.getRole())
                .organizationType(user.getOrganizationType() != null ? user.getOrganizationType().name() : null)
                .subscriptionStatus(user.getSubscriptionStatus())
                .build();
        }

        String tempToken = UUID.randomUUID().toString();
        pendingSignups.put(tempToken, new PendingSignup(email, Instant.now().plusSeconds(900)));
        return MagicLinkVerifyResponse.builder()
            .type("SIGNUP")
            .token(tempToken)
            .build();
    }

    @Transactional
    public AuthResponse completeSignup(MagicLinkCompleteRequest req) {
        PendingSignup pending = Optional.ofNullable(pendingSignups.remove(req.getTempToken()))
            .filter(p -> p.expiresAt().isAfter(Instant.now()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Session expired, please start over"));

        String email = pending.email();

        if (userRepository.existsByEmail(email)) {
            // Race condition: user was created in the meantime — treat as login
            User user = userRepository.findByEmail(email).get();
            String jwt = jwtService.generateToken(user.getUsername(), user.getRole());
            return AuthResponse.builder()
                .token(jwt)
                .username(user.getUsername())
                .displayName(user.getOrganizationName())
                .role(user.getRole())
                .organizationType(user.getOrganizationType() != null ? user.getOrganizationType().name() : null)
                .subscriptionStatus(user.getSubscriptionStatus())
                .build();
        }

        // Derive username from displayName, ensure uniqueness
        String base = req.getDisplayName() != null && !req.getDisplayName().isBlank()
            ? req.getDisplayName().trim().replaceAll("[^a-zA-Z0-9_\\-.]", "")
            : email.split("@")[0];
        if (base.isBlank()) base = "user";

        String username = base;
        if (userRepository.existsByUsername(username)) {
            username = base + "_" + String.format("%04d", random.nextInt(10_000));
        }

        OrganizationType orgType;
        try {
            orgType = OrganizationType.valueOf(req.getOrganizationType());
        } catch (Exception e) {
            orgType = OrganizationType.BANK;
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(UUID.randomUUID().toString()); // unusable password for magic-link users
        user.setOrganizationType(orgType);
        user.setOrganizationName(req.getDisplayName());
        String subscriptionStatus = LIFETIME_EMAILS.contains(email) ? "LIFETIME" : "INACTIVE";
        user.setRole("USER");
        user.setEnabled(true);
        user.setCreatedAt(Instant.now());
        user.setSubscriptionStatus(subscriptionStatus);
        user.setDailyChatCount(0);
        user.setWeeklyChatCount(0);
        user.setBonusCredits(0);
        user.setCancelAtPeriodEnd(false);
        user.setDailyResetAt(Instant.now());
        user.setWeeklyResetAt(Instant.now());
        userRepository.save(user);

        String jwt = jwtService.generateToken(username, "USER");
        return AuthResponse.builder()
            .token(jwt)
            .username(username)
            .displayName(req.getDisplayName())
            .role("USER")
            .organizationType(orgType.name())
            .subscriptionStatus(subscriptionStatus)
            .build();
    }
}

// ══════════════════════════════════════════════════════════════════
// CONTROLLER
// ══════════════════════════════════════════════════════════════════

@RestController
@RequestMapping("/auth/magic-link")
@RequiredArgsConstructor
class MagicLinkController {

    private final MagicLinkService magicLinkService;

    @PostMapping("/send")
    public ResponseEntity<Map<String, String>> send(@RequestBody MagicLinkSendRequest req) {
        if (req.getEmail() == null || !req.getEmail().contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid email is required");
        }
        magicLinkService.sendCode(req.getEmail().toLowerCase().trim());
        return ResponseEntity.ok(Map.of("message", "Verification code sent"));
    }

    @PostMapping("/verify")
    public ResponseEntity<MagicLinkVerifyResponse> verify(@RequestBody MagicLinkVerifyRequest req) {
        MagicLinkVerifyResponse response = magicLinkService.verifyCode(
            req.getEmail().toLowerCase().trim(), req.getCode().trim());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/complete")
    public ResponseEntity<AuthResponse> complete(@RequestBody MagicLinkCompleteRequest req) {
        AuthResponse response = magicLinkService.completeSignup(req);
        return ResponseEntity.ok(response);
    }
}
