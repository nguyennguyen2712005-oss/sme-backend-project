package sme.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import sme.backend.entity.AiChatSession;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AiChatSessionRepository extends JpaRepository<AiChatSession, UUID> {

    List<AiChatSession> findByUserIdOrderByLastMessageAtDescCreatedAtDesc(UUID userId);

    Optional<AiChatSession> findByIdAndUserId(UUID id, UUID userId);
}