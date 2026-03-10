package com.emvagent.auth;

import com.google.gson.JsonParser;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.*;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;

// ══════════════════════════════════════════════════════════════════
// CONFIG
// ══════════════════════════════════════════════════════════════════

@Configuration
@EnableConfigurationProperties(BillingConfig.class)
class BillingConfiguration {
}

@ConfigurationProperties(prefix = "billing")
@Data
class BillingConfig {
    private int dailyLimit = 50;
    private int weeklyLimit = 200;
}

// ══════════════════════════════════════════════════════════════════
// DTOs
// ══════════════════════════════════════════════════════════════════

@Data
class CheckoutRequest {
    private String type; // "subscription" or "payment"
}

@Data
class BillingStatus {
    private String subscriptionStatus;
    private int dailyChatCount;
    private int weeklyChatCount;
    private int dailyLimit;
    private int weeklyLimit;
    private int bonusCredits;
    private Instant subscriptionExpiresAt;
    private boolean cancelAtPeriodEnd;
}

// ══════════════════════════════════════════════════════════════════
// SERVICE
// ══════════════════════════════════════════════════════════════════

@Service
@RequiredArgsConstructor
@Slf4j
class BillingService implements UsageCheckService {

    private final UserRepository userRepository;
    private final BillingConfig billingConfig;

    @Value("${stripe.secret-key}")
    private String secretKey;

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    @Value("${stripe.price-id-monthly}")
    private String priceIdMonthly;

    @Value("${stripe.price-id-credits}")
    private String priceIdCredits;

    @Value("${app.base-url:http://localhost:3000}")
    private String baseUrl;

    private void initStripe() {
        Stripe.apiKey = secretKey;
    }

    /**
     * Create or reuse Stripe Customer for the user, then create a Checkout Session.
     * mode = "subscription" or "payment"
     */
    @Transactional
    public String createCheckoutSession(String username, String mode) {
        initStripe();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        try {
            String customerId = ensureStripeCustomer(user);

            if ("subscription".equals(mode)) {
                SessionCreateParams params = SessionCreateParams.builder()
                        .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                        .setCustomer(customerId)
                        .addLineItem(SessionCreateParams.LineItem.builder()
                                .setPrice(priceIdMonthly)
                                .setQuantity(1L)
                                .build())
                        .setSuccessUrl(baseUrl + "/billing/success?session_id={CHECKOUT_SESSION_ID}")
                        .setCancelUrl(baseUrl + "/billing/cancel")
                        .putMetadata("username", username)
                        .putMetadata("mode", "subscription")
                        .build();
                Session session = Session.create(params);
                return session.getUrl();
            } else {
                // one-time credits purchase
                SessionCreateParams params = SessionCreateParams.builder()
                        .setMode(SessionCreateParams.Mode.PAYMENT)
                        .setCustomer(customerId)
                        .addLineItem(SessionCreateParams.LineItem.builder()
                                .setPrice(priceIdCredits)
                                .setQuantity(1L)
                                .build())
                        .setSuccessUrl(baseUrl + "/billing/success?session_id={CHECKOUT_SESSION_ID}")
                        .setCancelUrl(baseUrl + "/billing/cancel")
                        .putMetadata("username", username)
                        .putMetadata("mode", "payment")
                        .build();
                Session session = Session.create(params);
                return session.getUrl();
            }
        } catch (StripeException e) {
            log.error("Stripe checkout session creation failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create checkout session");
        }
    }

    /**
     * Create a Stripe Customer Portal session for subscription management.
     */
    @Transactional
    public String createPortalSession(String username) {
        initStripe();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (user.getStripeCustomerId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No Stripe customer found");
        }

        try {
            com.stripe.param.billingportal.SessionCreateParams params =
                    com.stripe.param.billingportal.SessionCreateParams.builder()
                            .setCustomer(user.getStripeCustomerId())
                            .setReturnUrl(baseUrl + "/billing")
                            .build();
            com.stripe.model.billingportal.Session session =
                    com.stripe.model.billingportal.Session.create(params);
            return session.getUrl();
        } catch (StripeException e) {
            log.error("Stripe portal session creation failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create portal session");
        }
    }

    /**
     * Handle incoming Stripe webhook events.
     */
    @Transactional
    public void handleWebhook(String payload, String sigHeader) {
        initStripe();
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Webhook signature verification failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook signature");
        }

        log.info("Received Stripe webhook: {}", event.getType());

