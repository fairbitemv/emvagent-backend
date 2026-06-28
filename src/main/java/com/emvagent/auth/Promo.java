package com.emvagent.auth;

import jakarta.persistence.*;
import lombok.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Promo-code redemption — self-serve "N months free Professional" invite links.
 *
 * Standalone, additive. Does NOT touch Stripe in any way: it grants ACTIVE and
 * records the expiry on {@code users.promo_expires_at}, which is enforced lazily
 * at access time (see BillingService). Lives in the {@code auth} package because
 * the {@link User} entity / {@link UserRepository} are package-private.
 */
@Entity
@Table(name = "promo_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
class PromoCodeEntity {

    @Id
    private String code;

    @Column(name = "plan_status", nullable = false)
    private String planStatus;

    @Column(name = "duration_days", nullable = false)
    private Integer durationDays;

    @Column(name = "max_uses", nullable = false)
    private Integer maxUses;

    @Column(name = "used_count", nullable = false)
    private Integer usedCount;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}

@Entity
@Table(name = "promo_redemptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
class PromoRedemptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String username;

    @Column(name = "redeemed_at", nullable = false)
    private Instant redeemedAt;

    @PrePersist
    void prePersist() {
        if (redeemedAt == null) redeemedAt = Instant.now();
    }
}

@Repository
interface PromoCodeRepository extends JpaRepository<PromoCodeEntity, String> {
}

@Repository
interface PromoRedemptionRepository extends JpaRepository<PromoRedemptionEntity, UUID> {
    boolean existsByCodeAndUsername(String code, String username);
}

@Getter
@Setter
class PromoRedeemRequest {
    private String code;
}

@Getter
@Setter
@Builder
class PromoRedeemResponse {
    private String status;            // REDEEMED, ALREADY_ACTIVE
    private String message;
    private String subscriptionStatus;
    private Instant promoExpiresAt;
}

@Service
class PromoService {

    private static final Logger log = LoggerFactory.getLogger(PromoService.class);

    private final PromoCodeRepository promoCodeRepository;
    private final PromoRedemptionRepository redemptionRepository;
    private final UserRepository userRepository;

    PromoService(PromoCodeRepository promoCodeRepository,
                 PromoRedemptionRepository redemptionRepository,
                 UserRepository userRepository) {
        this.promoCodeRepository = promoCodeRepository;
        this.redemptionRepository = redemptionRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    PromoRedeemResponse redeem(String rawCode, String username) {
        if (rawCode == null || rawCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Promo code is required");
        }
        String code = rawCode.trim().toUpperCase();

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // Already a paying / lifetime / active user → don't downgrade or touch. No-op success.
        String current = user.getSubscriptionStatus();
        if ("LIFETIME".equals(current) || "ACTIVE".equals(current)) {
            return PromoRedeemResponse.builder()
                    .status("ALREADY_ACTIVE")
                    .message("Your account already has active access.")
                    .subscriptionStatus(current)
                    .promoExpiresAt(user.getPromoExpiresAt())
                    .build();
        }

        // Already redeemed this code before → idempotent success (no double grant).
        if (redemptionRepository.existsByCodeAndUsername(code, username)) {
            return PromoRedeemResponse.builder()
                    .status("ALREADY_ACTIVE")
                    .message("You have already redeemed this code.")
                    .subscriptionStatus(user.getSubscriptionStatus())
                    .promoExpiresAt(user.getPromoExpiresAt())
                    .build();
        }

        PromoCodeEntity promo = promoCodeRepository.findById(code)
                .filter(PromoCodeEntity::getActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid or inactive promo code"));

        if (promo.getUsedCount() >= promo.getMaxUses()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This promo code has reached its usage limit");
        }

        Instant expiresAt = Instant.now().plus(promo.getDurationDays(), ChronoUnit.DAYS);
        user.setSubscriptionStatus(promo.getPlanStatus() != null ? promo.getPlanStatus() : "ACTIVE");
        user.setPromoExpiresAt(expiresAt);
        userRepository.save(user);

        redemptionRepository.save(PromoRedemptionEntity.builder()
                .code(code)
                .username(username)
                .redeemedAt(Instant.now())
                .build());

        promo.setUsedCount(promo.getUsedCount() + 1);
        promoCodeRepository.save(promo);

        log.info("Promo {} redeemed by {} until {} (uses {}/{})",
                code, username, expiresAt, promo.getUsedCount(), promo.getMaxUses());

        return PromoRedeemResponse.builder()
                .status("REDEEMED")
                .message("Your 6-month Professional access is active.")
                .subscriptionStatus(user.getSubscriptionStatus())
                .promoExpiresAt(expiresAt)
                .build();
    }
}

@RestController
@RequestMapping("/promo")
class PromoController {

    private final PromoService promoService;

    PromoController(PromoService promoService) {
        this.promoService = promoService;
    }

    @PostMapping("/redeem")
    public ResponseEntity<PromoRedeemResponse> redeem(
            @RequestBody PromoRedeemRequest request,
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(promoService.redeem(request.getCode(), user.getUsername()));
    }
}
