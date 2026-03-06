package com.emvagent.diagnosis;

import lombok.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

// ══════════════════════════════════════════════════════════════════
// DTOs
// ══════════════════════════════════════════════════════════════════

@Data
class DiagnosisRequest {
    private Map<String, String> emvTags;
    private String cardBrand;
    private String entryMode;
    private Long amountCents;
    private String merchantCategory;
}

@Data
@Builder
class DiagnosisResponse {
    private String diagnosisId;
    private String rootCause;
    private double confidence;
    private String explanation;
    private List<String> tvrFlags;
    private String cidDecoded;
    private String aipDecoded;
    private String cvmDecoded;
    private String ttqDecoded;
    private List<String> recommendations;
    private String severity;           // LOW, MEDIUM, HIGH, CRITICAL
    private String emvSpecReference;
    private Instant timestamp;
}

// ══════════════════════════════════════════════════════════════════
// SERVICE
// ══════════════════════════════════════════════════════════════════

@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
class DiagnosisService {

    private final WebClient aiServiceClient;

    /**
     * Diagnose EMV transaction from raw tags.
     *
     * Flow:
     *  1. Decode known EMV tags locally (fast, deterministic)
     *  2. Call Python AI microservice → Vertex AI AutoML model
     *  3. Return combined local decode + ML diagnosis
     */
    public DiagnosisResponse diagnose(DiagnosisRequest request) {

        // Step 1: Local decode
        Map<String, String> decoded = decodeLocally(request.getEmvTags());

        // Step 2: Python AI call
        AiResult aiResult = callPythonAI(request, decoded);

        return DiagnosisResponse.builder()
                .diagnosisId(UUID.randomUUID().toString())
                .rootCause(aiResult.getRootCause())
                .confidence(aiResult.getConfidence())
                .explanation(aiResult.getExplanation())
                .tvrFlags(decoded.get("tvr_flags") != null
                        ? Arrays.asList(decoded.get("tvr_flags").split(",")) : List.of())
                .cidDecoded(decoded.get("cid"))
                .aipDecoded(decoded.get("aip"))
                .cvmDecoded(decoded.get("cvm"))
                .ttqDecoded(decoded.get("ttq"))
                .recommendations(aiResult.getRecommendations())
                .severity(aiResult.getSeverity())
                .emvSpecReference(aiResult.getSpecReference())
                .timestamp(Instant.now())
                .build();
    }

    // ── Local EMV tag decoder ─────────────────────────────────────

    private Map<String, String> decodeLocally(Map<String, String> tags) {
        Map<String, String> result = new HashMap<>();
        if (tags == null) return result;

        // TVR (95) — Terminal Verification Results
        String tvr = tags.getOrDefault("0x95", tags.get("95"));
        if (tvr != null && tvr.length() == 10) {
            result.put("tvr_flags", decodeTvr(tvr));
        }

        // CID (9F27) — Cryptogram Information Data
        String cid = tags.getOrDefault("0x9F27", tags.get("9F27"));
        if (cid != null) {
            int cidVal = Integer.parseInt(cid, 16);
            result.put("cid", switch ((cidVal >> 6) & 0x03) {
                case 0 -> "AAC (Declined offline)";
                case 1 -> "TC (Approved offline)";
                case 2 -> "ARQC (Online requested)";
                default -> "Unknown";
            });
        }

        // AIP (82) — Application Interchange Profile
        String aip = tags.getOrDefault("0x82", tags.get("82"));
        if (aip != null && aip.length() >= 2) {
            int b1 = Integer.parseInt(aip.substring(0, 2), 16);
            List<String> flags = new ArrayList<>();
            if ((b1 & 0x40) != 0) flags.add("SDA");
            if ((b1 & 0x20) != 0) flags.add("DDA");
            if ((b1 & 0x10) != 0) flags.add("CVM_Supported");
            if ((b1 & 0x08) != 0) flags.add("Terminal_Risk_Mgmt");
            if ((b1 & 0x04) != 0) flags.add("Issuer_Auth");
            if ((b1 & 0x01) != 0) flags.add("CDA");
            result.put("aip", flags.isEmpty() ? "None" : String.join(" | ", flags));
        }

        // CVM Result (9F34)
        String cvm = tags.getOrDefault("0x9F34", tags.get("9F34"));
        if (cvm != null && cvm.length() >= 2) {
            result.put("cvm", switch (cvm.substring(0, 2).toUpperCase()) {
                case "1F", "5F" -> "No_CVM_Required";
                case "1E", "5E" -> "Signature";
                case "02"       -> "Online_PIN_Enciphered";
                case "01"       -> "Offline_PIN_Plaintext";
                case "04"       -> "Offline_PIN_Enciphered";
                default         -> "CVM_0x" + cvm.substring(0, 2).toUpperCase();
            });
        }

        // TTQ (9F66) — Terminal Transaction Qualifiers
        String ttq = tags.getOrDefault("0x9F66", tags.get("9F66"));
        if (ttq != null && ttq.length() >= 8) {
            int b1 = Integer.parseInt(ttq.substring(0, 2), 16);
            int b2 = Integer.parseInt(ttq.substring(2, 4), 16);
            List<String> flags = new ArrayList<>();
            if ((b1 & 0x80) != 0) flags.add("MSD_Supported");
            if ((b1 & 0x20) != 0) flags.add("EMV_Mode");
            if ((b1 & 0x10) != 0) flags.add("CVM_Required_Above_Limit");
            if ((b2 & 0x80) != 0) flags.add("Online_PIN_Capable");
            if ((b2 & 0x40) != 0) flags.add("Signature_Capable");
            result.put("ttq", String.join(" | ", flags));
        }

        return result;
    }

