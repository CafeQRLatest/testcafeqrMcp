package com.restaurant.pos.ai.assistant.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_message")
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage {

    public enum Role { USER, ASSISTANT }

    @Id
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(length = 100)
    private String model;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static ChatMessage of(Conversation conversation, Role role, String content, Instant now) {
        ChatMessage m = new ChatMessage();
        m.id = UUID.randomUUID();
        m.conversationId = conversation.getId();
        m.clientId = conversation.getClientId();
        m.userId = conversation.getUserId();
        m.role = role;
        m.content = content;
        m.createdAt = now;
        return m;
    }
}
