package com.emvagent.feedback;

import com.emvagent.kafka.KafkaProducer;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.temporal.ChronoUnit;

// ══════════════════════════════════════════════════════════════════
// ENTITY
// ══════════════════════════════════════════════════════════════════

@Entity
@Table(name = "feedback")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class FeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID messageId;

    @Column(nullable = false)
    private UUID sessionId;

    @Column(nullable = false)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Rating rating;

    @Column(columnDefinition = "TEXT")
    private String correctedAnswer;

    private String correctedLabel;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(columnDefinition = "JSONB")
    private String emvTags;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Status status = Status.PENDING;

    private String reviewedBy;
    private Instant reviewedAt;
    private String approvedLabel;

    @Column(columnDefinition = "TEXT")
    private String reviewerNotes;

    @Builder.Default
    private boolean exportedToBigQuery = false;

    @Builder.Default
    private Instant submittedAt = Instant.now();

    enum Rating { THUMBS_UP, THUMBS_DOWN }
    enum Status { PENDING, APPROVED, REJECTED }
}

// ══════════════════════════════════════════════════════════════════
// REPOSITORY
// ══════════════════════════════════════════════════════════════════

@Repository
interface FeedbackRepository extends JpaRepository<FeedbackEntity, UUID> {
    List<FeedbackEntity> findByStatusOrderBySubmittedAtAsc(FeedbackEntity.Status status);
    long countByStatus(FeedbackEntity.Status status);
    List<FeedbackEntity> findByStatusAndExportedToBigQueryFalse(FeedbackEntity.Status status);

    @Query("SELECT COUNT(f) FROM FeedbackEntity f WHERE f.status = 'APPROVED' AND f.reviewedAt >= :since")
    long countApprovedSince(@Param("since") Instant since);

    @Query("SELECT f.approvedLabel, COUNT(f) FROM FeedbackEntity f WHERE f.status = 'APPROVED' GROUP BY f.approvedLabel ORDER BY COUNT(f) DESC")
    List<Object[]> countByApprovedLabel();
}

// ══════════════════════════════════════════════════════════════════
// DTOs
// ══════════════════════════════════════════════════════════════════

@Data
class FeedbackRequest {
    private String messageId;
    private String sessionId;
    private String rating;           // THUMBS_UP, THUMBS_DOWN
    private String correctedAnswer;
    private String correctedLabel;
    private String notes;
    private Map<String, String> emvTags;
}

@Data
class FeedbackReviewRequest {
    private String decision;         // APPROVE, REJECT
    private String approvedLabel;
    private String reviewerNotes;
}

@Data
@Builder
class FeedbackResponse {
    private String feedbackId;
    private String status;
    private String message;
    private Instant timestamp;
}

@Data
@Builder
class FeedbackQueueResponse {
    private List<FeedbackQueueItem> items;
    private long totalItems;
    private int page;
    private int pageSize;
    private long pendingCount;
    private long approvedToday;
}

@Data
@Builder
class FeedbackQueueItem {
    private String feedbackId;
    private String messageId;
    private String userCorrectedAnswer;
    private String userCorrectedLabel;
    private String userNotes;
    private String rating;
    private Map<String, String> emvTags;
    private String status;
    private Instant submittedAt;
}

// ══════════════════════════════════════════════════════════════════
// SERVICE
// ══════════════════════════════════════════════════════════════════

@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
class FeedbackService {

    private final FeedbackRepository feedbackRepository;
    private final KafkaProducer kafkaProducer;

    /**
     * User submits feedback on AI response.
     * Saves as PENDING → publishes to Kafka emv.feedback.raw
     */
    public FeedbackResponse submit(FeedbackRequest request, String username) {

        FeedbackEntity feedback = FeedbackEntity.builder()
                .messageId(UUID.fromString(request.getMessageId()))
                .sessionId(UUID.fromString(request.getSessionId()))
                .username(username)
                .rating(FeedbackEntity.Rating.valueOf(request.getRating()))
                .correctedAnswer(request.getCorrectedAnswer())
                .correctedLabel(request.getCorrectedLabel())
                .notes(request.getNotes())
                .emvTags(request.getEmvTags() != null ? request.getEmvTags().toString() : null)
                .build();

        FeedbackEntity saved = feedbackRepository.save(feedback);
        log.info("Feedback saved id={} user={}", saved.getId(), username);

        kafkaProducer.publishRawFeedback(Map.of(
                "feedback_id",     saved.getId().toString(),
                "message_id",      request.getMessageId(),
                "username",        username,
                "rating",          request.getRating(),
                "corrected_label", request.getCorrectedLabel() != null ? request.getCorrectedLabel() : "",
                "submitted_at",    saved.getSubmittedAt().toString()
        ));

        return FeedbackResponse.builder()
                .feedbackId(saved.getId().toString())
                .status("QUEUED_FOR_REVIEW")
                .message("Thank you. An EMV expert will review your feedback shortly.")
                .timestamp(saved.getSubmittedAt())
                .build();
    }

