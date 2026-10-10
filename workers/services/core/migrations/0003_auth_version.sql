-- Password resets and changes increment auth_version so older access/refresh
-- JWTs are invalidated without keeping a per-user Redis set of raw tokens.
ALTER TABLE sys_user
  ADD COLUMN auth_version INTEGER NOT NULL DEFAULT 0 CHECK (auth_version >= 0);
