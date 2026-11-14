# Agent web research

The existing Agent model plans queries and writes answers. Web search and page
extraction use Tavily's HTTP API and do not require a second model. Search credits
belong to the configured Tavily account; they are separate from model token usage.

## Administration

Open `/workspace/admin/agent-web-search` as an administrator. Save a Tavily API Key,
enable research, and use **Test connection**. The test makes one real basic search
with one result and consumes provider credits. It does not run a model or write
test evidence into conversations.

The admin-only Gateway routes belong to Agent, not Core:

- `GET /api/admin/agent-web-search`: enabled state, provider, key presence,
  encryption readiness and updated time. Never returns a plaintext or masked key.
- `PUT /api/admin/agent-web-search`: `{ enabled, apiKey?, clearApiKey? }`.
  Omitted/empty `apiKey` preserves a saved key; a nonempty key replaces it.
  `clearApiKey: true` removes it and requires `enabled: false`.
- `POST /api/admin/agent-web-search/test`: tests only the saved, enabled settings.

Apply `migrations/0012_agent_web_settings.sql` to Agent D1 before deployment.
Every service still owns its own database. The singleton table stores enabled
state, versioned AES-GCM ciphertext, the editing administrator ID, and UTC time.
No credential is stored in frontend state after a successful save.

Credential encryption needs a stable Base64-encoded 32-byte
`AGENT_WEB_CREDENTIAL_ENCRYPTION_KEY` Worker Secret. When absent, the existing
`AGENT_MCP_CREDENTIAL_ENCRYPTION_KEY` is used. Preserve the key across deployments;
replacing it requires re-entering the search API Key. Admin responses and provider
errors never expose either key. No migration runs automatically in production.

Without an admin row, `TAVILY_API_KEY` is an optional environment fallback.
`AGENT_WEB_SEARCH_ENABLED=false` disables that fallback and native search.
An admin row takes precedence over both environment variables. Disabling it or
removing its key must never re-enable another search provider. Changes apply at
the next turn; an already-running turn keeps its existing configuration.
Configuration/decryption failures disable research while ordinary chat continues.
Before any settings exist, installations without a Tavily key retain their
previous upstream Responses search behavior for compatibility.

For local development, `pnpm dev` forwards the search and encryption environment
variables into its temporary ignored `.dev.vars` file. Configure real credentials
outside source control. The API base URL is fixed to `https://api.tavily.com`.

## Agent tools and evidence

`search_web` accepts `query` (1–500 characters), `limit` (1–8, default 5), optional
`timeRange` (`day|week|month|year`) and up to five public DNS domains.
`read_webpage` accepts a public HTTP(S) URL and optional `maxChars` (1,000–12,000).

Both tools register through `AgentToolRegistry` and run through
`AgentToolExecutionPipeline`, including read-only delegated subtasks. Each turn
shares a budget of three searches, five reads and 48,000 extracted characters.
Failures consume a call; validated cached results do not. The maximum provider
response is 512,000 bytes and each request has a 15-second deadline and shares
the turn cancellation signal. Requests are not automatically retried.

Provider calls use only fixed HTTPS search/extract endpoints. The Worker never
fetches a model-supplied URL. Targets reject credentials, IP literals, non-web
protocols, nonstandard ports, and local/internal hostnames. The remote extraction
provider owns actual target resolution, redirects and page access policy. Login
pages and sites restricting extraction can fail; no browser bypass is attempted.

Results distinguish `search_snippets` from `page_content`, are marked untrusted,
and include stable URL-derived `sourceId`, URL, title, snippet, UTC `retrievedAt`,
nullable `publishedAt`, and bounded extracted content where requested. Missing
publication dates remain unknown. The model is instructed to ignore webpage
instructions, keep private materials out of queries and cite the returned URLs.
Citation completeness and factual accuracy still depend on the Agent model.

Start/result/error events carry invocation IDs and persist in the existing
conversation event log. The Web frontend reuses the same source cards for live
results and owned conversation restoration. Public sharing keeps the existing
policy: private workspace tool results are not exported. Answer citations remain
ordinary links. No new public search endpoint is exposed.

## Verification

From `workers/`: `pnpm check`.
From `xander-lab-frontend/`: run focused web-research/admin Vitest tests,
changed-file ESLint, locale syntax checks, `pnpm run check:i18n`,
`pnpm run check:architecture`, and `pnpm run build`.
Tests mock provider/model HTTP traffic; no automated check uses a real API key
or spends search credits. Admin's manual connection test is the live check.
