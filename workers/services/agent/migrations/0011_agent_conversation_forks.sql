-- 分支请求幂等标识；普通会话为空，重试不重复创建分支。
ALTER TABLE agent_conversation ADD COLUMN fork_key TEXT;
-- 原会话 ID，仅作为同服务内来源记录。
ALTER TABLE agent_conversation ADD COLUMN fork_source_id INTEGER;
-- 被编辑的用户消息 ID；只继承此消息之前的历史。
ALTER TABLE agent_conversation ADD COLUMN fork_message_id INTEGER;
CREATE UNIQUE INDEX idx_agent_conversation_fork_key ON agent_conversation(user_id, fork_key);
