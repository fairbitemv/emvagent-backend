package com.emvagent.chat;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// ══════════════════════════════════════════════════════════════════
// ENTITIES
// ══════════════════════════════════════════════════════════════════

@Entity
@Table(name = "chat_sessions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String username;

    private String organizationType;

    @Builder.Default
    private Instant startedAt = Instant.now();

    @Builder.Default
    private Instant lastActivityAt = Instant.now();

    @Builder.Default
    private int messageCount = 0;
}

@Entity
@Table(name = "chat_messages")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID sessionId;

    @Column(nullable = false)
    private String role; // USER, ASSISTANT

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    private String agentUsed;         // CAPTAIN, SPEC, DATA, DIAGNOSIS
    private Double confidenceScore;
    private String rootCauseDetected;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    private String emvTags;

    @Builder.Default
    private Instant createdAt = Instant.now();
}

// ══════════════════════════════════════════════════════════════════
// REPOSITORIES
// ══════════════════════════════════════════════════════════════════

@Repository
interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {
    List<ChatSession> findByUsernameOrderByLastActivityAtDesc(String username);
}

@Repository
interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {
    List<ChatMessage> findBySessionIdOrderByCreatedAt(UUID sessionId);

    @Query("SELECT m FROM ChatMessage m WHERE m.sessionId = :sessionId ORDER BY m.createdAt DESC LIMIT :limit")
    List<ChatMessage> findLastNBySessionId(@Param("sessionId") UUID sessionId,
                                           @Param("limit") int limit);
}

// ══════════════════════════════════════════════════════════════════
// DTOs
// ══════════════════════════════════════════════════════════════════

@Data
class ChatRequest {
    private String sessionId;   // null → create new session
    private String message;
    private Map<String, String> emvTags;  // optional raw EMV tags
}

@Data
@Builder
class ChatResponse {
    private String sessionId;
    private String messageId;
    private String answer;
    private String agentUsed;
    private Double confidence;
    private String rootCause;
    private List<String> recommendations;
    private Instant timestamp;
}

@Data
@Builder
class SessionHistoryResponse {
    private String sessionId;
    private List<MessageItem> messages;
    private Instant startedAt;
    private int totalMessages;
}

@Data
@Builder
class MessageItem {
    private String messageId;
    private String role;
    private String content;
    private String agentUsed;
    private String rootCauseDetected;
    private Instant createdAt;
}

// ══════════════════════════════════════════════════════════════════
// SERVICE
// ══════════════════════════════════════════════════════════════════

@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
class ChatService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final WebClient aiServiceClient;

    /**
     * Process a chat message.
     * 1. Get or create session
     * 2. Save user message
     * 3. Build context (last 10 messages)
     * 4. Call Python AI microservice (multi-agent pipeline)
     * 5. Save AI response
     * 6. Return response
     */
    public ChatResponse chat(ChatRequest request, String username) {

        // ── Step 1: Get or create session ─────────────────────────
        ChatSession session = getOrCreateSession(request.getSessionId(), username);

        // ── Step 2: Save user message ──────────────────────────────
        ChatMessage userMsg = ChatMessage.builder()
                .sessionId(session.getId())
                .role("USER")
                .content(request.getMessage())
                .emvTags(request.getEmvTags() != null ? request.getEmvTags().toString() : null)
                .build();
        messageRepository.save(userMsg);

        // ── Step 3: Build conversation context ────────────────────
        List<ChatMessage> history = messageRepository
                .findLastNBySessionId(session.getId(), 10);

        List<Map<String, String>> context = history.stream()
                .map(m -> Map.of("role", m.getRole().toLowerCase(), "content", m.getContent()))
                .toList();

        // ── Step 4: Call Python AI microservice ───────────────────
        AiResponse aiResult = callPythonAI(request.getMessage(), context, request.getEmvTags());

        // ── Step 5: Save AI response ──────────────────────────────
        ChatMessage aiMsg = ChatMessage.builder()
                .sessionId(session.getId())
                .role("ASSISTANT")
                .content(aiResult.getAnswer())
                .agentUsed(aiResult.getAgentUsed())
                .confidenceScore(aiResult.getConfidence())
                .rootCauseDetected(aiResult.getRootCause())
                .build();
        messageRepository.save(aiMsg);

        // ── Step 6: Update session ────────────────────────────────
        session.setLastActivityAt(Instant.now());
        session.setMessageCount(session.getMessageCount() + 2);
        sessionRepository.save(session);

        return ChatResponse.builder()
                .sessionId(session.getId().toString())
                .messageId(aiMsg.getId().toString())
                .answer(aiResult.getAnswer())
                .agentUsed(aiResult.getAgentUsed())
                .confidence(aiResult.getConfidence())
                .rootCause(aiResult.getRootCause())
                .recommendations(aiResult.getRecommendations())
                .timestamp(aiMsg.getCreatedAt())
                .build();
    }

    /**
     * Get session message history.
     */
    public SessionHistoryResponse getHistory(UUID sessionId, String username) {
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));

        if (!session.getUsername().equals(username)) {
            throw new RuntimeException("Access denied");
        }

        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAt(sessionId);

        List<MessageItem> items = messages.stream()
                .map(m -> MessageItem.builder()
                        .messageId(m.getId().toString())
                        .role(m.getRole())
                        .content(m.getContent())
                        .agentUsed(m.getAgentUsed())
                        .rootCauseDetected(m.getRootCauseDetected())
                        .createdAt(m.getCreatedAt())
                        .build())
                .toList();

        return SessionHistoryResponse.builder()
                .sessionId(sessionId.toString())
                .messages(items)
                .startedAt(session.getStartedAt())
                .totalMessages(messages.size())
                .build();
    }

    // ── Private helpers ───────────────────────────────────────────

    private ChatSession getOrCreateSession(String sessionId, String username) {
        if (sessionId != null) {
            return sessionRepository.findById(UUID.fromString(sessionId))
                    .orElseGet(() -> createSession(username));
        }
        return createSession(username);
    }

    private ChatSession createSession(String username) {
        return sessionRepository.save(
                ChatSession.builder().username(username).build()
        );
    }

    private AiResponse callPythonAI(String message,
                                     List<Map<String, String>> context,
                                     Map<String, String> emvTags) {
        try {
            return aiServiceClient
                    .post()
                    .uri("/api/v1/chat")
                    .bodyValue(Map.of(
                            "message",  message,
                            "context",  context,
                            "emv_tags", emvTags != null ? emvTags : Map.of()
                    ))
                    .retrieve()
                    .bodyToMono(AiResponse.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
        } catch (Exception e) {
            log.error("Python AI call failed: {}", e.getMessage());
            return AiResponse.builder()
                    .answer("AI service temporarily unavailable. Please try again.")
                    .agentUsed("FALLBACK")
                    .confidence(0.0)
                    .build();
        }
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    static class AiResponse {
        private String answer;
        private String agentUsed;
        private double confidence;
        private String rootCause;
        private List<String> recommendations;
    }
}

// ══════════════════════════════════════════════════════════════════
// CONTROLLER
// ══════════════════════════════════════════════════════════════════

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
class ChatController {

    private final ChatService chatService;

    @PostMapping("/message")
    public ResponseEntity<ChatResponse> sendMessage(
            @RequestBody ChatRequest request,
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(chatService.chat(request, user.getUsername()));
    }

    @GetMapping("/session/{sessionId}")
    public ResponseEntity<SessionHistoryResponse> getHistory(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(chatService.getHistory(sessionId, user.getUsername()));
    }
}
