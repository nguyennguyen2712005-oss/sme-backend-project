package sme.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import sme.backend.entity.AiChatMessage;

import java.util.List;
import java.util.UUID;

@Repository
public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, UUID> {

    List<AiChatMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    List<AiChatMessage> findTop10BySessionIdOrderByCreatedAtDesc(UUID sessionId);
}