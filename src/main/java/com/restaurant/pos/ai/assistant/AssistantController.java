package com.restaurant.pos.ai.assistant;

import com.restaurant.pos.ai.assistant.AssistantService.ChatEvent;
import com.restaurant.pos.ai.assistant.AssistantService.PreparedChat;
import com.restaurant.pos.ai.assistant.AssistantService.UsageSummary;
import com.restaurant.pos.ai.security.OwnerPrincipal;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AssistantController.class);
    private static final long STREAM_TIMEOUT_MS = 180_000;

    public record ChatRequest(@NotBlank String message, UUID conversationId) { }

    public record ConversationView(UUID id, String title, Instant updatedAt) { }

    public record MessageView(UUID id, String role, String content, Instant createdAt) { }

    private final AssistantService assistant;
    private final TaskExecutor taskExecutor;

    public AssistantController(AssistantService assistant,
                               @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.assistant = assistant;
        this.taskExecutor = taskExecutor;
    }

    /**
     * Server-Sent Events: started, tool (progress), token (answer text), done | error.
     * Quota and validation errors are returned as normal JSON errors before the stream opens.
     */
    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request, Authentication authentication) {
        OwnerPrincipal owner = OwnerPrincipal.from(authentication);
        PreparedChat prepared = assistant.prepare(owner, request.message(), request.conversationId());

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        taskExecutor.execute(() -> {
            try {
                assistant.answer(prepared, event -> send(emitter, event));
                emitter.complete();
            } catch (Exception e) {
                log.error("Assistant chat failed for {}", owner, e);
                send(emitter, new ChatEvent.Failed("Sorry, something went wrong. Please try again."));
                emitter.complete();
            }
        });
        return emitter;
    }

    @GetMapping("/usage")
    public UsageSummary usage(Authentication authentication) {
        return assistant.usage(OwnerPrincipal.from(authentication));
    }

    @GetMapping("/conversations")
    public List<ConversationView> conversations(Authentication authentication) {
        return assistant.recentConversations(OwnerPrincipal.from(authentication)).stream()
                .map(c -> new ConversationView(c.getId(), c.getTitle(), c.getUpdatedAt()))
                .toList();
    }

    @GetMapping("/conversations/{id}")
    public List<MessageView> messages(@PathVariable UUID id, Authentication authentication) {
        return assistant.conversationMessages(OwnerPrincipal.from(authentication), id).stream()
                .map(m -> new MessageView(m.getId(), m.getRole().name().toLowerCase(), m.getContent(), m.getCreatedAt()))
                .toList();
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        assistant.deleteConversation(OwnerPrincipal.from(authentication), id);
        return ResponseEntity.noContent().build();
    }

    private static void send(SseEmitter emitter, ChatEvent event) {
        try {
            switch (event) {
                case ChatEvent.Started s -> emitter.send(SseEmitter.event().name("started")
                        .data(Map.of("conversationId", s.conversationId()), MediaType.APPLICATION_JSON));
                case ChatEvent.ToolUse t -> emitter.send(SseEmitter.event().name("tool")
                        .data(Map.of("tool", t.tool(), "title", t.title()), MediaType.APPLICATION_JSON));
                case ChatEvent.Token t -> emitter.send(SseEmitter.event().name("token")
                        .data(Map.of("text", t.text()), MediaType.APPLICATION_JSON));
                case ChatEvent.Done d -> emitter.send(SseEmitter.event().name("done")
                        .data(Map.of("conversationId", d.conversationId(), "messageId", d.messageId()), MediaType.APPLICATION_JSON));
                case ChatEvent.Failed f -> emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("message", f.message()), MediaType.APPLICATION_JSON));
            }
        } catch (IOException | IllegalStateException e) {
            // browser closed the connection; nothing else to do
        }
    }
}
