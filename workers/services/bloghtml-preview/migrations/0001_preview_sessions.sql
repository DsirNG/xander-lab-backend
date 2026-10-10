-- Preview bodies are stored in Alibaba OSS in deployed Workers. The inline
-- column is used only by the explicit local-inline development mode.
-- A preview session owns one temporary HTML object or one local inline body.
CREATE TABLE preview_session (
  -- 64-bit random lowercase hex id embedded in the public sandbox URL.
  session_id TEXT PRIMARY KEY
    CHECK (length(session_id) = 16 AND session_id NOT GLOB '*[^0-9a-f]*'),
  -- OSS key under the service's configured private prefix; NULL for local-inline.
  object_key TEXT,
  -- Local-development-only body; never populated in deployed OSS mode.
  inline_code TEXT,
  -- UTF-8 byte length used to reject oversized reads.
  code_size INTEGER NOT NULL CHECK (code_size BETWEEN 1 AND 262144),
  -- SHA-256 of the UTF-8 HTML body, checked before rendering.
  content_sha256 TEXT NOT NULL CHECK (length(content_sha256) = 64),
  -- Unix epoch milliseconds when this record was created.
  created_at INTEGER NOT NULL,
  -- Unix epoch milliseconds after which the public preview is no longer served.
  expires_at INTEGER NOT NULL,
  CHECK ((object_key IS NOT NULL) != (inline_code IS NOT NULL))
);

CREATE INDEX idx_preview_session_expires
  ON preview_session (expires_at);

-- Store a keyed digest of the client IP instead of the IP itself.
-- Fixed-window request counters for the public session-creation endpoint.
CREATE TABLE preview_rate_limit (
  -- HMAC-SHA256 of the trusted edge IP and internal service token.
  ip_fingerprint TEXT PRIMARY KEY,
  -- Unix epoch milliseconds at the beginning of this fixed window.
  window_started_at INTEGER NOT NULL,
  -- Number of attempted preview creations in this window.
  request_count INTEGER NOT NULL CHECK (request_count > 0),
  -- Unix epoch milliseconds of the last attempted creation.
  updated_at INTEGER NOT NULL
);

CREATE INDEX idx_preview_rate_limit_window
  ON preview_rate_limit (window_started_at);
