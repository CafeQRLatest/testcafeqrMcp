package com.restaurant.pos.ai.assistant;

import org.springframework.beans.factory.annotation.Autowired;
import com.restaurant.pos.ai.assistant.AssistantExceptions.BadQuestionException;
import com.restaurant.pos.ai.assistant.AssistantExceptions.NotFoundException;
import com.restaurant.pos.ai.assistant.AssistantExceptions.QuotaExceededException;
import com.restaurant.pos.ai.assistant.persistence.ChatMessage;
import com.restaurant.pos.ai.assistant.persistence.ChatMessageRepository;
import com.restaurant.pos.ai.assistant.persistence.Conversation;
import com.restaurant.pos.ai.assistant.persistence.ConversationRepository;
import com.restaurant.pos.ai.backend.BusinessInfoService;
import com.restaurant.pos.ai.backend.BusinessInfoService.BusinessInfo;
import com.restaurant.pos.ai.config.AiProperties;
import com.restaurant.pos.ai.security.OwnerPrincipal;
import com.restaurant.pos.ai.tools.CafeQrTool;
import com.restaurant.pos.ai.tools.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Built-in "Ask CafeQR" chat: checks quota, keeps conversation history, runs the configured AI model with the
 * shared read-only tools and streams the answer back.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    /** Streaming events sent to the browser. */
    public sealed interface ChatEvent {
        record Started(UUID conversationId) implements ChatEvent { }
        record ToolUse(String tool, String title) implements ChatEvent { }
        record Token(String text) implements ChatEvent { }
        record Done(UUID conversationId, UUID messageId) implements ChatEvent { }
        record Failed(String message) implements ChatEvent { }
    }

    /** Validated question with its conversation, ready to be answered. */
    public record PreparedChat(OwnerPrincipal owner, Conversation conversation, List<Message> history, String question) { }

    public record UsageSummary(String month, long used, int limit) { }

    private final ChatClient chatClient;
    private final ToolExecutor executor;
    private final ObjectMapper objectMapper;
    private final BusinessInfoService businessInfo;
    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final AiProperties properties;
    private final Clock clock;

    @Autowired
    public AssistantService(ChatClient.Builder chatClientBuilder, ToolExecutor executor, ObjectMapper objectMapper,
                            BusinessInfoService businessInfo, ConversationRepository conversations,
                            ChatMessageRepository messages, AiProperties properties) {
        this(chatClientBuilder.build(), executor, objectMapper, businessInfo, conversations, messages, properties,
                Clock.systemUTC());
    }

    AssistantService(ChatClient chatClient, ToolExecutor executor, ObjectMapper objectMapper,
                     BusinessInfoService businessInfo, ConversationRepository conversations,
                     ChatMessageRepository messages, AiProperties properties, Clock clock) {
        this.chatClient = chatClient;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.businessInfo = businessInfo;
        this.conversations = conversations;
        this.messages = messages;
        this.properties = properties;
        this.clock = clock;
    }

    /** Validates, enforces the monthly quota and stores the question. Throws before any streaming starts. */
    @Transactional
    public PreparedChat prepare(OwnerPrincipal owner, String question, UUID conversationId) {
        String q = question == null ? "" : question.strip();
        if (q.isEmpty()) {
            throw new BadQuestionException("Please type a question.");
        }
        if (q.length() > properties.assistant().maxMessageChars()) {
            throw new BadQuestionException("Question is too long (max " + properties.assistant().maxMessageChars() + " characters).");
        }
        if (owner.userId() == null) {
            throw new BadQuestionException("Your sign-in is missing a user ID. Please sign in again.");
        }
        UsageSummary usage = usage(owner);
        if (usage.used() >= usage.limit()) {
            throw new QuotaExceededException("Your café has used all " + usage.limit()
                    + " assistant questions for this month. The limit resets on the 1st.");
        }

        Instant now = clock.instant();
        Conversation conversation;
        List<Message> history = new ArrayList<>();
        if (conversationId != null) {
            conversation = conversations.findByIdAndClientIdAndUserId(conversationId, owner.clientId(), owner.userId())
                    .orElseThrow(() -> new NotFoundException("Conversation not found."));
            List<ChatMessage> recent = new ArrayList<>(messages.findByConversationIdOrderByCreatedAtDesc(
                    conversation.getId(), PageRequest.of(0, properties.assistant().historyMessages())));
            Collections.reverse(recent);
            for (ChatMessage m : recent) {
                history.add(m.getRole() == ChatMessage.Role.USER
                        ? new UserMessage(m.getContent())
                        : new AssistantMessage(m.getContent()));
            }
            conversation.setUpdatedAt(now);
        } else {
            conversation = Conversation.start(owner.clientId(), owner.userId(), q, now);
        }
        conversations.save(conversation);
        messages.save(ChatMessage.of(conversation, ChatMessage.Role.USER, q, now));
        return new PreparedChat(owner, conversation, history, q);
    }

    /** Streams the model's answer; tool calls are reported as {@link ChatEvent.ToolUse} events. */
    public void answer(PreparedChat chat, Consumer<ChatEvent> events) {
        events.accept(new ChatEvent.Started(chat.conversation().getId()));
        Consumer<CafeQrTool> progress = tool -> events.accept(new ChatEvent.ToolUse(tool.name(), tool.title()));
        List<ToolCallback> callbacks = executor.tools().stream()
                .map(t -> (ToolCallback) new ChatToolCallback(t, executor, objectMapper))
                .toList();

        StringBuilder answer = new StringBuilder();
        Usage[] usage = new Usage[1];
        String[] model = new String[1];

        Flux<ChatResponse> stream = chatClient.prompt()
                .system(systemPrompt(chat.owner()))
                .messages(chat.history())
                .user(chat.question())
                .toolCallbacks(callbacks)
                .toolContext(Map.of(ChatToolCallback.OWNER_KEY, chat.owner(), ChatToolCallback.PROGRESS_KEY, progress))
                .stream()
                .chatResponse();

        stream.doOnNext(response -> {
                    if (response.getResult() != null && response.getResult().getOutput() != null) {
                        String text = response.getResult().getOutput().getText();
                        if (text != null && !text.isEmpty()) {
                            answer.append(text);
                            events.accept(new ChatEvent.Token(text));
                        }
                    }
                    if (response.getMetadata() != null) {
                        Usage u = response.getMetadata().getUsage();
                        if (u != null && u.getTotalTokens() != null && u.getTotalTokens() > 0) {
                            usage[0] = u; // cumulative across tool-call rounds; keep the latest
                        }
                        if (response.getMetadata().getModel() != null && !response.getMetadata().getModel().isBlank()) {
                            model[0] = response.getMetadata().getModel();
                        }
                    }
                })
                .doOnError(e -> {
                    log.error("Assistant answer failed for {}", chat.owner(), e);
                    events.accept(new ChatEvent.Failed("Sorry, I couldn't answer that right now. Please try again."));
                })
                .doOnComplete(() -> {
                    String text = answer.isEmpty() ? "Sorry, I don't have an answer for that." : answer.toString();
                    ChatMessage saved = saveAnswer(chat.conversation(), text, usage[0], model[0]);
                    events.accept(new ChatEvent.Done(chat.conversation().getId(), saved.getId()));
                })
                .onErrorComplete()
                .blockLast();
    }

    private ChatMessage saveAnswer(Conversation conversation, String text, Usage usage, String model) {
        ChatMessage m = ChatMessage.of(conversation, ChatMessage.Role.ASSISTANT, text, clock.instant());
        if (usage != null) {
            m.setPromptTokens(usage.getPromptTokens());
            m.setCompletionTokens(usage.getCompletionTokens());
        }
        m.setModel(model != null ? model : properties.assistant().model());
        return messages.save(m);
    }

    public UsageSummary usage(OwnerPrincipal owner) {
        YearMonth month = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        Instant since = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        long used = messages.countByClientIdAndRoleAndCreatedAtGreaterThanEqual(owner.clientId(), ChatMessage.Role.USER, since);
        return new UsageSummary(month.toString(), used, properties.limits().monthlyQuestionsPerClient());
    }

    public List<Conversation> recentConversations(OwnerPrincipal owner) {
        return conversations.findByClientIdAndUserIdOrderByUpdatedAtDesc(owner.clientId(), owner.userId(), PageRequest.of(0, 30));
    }

    public List<ChatMessage> conversationMessages(OwnerPrincipal owner, UUID conversationId) {
        Conversation c = conversations.findByIdAndClientIdAndUserId(conversationId, owner.clientId(), owner.userId())
                .orElseThrow(() -> new NotFoundException("Conversation not found."));
        return messages.findByConversationIdOrderByCreatedAtAsc(c.getId());
    }

    @Transactional
    public void deleteConversation(OwnerPrincipal owner, UUID conversationId) {
        Conversation c = conversations.findByIdAndClientIdAndUserId(conversationId, owner.clientId(), owner.userId())
                .orElseThrow(() -> new NotFoundException("Conversation not found."));
        conversations.delete(c);
    }

    String systemPrompt(OwnerPrincipal owner) {
        String cafe = "this café";
        String currency = "unknown (call get_business_info)";
        String zone = "the café's timezone";
        String today = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
        try {
            BusinessInfo info = businessInfo.get(owner);
            cafe = info.name() != null ? info.name() : cafe;
            currency = info.currency() != null ? info.currency() : currency;
            zone = info.zone().getId();
            today = LocalDate.now(clock.withZone(info.zone())).toString();
        } catch (Exception e) {
            log.warn("Could not load business info for system prompt: {}", e.getMessage());
        }
        return """
                You are "Ask CafeQR", the assistant inside the CafeQR restaurant POS dashboard, talking to the owner \
                or manager of %s.
                Today is %s (timezone %s). Currency: %s.

                Rules:
                - Answer ONLY from data returned by the tools. Never guess or invent numbers, items or dates.
                - If a tool returns an error or no data, say so plainly and suggest what the user can check.
                - You are read-only: you cannot change orders, prices, stock or settings. If asked, say so and point \
                to the relevant dashboard page.
                - Resolve relative dates ("yesterday", "last week", "this month") to YYYY-MM-DD in the café's timezone \
                before calling tools.
                - Show money with the café's currency. Do not apply or explain tax rules yourself; report tax figures \
                exactly as recorded in CafeQR.
                - Do not reveal customer names or phone numbers.
                - Be brief: lead with the answer, then at most a few bullet points or a small table. Reply in the \
                user's language.
                """.formatted(cafe, today, zone, currency);
    }
}
