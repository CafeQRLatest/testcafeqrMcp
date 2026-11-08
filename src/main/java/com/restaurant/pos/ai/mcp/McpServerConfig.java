package com.restaurant.pos.ai.mcp;

import com.restaurant.pos.ai.security.OwnerPrincipal;
import com.restaurant.pos.ai.tools.CafeQrTool;
import com.restaurant.pos.ai.tools.ToolExecutor;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;

import java.util.List;
import java.util.Map;

/**
 * MCP endpoint (Streamable HTTP, stateless) at /mcp for external AI apps such as Claude, ChatGPT and Gemini.
 * Stateless = no server-side sessions, so instances can be scaled horizontally behind a load balancer.
 * Spring Security has already verified the ai:read bearer token before a request reaches this transport.
 */
@Configuration
public class McpServerConfig {

    static final String OWNER_KEY = "cafeqr.owner";

    private static final String INSTRUCTIONS = """
            CafeQR is a restaurant/café POS. These tools give READ-ONLY access to the signed-in owner's own café data:
            sales, top items, payment methods, hourly sales, tax summary, profit and loss, orders, stock, menu and tables.
            Call get_business_info first to learn the café's currency, timezone and today's date.
            Dates are YYYY-MM-DD in the café's timezone. Never invent figures: if a tool returns an error or no data, say so.
            """;

    @Bean
    McpJsonMapper mcpJsonMapper() {
        return McpJsonDefaults.getMapper();
    }

    @Bean
    WebMvcStatelessServerTransport mcpTransport(McpJsonMapper jsonMapper) {
        return WebMvcStatelessServerTransport.builder()
                .jsonMapper(jsonMapper)
                .messageEndpoint("/mcp")
                .contextExtractor(request -> request.principal()
                        .map(p -> McpTransportContext.create(Map.of(OWNER_KEY, OwnerPrincipal.from(p))))
                        .orElse(McpTransportContext.EMPTY))
                .build();
    }

    @Bean
    RouterFunction<ServerResponse> mcpRoutes(WebMvcStatelessServerTransport transport) {
        return transport.getRouterFunction();
    }

    @Bean(destroyMethod = "close")
    McpStatelessSyncServer mcpServer(WebMvcStatelessServerTransport transport, McpJsonMapper jsonMapper,
                                     ToolExecutor executor) {
        List<McpStatelessServerFeatures.SyncToolSpecification> specs = executor.tools().stream()
                .map(tool -> toSpec(tool, executor))
                .toList();
        return McpServer.sync(transport)
                .serverInfo("cafeqr", "1.0.0")
                .instructions(INSTRUCTIONS)
                .jsonMapper(jsonMapper)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(specs)
                .build();
    }

    static McpStatelessServerFeatures.SyncToolSpecification toSpec(CafeQrTool tool, ToolExecutor executor) {
        McpSchema.Tool mcpTool = McpSchema.Tool.builder()
                .name(tool.name())
                .title(tool.title())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .annotations(McpSchema.ToolAnnotations.builder()
                        .title(tool.title())
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(mcpTool)
                .callHandler((context, request) -> {
                    Object owner = context.get(OWNER_KEY);
                    if (!(owner instanceof OwnerPrincipal principal)) {
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("{\"error\":\"Not signed in to CafeQR.\"}")
                                .isError(true)
                                .build();
                    }
                    ToolExecutor.Result result = executor.execute(
                            tool.name(), request.arguments(), principal, ToolExecutor.Channel.MCP);
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(result.json())
                            .isError(result.error())
                            .build();
                })
                .build();
    }
}
