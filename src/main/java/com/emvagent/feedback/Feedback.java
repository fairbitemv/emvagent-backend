package com.emvagent.feedback;

import com.emvagent.kafka.KafkaProducer;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.annotation.Value;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.InsertAllRequest;
import com.google.cloud.bigquery.TableResult;
import com.google.cloud.bigquery.InsertAllResponse;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableId;
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
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    @Query("SELECT MAX(f.reviewedAt) FROM FeedbackEntity f WHERE f.status = 'APPROVED' AND f.exportedToBigQuery = true")
    Optional<Instant> findLastExportedAt();
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
    private String userQuestion;     // populated by frontend on THUMBS_DOWN for email alert
    private String aiAnswer;         // populated by frontend on THUMBS_DOWN for email alert
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

@Data
@Builder
class LabelSummaryResponse {
    private Map<String, Long> labelDistribution;
    private long totalApproved;
    private long unexportedCount;
    private Instant lastExportAt;
}

@Data
@Builder
class RetrainResponse {
    private boolean triggered;
    private int exportedCount;
    private String triggeredBy;
    private Instant triggeredAt;
}

@Data
@Builder
class CsvUploadResponse {
    private int matched;
    private int unmatched;
    private int seedRows;
    private String labeledCsv;
    private String uploadedBy;
    private Instant uploadedAt;
}

@Data
@Builder
class BigQueryRecord {
    private String feedbackId;
    private String approvedLabel;
    private String correctedAnswer;
    private String emvTags;
    private String reviewer;
    private String approvedAt;
    private String exportedAt;
}

@Data
@Builder
class BigQueryPageResponse {
    private List<BigQueryRecord> items;
    private long totalCount;
    private int page;
    private int pageSize;
    @Builder.Default
    private boolean bigQueryConnected = true;
}

// ══════════════════════════════════════════════════════════════════
// SERVICE
// ══════════════════════════════════════════════════════════════════

@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
class FeedbackService {

    private static final java.util.regex.Pattern EMV_JSON_PATTERN =
            java.util.regex.Pattern.compile("\"(0x[0-9A-Fa-f]+)\"\\s*:\\s*\"([^\"]*)\"");

    private final FeedbackRepository feedbackRepository;
    private final KafkaProducer kafkaProducer;

    @Autowired(required = false)
    private BigQuery bigQuery;

    @Autowired
    private JavaMailSender mailSender;

    @Value("${spring.mail.username:contact@fairbit.com}")
    private String fromEmail;

    private static final String[] ALERT_RECIPIENTS = {
        "erdal.yazmaci@fairbit.com",
        "yusuf@fairbit.com",
        "ozgur.altuntas@fairbit.com"
    };

    @org.springframework.beans.factory.annotation.Value("${gcp.bigquery.dataset:emvagent}")
    private String bqDataset;

    @org.springframework.beans.factory.annotation.Value("${gcp.bigquery.project:emvagent-gcp}")
    private String bqProject;

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

        if (saved.getRating() == FeedbackEntity.Rating.THUMBS_DOWN) {
            sendThumbsDownAlert(saved, username, request.getUserQuestion(), request.getAiAnswer());
        }

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

    private void sendThumbsDownAlert(FeedbackEntity feedback, String username, String userQuestion, String aiAnswer) {
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom("Fairbit <" + fromEmail + ">");
            mail.setTo(ALERT_RECIPIENTS);
            mail.setSubject("EMVAgent — Thumbs Down from " + username);
            mail.setText(
                "User: " + username + "\n" +
                "Feedback ID: " + feedback.getId() + "\n\n" +
                "--- User Question ---\n" + (userQuestion != null ? userQuestion : "(not provided)") + "\n\n" +
                "--- AI Response ---\n" + (aiAnswer != null ? aiAnswer : "(not provided)") + "\n\n" +
                "Review at: https://chat.fairbit.com/expert"
            );
            mailSender.send(mail);
            log.info("Thumbs-down alert sent for feedback={} user={}", feedback.getId(), username);
        } catch (Exception e) {
            log.error("Failed to send thumbs-down alert: {}", e.getMessage());
        }
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

