package com.emvagent.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishRawFeedback(Map<String, Object> payload) {
        String key = payload.get("feedback_id").toString();
        kafkaTemplate.send("emv.feedback.raw", key, payload);
        log.info("Published raw feedback: {}", key);
    }

    public void publishApprovedFeedback(Map<String, Object> payload) {
        String key = payload.get("feedback_id").toString();
        kafkaTemplate.send("emv.feedback.approved", key, payload);
        log.info("Published approved feedback: {}", key);
    }

    public void publishDiagnosisEvent(Map<String, Object> payload) {
        String key = payload.getOrDefault("diagnosis_id", UUID.randomUUID().toString()).toString();
        kafkaTemplate.send("emv.diagnosis.events", key, payload);
    }
}
