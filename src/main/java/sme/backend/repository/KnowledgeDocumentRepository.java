
package sme.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sme.backend.entity.KnowledgeDocument;

import java.util.List;
import java.util.UUID;

@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, UUID> {
    List<KnowledgeDocument> findAllByOrderByCreatedAtDesc();

    // Đếm số lượng chunk của tài liệu đang nằm trong vector_store
    @Query(value = "SELECT COUNT(*) FROM vector_store WHERE metadata->>'documentId' = cast(:docId as text)", nativeQuery = true)
    int countChunksByDocumentId(@Param("docId") UUID docId);
}
