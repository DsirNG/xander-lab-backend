ALTER TABLE agent_attachment ADD COLUMN media_asset_id INTEGER;

CREATE INDEX idx_agent_attachment_media_asset ON agent_attachment (user_id, media_asset_id);