    private String decodeTvr(String tvrHex) {
        List<String> flags = new ArrayList<>();
        long tvr = Long.parseLong(tvrHex, 16);
        int b1 = (int)((tvr >> 32) & 0xFF);
        int b2 = (int)((tvr >> 24) & 0xFF);
        int b3 = (int)((tvr >> 16) & 0xFF);
        int b4 = (int)((tvr >> 8)  & 0xFF);
        int b5 = (int)(tvr & 0xFF);

        if ((b1 & 0x80) != 0) flags.add("Offline_Auth_Failed");
        if ((b1 & 0x08) != 0) flags.add("DDA_Failed");
        if ((b2 & 0x40) != 0) flags.add("Expired_App");
        if ((b3 & 0x80) != 0) flags.add("CVM_Required_Unperformed");
        if ((b3 & 0x20) != 0) flags.add("PIN_Try_Limit_Exceeded");
        if ((b3 & 0x08) != 0) flags.add("PIN_Entry_Bypassed");
        if ((b3 & 0x04) != 0) flags.add("Online_PIN_Entered");
        if ((b4 & 0x80) != 0) flags.add("Floor_Limit_Exceeded");
        if ((b5 & 0x40) != 0) flags.add("Issuer_Auth_Failed");

        return flags.isEmpty() ? "No_Issues" : String.join(",", flags);
    }

    // ── Python AI call ────────────────────────────────────────────

    private AiResult callPythonAI(DiagnosisRequest request, Map<String, String> decoded) {
        try {
            return aiServiceClient
                    .post()
                    .uri("/diagnose")
                    .bodyValue(Map.of(
                            "emv_tags",        request.getEmvTags() != null ? request.getEmvTags() : Map.of(),
                            "decoded_tags",    decoded,
                            "card_brand",      request.getCardBrand() != null ? request.getCardBrand() : "",
                            "entry_mode",      request.getEntryMode() != null ? request.getEntryMode() : "",
                            "amount_cents",    request.getAmountCents() != null ? request.getAmountCents() : 0,
                            "merchant_category", request.getMerchantCategory() != null ? request.getMerchantCategory() : ""
                    ))
                    .retrieve()
                    .bodyToMono(AiResult.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
        } catch (Exception e) {
            log.error("Python AI diagnose failed: {}", e.getMessage());
            return AiResult.builder()
                    .rootCause("AI service unavailable")
                    .confidence(0.0)
                    .explanation("Local decode completed. ML diagnosis unavailable.")
                    .severity("UNKNOWN")
                    .recommendations(List.of("Please retry later."))
                    .build();
        }
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    static class AiResult {
        private String rootCause;
        private double confidence;
        private String explanation;
        private String severity;
        private String specReference;
        private List<String> recommendations;
    }
}

// ══════════════════════════════════════════════════════════════════
// CONTROLLER
// ══════════════════════════════════════════════════════════════════

@RestController
@RequestMapping("/diagnosis")
@RequiredArgsConstructor
class DiagnosisController {

    private final DiagnosisService diagnosisService;

    @PostMapping
    public ResponseEntity<DiagnosisResponse> diagnose(
            @RequestBody DiagnosisRequest request,
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(diagnosisService.diagnose(request));
    }
}
