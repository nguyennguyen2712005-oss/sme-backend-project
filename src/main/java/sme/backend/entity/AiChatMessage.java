package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "ai_chat_messages")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class AiChatMessage extends BaseSimpleEntity {

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageRole role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String sources;

    public enum MessageRole {
        USER, ASSISTANT
    }
}