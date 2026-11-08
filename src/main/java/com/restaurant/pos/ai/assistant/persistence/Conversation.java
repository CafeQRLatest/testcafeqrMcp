package com.restaurant.pos.ai.assistant.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_conversation")
@Getter
@Setter
@NoArgsConstructor
public class Conversation {

    @Id
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Conversation start(UUID clientId, UUID userId, String firstQuestion, Instant now) {
        Conversation c = new Conversation();
        c.id = UUID.randomUUID();
        c.clientId = clientId;
        c.userId = userId;
        String title = firstQuestion.strip().replaceAll("\\s+", " ");
        c.title = title.length() > 80 ? title.substring(0, 77) + "..." : title;
        c.createdAt = now;
        c.updatedAt = now;
        return c;
    }
}