        // Extract object ID from raw payload and fetch fresh from Stripe API.
        // This avoids SDK deserialization failures when the webhook's API version
        // doesn't match the SDK's expected schema (getObject() returns empty Optional).
        try {
            String objectId = JsonParser.parseString(payload)
                    .getAsJsonObject()
                    .getAsJsonObject("data")
                    .getAsJsonObject("object")
                    .get("id").getAsString();

            switch (event.getType()) {
                case "checkout.session.completed" -> {
                    Session session = Session.retrieve(objectId);
                    String username = session.getMetadata().get("username");
                    String mode = session.getMetadata().get("mode");
                    if ("subscription".equals(mode)) {
                        handleSubscriptionActivated(username, session.getSubscription());
                    } else if ("payment".equals(mode)) {
                        handleCreditsAdded(username);
                    }
                }
                case "customer.subscription.deleted" -> {
                    Subscription sub = Subscription.retrieve(objectId);
                    handleSubscriptionDeleted(sub);
                }
                case "customer.subscription.updated" -> {
                    Subscription sub = Subscription.retrieve(objectId);
                    handleSubscriptionUpdated(sub);
                }
                case "invoice.payment_failed" -> {
                    Invoice invoice = Invoice.retrieve(objectId);
                    handlePaymentFailed(invoice);
                }
                default -> log.debug("Unhandled event type: {}", event.getType());
            }
        } catch (StripeException e) {
            log.error("Stripe API call failed during webhook processing: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Webhook processing failed");
        }
    }

    /**
     * Get billing status for a user.
     */
    public BillingStatus getStatus(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        BillingStatus status = new BillingStatus();
        status.setSubscriptionStatus(user.getSubscriptionStatus());
        status.setDailyChatCount(user.getDailyChatCount());
        status.setWeeklyChatCount(user.getWeeklyChatCount());
        status.setDailyLimit(billingConfig.getDailyLimit());
        status.setWeeklyLimit(billingConfig.getWeeklyLimit());
        status.setBonusCredits(user.getBonusCredits());
        status.setSubscriptionExpiresAt(user.getSubscriptionExpiresAt());
        status.setCancelAtPeriodEnd(user.isCancelAtPeriodEnd());
        return status;
    }

    /**
     * Subscription + usage gate called by ChatService before processing.
     */
    @Override
    @Transactional
    public void checkAndIncrementUsage(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (!"ACTIVE".equals(user.getSubscriptionStatus())) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "{\"error\":\"SUBSCRIPTION_REQUIRED\"}");
        }

        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime startOfToday = now.toLocalDate().atStartOfDay(ZoneOffset.UTC);
        if (user.getDailyResetAt().isBefore(startOfToday.toInstant())) {
            user.setDailyChatCount(0);
            user.setDailyResetAt(startOfToday.toInstant());
        }

        ZonedDateTime startOfWeek = now.toLocalDate()
                .with(DayOfWeek.MONDAY).atStartOfDay(ZoneOffset.UTC);
        if (user.getWeeklyResetAt().isBefore(startOfWeek.toInstant())) {
            user.setWeeklyChatCount(0);
            user.setWeeklyResetAt(startOfWeek.toInstant());
        }

        int daily = billingConfig.getDailyLimit();
        int weekly = billingConfig.getWeeklyLimit();

        if (user.getDailyChatCount() >= daily && user.getBonusCredits() == 0) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "{\"error\":\"DAILY_LIMIT_EXCEEDED\"}");
        }

        if (user.getWeeklyChatCount() >= weekly && user.getBonusCredits() == 0) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "{\"error\":\"WEEKLY_LIMIT_EXCEEDED\"}");
        }

        // Deduct bonus credit if over regular limit
        boolean overLimit = user.getDailyChatCount() >= daily || user.getWeeklyChatCount() >= weekly;
        if (overLimit) {
            user.setBonusCredits(Math.max(0, user.getBonusCredits() - 1));
        }

        user.setDailyChatCount(user.getDailyChatCount() + 1);
        user.setWeeklyChatCount(user.getWeeklyChatCount() + 1);
        userRepository.save(user);
    }

    // ── Private helpers ───────────────────────────────────────────

    private String ensureStripeCustomer(User user) throws StripeException {
        if (user.getStripeCustomerId() != null) {
            return user.getStripeCustomerId();
        }
        CustomerCreateParams params = CustomerCreateParams.builder()
                .setEmail(user.getEmail())
                .setName(user.getUsername())
                .putMetadata("username", user.getUsername())
                .build();
        Customer customer = Customer.create(params);
        user.setStripeCustomerId(customer.getId());
        userRepository.save(user);
        return customer.getId();
    }

    private void handleSubscriptionActivated(String username, String subscriptionId) {
        userRepository.findByUsername(username).ifPresent(user -> {
            try {
                Stripe.apiKey = secretKey;
                Subscription sub = Subscription.retrieve(subscriptionId);
                long periodEnd = sub.getCurrentPeriodEnd();
                user.setStripeSubscriptionId(subscriptionId);
                user.setSubscriptionStatus("ACTIVE");
                user.setSubscriptionExpiresAt(Instant.ofEpochSecond(periodEnd));
                userRepository.save(user);
                log.info("Subscription activated for user: {}", username);
            } catch (StripeException e) {
                log.error("Failed to retrieve subscription details: {}", e.getMessage());
                user.setStripeSubscriptionId(subscriptionId);
                user.setSubscriptionStatus("ACTIVE");
                userRepository.save(user);
            }
        });
    }

    private void handleCreditsAdded(String username) {
        userRepository.findByUsername(username).ifPresent(user -> {
            user.setBonusCredits(user.getBonusCredits() + 100);
            userRepository.save(user);
            log.info("Added 100 bonus credits to user: {}", username);
        });
    }

    private void handleSubscriptionDeleted(Subscription sub) {
        userRepository.findByStripeSubscriptionId(sub.getId()).ifPresent(user -> {
            user.setSubscriptionStatus("INACTIVE");
            user.setSubscriptionExpiresAt(null);
            user.setCancelAtPeriodEnd(false);
            userRepository.save(user);
            log.info("Subscription cancelled for user: {}", user.getUsername());
        });
    }

    private void handleSubscriptionUpdated(Subscription sub) {
        userRepository.findByStripeSubscriptionId(sub.getId()).ifPresent(user -> {
            String status = mapStripeStatus(sub.getStatus());
            user.setSubscriptionStatus(status);
            user.setCancelAtPeriodEnd(Boolean.TRUE.equals(sub.getCancelAtPeriodEnd()));
            if (sub.getCurrentPeriodEnd() != null) {
                user.setSubscriptionExpiresAt(Instant.ofEpochSecond(sub.getCurrentPeriodEnd()));
            }
            userRepository.save(user);
            log.info("Subscription updated for user: {} status: {} cancelAtPeriodEnd: {}",
                    user.getUsername(), status, user.isCancelAtPeriodEnd());
        });
    }

    private void handlePaymentFailed(Invoice invoice) {
        if (invoice.getSubscription() == null) return;
        userRepository.findByStripeSubscriptionId(invoice.getSubscription()).ifPresent(user -> {
            user.setSubscriptionStatus("PAST_DUE");
            userRepository.save(user);
            log.warn("Payment failed for user: {}", user.getUsername());
        });
    }

    private String mapStripeStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "active" -> "ACTIVE";
            case "past_due" -> "PAST_DUE";
            case "canceled", "cancelled" -> "INACTIVE";
            case "trialing" -> "ACTIVE";
            default -> "INACTIVE";
        };
    }
}

// ══════════════════════════════════════════════════════════════════
// CONTROLLER
// ══════════════════════════════════════════════════════════════════

@RestController
@RequestMapping("/billing")
@RequiredArgsConstructor
@Slf4j
class BillingController {

    private final BillingService billingService;

    @PostMapping("/checkout")
    public ResponseEntity<Map<String, String>> createCheckout(
            @RequestBody CheckoutRequest request,
            @AuthenticationPrincipal UserDetails user) {
        String url = billingService.createCheckoutSession(user.getUsername(), request.getType());
        return ResponseEntity.ok(Map.of("url", url));
    }

    @PostMapping("/portal")
    public ResponseEntity<Map<String, String>> createPortal(
            @AuthenticationPrincipal UserDetails user) {
        String url = billingService.createPortalSession(user.getUsername());
        return ResponseEntity.ok(Map.of("url", url));
    }

    @GetMapping("/status")
    public ResponseEntity<BillingStatus> getStatus(
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(billingService.getStatus(user.getUsername()));
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {
        billingService.handleWebhook(payload, sigHeader);
        return ResponseEntity.ok().build();
    }
}
