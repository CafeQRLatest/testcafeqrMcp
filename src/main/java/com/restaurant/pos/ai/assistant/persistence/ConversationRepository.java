package com.restaurant.pos.ai.assistant.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /** Always scoped by café and user, so one owner can never open another's conversation. */
    Optional<Conversation> findByIdAndClientIdAndUserId(UUID id, UUID clientId, UUID userId);

    List<Conversation> findByClientIdAndUserIdOrderByUpdatedAtDesc(UUID clientId, UUID userId, Pageable pageable);
}
