package com.restaurant.pos.ai.assistant;

import com.restaurant.pos.ai.security.OwnerPrincipal;
import com.restaurant.pos.ai.tools.CafeQrTool;
import com.restaurant.pos.ai.tools.ToolExecutor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Exposes a shared {@link CafeQrTool} to the built-in chat model. The owner (and a progress listener) travel in
 * the Spring AI {@link ToolContext}, never in thread-locals, so it is safe on streaming/reactive threads.
 */
public class ChatToolCallback implements ToolCallback {

    static final String OWNER_KEY = "cafeqr.owner";
    static final String PROGRESS_KEY = "cafeqr.progress";

    private final CafeQrTool tool;
    private final ToolExecutor executor;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public ChatToolCallback(CafeQrTool tool, ToolExecutor executor, ObjectMapper objectMapper) {
        this.tool = tool;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.definition = ToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(objectMapper.writeValueAsString(tool.inputSchema()))
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        throw new IllegalStateException("CafeQR tools need the signed-in owner in the tool context");
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput, ToolContext toolContext) {
        Map<String, Object> ctx = toolContext == null ? Map.of() : toolContext.getContext();
        if (!(ctx.get(OWNER_KEY) instanceof OwnerPrincipal owner)) {
            return "{\"error\":\"Not signed in to CafeQR.\"}";
        }
        if (ctx.get(PROGRESS_KEY) instanceof Consumer<?> progress) {
            ((Consumer<CafeQrTool>) progress).accept(tool);
        }
        Map<String, Object> args;
        try {
            args = toolInput == null || toolInput.isBlank()
                    ? Map.of()
                    : objectMapper.readValue(toolInput, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return "{\"error\":\"Tool arguments must be a JSON object.\"}";
        }
        return executor.execute(tool.name(), args, owner, ToolExecutor.Channel.CHAT).json();
    }
}
