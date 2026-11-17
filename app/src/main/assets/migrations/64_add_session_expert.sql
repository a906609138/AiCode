-- 根会话可绑定一个专家定义（experts/<name>.md），以该专家的人设与工具集运行。
-- 仅根会话使用；子代理会话仍靠 subagentType。
ALTER TABLE chat_sessions ADD COLUMN expertName TEXT;
