package sme.backend.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import sme.backend.entity.AiChatMessage;
import sme.backend.entity.AiChatSession;
import sme.backend.entity.KnowledgeDocument;
import sme.backend.entity.User;
import sme.backend.entity.Warehouse;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.*;
import sme.backend.security.UserPrincipal;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    private final UserRepository userRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final AiChatSessionRepository aiChatSessionRepository;
    private final AiChatMessageRepository aiChatMessageRepository;
    private final WarehouseRepository warehouseRepository;

    // Repository dùng cho bộ tools đọc dữ liệu thật
    private final InventoryRepository inventoryRepository;
    private final ProductRepository productRepository;
    private final InvoiceRepository invoiceRepository;
    private final OrderRepository orderRepository;
    private final SupplierDebtRepository supplierDebtRepository;
    private final SupplierRepository supplierRepository;
    private final CashbookTransactionRepository cashbookTransactionRepository;

    private final EntityManager entityManager;

    // ─────────────────────────────────────────────────────────
    // SYS-03: UPLOAD & INDEX DOCUMENT (KHÔNG ĐỔI)
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
    // AI CHAT — RAG Co-pilot + Real-data tools + Lịch sử DB
    // ─────────────────────────────────────────────────────────
    @Transactional
    public Map<String, Object> chat(UUID sessionId, String userMessage, UserPrincipal principal) {
        UUID userId = principal.getId();

        // 1. Lấy hoặc tạo session
        AiChatSession session;
        if (sessionId != null) {
            session = aiChatSessionRepository.findByIdAndUserId(sessionId, userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Đoạn chat", sessionId));
        } else {
            session = aiChatSessionRepository.save(AiChatSession.builder()
                    .userId(userId)
                    .title(buildSessionTitle(userMessage))
                    .lastMessageAt(Instant.now())
                    .build());
        }

        // 2. Dựng lại lịch sử hội thoại từ DB
        String conversationHistory = buildConversationHistoryText(session.getId());

        // 3. Lưu tin nhắn của user
        aiChatMessageRepository.save(AiChatMessage.builder()
                .sessionId(session.getId())
                .role(AiChatMessage.MessageRole.USER)
                .content(userMessage)
                .build());

        // 4. RAG: tìm đoạn tài liệu nội bộ liên quan
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

        // 5. Chuẩn bị bộ tools đọc dữ liệu thật
        boolean isAdmin = principal.getRole() == User.UserRole.ROLE_ADMIN;
        String managerWarehouseLabel = null;
        if (!isAdmin) {
            managerWarehouseLabel = warehouseRepository.findById(principal.getWarehouseId())
                    .map(Warehouse::getName)
                    .orElse("(không xác định)");
        }

        List<Object> tools = new ArrayList<>();
        tools.add(new AiBusinessDataTools(
                principal.getWarehouseId(), managerWarehouseLabel, isAdmin,
                inventoryRepository, productRepository, invoiceRepository, orderRepository,
                supplierDebtRepository, supplierRepository, cashbookTransactionRepository, warehouseRepository));
        if (isAdmin) {
            tools.add(new AiAdminOnlyTools(warehouseRepository, userRepository));
        }

        String userName = userRepository.findById(userId).map(User::getFullName).orElse("người dùng");
        String systemPrompt = buildSystemPrompt(userName, principal.getRole(), managerWarehouseLabel, conversationHistory, context);

        String reply;
        try {
            reply = chatClient.prompt()
                    .system(systemPrompt)
                    .tools(tools.toArray())
                    .user(userMessage)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("AI chat error: {}", e.getMessage(), e);
            reply = "Xin lỗi, tôi gặp sự cố khi xử lý yêu cầu của bạn. Vui lòng thử lại.";
        }

        List<Map<String, String>> sources = buildSources(similarDocuments);

        // 6. Lưu tin nhắn trả lời
        aiChatMessageRepository.save(AiChatMessage.builder()
                .sessionId(session.getId())
                .role(AiChatMessage.MessageRole.ASSISTANT)
                .content(reply)
                .sources(serializeSources(sources))
                .build());

        session.setLastMessageAt(Instant.now());
        aiChatSessionRepository.save(session);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getId());
        result.put("reply", reply);
        result.put("sources", sources);
        return result;
    }

    // ─────────────────────────────────────────────────────────
    // QUẢN LÝ LỊCH SỬ CHAT
    // ─────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSessions(UUID userId) {
        return aiChatSessionRepository.findByUserIdOrderByLastMessageAtDescCreatedAtDesc(userId)
                .stream().map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.getId());
                    m.put("title", s.getTitle());
                    m.put("lastMessageAt", s.getLastMessageAt());
                    m.put("createdAt", s.getCreatedAt());
                    return m;
                }).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSessionMessages(UUID sessionId, UUID userId) {
        aiChatSessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Đoạn chat", sessionId));

        return aiChatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)
                .stream().map(m -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", m.getId());
                    item.put("role", m.getRole() == AiChatMessage.MessageRole.USER ? "user" : "assistant");
                    item.put("content", m.getContent());
                    item.put("sources", parseSources(m.getSources()));
                    item.put("createdAt", m.getCreatedAt());
                    return item;
                }).toList();
    }

    @Transactional
    public void deleteSession(UUID sessionId, UUID userId) {
        AiChatSession session = aiChatSessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Đoạn chat", sessionId));
        aiChatSessionRepository.delete(session);
        log.info("Đã xóa đoạn chat {} của user {}", sessionId, userId);
    }

    public List<String> getSuggestedQuestions(User.UserRole role) {
        return switch (role) {
            case ROLE_CASHIER -> List.of();
            case ROLE_MANAGER -> List.of(
                    "Quy trình xác nhận đơn hàng online gồm các bước nào?",
                    "Chi nhánh tôi hôm nay doanh thu bao nhiêu, có gì cần lưu ý?",
                    "Hàng nào ở kho tôi sắp hết, cần nhập thêm?",
                    "Công nợ nhà cung cấp của chi nhánh tôi sắp đến hạn xem ở đâu?"
            );
            case ROLE_ADMIN -> List.of(
                    "So sánh doanh thu 30 ngày qua giữa các chi nhánh",
                    "Chi nhánh nào đang có hàng tồn đọng nhiều nhất?",
                    "Tổng công nợ nhà cung cấp toàn hệ thống hiện tại là bao nhiêu?",
                    "Đề xuất kế hoạch nhập hàng dựa trên top sản phẩm bán chạy tháng này"
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

    // ─────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────

    private String buildSessionTitle(String firstMessage) {
        String trimmed = firstMessage.trim().replaceAll("\\s+", " ");
        return trimmed.length() > 60 ? trimmed.substring(0, 60) + "..." : trimmed;
    }

    private String buildConversationHistoryText(UUID sessionId) {
        List<AiChatMessage> recent = aiChatMessageRepository.findTop10BySessionIdOrderByCreatedAtDesc(sessionId);
        Collections.reverse(recent);
        return recent.stream()
                .map(m -> (m.getRole() == AiChatMessage.MessageRole.USER ? "User" : "Assistant") + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
    }

    private List<Map<String, String>> buildSources(List<Document> similarDocuments) {
        return similarDocuments.stream()
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
    }

    private String serializeSources(List<Map<String, String>> sources) {
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<Map<String, String>> parseSources(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String buildSystemPrompt(String userName, User.UserRole role, String managerWarehouseLabel,
                                      String conversationHistory, String context) {
        String scopeNote = switch (role) {
            case ROLE_ADMIN -> "Người dùng này là ADMIN — được xem dữ liệu TOÀN HỆ THỐNG (tất cả chi nhánh). " +
                    "Có thể lọc theo 1 chi nhánh cụ thể nếu người dùng cung cấp mã chi nhánh.";
            case ROLE_MANAGER -> "Người dùng này là MANAGER — CHỈ được xem dữ liệu của chi nhánh họ quản lý: " +
                    managerWarehouseLabel + ". Các công cụ tra cứu đã bị khóa cứng theo chi nhánh này ở tầng " +
                    "hệ thống, tuyệt đối không được nói/ám chỉ số liệu của chi nhánh khác.";
            case ROLE_CASHIER -> "Không áp dụng.";
        };

        return """
                Bạn là AI Co-pilot của hệ thống SME ERP & POS đa chi nhánh, trợ lý thông minh cho quản lý.

                Vai trò của bạn:
                - Trả lời câu hỏi về nghiệp vụ: bán hàng, kho, tài chính, đơn hàng
                - Dùng các công cụ (tools) được cung cấp để tra cứu SỐ LIỆU THỰC TẾ (doanh thu, tồn kho, công nợ,
                  đơn hàng, quỹ tiền...) mỗi khi câu hỏi cần đến số liệu cụ thể — TUYỆT ĐỐI không tự bịa số liệu.
                - Sau khi có số liệu, hãy PHÂN TÍCH và đề xuất hành động kinh doanh cụ thể (nên nhập thêm hàng gì,
                  nên xả kho gì, thời điểm nên đẩy khuyến mãi, cảnh báo rủi ro công nợ/dòng tiền...), không chỉ
                  liệt kê số khô khan.
                - Hỗ trợ tra cứu chính sách, quy trình nội bộ từ tài liệu đã upload (phần NGỮ CẢNH TỪ TÀI LIỆU bên dưới)
                - Giao tiếp bằng tiếng Việt, thân thiện và chuyên nghiệp

                Tên người dùng: %s
                Phạm vi dữ liệu: %s

                THÔNG TIN NGỮ CẢNH TỪ TÀI LIỆU (RAG):
                %s

                LỊCH SỬ HỘI THOẠI GẦN ĐÂY:
                %s

                Lưu ý quan trọng:
                - Với câu hỏi về số liệu thực tế, LUÔN gọi tool tương ứng để lấy dữ liệu mới nhất, không trả lời chung chung.
                - Nếu không tìm thấy thông tin liên quan (cả từ tool lẫn tài liệu), hãy nói rõ và tuyệt đối không bịa đặt (hallucinate).
                - Luôn trả lời có cấu trúc rõ ràng, ngắn gọn, dễ đọc trên khung chat.
                """.formatted(
                userName,
                scopeNote,
                context.isBlank() ? "Không có tài liệu liên quan nào được tìm thấy." : context,
                conversationHistory.isBlank() ? "Đây là đầu cuộc hội thoại" : conversationHistory);
    }
}