package com.restaurant.pos.ai.assistant.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    List<ChatMessage> findByConversationIdOrderByCreatedAtDesc(UUID conversationId, Pageable pageable);

    /** Questions asked by a café since a point in time (monthly quota). */
    long countByClientIdAndRoleAndCreatedAtGreaterThanEqual(UUID clientId, ChatMessage.Role role, Instant since);
}
