
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

    /**
     * POST /ai/chat — AI Co-pilot chat
     * Body: { "message": "...", "conversationHistory": "..." }
     * ĐÃ SỬA: mở cho CASHIER (trước đây chỉ MANAGER, ADMIN). Nhớ là
     * SecurityConfig cũng phải mở /ai/** cho CASHIER song song - sửa 1 trong 2
     * chỗ là không đủ, request sẽ bị chặn ở filter chain trước khi tới đây.
     * Response cũng đổi từ { reply } sang { reply, sources } để hiển thị được
     * nguồn tài liệu AI đã dùng.
     */
    @PostMapping("/chat")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> chat(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserPrincipal principal) {

        String message = body.get("message");
        String history = body.get("conversationHistory");

        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.<Map<String, Object>>builder()
                            .success(false).message("message bắt buộc").build());
        }

        Map<String, Object> result = aiService.chat(message, principal.getId(), history);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * GET /ai/suggested-questions — Câu hỏi gợi ý theo role (đọc role từ JWT)
     */
    @GetMapping("/suggested-questions")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
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

    /**
     * POST /ai/documents — SYS-03: Upload tài liệu RAG
     * Hỗ trợ: PDF, DOCX, PPTX, TXT
     */
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

    /**
     * GET /ai/search — Tìm kiếm ngữ nghĩa trong tài liệu
     */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<?>> semanticSearch(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        var results = aiService.searchSimilar(query, topK);
        return ResponseEntity.ok(ApiResponse.ok(results));
    }
}
