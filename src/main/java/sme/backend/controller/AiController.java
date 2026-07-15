package sme.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import sme.backend.ai.AiService;
import sme.backend.dto.response.ApiResponse;
import sme.backend.security.UserPrincipal;
import sme.backend.entity.KnowledgeDocument;

import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;

    @PostMapping("/chat")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> chat(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserPrincipal principal) {

        String message = body.get("message");
        String sessionIdRaw = body.get("sessionId");

        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.<Map<String, Object>>builder()
                            .success(false).message("message bắt buộc").build());
        }

        UUID sessionId = null;
        if (sessionIdRaw != null && !sessionIdRaw.isBlank()) {
            try {
                sessionId = UUID.fromString(sessionIdRaw);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(
                        ApiResponse.<Map<String, Object>>builder()
                                .success(false).message("sessionId không hợp lệ").build());
            }
        }

        Map<String, Object> result = aiService.chat(sessionId, message, principal);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/sessions")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listSessions(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(aiService.listSessions(principal.getId())));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSessionMessages(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(aiService.getSessionMessages(sessionId, principal.getId())));
    }

    @DeleteMapping("/sessions/{sessionId}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteSession(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        aiService.deleteSession(sessionId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok("Đã xóa đoạn chat", null));
    }

    @GetMapping("/suggested-questions")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<String>>> getSuggestedQuestions(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(aiService.getSuggestedQuestions(principal.getRole())));
    }

    @GetMapping("/documents")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<KnowledgeDocument>>> getAllDocuments() {
        return ResponseEntity.ok(ApiResponse.ok(aiService.getAllDocuments()));
    }

    @DeleteMapping("/documents/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteDocument(@PathVariable UUID id) {
        aiService.deleteDocument(id);
        return ResponseEntity.ok(ApiResponse.ok("Đã xóa tài liệu và làm sạch dữ liệu AI", null));
    }

    @GetMapping("/documents/{id}/chunks")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<String>>> getDocumentChunks(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(aiService.getDocumentChunks(id)));
    }

    @PostMapping("/documents")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @AuthenticationPrincipal UserPrincipal principal) throws IOException {

        String documentTitle = title != null ? title : file.getOriginalFilename();

        org.springframework.core.io.Resource resource =
                new org.springframework.core.io.ByteArrayResource(file.getBytes()) {
                    @Override
                    public String getFilename() { return file.getOriginalFilename(); }
                };

        int chunks = aiService.indexDocument(resource, documentTitle, principal.getId());

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "title",  documentTitle,
                "chunks", chunks,
                "status", "indexed"
        )));
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<?>> semanticSearch(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        var results = aiService.searchSimilar(query, topK);
        return ResponseEntity.ok(ApiResponse.ok(results));
    }
}