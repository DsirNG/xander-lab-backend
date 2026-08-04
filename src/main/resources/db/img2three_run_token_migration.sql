-- 为现有图片转 3D 任务增加执行代次令牌，防止超时后的旧工作线程覆盖新重试。
ALTER TABLE `img2three_task`
  ADD COLUMN `run_token` VARCHAR(36) NULL COMMENT '当前执行代次令牌，用于隔离超时重试与旧工作线程' AFTER `stage`;

ALTER TABLE `img2three_task`
  COMMENT = '图片转过程式 Three.js 持久化任务';
