package com.emvagent.auth;

import jakarta.persistence.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
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

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// ══════════════════════════════════════════════════════════════════
// ENTITY
// ══════════════════════════════════════════════════════════════════

@Entity
@Table(name = "password_reset_tokens")
@Data
class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean used = false;
}

// ══════════════════════════════════════════════════════════════════
// REPOSITORY
// ══════════════════════════════════════════════════════════════════

@Repository
interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {
    Optional<PasswordResetToken> findByToken(String token);
    void deleteByUsername(String username);
}

// ══════════════════════════════════════════════════════════════════
// DTOs
// ══════════════════════════════════════════════════════════════════

@Data
class ForgotPasswordRequest {
    private String email;
}

@Data
class ResetPasswordRequest {
    private String token;
    private String newPassword;
}

// ══════════════════════════════════════════════════════════════════
// SERVICE
// ══════════════════════════════════════════════════════════════════

@Service
@RequiredArgsConstructor
@Slf4j
class PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final JavaMailSender mailSender;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Value("${app.base-url:http://localhost:3000}")
    private String baseUrl;

    @Value("${spring.mail.username:contact@fairbit.com}")
    private String fromEmail;

    @Transactional
    public void sendResetEmail(String email) {
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            // Don't reveal whether the email exists
            return;
        }
        User user = userOpt.get();

        // Invalidate any existing tokens
        tokenRepository.deleteByUsername(user.getUsername());

        String token = UUID.randomUUID().toString();
        PasswordResetToken prt = new PasswordResetToken();
        prt.setToken(token);
        prt.setUsername(user.getUsername());
        prt.setExpiresAt(Instant.now().plusSeconds(3600)); // 1 hour
        tokenRepository.save(prt);

        String resetLink = baseUrl + "/reset-password?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(email);
        message.setSubject("EMVAgent — Password Reset");
        message.setText(
            "Hello " + user.getUsername() + ",\n\n" +
            "You requested a password reset for your EMVAgent account.\n\n" +
            "Click the link below to set a new password (valid for 1 hour):\n" +
            resetLink + "\n\n" +
            "If you did not request this, please ignore this email.\n\n" +
            "— Fairbit EMVAgent Team"
        );

        try {
            mailSender.send(message);
            log.info("Password reset email sent to: {}", email);
        } catch (Exception e) {
            log.error("Failed to send password reset email: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to send reset email");
        }
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        PasswordResetToken prt = tokenRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired reset token"));

        if (prt.isUsed()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reset token has already been used");
        }
        if (prt.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reset token has expired");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters");
        }

        User user = userRepository.findByUsername(prt.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        prt.setUsed(true);
        tokenRepository.save(prt);

        log.info("Password reset successfully for user: {}", user.getUsername());
    }
}

// ══════════════════════════════════════════════════════════════════
// CONTROLLER
// ══════════════════════════════════════════════════════════════════

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j
class PasswordResetController {

    private final PasswordResetService passwordResetService;

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        passwordResetService.sendResetEmail(request.getEmail());
        return ResponseEntity.ok(Map.of("message", "If that email is registered, a reset link has been sent."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.getToken(), request.getNewPassword());
        return ResponseEntity.ok(Map.of("message", "Password updated successfully."));
    }
}
