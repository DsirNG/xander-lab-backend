CREATE TABLE IF NOT EXISTS agent_weather_geocoding_rate_limit (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  next_request_at INTEGER NOT NULL DEFAULT 0
);

INSERT OR IGNORE INTO agent_weather_geocoding_rate_limit (id, next_request_at)
VALUES (1, 0);
