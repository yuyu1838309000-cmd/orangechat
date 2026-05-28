# Supabase 外置记忆库插件

基于 Supabase 的外置记忆库插件，支持聊天记录同步、自动阶段总结、每日日记生成，可选择向量化存储。

## 功能特性

- 📊 **消息同步** - 自动将聊天记录同步到 Supabase
- 📝 **阶段总结** - 达到设定消息数自动触发 AI 总结
- 📔 **每日日记** - 每天凌晨 3 点自动生成日记
- 🔍 **向量化** - 可选对总结和日记进行向量化（默认关闭）
- 📈 **统计面板** - 查看总消息数、总结数、日记数

## 安装步骤

### 1. 创建 Supabase 项目

1. 访问 [Supabase](https://supabase.com) 创建新项目
2. 进入项目的 SQL Editor
3. 复制执行 `supabase_schema.sql` 中的全部内容

### 2. 获取 API 凭证

1. 在 Supabase 项目设置中找到 **Project URL** 和 **Project API keys**
2. 复制 `anon` public API key（不是 service_role key）

### 3. 配置插件

在插件配置中填写：

```
🔗 Supabase 配置
- Supabase URL: https://your-project.supabase.co
- Supabase API Key: your-anon-key

📝 总结模型配置
- Base URL: https://api.openai.com/v1 (或其他兼容 API)
- API Key: your-api-key
- 模型: gpt-4o-mini (或其他模型)
- 阶段总结阈值: 50 (每50条消息触发)

📔 每日日记
- 启用每日日记: ✅ (每天03:00自动生成)

🔍 向量化 (可选)
- 启用向量化: ⬜ (默认关闭)
- Embedding Base URL: (可选，默认使用总结模型配置)
- Embedding API Key: (可选)
- Embedding 模型: text-embedding-3-small
```

## 数据库表结构

| 表名 | 说明 |
|------|------|
| `chat_messages` | 存储所有聊天记录 |
| `memory_summaries` | 存储阶段总结 |
| `daily_journals` | 存储每日日记 |
| `chat_stats` | 聊天统计缓存 |

## 工具函数

插件提供以下工具供 AI 调用：

| 工具名 | 功能 |
|--------|------|
| `memory_search` | 搜索总结和日记 |
| `memory_get_stats` | 获取统计信息 |
| `memory_manual_summary` | 手动触发阶段总结 |
| `memory_manual_journal` | 手动生成日记 |

## 使用说明

### 阶段总结
- 每达到设定的消息数（默认50条），自动调用 AI 生成阶段总结
- 总结内容包含关键信息、用户偏好、重要事实
- 可在插件 UI 中查看所有历史总结

### 每日日记
- 每天凌晨 3 点自动执行
- 基于前一天的所有聊天记录生成
- 失败自动重试 3 次，之后可手动触发

### 向量化
- 默认关闭，需要额外配置 Embedding API
- 开启后会将总结和日记转换为向量存储
- 支持语义搜索（需要 Supabase pgvector 扩展）

## 注意事项

1. **API Key 安全** - 建议为插件单独创建 API Key，不要直接使用主账号 Key
2. **数据隐私** - 聊天记录存储在你的 Supabase 项目中
3. **网络要求** - 需要设备能访问 Supabase 和 AI API
4. **存储空间** - 大量聊天记录会占用 Supabase 存储空间

## 故障排除

### 连接失败
- 检查 Supabase URL 和 Key 是否正确
- 确认设备网络可以访问 Supabase

### 总结/日记未生成
- 检查总结模型配置是否正确
- 查看 AI API 是否有额度
- 检查插件日志

### 向量化失败
- 确认已启用向量化开关
- 检查 Embedding API 配置
- 确认 Supabase 已启用 pgvector 扩展

## 更新日志

### v1.0.0
- 初始版本
- 支持消息同步、阶段总结、每日日记
- 可选向量化存储
- 美观的 UI 界面