    /**
     * EMV Expert reviews feedback.
     * APPROVE → publishes to Kafka emv.feedback.approved → BigQuery → Vertex AI retraining
     */
    public FeedbackResponse review(UUID feedbackId, FeedbackReviewRequest request, String reviewer) {

        FeedbackEntity feedback = feedbackRepository.findById(feedbackId)
                .orElseThrow(() -> new RuntimeException("Feedback not found: " + feedbackId));

        if (feedback.getStatus() != FeedbackEntity.Status.PENDING) {
            throw new RuntimeException("Already reviewed: " + feedbackId);
        }

        boolean approved = "APPROVE".equals(request.getDecision());

        feedback.setStatus(approved ? FeedbackEntity.Status.APPROVED : FeedbackEntity.Status.REJECTED);
        feedback.setReviewedBy(reviewer);
        feedback.setReviewedAt(Instant.now());
        feedback.setReviewerNotes(request.getReviewerNotes());
        if (approved) feedback.setApprovedLabel(request.getApprovedLabel());

        feedbackRepository.save(feedback);
        log.info("Feedback reviewed id={} decision={} reviewer={}", feedbackId, request.getDecision(), reviewer);

        if (approved) {
            kafkaProducer.publishApprovedFeedback(Map.of(
                    "feedback_id",    feedbackId.toString(),
                    "approved_label", request.getApprovedLabel() != null ? request.getApprovedLabel() : "",
                    "reviewer",       reviewer,
                    "approved_at",    feedback.getReviewedAt().toString()
            ));
        }

        return FeedbackResponse.builder()
                .feedbackId(feedbackId.toString())
                .status(approved ? "APPROVED" : "REJECTED")
                .message(approved ? "Approved and queued for BigQuery export." : "Rejected.")
                .timestamp(feedback.getReviewedAt())
                .build();
    }

    /**
     * Get pending feedback queue for EMV Expert admin panel.
     */
    public FeedbackQueueResponse getQueue(int page, int size, String statusStr) {
        FeedbackEntity.Status status = FeedbackEntity.Status.valueOf(statusStr);
        List<FeedbackEntity> all = feedbackRepository.findByStatusOrderBySubmittedAtAsc(status);

        int start = page * size;
        int end   = Math.min(start + size, all.size());
        List<FeedbackEntity> paged = start < all.size() ? all.subList(start, end) : List.of();

        return FeedbackQueueResponse.builder()
                .items(paged.stream().map(this::toQueueItem).toList())
                .totalItems(all.size())
                .page(page)
                .pageSize(size)
                .pendingCount(feedbackRepository.countByStatus(FeedbackEntity.Status.PENDING))
                .approvedToday(feedbackRepository.countApprovedSince(
                        Instant.now().truncatedTo(ChronoUnit.DAYS)))
                .build();
    }

    public Map<String, Object> getStats() {
        return Map.of(
                "pending",  feedbackRepository.countByStatus(FeedbackEntity.Status.PENDING),
                "approved", feedbackRepository.countByStatus(FeedbackEntity.Status.APPROVED),
                "rejected", feedbackRepository.countByStatus(FeedbackEntity.Status.REJECTED)
        );
    }

    private FeedbackQueueItem toQueueItem(FeedbackEntity f) {
        return FeedbackQueueItem.builder()
                .feedbackId(f.getId().toString())
                .messageId(f.getMessageId().toString())
                .userCorrectedAnswer(f.getCorrectedAnswer())
                .userCorrectedLabel(f.getCorrectedLabel())
                .userNotes(f.getNotes())
                .rating(f.getRating().name())
                .status(f.getStatus().name())
                .submittedAt(f.getSubmittedAt())
                .build();
    }
}

// ══════════════════════════════════════════════════════════════════
// CONTROLLER
// ══════════════════════════════════════════════════════════════════

@RestController
@RequestMapping("/feedback")
@RequiredArgsConstructor
class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping
    public ResponseEntity<FeedbackResponse> submit(
            @RequestBody FeedbackRequest request,
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(feedbackService.submit(request, user.getUsername()));
    }

    @GetMapping("/admin/queue")
    @PreAuthorize("hasAnyRole('EMV_EXPERT', 'ADMIN')")
    public ResponseEntity<FeedbackQueueResponse> getQueue(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "PENDING") String status) {
        return ResponseEntity.ok(feedbackService.getQueue(page, size, status));
    }

    @PutMapping("/admin/{feedbackId}/review")
    @PreAuthorize("hasAnyRole('EMV_EXPERT', 'ADMIN')")
    public ResponseEntity<FeedbackResponse> review(
            @PathVariable UUID feedbackId,
            @RequestBody FeedbackReviewRequest request,
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(feedbackService.review(feedbackId, request, user.getUsername()));
    }

    @GetMapping("/admin/stats")
    @PreAuthorize("hasAnyRole('EMV_EXPERT', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(feedbackService.getStats());
    }
}
