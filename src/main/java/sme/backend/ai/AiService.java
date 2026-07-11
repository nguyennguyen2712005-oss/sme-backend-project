
package sme.backend.ai;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.config.AppProperties;
import sme.backend.entity.KnowledgeDocument;
import sme.backend.entity.User;
import sme.backend.repository.KnowledgeDocumentRepository;
import sme.backend.repository.UserRepository;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AiService — RAG (Retrieval-Augmented Generation) Co-pilot
 *
 * Architecture:
 * 1. SYS-03: Upload file → Tika extract text → TokenTextSplitter chunking
 * → Gemini embedding → lưu vào pgvector
 * 2. Chat:   User message → pgvector similarity search → top-K chunks
 * → System prompt + context + message → Gemini → response (+ sources)
 *
 * LƯU Ý: lịch sử hội thoại (ai_chat_sessions/ai_chat_messages) chỉ tồn tại ở
 * schema DB, KHÔNG được service này đọc/ghi - frontend hiện đang tự giữ toàn
 * bộ lịch sử hội thoại trong React state và gửi lại nguyên văn ở mỗi lần gọi
 * (param conversationHistory). Nghĩa là đóng tab/đóng panel chat là mất lịch
 * sử - đây là một giới hạn có thật của bản hiện tại, không phải do ĐATN cần
 * "session-based" mà do chưa nối dây tới 2 bảng đã có sẵn trong schema.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final AppProperties appProperties;
    private final UserRepository userRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final EntityManager entityManager;

    // ─────────────────────────────────────────────────────────
    // SYS-03: UPLOAD & INDEX DOCUMENT
    // ─────────────────────────────────────────────────────────
    @Transactional
    public int indexDocument(Resource fileResource, String documentTitle, UUID uploadedBy) {
        log.info("Indexing document: {}", documentTitle);

        String fileName = fileResource.getFilename() != null ? fileResource.getFilename() : "unknown.txt";
        String fileExtension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf(".") + 1) : "txt";

        KnowledgeDocument docRecord = KnowledgeDocument.builder()
                .title(documentTitle)
                .fileName(fileName)
                .fileType(fileExtension)
                .uploadedByUserId(uploadedBy)
                .isActive(true)
                .build();
        docRecord = documentRepository.save(docRecord);
        final String docIdStr = docRecord.getId().toString();

        TikaDocumentReader reader = new TikaDocumentReader(fileResource);
        List<Document> rawDocs = reader.get();

        rawDocs.forEach(doc -> {
            doc.getMetadata().put("documentId", docIdStr);
            doc.getMetadata().put("source", documentTitle);
            doc.getMetadata().put("uploadedBy", uploadedBy.toString());
            doc.getMetadata().put("indexedAt", Instant.now().toString());
        });

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(appProperties.getAi().getChunkSize())
                .withMinChunkSizeChars(100)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .build();
        List<Document> chunks = splitter.apply(rawDocs);

        vectorStore.add(chunks);

        log.info("Document indexed: {} chunks from '{}'", chunks.size(), documentTitle);
        return chunks.size();
    }

    public List<KnowledgeDocument> getAllDocuments() {
        List<KnowledgeDocument> docs = documentRepository.findAllByOrderByCreatedAtDesc();
        docs.forEach(d -> d.setChunkCount(documentRepository.countChunksByDocumentId(d.getId())));
        return docs;
    }

    @Transactional
    public void deleteDocument(UUID documentId) {
        entityManager.createNativeQuery("DELETE FROM vector_store WHERE metadata->>'documentId' = :docId")
                .setParameter("docId", documentId.toString())
                .executeUpdate();

        documentRepository.deleteById(documentId);
        log.info("Deleted document and its vectors: {}", documentId);
    }

    // ─────────────────────────────────────────────────────────
    // AI CHAT — RAG Co-pilot (SYS-03 + MODULE AI)
    // ĐÃ SỬA: trả về Map { reply, sources } thay vì String thuần, để FE hiển thị
    // được "nguồn tài liệu AI đã dùng để trả lời" như đặc tả yêu cầu.
    // ─────────────────────────────────────────────────────────
    public Map<String, Object> chat(String userMessage, UUID userId, String conversationHistory) {
        String userName = userRepository.findById(userId)
                .map(User::getFullName).orElse("người dùng");

        List<Document> similarDocuments = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(userMessage)
                        .topK(appProperties.getAi().getTopKResults())
                        .similarityThreshold(0.65)
                        .build()
        );

        String context = similarDocuments.stream()
                .map(Document::getFormattedContent)
                .collect(Collectors.joining("\n---\n"));

        String systemPrompt = buildSystemPrompt(userName, conversationHistory, context);

        String reply;
        try {
            reply = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userMessage)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("AI chat error: {}", e.getMessage(), e);
            reply = "Xin lỗi, tôi gặp sự cố khi xử lý yêu cầu của bạn. Vui lòng thử lại.";
        }

        // Mỗi tài liệu nguồn chỉ hiện 1 lần (nhiều chunk có thể cùng 1 tài liệu)
        List<Map<String, String>> sources = similarDocuments.stream()
                .map(doc -> {
                    String title = String.valueOf(doc.getMetadata().getOrDefault("source", "Tài liệu nội bộ"));
                    String content = doc.getFormattedContent();
                    String excerpt = content.length() > 220 ? content.substring(0, 220) + "..." : content;
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("title", title);
                    m.put("excerpt", excerpt);
                    return m;
                })
                .collect(Collectors.toMap(m -> m.get("title"), m -> m, (a, b) -> a, LinkedHashMap::new))
                .values().stream().toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reply", reply);
        result.put("sources", sources);
        return result;
    }

    /**
     * ĐÃ THÊM: Câu hỏi gợi ý theo role, hardcode trong service vì với phạm vi
     * ĐATN không cần thiết phải có bảng ai_suggested_questions riêng.
     */
    public List<String> getSuggestedQuestions(User.UserRole role) {
        return switch (role) {
            case ROLE_CASHIER -> List.of(
                    "Quy trình mở ca làm việc như thế nào?",
                    "Đóng ca bị lệch tiền thì xử lý ra sao?",
                    "Cách xử lý khi khách muốn đổi trả sản phẩm?",
                    "Đơn hàng online cần đóng gói xem ở đâu?"
            );
            case ROLE_MANAGER -> List.of(
                    "Quy trình xác nhận đơn hàng online gồm các bước nào?",
                    "Khi nào nên tạo phiếu chuyển kho giữa các chi nhánh?",
                    "Làm sao để duyệt ca làm việc của nhân viên?",
                    "Công nợ nhà cung cấp sắp đến hạn xem ở đâu?"
            );
            case ROLE_ADMIN -> List.of(
                    "Cách thêm một chi nhánh mới vào hệ thống?",
                    "Làm sao xem báo cáo doanh thu toàn chuỗi theo tháng?",
                    "Khi nào nên dùng chức năng hủy khẩn cấp đơn hàng?",
                    "Cách upload tài liệu để AI trả lời chính xác hơn?"
            );
        };
    }

    public List<Document> searchSimilar(String query, int topK) {
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .similarityThreshold(0.6)
                        .build()
        );
    }

    public List<String> getDocumentChunks(UUID documentId) {
        @SuppressWarnings("unchecked")
        List<String> chunks = entityManager.createNativeQuery(
                "SELECT content FROM vector_store WHERE metadata->>'documentId' = :docId"
        )
        .setParameter("docId", documentId.toString())
        .getResultList();

        return chunks;
    }

    private String buildSystemPrompt(String userName, String conversationHistory, String context) {
        return """
                Bạn là AI Co-pilot của hệ thống quản lý nhà sách đa chi nhánh Bookly, trợ lý thông minh cho nhân viên.

                Vai trò của bạn:
                - Trả lời câu hỏi về nghiệp vụ: bán hàng, kho, tài chính, đơn hàng
                - Phân tích dữ liệu và đưa ra gợi ý kinh doanh
                - Hỗ trợ tra cứu chính sách, quy trình nội bộ từ tài liệu đã upload
                - Giao tiếp bằng tiếng Việt, thân thiện và chuyên nghiệp

                Tên người dùng: %s

                THÔNG TIN NGỮ CẢNH TỪ TÀI LIỆU (RAG):
                %s

                LỊCH SỬ HỘI THOẠI:
                %s

                Lưu ý quan trọng:
                - Ưu tiên sử dụng "THÔNG TIN NGỮ CẢNH" để trả lời nếu câu hỏi liên quan đến tài liệu.
                - Nếu không tìm thấy thông tin trong ngữ cảnh, hãy nói rõ và tuyệt đối không bịa đặt (hallucinate).
                - Với câu hỏi về số liệu thực tế (doanh thu, tồn kho), hãy hướng dẫn người dùng xem báo cáo.
                - Luôn trả lời ngắn gọn, có cấu trúc rõ ràng.
                """.formatted(
                userName,
                context.isBlank() ? "Không có tài liệu liên quan nào được tìm thấy." : context,
                conversationHistory != null ? conversationHistory : "Đây là đầu cuộc hội thoại");
    }
}
