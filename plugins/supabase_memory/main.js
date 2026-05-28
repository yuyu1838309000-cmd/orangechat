// Supabase 外置记忆库插件
// 功能：消息同步、阶段总结、每日日记、向量化存储

// ==================== 配置和状态 ====================

const CONFIG = {
  supabaseUrl: '',
  supabaseKey: '',
  summaryBaseUrl: '',
  summaryApiKey: '',
  summaryModel: '',
  embeddingBaseUrl: '',
  embeddingApiKey: '',
  embeddingModel: '',
  phaseSummaryThreshold: 50,
  enableDailyJournal: true,
  enableVectorization: false,
  maxRetryCount: 3
};

// 本地状态
const state = {
  messageBuffer: [], // 待同步的消息缓冲
  lastSyncTime: null,
  pendingSummaries: new Map(), // 待生成的总结
  journalRetryCount: new Map() // 日记重试计数
};

// ==================== 工具函数 ====================

// 辅助函数：判断配置值是否为启用状态
function isConfigEnabled(value) {
  return value === true || value === 'true' || value === 1 || value === '1';
}

// 初始化配置
function initConfig() {
  CONFIG.supabaseUrl = config.supabase_url || '';
  CONFIG.supabaseKey = config.supabase_key || '';
  CONFIG.summaryBaseUrl = config.summary_base_url || 'https://api.openai.com/v1';
  CONFIG.summaryApiKey = config.summary_api_key || '';
  CONFIG.summaryModel = config.summary_model || 'gpt-4o-mini';
  CONFIG.embeddingBaseUrl = config.embedding_base_url || CONFIG.summaryBaseUrl;
  CONFIG.embeddingApiKey = config.embedding_api_key || CONFIG.summaryApiKey;
  CONFIG.embeddingModel = config.embedding_model || 'text-embedding-3-small';
  CONFIG.phaseSummaryThreshold = config.phase_summary_threshold || 50;
  CONFIG.enableDailyJournal = isConfigEnabled(config.enable_daily_journal);
  CONFIG.enableVectorization = isConfigEnabled(config.enable_vectorization);
  CONFIG.maxRetryCount = config.max_retry_count || 3;
}

