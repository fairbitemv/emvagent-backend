package com.emvagent.feedback;

import jakarta.persistence.*;
import lombok.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/**
 * Pro "Feedback to the expert" widget — standalone, additive feature.
 *
 * Deliberately separate from {@link Feedback} (the per-message thumbs up/down
 * flow + review queue + BigQuery export). This file owns its own table
 * ({@code expert_feedback}, migration V11), endpoint ({@code /feedback/expert}),
 * and email alert. The existing feedback flow is untouched.
 */
@Entity
@Table(name = "expert_feedback")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
class ExpertFeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private Short score;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    // Optional context (null when no active answer on screen)
    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "user_question", columnDefinition = "text")
    private String userQuestion;

    @Column(name = "ai_answer", columnDefinition = "text")
    private String aiAnswer;

    @Column(name = "emv_tags", columnDefinition = "text")
    private String emvTags;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}

@Repository
interface ExpertFeedbackRepository extends JpaRepository<ExpertFeedbackEntity, UUID> {
}

@Getter
@Setter
class ExpertFeedbackRequest {
    private Integer score;          // 1..5, required
    private String message;         // required
    private String sessionId;       // optional context
    private String messageId;       // optional context
    private String userQuestion;    // optional context
    private String aiAnswer;        // optional context
    private String emvTags;         // optional context (human-readable list)
}

@Getter
@Setter
@Builder
class ExpertFeedbackResponse {
    private String id;
    private String status;
    private String message;
}

@Service
class ExpertFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(ExpertFeedbackService.class);

    private static final String[] RECIPIENTS = {
        "ozgur.altuntas@fairbit.com",
        "erdal.yazmaci@fairbit.com",
        "yusuf@fairbit.com"
    };

    private final ExpertFeedbackRepository repository;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:contact@fairbit.com}")
    private String fromEmail;

    ExpertFeedbackService(ExpertFeedbackRepository repository, JavaMailSender mailSender) {
        this.repository = repository;
        this.mailSender = mailSender;
    }

    ExpertFeedbackResponse submit(ExpertFeedbackRequest req, String username) {
        if (req.getScore() == null || req.getScore() < 1 || req.getScore() > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Score must be between 1 and 5");
        }
        if (req.getMessage() == null || req.getMessage().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Feedback message is required");
        }

        ExpertFeedbackEntity entity = ExpertFeedbackEntity.builder()
                .username(username)
                .score(req.getScore().shortValue())
                .message(req.getMessage().trim())
                .sessionId(parseUuid(req.getSessionId()))
                .messageId(parseUuid(req.getMessageId()))
                .userQuestion(emptyToNull(req.getUserQuestion()))
                .aiAnswer(emptyToNull(req.getAiAnswer()))
                .emvTags(emptyToNull(req.getEmvTags()))
                .build();

        ExpertFeedbackEntity saved = repository.save(entity);
        log.info("Expert feedback saved id={} user={} score={}", saved.getId(), username, saved.getScore());

        sendAlert(saved, username);

        return ExpertFeedbackResponse.builder()
                .id(saved.getId().toString())
                .status("RECEIVED")
                .message("Thank you for your feedback!")
                .build();
    }

    /** Emails the feedback to the team. Failure to send never blocks the save. */
    private void sendAlert(ExpertFeedbackEntity fb, String username) {
        try {
            StringBuilder body = new StringBuilder();
            body.append("New Pro feedback from ").append(username).append("\n\n");
            body.append("Score: ").append(fb.getScore()).append("/5\n\n");
            body.append("Comment:\n").append(fb.getMessage()).append("\n");

            boolean hasContext = fb.getUserQuestion() != null || fb.getAiAnswer() != null
                    || fb.getEmvTags() != null || fb.getSessionId() != null;
            if (hasContext) {
                body.append("\n--- Context ---\n");
                if (fb.getUserQuestion() != null)
                    body.append("User question:\n").append(fb.getUserQuestion()).append("\n\n");
                if (fb.getAiAnswer() != null)
                    body.append("AI answer:\n").append(fb.getAiAnswer()).append("\n\n");
                if (fb.getEmvTags() != null)
                    body.append("Decoded EMV tags: ").append(fb.getEmvTags()).append("\n");
                if (fb.getSessionId() != null)
                    body.append("Session: ").append(fb.getSessionId()).append("\n");
                if (fb.getMessageId() != null)
                    body.append("Message: ").append(fb.getMessageId()).append("\n");
            }
            body.append("\nFeedback ID: ").append(fb.getId());

            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom("Fairbit <" + fromEmail + ">");
            mail.setTo(RECIPIENTS);
            mail.setSubject("EMVAgent — Pro feedback (" + fb.getScore() + "/5) from " + username);
            mail.setText(body.toString());
            mailSender.send(mail);
            log.info("Expert feedback alert sent for id={}", fb.getId());
        } catch (Exception e) {
            log.error("Failed to send expert feedback alert: {}", e.getMessage());
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}

@RestController
@RequestMapping("/feedback/expert")
class ExpertFeedbackController {

    private final ExpertFeedbackService service;

    ExpertFeedbackController(ExpertFeedbackService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ExpertFeedbackResponse> submit(
            @RequestBody ExpertFeedbackRequest request,
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(service.submit(request, user.getUsername()));
    }
}