    public LabelSummaryResponse getLabelSummary() {
        List<Object[]> rows = feedbackRepository.countByApprovedLabel();
        Map<String, Long> distribution = new LinkedHashMap<>();
        for (Object[] row : rows) {
            distribution.put((String) row[0], (Long) row[1]);
        }
        long unexportedCount = feedbackRepository
                .findByStatusAndExportedToBigQueryFalse(FeedbackEntity.Status.APPROVED).size();

        return LabelSummaryResponse.builder()
                .labelDistribution(distribution)
                .totalApproved(feedbackRepository.countByStatus(FeedbackEntity.Status.APPROVED))
                .unexportedCount(unexportedCount)
                .lastExportAt(feedbackRepository.findLastExportedAt().orElse(null))
                .build();
    }

    public RetrainResponse triggerRetrain(String triggeredBy) {
        List<FeedbackEntity> unexported = feedbackRepository
                .findByStatusAndExportedToBigQueryFalse(FeedbackEntity.Status.APPROVED);

        if (bigQuery != null) {
            TableId tableId = TableId.of(bqProject, bqDataset, "feedback_approved");
            InsertAllRequest.Builder insertBuilder = InsertAllRequest.newBuilder(tableId);
            for (FeedbackEntity entity : unexported) {
                insertBuilder.addRow(entity.getId().toString(), Map.of(
                        "feedback_id",      entity.getId().toString(),
                        "message_id",       entity.getMessageId().toString(),
                        "username",         entity.getUsername(),
                        "approved_label",   entity.getApprovedLabel() != null ? entity.getApprovedLabel() : "",
                        "corrected_answer", entity.getCorrectedAnswer() != null ? entity.getCorrectedAnswer() : "",
                        "reviewer",         entity.getReviewedBy() != null ? entity.getReviewedBy() : "",
                        "reviewer_notes",   entity.getReviewerNotes() != null ? entity.getReviewerNotes() : "",
                        "approved_at",      entity.getReviewedAt() != null ? entity.getReviewedAt().toString() : Instant.now().toString(),
                        "exported_at",      Instant.now().toString()
                ));
            }
            InsertAllResponse bqResponse = bigQuery.insertAll(insertBuilder.build());
            if (bqResponse.hasErrors()) {
                bqResponse.getInsertErrors().forEach((idx, errors) ->
                        errors.forEach(e -> log.error("BigQuery insert error during manual retrain: {}", e.getMessage())));
            }
            // TODO: POST https://us-central1-aiplatform.googleapis.com/v1/projects/{project}/locations/us-central1/trainingPipelines
            log.info("Vertex AI retraining triggered manually with {} approved labels", unexported.size());
        } else {
            log.warn("BigQuery not available — skipping BQ export for manual retrain");
        }

        unexported.forEach(e -> e.setExportedToBigQuery(true));
        feedbackRepository.saveAll(unexported);

        log.info("Manual retrain triggered by {} — exported {} labels", triggeredBy, unexported.size());

        return RetrainResponse.builder()
                .triggered(true)
                .exportedCount(unexported.size())
                .triggeredBy(triggeredBy)
                .triggeredAt(Instant.now())
                .build();
    }