// Supabase REST API 请求
async function supabaseRequest(table, method, data, query) {
  const url = `${CONFIG.supabaseUrl}/rest/v1/${table}${query || ''}`;
  const headers = {
    'apikey': CONFIG.supabaseKey,
    'Authorization': `Bearer ${CONFIG.supabaseKey}`,
    'Content-Type': 'application/json',
    'Prefer': method === 'POST' ? 'return=representation' : ''
  };

  try {
    const response = await fetch(url, {
      method: method,
      headers: headers,
      body: data ? JSON.stringify(data) : undefined
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Supabase error: ${response.status} - ${errorText}`);
    }

    return await response.json();
  } catch (error) {
    console.error('Supabase request failed:', error);
    throw error;
  }
}

// AI 模型请求
async function aiRequest(baseUrl, apiKey, model, messages, temperature = 0.7) {
  const url = `${baseUrl}/chat/completions`;
  
  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${apiKey}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        model: model,
        messages: messages,
        temperature: temperature
      })
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`AI API error: ${response.status} - ${errorText}`);
    }

    const result = await response.json();
    return result.choices[0].message.content;
  } catch (error) {
    console.error('AI request failed:', error);
    throw error;
  }
}

// Embedding 请求
async function embeddingRequest(text) {
  if (!CONFIG.enableVectorization) return null;
  
  const url = `${CONFIG.embeddingBaseUrl}/embeddings`;
  
  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${CONFIG.embeddingApiKey}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        model: CONFIG.embeddingModel,
        input: text
      })
    });

    if (!response.ok) {
      throw new Error(`Embedding API error: ${response.status}`);
    }

    const result = await response.json();
    return result.data[0].embedding;
  } catch (error) {
    console.error('Embedding request failed:', error);
    return null;
  }
}

// ==================== 数据库操作 ====================

// 保存消息到 Supabase
async function saveMessageToSupabase(message) {
  const data = {
    assistant_id: message.assistantId,
    conversation_id: message.conversationId,
    role: message.role,
    content: message.content,
    created_at: message.timestamp || new Date().toISOString()
  };

  return await supabaseRequest('chat_messages', 'POST', data);
}

// 获取对话消息数
async function getMessageCount(conversationId) {
  const result = await supabaseRequest(
    'chat_messages',
    'GET',
    null,
    `?conversation_id=eq.${conversationId}&select=id`
  );
  return result.length;
}

// 获取最近消息用于总结
async function getRecentMessages(conversationId, limit) {
  const result = await supabaseRequest(
    'chat_messages',
    'GET',
    null,
    `?conversation_id=eq.${conversationId}&order=created_at.desc&limit=${limit}`
  );
  return result.reverse(); // 按时间正序
}

// 保存阶段总结
async function savePhaseSummary(conversationId, assistantId, summary, messageCount, startTime, endTime) {
  const embedding = CONFIG.enableVectorization ? await embeddingRequest(summary) : null;
  
  const data = {
    assistant_id: assistantId || 'unknown',
    conversation_id: conversationId,
    summary: summary,
    message_count: messageCount,
    period_start: startTime,
    period_end: endTime,
    vectorized: CONFIG.enableVectorization && embedding !== null,
    embedding: embedding
  };

  return await supabaseRequest('memory_summaries', 'POST', data);
}

// 保存日记
async function saveDailyJournal(assistantId, date, content) {
  const embedding = CONFIG.enableVectorization ? await embeddingRequest(content) : null;
  
  const data = {
    assistant_id: assistantId,
    journal_date: date,
    content: content,
    vectorized: CONFIG.enableVectorization && embedding !== null,
    embedding: embedding
  };

  return await supabaseRequest('daily_journals', 'POST', data);
}

// 获取统计信息
async function getStats(assistantId) {
  let messages = [];
  let summaries = [];
  let journals = [];
  
  // 如果未指定 assistantId 或为 'default'，查询所有记录
  const filter = assistantId && assistantId !== 'default' 
    ? `?assistant_id=eq.${assistantId}&select=id` 
    : `?select=id`;
  
  try {
    messages = await supabaseRequest('chat_messages', 'GET', null, filter);
  } catch (e) {
    // 表不存在时忽略
  }
  
  try {
    summaries = await supabaseRequest('memory_summaries', 'GET', null, filter);
  } catch (e) {
    // 表不存在时忽略
  }
  
  try {
    journals = await supabaseRequest('daily_journals', 'GET', null, filter);
  } catch (e) {
    // 表不存在时忽略
  }

  return {
    success: true,
    data: {
      totalMessages: messages.length,
      totalSummaries: summaries.length,
      totalJournals: journals.length
    }
  };
}

// ==================== 总结生成 ====================

// 生成阶段总结
async function generatePhaseSummary(conversationId, messages) {
  const messageText = messages.map(m => {
    const role = m.role === 'user' ? '用户' : 'AI';
    return `${role}: ${m.content}`;
  }).join('\n\n');

  const prompt = `请对以下对话进行阶段总结，提取关键信息、用户偏好、重要事实等：

${messageText}

请用中文总结，要求：
1. 简洁明了，突出重点
2. 包含用户的偏好、需求、重要信息
3. 适合作为后续对话的上下文参考`;

  const summary = await aiRequest(
    CONFIG.summaryBaseUrl,
    CONFIG.summaryApiKey,
    CONFIG.summaryModel,
    [{ role: 'user', content: prompt }],
    0.7
  );

  return summary;
}

// 生成日记
async function generateDailyJournal(assistantId, date, messages) {
  const messageText = messages.map(m => {
    const role = m.role === 'user' ? '用户' : 'AI';
    return `[${m.created_at}] ${role}: ${m.content.substring(0, 200)}${m.content.length > 200 ? '...' : ''}`;
  }).join('\n');

  const prompt = `请根据以下${date}的聊天记录，生成一篇日记风格的总结：

${messageText}

请用第一人称写日记风格，包含：
1. 今天聊了什么话题
2. 有什么重要信息或决定
3. 用户的情绪或状态
4. 明天可能需要跟进的事项

要求：温暖、自然、像朋友间的记录。`;

  const journal = await aiRequest(
    CONFIG.summaryBaseUrl,
    CONFIG.summaryApiKey,
    CONFIG.summaryModel,
    [{ role: 'user', content: prompt }],
    0.8
  );

  return journal;
}

// ==================== 事件处理 ====================

// 消息发送时
async function onMessageSent(event) {
  initConfig();
  
  if (!CONFIG.supabaseUrl || !CONFIG.supabaseKey) {
    return;
  }

  const message = {
    assistantId: event.assistant_id,
    conversationId: event.conversation_id,
    role: 'user',
    content: event.message,
    timestamp: new Date().toISOString()
  };

  try {
    await saveMessageToSupabase(message);
    
    // 检查是否需要触发阶段总结
    const count = getMessageCount(event.conversation_id);
    if (count % CONFIG.phaseSummaryThreshold === 0 && count > 0) {
      // 直接调用（QuickJS沙箱中setTimeout不可靠）
      triggerPhaseSummary(event.conversation_id, event.assistant_id);
    }
  } catch (error) {
    console.error('Failed to sync message:', error);
  }
}

// 消息接收时
async function onMessageReceived(event) {
  initConfig();
  
  if (!CONFIG.supabaseUrl || !CONFIG.supabaseKey) {
    return;
  }

  const message = {
    assistantId: event.assistant_id,
    conversationId: event.conversation_id,
    role: 'assistant',
    content: event.message,
    timestamp: new Date().toISOString()
  };

  try {
    await saveMessageToSupabase(message);
  } catch (error) {
    console.error('Failed to sync message:', error);
  }
}

// 触发阶段总结
async function triggerPhaseSummary(conversationId, assistantId) {
  try {
    const messages = await getRecentMessages(conversationId, CONFIG.phaseSummaryThreshold);
    if (messages.length < 5) return; // 消息太少不总结

    const summary = await generatePhaseSummary(conversationId, messages);
    const startTime = messages[0].created_at;
    const endTime = messages[messages.length - 1].created_at;

    // 从消息中提取 assistant_id（优先使用参数，其次从消息中获取）
    const resolvedAssistantId = assistantId || (messages[0] && messages[0].assistant_id) || 'unknown';

    await savePhaseSummary(
      conversationId,
      resolvedAssistantId,
      summary,
      messages.length,
      startTime,
      endTime
    );

    console.log(`Phase summary generated for ${conversationId}`);
  } catch (error) {
    console.error('Failed to generate phase summary:', error);
  }
}

// 每日定时任务（凌晨3点）
async function onDailyCron(event) {
  initConfig();
  
  if (!CONFIG.enableDailyJournal) return;
  if (!CONFIG.supabaseUrl || !CONFIG.supabaseKey) return;

  const yesterday = new Date();
  yesterday.setDate(yesterday.getDate() - 1);
  const dateStr = yesterday.toISOString().split('T')[0];

  try {
    // 获取昨天的所有消息
    const result = await supabaseRequest(
      'chat_messages',
      'GET',
      null,
      `?created_at=gte.${dateStr}T00:00:00&created_at=lt.${dateStr}T23:59:59&order=created_at.asc`
    );

    if (result.length === 0) {
      console.log('No messages yesterday, skipping journal');
      return;
    }

    // 按 assistant 分组
    const messagesByAssistant = {};
    result.forEach(m => {
      if (!messagesByAssistant[m.assistant_id]) {
        messagesByAssistant[m.assistant_id] = [];
      }
      messagesByAssistant[m.assistant_id].push(m);
    });

    // 为每个 assistant 生成日记
    for (const [assistantId, messages] of Object.entries(messagesByAssistant)) {
      await generateJournalWithRetry(assistantId, dateStr, messages);
    }
  } catch (error) {
    console.error('Daily cron failed:', error);
  }
}

// 带重试的日记生成
async function generateJournalWithRetry(assistantId, date, messages, retryCount = 0) {
  try {
    const journal = await generateDailyJournal(assistantId, date, messages);
    await saveDailyJournal(assistantId, date, journal);
    console.log(`Daily journal generated for ${assistantId} on ${date}`);
    state.journalRetryCount.delete(`${assistantId}_${date}`);
  } catch (error) {
    console.error(`Journal generation failed (attempt ${retryCount + 1}):`, error);
    
    const key = `${assistantId}_${date}`;
    const currentRetry = state.journalRetryCount.get(key) || 0;
    
    if (currentRetry < CONFIG.maxRetryCount) {
      state.journalRetryCount.set(key, currentRetry + 1);
      // 延迟重试
      setTimeout(() => {
        generateJournalWithRetry(assistantId, date, messages, currentRetry + 1);
      }, 60000 * (currentRetry + 1)); // 递增延迟
    } else {
      console.error(`Journal generation failed after ${CONFIG.maxRetryCount} retries`);
      state.journalRetryCount.delete(key);
    }
  }
}

// ==================== 工具函数 ====================

// 搜索记忆
async function memory_search(params) {
  initConfig();
  
  const { query, type = 'all', limit = 10 } = params;
  
  try {
    let results = [];
    
    if (type === 'all' || type === 'summary') {
      try {
        const summaries = await supabaseRequest(
          'memory_summaries',
          'GET',
          null,
          `?order=created_at.desc&limit=${limit}`
        );
        results.push(...summaries.map(s => ({ ...s, _type: 'summary' })));
      } catch (e) {
        // 表不存在时忽略
      }
    }
    
    if (type === 'all' || type === 'journal') {
      try {
        const journals = await supabaseRequest(
          'daily_journals',
          'GET',
          null,
          `?order=journal_date.desc&limit=${limit}`
        );
        results.push(...journals.map(j => ({ ...j, _type: 'journal' })));
      } catch (e) {
        // 表不存在时忽略
      }
    }

    // 简单关键词过滤
    if (query) {
      const lowerQuery = query.toLowerCase();
      results = results.filter(r => {
        const text = (r.summary || r.content || '').toLowerCase();
        return text.includes(lowerQuery);
      });
    }

    return {
      success: true,
      data: results.slice(0, limit)
    };
  } catch (error) {
    return {
      success: false,
      error: error.message
    };
  }
}

// 获取统计
async function memory_get_stats(params) {
  initConfig();
  
  try {
    // 尝试从上下文获取 assistantId
    const assistantId = params.assistantId || 'default';
    return await getStats(assistantId);
  } catch (error) {
    return {
      success: false,
      error: error.message
    };
  }
}

// 手动触发总结
async function memory_manual_summary(params) {
  initConfig();
  
  const { conversation_id } = params;
  
  try {
    await triggerPhaseSummary(conversation_id);
    return {
      success: true,
      message: '阶段总结已触发'
    };
  } catch (error) {
    return {
      success: false,
      error: error.message
    };
  }
}

// 手动生成日记
async function memory_manual_journal(params) {
  initConfig();
  
  const date = params.date || new Date(Date.now() - 86400000).toISOString().split('T')[0];
  
  try {
    const result = await supabaseRequest(
      'chat_messages',
      'GET',
      null,
      `?created_at=gte.${date}T00:00:00&created_at=lt.${date}T23:59:59&order=created_at.asc`
    );

    if (result.length === 0) {
      return {
        success: false,
        error: '该日期没有聊天记录'
      };
    }

    const messagesByAssistant = {};
    result.forEach(m => {
      if (!messagesByAssistant[m.assistant_id]) {
        messagesByAssistant[m.assistant_id] = [];
      }
      messagesByAssistant[m.assistant_id].push(m);
    });

    for (const [assistantId, messages] of Object.entries(messagesByAssistant)) {
      const journal = await generateDailyJournal(assistantId, date, messages);
      await saveDailyJournal(assistantId, date, journal);
    }

    return {
      success: true,
      message: `已生成 ${Object.keys(messagesByAssistant).length} 个日记`
    };
  } catch (error) {
    return {
      success: false,
      error: error.message
    };
  }
}

// ==================== 导出 ====================

exports.onMessageSent = onMessageSent;
exports.onMessageReceived = onMessageReceived;
exports.onDailyCron = onDailyCron;
exports.memory_search = memory_search;
exports.memory_get_stats = memory_get_stats;
exports.memory_manual_summary = memory_manual_summary;
exports.memory_manual_journal = memory_manual_journal;