ALTER TABLE blog_agent_task
  ADD COLUMN generation_mode TEXT NOT NULL DEFAULT 'full'
  CHECK (generation_mode IN ('quick', 'full'));