    public CsvUploadResponse uploadTrainingCsv(MultipartFile file, String uploadedBy) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty.");
        }

        List<CSVRecord> allRecords = new ArrayList<>();
        List<String> headers;
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            headers = parser.getHeaderNames();
            allRecords.addAll(parser.getRecords());
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse CSV: " + e.getMessage(), e);
        }

        boolean hasMergedColumn = headers.stream()
                .anyMatch(h -> normalize(h).equals("emv_tags"));
        List<String> tagColumns = headers.stream()
                .filter(h -> { String n = normalize(h); return n.startsWith("0x") || n.startsWith("0X"); })
                .toList();
        String jsonColumnName = headers.stream()
                .filter(h -> normalize(h).equalsIgnoreCase("emv data") || normalize(h).equalsIgnoreCase("emv_data"))
                .findFirst().orElse(null);
        boolean hasJsonColumn = jsonColumnName != null;

        if (!hasMergedColumn && tagColumns.isEmpty() && !hasJsonColumn) {
            throw new IllegalArgumentException(
                    "No EMV tag data found. Provide either an 'emv_tags' column, individual tag columns (e.g. 0x95, 0x9F66), or an 'EMV Data' JSON column. Found columns: " + headers + ".");
        }

        if (allRecords.isEmpty()) {
            throw new IllegalArgumentException("CSV has no data rows (header-only file).");
        }

        // Separate seed rows (have approved_label) from unlabeled rows
        // Cap to 500 seeds per label to avoid OOM on large all-labeled uploads
        record SeedLabel(String label, Map<String, String> tags) {}
        List<SeedLabel> seeds = new ArrayList<>();
        Map<String, Integer> seedCountPerLabel = new HashMap<>();
        for (CSVRecord rec : allRecords) {
            String label = rec.isMapped("approved_label") ? rec.get("approved_label").trim() : "";
            if (!label.isEmpty() && seedCountPerLabel.getOrDefault(label, 0) < 2000) {
                seeds.add(new SeedLabel(label, extractTagMap(rec, hasMergedColumn, tagColumns, hasJsonColumn, jsonColumnName)));
                seedCountPerLabel.merge(label, 1, Integer::sum);
            }
        }

        // Auto-label unlabeled rows
        String[] assignedLabels = new String[allRecords.size()];
        int matchedCount = 0;
        int unmatchedCount = 0;
        for (int i = 0; i < allRecords.size(); i++) {
            CSVRecord rec = allRecords.get(i);
            String existing = rec.isMapped("approved_label") ? rec.get("approved_label").trim() : "";
            if (!existing.isEmpty()) {
                assignedLabels[i] = existing;
                continue;
            }
            Map<String, String> rowTags = extractTagMap(rec, hasMergedColumn, tagColumns, hasJsonColumn, jsonColumnName);
            String bestLabel = null;
            double bestScore = 0.0;
            for (SeedLabel seed : seeds) {
                if (seed.tags().isEmpty()) continue;
                long overlap = seed.tags().entrySet().stream()
                        .filter(e -> e.getValue().equals(rowTags.get(e.getKey())))
                        .count();
                double score = (double) overlap / seed.tags().size();
                if (score > bestScore) {
                    bestScore = score;
                    bestLabel = seed.label();
                }
            }
            if (bestScore >= 0.5) {
                assignedLabels[i] = bestLabel;
                matchedCount++;
            } else {
                assignedLabels[i] = "";
                unmatchedCount++;
            }
        }

        Instant now = Instant.now();

        // Export matched + seed rows to BigQuery in batches of 500
        if (bigQuery != null) {
            TableId tableId = TableId.of(bqProject, bqDataset, "feedback_approved");
            InsertAllRequest.Builder insertBuilder = InsertAllRequest.newBuilder(tableId);
            int batchCount = 0;
            for (int i = 0; i < allRecords.size(); i++) {
                if (assignedLabels[i] == null || assignedLabels[i].isEmpty()) continue;
                CSVRecord rec = allRecords.get(i);
                String emvTagsStr = serializeTagMap(extractTagMap(rec, hasMergedColumn, tagColumns, hasJsonColumn, jsonColumnName));
                String insertId = UUID.randomUUID().toString();
                insertBuilder.addRow(insertId, Map.of(
                        "feedback_id",      insertId,
                        "approved_label",   assignedLabels[i],
                        "corrected_answer", rec.isMapped("corrected_answer") ? rec.get("corrected_answer") : "",
                        "emv_tags",         emvTagsStr,
                        "reviewer",         uploadedBy,
                        "approved_at",      now.toString(),
                        "exported_at",      now.toString()
                ));
                batchCount++;
                if (batchCount % 500 == 0) {
                    try {
                        InsertAllResponse batchResponse = bigQuery.insertAll(insertBuilder.build());
                        if (batchResponse.hasErrors()) {
                            batchResponse.getInsertErrors().forEach((idx, errors) ->
                                    errors.forEach(e -> log.error("BigQuery insert error during CSV upload: {}", e.getMessage())));
                        }
                    } catch (Exception e) {
                        log.warn("BigQuery batch insert failed during CSV upload: {}", e.getMessage());
                    }
                    insertBuilder = InsertAllRequest.newBuilder(tableId);
                }
            }
            if (batchCount % 500 != 0) {
                try {
                    InsertAllResponse bqResponse = bigQuery.insertAll(insertBuilder.build());
                    if (bqResponse.hasErrors()) {
                        bqResponse.getInsertErrors().forEach((idx, errors) ->
                                errors.forEach(e -> log.error("BigQuery insert error during CSV upload: {}", e.getMessage())));
                    }
                } catch (Exception e) {
                    log.warn("BigQuery final insert failed during CSV upload: {}", e.getMessage());
                }
            }
        } else {
            log.warn("BigQuery not available — skipping BQ export for CSV upload");
        }

        // Build labeled CSV string
        String labeledCsv;
        try (StringWriter sw = new StringWriter();
             CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT.builder()
                     .setHeader(headers.toArray(new String[0]))
                     .build())) {
            for (int i = 0; i < allRecords.size(); i++) {
                CSVRecord rec = allRecords.get(i);
                List<String> values = new ArrayList<>();
                for (String h : headers) {
                    if ("approved_label".equals(h)) {
                        values.add(assignedLabels[i] != null ? assignedLabels[i] : "");
                    } else {
                        values.add(rec.isMapped(h) ? rec.get(h) : "");
                    }
                }
                printer.printRecord(values);
            }
            printer.flush();
            labeledCsv = sw.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to build labeled CSV: " + e.getMessage(), e);
        }

        int exportedSeedRows = (int) Arrays.stream(assignedLabels)
                .filter(l -> l != null && !l.isEmpty())
                .count() - matchedCount;

        log.info("CSV upload by {} — exported={} matched={} unmatched={}", uploadedBy, exportedSeedRows, matchedCount, unmatchedCount);

        return CsvUploadResponse.builder()
                .matched(matchedCount)
                .unmatched(unmatchedCount)
                .seedRows(exportedSeedRows)
                .labeledCsv(labeledCsv)
                .uploadedBy(uploadedBy)
                .uploadedAt(now)
                .build();
    }

    private Map<String, String> extractTagMap(CSVRecord rec, boolean hasMergedColumn, List<String> tagColumns, boolean hasJsonColumn, String jsonColumnName) {
        if (hasMergedColumn) {
            return parseEmvTags(rec.isMapped("emv_tags") ? rec.get("emv_tags") : "");
        }
        if (hasJsonColumn && jsonColumnName != null) {
            return parseJsonEmvTags(rec.isMapped(jsonColumnName) ? rec.get(jsonColumnName) : "{}");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (String col : tagColumns) {
            String val = rec.isMapped(col) ? rec.get(col).trim() : "";
            if (!val.isEmpty()) tags.put(col, val);
        }
        return tags;
    }

    private Map<String, String> parseJsonEmvTags(String json) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return tags;
        var m = EMV_JSON_PATTERN.matcher(json);
        while (m.find()) tags.put(m.group(1), m.group(2));
        return tags;
    }

    private String normalize(String header) {
        return header == null ? "" : header.replace("\uFEFF", "").trim();
    }

    private String serializeTagMap(Map<String, String> tags) {
        return tags.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private Map<String, String> parseEmvTags(String emvTagsStr) {
        Map<String, String> tags = new HashMap<>();
        if (emvTagsStr == null || emvTagsStr.isBlank()) return tags;
        for (String pair : emvTagsStr.split(",")) {
            String[] kv = pair.trim().split("=", 2);
            if (kv.length == 2) tags.put(kv[0].trim(), kv[1].trim());
        }
        return tags;
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public BigQueryPageResponse getBigQueryRecords(int page, int size, String label, String reviewer) {
        if (bigQuery == null) {
            return BigQueryPageResponse.builder()
                    .items(List.of()).totalCount(0).page(page).pageSize(size)
                    .bigQueryConnected(false).build();
        }

        List<String> conditions = new ArrayList<>();
        if (label != null && !label.isBlank())    conditions.add("approved_label = '" + label.replace("'", "") + "'");
        if (reviewer != null && !reviewer.isBlank()) conditions.add("reviewer = '" + reviewer.replace("'", "") + "'");
        String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);

        String dataset = "`" + bqProject + "." + bqDataset + ".feedback_approved`";

        try {
            TableResult countResult = bigQuery.query(QueryJobConfiguration.newBuilder(
                    "SELECT COUNT(*) as total FROM " + dataset + " " + where).build());
            long total = countResult.iterateAll().iterator().next().get("total").getLongValue();

            TableResult dataResult = bigQuery.query(QueryJobConfiguration.newBuilder(
                    "SELECT feedback_id, approved_label, corrected_answer, emv_tags, reviewer, approved_at, exported_at " +
                    "FROM " + dataset + " " + where + " ORDER BY approved_at DESC " +
                    "LIMIT " + size + " OFFSET " + (long) page * size).build());

            List<BigQueryRecord> items = new ArrayList<>();
            for (FieldValueList row : dataResult.iterateAll()) {
                items.add(BigQueryRecord.builder()
                        .feedbackId(row.get("feedback_id").getStringValue())
                        .approvedLabel(row.get("approved_label").getStringValue())
                        .correctedAnswer(row.get("corrected_answer").isNull() ? "" : row.get("corrected_answer").getStringValue())
                        .emvTags(row.get("emv_tags").isNull() ? "" : row.get("emv_tags").getStringValue())
                        .reviewer(row.get("reviewer").isNull() ? "" : row.get("reviewer").getStringValue())
                        .approvedAt(row.get("approved_at").isNull() ? "" : row.get("approved_at").getStringValue())
                        .exportedAt(row.get("exported_at").isNull() ? "" : row.get("exported_at").getStringValue())
                        .build());
            }

            return BigQueryPageResponse.builder()
                    .items(items).totalCount(total).page(page).pageSize(size).build();
        } catch (Exception e) {
            log.error("BigQuery query failed: {}", e.getMessage());
            throw new RuntimeException("Failed to query BigQuery: " + e.getMessage(), e);
        }
    }

    public List<String> getDistinctLabels() {
        if (bigQuery == null) return List.of();
        String dataset = "`" + bqProject + "." + bqDataset + ".feedback_approved`";
        try {
            TableResult result = bigQuery.query(QueryJobConfiguration.newBuilder(
                    "SELECT DISTINCT approved_label FROM " + dataset +
                    " WHERE approved_label IS NOT NULL ORDER BY approved_label").build());
            List<String> labels = new ArrayList<>();
            for (FieldValueList row : result.iterateAll()) {
                labels.add(row.get("approved_label").getStringValue());
            }
            return labels;
        } catch (Exception e) {
            log.error("Failed to fetch distinct labels: {}", e.getMessage());
            return List.of();
        }
    }

    public void deleteFromBigQuery(String feedbackId) {
        if (bigQuery == null) throw new IllegalStateException("BigQuery not connected");
        deleteBulkFromBigQuery(List.of(feedbackId));
    }

    public void deleteBulkFromBigQuery(List<String> feedbackIds) {
        if (feedbackIds == null || feedbackIds.isEmpty()) return;
        if (bigQuery == null) throw new IllegalStateException("BigQuery not connected");
        String inClause = feedbackIds.stream()
                .map(id -> "'" + id.replace("'", "") + "'")
                .collect(java.util.stream.Collectors.joining(", "));
        String table = "`" + bqProject + "." + bqDataset + ".feedback_approved`";
        // DML DELETE is blocked on rows still in the streaming buffer (~90 min window).
        // CREATE OR REPLACE TABLE AS SELECT reads the table (allowed during buffering)
        // and atomically overwrites it, effectively deleting the excluded rows.
        String sql = "CREATE OR REPLACE TABLE " + table + " AS "
                + "SELECT * FROM " + table + " WHERE feedback_id NOT IN (" + inClause + ")";
        try {
            bigQuery.query(QueryJobConfiguration.newBuilder(sql).build());
            log.info("Bulk deleted {} feedback records from BigQuery", feedbackIds.size());
        } catch (Exception e) {
            log.error("Failed to bulk delete from BigQuery: {}", e.getMessage());
            throw new RuntimeException("Failed to bulk delete from BigQuery: " + e.getMessage(), e);
        }
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
    private final WebClient aiServiceClient;

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

    @GetMapping("/admin/label-summary")
    @PreAuthorize("hasAnyRole('EMV_EXPERT', 'ADMIN')")
    public ResponseEntity<LabelSummaryResponse> getLabelSummary() {
        return ResponseEntity.ok(feedbackService.getLabelSummary());
    }

    @PostMapping("/admin/trigger-retrain")
    @PreAuthorize("hasAnyRole('EMV_EXPERT', 'ADMIN')")
    public ResponseEntity<RetrainResponse> triggerRetrain(
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(feedbackService.triggerRetrain(user.getUsername()));
    }

    @PostMapping("/admin/start-training")
    @PreAuthorize("hasAnyRole('EMV_EXPERT', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> startTraining(
            @RequestBody Map<String, Object> params) {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = aiServiceClient.post()
                .uri("/api/v1/train")
                .bodyValue(params)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(300))
                .block();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/admin/bigquery-records")
    @PreAuthorize("hasAnyRole('EMV_EXPERT', 'ADMIN')")
    public ResponseEntity<BigQueryPageResponse> getBigQueryRecords(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String label,
            @RequestParam(required = false) String reviewer) {
        return ResponseEntity.ok(feedbackService.getBigQueryRecords(page, size, label, reviewer));
    }

    @GetMapping("/admin/bigquery-labels")
    @PreAuthorize("hasAnyRole('EMV_EXPERT', 'ADMIN')")
    public ResponseEntity<List<String>> getBigQueryLabels() {
        return ResponseEntity.ok(feedbackService.getDistinctLabels());
    }

    @DeleteMapping("/admin/bigquery-records/{feedbackId}")
    @PreAuthorize("hasAnyRole('EMV_EXPERT', 'ADMIN')")
    public ResponseEntity<Void> deleteBigQueryRecord(@PathVariable String feedbackId) {
        feedbackService.deleteFromBigQuery(feedbackId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/admin/bigquery-records")
    @PreAuthorize("hasAnyRole('EMV_EXPERT', 'ADMIN')")
    public ResponseEntity<Void> deleteBigQueryRecords(@RequestBody List<String> feedbackIds) {
        feedbackService.deleteBulkFromBigQuery(feedbackIds);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/upload-training-csv")
    @PreAuthorize("hasAnyRole('EMV_EXPERT', 'ADMIN')")
    public ResponseEntity<CsvUploadResponse> uploadTrainingCsv(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(feedbackService.uploadTrainingCsv(file, user.getUsername()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadInput(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
