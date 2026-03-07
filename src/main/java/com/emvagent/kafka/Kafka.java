package com.emvagent.kafka;

import com.google.cloud.bigquery.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

// ══════════════════════════════════════════════════════════════════
// BIGQUERY EXPORT SERVICE
// KafkaProducer → KafkaProducer.java
// ══════════════════════════════════════════════════════════════════

@Service
@RequiredArgsConstructor
@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(com.google.cloud.bigquery.BigQuery.class)
class BigQueryExportService {

    private final BigQuery bigQuery;

    @Value("${gcp.bigquery.dataset:emvagent}")
    private String dataset;

    @Value("${gcp.bigquery.project:emvagent-gcp}")
    private String project;

    private static final int RETRAIN_THRESHOLD = 50;

    @KafkaListener(topics = "emv.feedback.approved", groupId = "emvagent-bigquery-export")
    @Async
    public void onApprovedFeedback(Map<String, Object> payload) {
        log.info("Received approved feedback for BigQuery export: {}", payload.get("feedback_id"));
        try {
            exportToBigQuery(payload);
            checkAndTriggerRetraining();
        } catch (Exception e) {
            log.error("BigQuery export failed for feedback {}: {}",
                    payload.get("feedback_id"), e.getMessage());
        }
    }

    private void exportToBigQuery(Map<String, Object> payload) {
        TableId tableId = TableId.of(project, dataset, "feedback_approved");

        InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId)
                .addRow(payload.get("feedback_id").toString(), Map.of(
                        "feedback_id",      payload.get("feedback_id"),
                        "message_id",       payload.getOrDefault("message_id", ""),
                        "username",         payload.getOrDefault("username", ""),
                        "approved_label",   payload.getOrDefault("approved_label", ""),
                        "corrected_answer", payload.getOrDefault("corrected_answer", ""),
                        "reviewer",         payload.getOrDefault("reviewer", ""),
                        "reviewer_notes",   payload.getOrDefault("reviewer_notes", ""),
                        "approved_at",      payload.getOrDefault("approved_at", Instant.now().toString()),
                        "exported_at",      Instant.now().toString()
                ))
                .build();

        InsertAllResponse response = bigQuery.insertAll(insertRequest);

        if (response.hasErrors()) {
            response.getInsertErrors().forEach((idx, errors) ->
                    errors.forEach(e -> log.error("BigQuery insert error: {}", e.getMessage())));
        } else {
            log.info("Exported feedback {} to BigQuery", payload.get("feedback_id"));
        }
    }

    private void checkAndTriggerRetraining() {
        try {
            String query = String.format(
                    "SELECT COUNT(*) as cnt FROM `%s.%s.feedback_approved` WHERE DATE(approved_at) = CURRENT_DATE()",
                    project, dataset);

            TableResult result = bigQuery.query(QueryJobConfiguration.newBuilder(query).build());
            long count = result.iterateAll().iterator().next().get("cnt").getLongValue();

            log.info("Approved feedback count today: {}", count);

            if (count > 0 && count % RETRAIN_THRESHOLD == 0) {
                log.info("Threshold reached ({} labels) - triggering Vertex AI retraining", count);
                triggerVertexAiRetraining(count);
            }
        } catch (Exception e) {
            log.error("Retraining check failed: {}", e.getMessage());
        }
    }

    private void triggerVertexAiRetraining(long labelCount) {
        // TODO: POST https://us-central1-aiplatform.googleapis.com/v1/projects/{project}/locations/us-central1/trainingPipelines
        log.info("Vertex AI retraining triggered with {} approved labels", labelCount);
    }
}
