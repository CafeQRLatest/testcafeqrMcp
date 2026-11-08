# cafe-qr-ai

AI assistant service for CafeQR café owners and managers. **Read-only v1.**

One shared set of tools, exposed two ways:

| Channel | Who uses it | Who pays for the AI |
|---|---|---|
| **Built-in "Ask CafeQR" chat** (`/api/assistant/*`, page `/owner/assistant` in cafe-qr-frontend) | Every owner, no setup | Us (per question) |
| **MCP server** (`/mcp`, Streamable HTTP, stateless) | Owners using Claude, ChatGPT, Gemini or other MCP clients | The owner's own AI subscription |

```
Owner dashboard ──► /api/assistant/chat (SSE) ──► AI model ─┐
                                                            │ tool calls
Claude/ChatGPT  ──► /mcp ───────────────────────────────────┤
                                                            ▼
                                      Shared tools (CafeQrTools) ──► cafe-qr-backend REST API
                                                                     (owner's own ai:read token)
```

## Security model

- The dashboard gets a **short-lived `ai:read` token** from cafe-qr-backend (`POST /api/v1/ai/token`, SUPER_ADMIN only, 30 min).
- cafe-qr-backend **rejects every non-GET request** made with an `ai:read` token, so the assistant can never change data, even if this service has a bug.
- An `ai:read` token can only GET the assistant's own endpoints (allowlist in `cafe-qr-backend/.../ai/AiTokenPolicy.java`). Backups, payroll exports, ledgers, customer lists and everything else return 403. **A new tool that calls another endpoint must be added to that allowlist.**
- This service verifies tokens with the backend's **RSA public key** (RS256). It cannot mint tokens. Normal login tokens, other roles and foreign signatures are rejected.
- Every tool calls the existing backend endpoint **with the owner's own token**, so tenant isolation and role checks are the backend's, unchanged. No direct DB access to POS data.
- Customer names and phone numbers are never returned by tools. Tax figures are passed through as recorded; no tax rules are applied (they differ per country).
- Audit log `cafeqr.ai.audit`: tool, channel, café, user, outcome, duration. Responses are not logged.

## Tools (v1)

`get_business_info`, `list_branches`, `get_sales_summary`, `get_top_items`, `get_payment_breakdown`, `get_hourly_sales`, `get_tax_summary`, `get_profit_loss`, `list_orders`, `get_stock_levels`, `list_menu_items`, `get_table_status`. All are marked `readOnlyHint: true`.

Dates are `YYYY-MM-DD` in the café's own timezone (from `/api/v1/clients/me`); results are capped (`max-rows`, default 50).

## Run locally

Requirements: Java 21, Maven, PostgreSQL (the existing CafeQR DB; this service only uses schema `ai`), cafe-qr-backend with the `ai:read` token changes.

1. Free local model (dev / test data only):
   ```bash
   ollama pull qwen3:8b
   ```
2. Copy `.env.example` to `.env` (or export the variables) and set at least `AI_DATASOURCE_PASSWORD`.
3. Start:
   ```bash
   mvn spring-boot:run
   ```
   Service on `http://localhost:8090`. Flyway creates schema `ai` with tables `ai_conversation`, `ai_message`.
4. Frontend (`cafe-qr-frontend/.env.local`), local dev only:
   ```
   NEXT_PUBLIC_AI_API_URL=http://localhost:8090
   ```
5. Sign in as a SUPER_ADMIN, open **Settings → Enable MCP** and switch it on. "Ask CafeQR" then appears under INSIGHTS.
   The setting is per café (and per branch when branch settings are used). While it is off, the backend refuses to issue AI tokens, so neither the chat nor MCP works. Turning it off stops new tokens at once; tokens already issued expire within 30 minutes.

### Switching AI provider

Set `AI_PROVIDER` (no code change):

- `ollama`: free, local. Dev only.
- `anthropic`: production default, `ANTHROPIC_API_KEY`, model `ANTHROPIC_MODEL` (default `claude-haiku-5-5`).
- `openai`: OpenAI or any OpenAI-compatible API (Gemini, Groq, OpenRouter) via `AI_OPENAI_BASE_URL`. Do not use a free tier that trains on inputs with real café data.

### Try the MCP endpoint

Get a token while logged in to the dashboard (browser devtools or curl with your login token):

```bash
curl -s -X POST http://localhost:8080/api/v1/ai/token -H "Authorization: Bearer <login token>"
```

MCP Inspector:

```bash
npx @modelcontextprotocol/inspector
```

Transport "Streamable HTTP", URL `http://localhost:8090/mcp`, header `Authorization: Bearer <ai token>`.

Claude Desktop (`claude_desktop_config.json`), via the `mcp-remote` bridge:

```json
{
  "mcpServers": {
    "cafeqr": {
      "command": "npx",
      "args": ["mcp-remote", "http://localhost:8090/mcp", "--header", "Authorization: Bearer <ai token>"]
    }
  }
}
```

The token lasts 30 minutes (`jwt.ai-expiration` in the backend). For real owners, claude.ai/ChatGPT connectors need OAuth 2.1 (next phase).

## Tests

```bash
mvn test
```

Unit tests for every tool's date handling, ranking, PII stripping and stock joins; integration tests (H2) for token rules, MCP `tools/list` + `tools/call`, streaming chat, history and the monthly quota.

## Deploy (not yet wired into production)

Add a service next to `backend` in `cafe-qr-backend/docker-compose.prod.yml` once an image is published:

```yaml
  ai:
    image: ${AI_IMAGE:-ghcr.io/<org>/cafeqr-ai:latest}
    restart: unless-stopped
    env_file: .env.ai
    environment:
      CAFEQR_BACKEND_URL: http://backend:8080
      AI_DATASOURCE_URL: jdbc:postgresql://db:5432/cafeqr_db
      AI_PROVIDER: anthropic
    depends_on:
      - backend
    networks:
      - <same network as backend>
```

And in the `Caddyfile`, **before** `handle /api/*` (SSE must not be buffered):

```
	handle /api/assistant/* {
		reverse_proxy ai:8090 {
			flush_interval -1
		}
	}

	handle /mcp* {
		reverse_proxy ai:8090 {
			flush_interval -1
		}
	}
```

With those routes the frontend needs no `NEXT_PUBLIC_AI_API_URL`; it uses the API host.

## Not done yet (next phases)

- OAuth 2.1 authorization server so owners can add CafeQR as a connector in claude.ai / ChatGPT without pasting tokens.
- Rate limiter is in-memory per instance; move to Redis before running more than one instance.
- Quota is one number for all cafés; tie it to the subscription plan.
- Anthropic prompt caching for the system prompt and tool definitions.
- Write actions (e.g. mark item unavailable), with confirmation.
