-- Supabase 外置记忆库数据库 Schema
-- 在 Supabase SQL Editor 中执行此脚本

-- 启用 pgvector 扩展（用于向量化存储）
CREATE EXTENSION IF NOT EXISTS vector;

-- 聊天记录表
CREATE TABLE IF NOT EXISTS chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assistant_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('user', 'assistant', 'system')),
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    
    -- 索引
    CONSTRAINT idx_chat_messages_conversation UNIQUE (conversation_id, created_at)
);

-- 阶段总结表
CREATE TABLE IF NOT EXISTS memory_summaries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assistant_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
    summary TEXT NOT NULL,
    message_count INTEGER NOT NULL DEFAULT 0,
    period_start TIMESTAMPTZ,
    period_end TIMESTAMPTZ,
    vectorized BOOLEAN DEFAULT FALSE,
    embedding VECTOR(1536), -- OpenAI text-embedding-3-small 维度
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 每日日记表
CREATE TABLE IF NOT EXISTS daily_journals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assistant_id TEXT NOT NULL,
    journal_date DATE NOT NULL,
    content TEXT NOT NULL,
    vectorized BOOLEAN DEFAULT FALSE,
    embedding VECTOR(1536),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    
    -- 每个助手每天只能有一条日记
    CONSTRAINT idx_daily_journals_unique UNIQUE (assistant_id, journal_date)
);

-- 聊天统计表（用于快速查询）
CREATE TABLE IF NOT EXISTS chat_stats (
    assistant_id TEXT PRIMARY KEY,
    total_messages INTEGER DEFAULT 0,
    total_summaries INTEGER DEFAULT 0,
    total_journals INTEGER DEFAULT 0,
    last_message_at TIMESTAMPTZ,
    last_updated TIMESTAMPTZ DEFAULT NOW()
);

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_chat_messages_assistant ON chat_messages(assistant_id);
CREATE INDEX IF NOT EXISTS idx_chat_messages_created ON chat_messages(created_at);
CREATE INDEX IF NOT EXISTS idx_chat_messages_conversation_id ON chat_messages(conversation_id);

CREATE INDEX IF NOT EXISTS idx_memory_summaries_assistant ON memory_summaries(assistant_id);
CREATE INDEX IF NOT EXISTS idx_memory_summaries_conversation ON memory_summaries(conversation_id);
CREATE INDEX IF NOT EXISTS idx_memory_summaries_created ON memory_summaries(created_at);

CREATE INDEX IF NOT EXISTS idx_daily_journals_assistant ON daily_journals(assistant_id);
CREATE INDEX IF NOT EXISTS idx_daily_journals_date ON daily_journals(journal_date);

-- 向量化搜索函数（使用 cosine 相似度）
CREATE OR REPLACE FUNCTION search_similar_summaries(
    query_embedding VECTOR(1536),
    match_threshold FLOAT,
    match_count INT,
    p_assistant_id TEXT DEFAULT NULL
)
RETURNS TABLE(
    id UUID,
    assistant_id TEXT,
    conversation_id TEXT,
    summary TEXT,
    similarity FLOAT
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        ms.id,
        ms.assistant_id,
        ms.conversation_id,
        ms.summary,
        1 - (ms.embedding <=> query_embedding) AS similarity
    FROM memory_summaries ms
    WHERE ms.vectorized = TRUE
        AND ms.embedding IS NOT NULL
        AND (p_assistant_id IS NULL OR ms.assistant_id = p_assistant_id)
        AND 1 - (ms.embedding <=> query_embedding) > match_threshold
    ORDER BY ms.embedding <=> query_embedding
    LIMIT match_count;
END;
$$ LANGUAGE plpgsql;

-- 日记向量化搜索函数
CREATE OR REPLACE FUNCTION search_similar_journals(
    query_embedding VECTOR(1536),
    match_threshold FLOAT,
    match_count INT,
    p_assistant_id TEXT DEFAULT NULL
)
RETURNS TABLE(
    id UUID,
    assistant_id TEXT,
    journal_date DATE,
    content TEXT,
    similarity FLOAT
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        dj.id,
        dj.assistant_id,
        dj.journal_date,
        dj.content,
        1 - (dj.embedding <=> query_embedding) AS similarity
    FROM daily_journals dj
    WHERE dj.vectorized = TRUE
        AND dj.embedding IS NOT NULL
        AND (p_assistant_id IS NULL OR dj.assistant_id = p_assistant_id)
        AND 1 - (dj.embedding <=> query_embedding) > match_threshold
    ORDER BY dj.embedding <=> query_embedding
    LIMIT match_count;
END;
$$ LANGUAGE plpgsql;

-- 更新统计的触发器函数
CREATE OR REPLACE FUNCTION update_chat_stats()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO chat_stats (assistant_id, total_messages, last_message_at, last_updated)
        VALUES (NEW.assistant_id, 1, NEW.created_at, NOW())
        ON CONFLICT (assistant_id) 
        DO UPDATE SET 
            total_messages = chat_stats.total_messages + 1,
            last_message_at = NEW.created_at,
            last_updated = NOW();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 消息插入触发器
DROP TRIGGER IF EXISTS trigger_update_stats ON chat_messages;
CREATE TRIGGER trigger_update_stats
    AFTER INSERT ON chat_messages
    FOR EACH ROW
    EXECUTE FUNCTION update_chat_stats();

-- 总结统计更新触发器
CREATE OR REPLACE FUNCTION update_summary_stats()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE chat_stats 
        SET total_summaries = total_summaries + 1,
            last_updated = NOW()
        WHERE assistant_id = NEW.assistant_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_update_summary_stats ON memory_summaries;
CREATE TRIGGER trigger_update_summary_stats
    AFTER INSERT ON memory_summaries
    FOR EACH ROW
    EXECUTE FUNCTION update_summary_stats();

-- 日记统计更新触发器
CREATE OR REPLACE FUNCTION update_journal_stats()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE chat_stats 
        SET total_journals = total_journals + 1,
            last_updated = NOW()
        WHERE assistant_id = NEW.assistant_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_update_journal_stats ON daily_journals;
CREATE TRIGGER trigger_update_journal_stats
    AFTER INSERT ON daily_journals
    FOR EACH ROW
    EXECUTE FUNCTION update_journal_stats();

-- 设置 RLS (Row Level Security) 策略
-- 注意：需要根据你的 Supabase 项目配置调整

-- 允许匿名用户插入（从插件）
ALTER TABLE chat_messages ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Allow anonymous insert" ON chat_messages
    FOR INSERT TO anon WITH CHECK (true);
CREATE POLICY "Allow anonymous select" ON chat_messages
    FOR SELECT TO anon USING (true);

ALTER TABLE memory_summaries ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Allow anonymous all" ON memory_summaries
    FOR ALL TO anon USING (true) WITH CHECK (true);

ALTER TABLE daily_journals ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Allow anonymous all" ON daily_journals
    FOR ALL TO anon USING (true) WITH CHECK (true);

ALTER TABLE chat_stats ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Allow anonymous all" ON chat_stats
    FOR ALL TO anon USING (true) WITH CHECK (true);

-- 注释说明
COMMENT ON TABLE chat_messages IS '存储所有聊天记录';
COMMENT ON TABLE memory_summaries IS '存储阶段总结';
COMMENT ON TABLE daily_journals IS '存储每日日记';
COMMENT ON TABLE chat_stats IS '聊天统计缓存表';