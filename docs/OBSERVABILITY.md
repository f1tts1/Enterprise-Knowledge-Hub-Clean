# 基础可观测性

本文记录 P0-2 已落地的可观测边界。目标是用最小工程闭环回答“一次上传索引、检索或 RAG 问答慢/失败在哪一段”，不是建设完整 tracing、告警或 RAG 实验平台。

## 1. requestId 关联规则

### 同步 HTTP 链路

1. 客户端可以向 Java API 传入 `X-Request-Id`。
2. Java 只接受 1～64 字符且匹配 `[A-Za-z0-9._:-]+` 的值；缺失或非法时生成 UUID。
3. Java 把最终值写入 MDC、`ApiResponse.requestId` 和响应 `X-Request-Id`。
4. Java WebClient 调用 FastAPI 时继续设置同一个 header。
5. FastAPI 中间件再次执行同样的安全校验，通过 `ContextVar` 隔离并发请求，并在响应头和 `app.*` 日志中使用最终值。
6. FastAPI 调用 OpenAI-compatible LLM 时继续传递同一个 `X-Request-Id`。

因此，一次 Java 检索或 RAG 请求可以通过同一 requestId 查询 Java 入口/服务日志、FastAPI 请求/pipeline 日志和 CHAT `model_call_log`。

### 异步索引链路

上传请求结束后，worker 可能延迟执行或重投，不能把短生命周期 HTTP requestId 当成索引 attempt 身份。当前规则是：

```text
index-task-{indexingTaskId}
```

- RabbitMQ producer 用它作为 AMQP correlationId 和发布日志 MDC。
- 消息 body 继续只包含 `documentId` 和 `indexingTaskId`。
- consumer 不信任消息属性中的任意 correlationId；成功解析消息后，从 taskId 重新生成同一标识。
- consumer、索引 Service、Java→FastAPI WebClient 和 Python pipeline 使用同一标识。
- 同一 PENDING attempt 重投时 requestId 稳定；FAILED 业务重试会新建 task，因此自然生成新的关联 ID。

requestId 只用于排障，不参与鉴权、幂等、current task fencing 或状态判断。

## 2. Python 阶段字段

所有耗时都使用 Python 进程内单调时钟计算，单位为非负整数毫秒。

### 文档索引响应

| 字段 | 含义 |
|---|---|
| `download_latency_ms` | 从 MinIO 下载对象 |
| `parse_latency_ms` | loader 解析原文件 |
| `split_latency_ms` | 文本切分 chunk |
| `embedding_latency_ms` | 批量生成文档 embedding |
| `vector_store_latency_ms` | Qdrant 删除旧点并写入新点；`CHUNKED` 调试路径为 0 |
| `total_latency_ms` | Python 索引 pipeline 总耗时 |

### 向量检索响应

| 字段 | 含义 |
|---|---|
| `embedding_provider` / `embedding_model` | query embedding 实现 |
| `embedding_latency_ms` | query embedding 耗时 |
| `vector_store_latency_ms` | Qdrant filter 检索耗时 |
| `total_latency_ms` | Python retrieval pipeline 总耗时 |

Java 在 MySQL 二次过滤时保留这些字段，因此既能看到 Python 命中数，也能记录 Java 权限过滤后的最终 hitCount。

### RAG 生成响应

| 字段 | 含义 |
|---|---|
| `llm_latency_ms` | 非流式 Chat Completions 完整 HTTP 调用耗时 |
| `prompt_tokens` | provider 可选 prompt usage |
| `completion_tokens` | provider 可选 completion usage |
| `total_tokens` | provider 可选总 usage；缺失时 Python 仅在前两项均存在时计算 |

provider 不返回 usage 时字段为 `null`，不能伪造为已知的真实 0。`llm_latency_ms` 不是首 token 延迟；当前没有 SSE，因此没有 TTFT 指标。

### 失败阶段日志

失败响应通常无法携带完整的成功 DTO，因此 FastAPI 在异常原样传播前记录安全的阶段枚举：

- 索引：`download`、`parse`、`split`、`embedding`、`vector_store`。
- 检索：`embedding`、`vector_store`。
- 生成：`configuration`、`llm_http`、`response_parse`。

失败日志只包含 requestId、允许的业务 ID、`failure_stage`、累计耗时和异常类型，不包含异常 message/stack、对象 key、query/question、prompt/context/answer 或凭证。

## 3. Java 结构化日志

Java console pattern 会自动输出 `[requestId=...]`。关键业务日志额外包含有限的排障字段：

- Java→Python HTTP：operation、method、path、status、durationMs、errorType。
- 索引：documentId、taskId、状态、chunk 统计、provider/model、Python 各阶段耗时、Java 总耗时。
- 检索：userId、kbId、requestedTopK、aiTopK、Java 过滤后 hitCount、embedding/Qdrant/Python/Java 耗时。
- RAG：outcome、userId、kbId、contextCount、retrieval/generation/total 耗时、provider/model 和可用 usage。
- Python：request_id、operation、必要业务 ID/数量、阶段耗时和 HTTP status。

日志不记录完整 question、query、prompt、chunk、answer、Authorization、API Key 或文件正文。异常路径优先记录异常类型；`model_call_log` 的错误文本还会做凭证脱敏、疑似模型正文整体替换和长度限制。

## 4. model_call_log 语义

现有 V1 schema 的 `model_call_log` 已由 Java Mapper/Service 使用，写入是 best-effort：插入失败只产生脱敏告警，不能回滚或改写已经提交的索引、检索、RAG 业务结果。

当前写入规则：

| 场景 | call_type | 是否写入 | 说明 |
|---|---|---|---|
| 索引成功且 MySQL SUCCESS/INDEXED 已提交 | `EMBEDDING` | 是 | 记录 Python 返回的 provider/model 和 embedding latency |
| 下载、解析、embedding 或 Qdrant pipeline 失败 | — | 否 | Python 日志有失败阶段，但失败 HTTP 响应没有把该阶段作为可信模型调用结果返回 Java；避免把所有失败误归类成 embedding 失败 |
| 有已索引文档且 Python retrieval 成功 | `EMBEDDING` | 是 | 记录 query embedding latency |
| 无已索引文档，Java 直接返回空结果 | — | 否 | 没有发生模型调用 |
| LLM 生成成功 | `CHAT` | 是 | 记录 provider/model、latency 和可用 prompt/completion usage |
| 已发起生成但配置、HTTP 或响应处理失败 | `CHAT` | 是 | `success=0`，错误只保存安全类型/脱敏摘要 |
| 无上下文，Java 直接返回兜底答案 | — | 否 | 不创建伪造 CHAT 记录 |

表中没有 `total_tokens` 列，当前只持久化 prompt/completion tokens；provider usage 缺失时受现有非空 schema 限制写为 0，因此这类行不能解释为“provider 明确返回了 0 token”。当前也没有价格表或成本金额计算。

示例排查 SQL（仅为查询模板，不是运行结果）：

```sql
SELECT request_id,
       user_id,
       provider,
       model,
       call_type,
       prompt_tokens,
       completion_tokens,
       latency_ms,
       success,
       error_message,
       created_at
FROM model_call_log
WHERE request_id = 'replace-with-request-id'
ORDER BY id;
```

```sql
SELECT call_type,
       success,
       COUNT(*) AS call_count,
       ROUND(AVG(latency_ms), 1) AS avg_latency_ms,
       MAX(latency_ms) AS max_latency_ms,
       SUM(prompt_tokens) AS prompt_tokens,
       SUM(completion_tokens) AS completion_tokens
FROM model_call_log
WHERE created_at >= NOW() - INTERVAL 1 DAY
GROUP BY call_type, success
ORDER BY call_type, success DESC;
```

当前没有 retention/归档任务或对外查询 API；长期运行前需要单独决定保留周期，但本阶段不扩展成日志平台。

## 5. Micrometer 指标

Java 逻辑指标名和低基数标签如下：

| Micrometer 名称 | 类型 | 标签 | 语义 |
|---|---|---|---|
| `ekb.ai.http.duration` | Timer | `operation`, `outcome` | Java→Python HTTP 调用 |
| `ekb.indexing.attempt.duration` | Timer | `outcome`, `failure_stage` | 业务终态已经提交的索引 attempt；包含成功、业务失败和 timeout |
| `ekb.retrieval.duration` | Timer | `operation=search`, `outcome` | Java retrieval 总耗时 |
| `ekb.retrieval.hits` | DistributionSummary | `outcome` | Java 权限/状态二次过滤后返回 chunk 数 |
| `ekb.rag.duration` | Timer | `operation=ask`, `outcome` | Java RAG 总耗时 |
| `ekb.rag.phase.duration` | Timer | `phase`, `outcome` | retrieval/generation 分阶段耗时 |
| `ekb.model.call.duration` | Timer | `operation`, `outcome` | `operation` 当前为 `EMBEDDING` 或 `CHAT` |
| `ekb.llm.tokens` | Counter | `type` | provider 已报告的 prompt/completion token 累计值 |

`application` 是固定的 Spring 应用名；`operation`、`outcome`、`failure_stage`、`phase`、`type` 都是代码内有限枚举。requestId、用户/知识库/文档/task ID、模型名、URL、异常全文不进入标签，避免时序 series 随业务数据无限增长。

Prometheus registry 会把点号转换为下划线，并为 Timer/Counter 添加 `_seconds`、`_count`、`_sum`、`_total` 等后缀；具体 exposition 以后端当前 Micrometer 版本实际输出为准，可以按 `ekb_` 前缀筛选。

## 6. Prometheus 端点

`application.yml` 只暴露：

```text
GET /actuator/prometheus
```

`SecurityConfig` 没有为该路径增加 `permitAll`，所以它继续受 `.anyRequest().authenticated()` 保护：

```bash
curl http://localhost:8080/actuator/prometheus \
  -H "Authorization: Bearer {accessToken}"
```

返回值是 Actuator 的 Prometheus text exposition，不是 `ApiResponse<T>`。当前没有恢复 `/health`、`/api/v1/health` 或 `/actuator/health` 暴露，也没有配置 Prometheus server、Grafana dashboard、alert rule 或长期存储。

## 7. 验证边界

代码中有针对 requestId 归一化/传播、RabbitMQ 异步关联、`model_call_log` best-effort、索引/检索/RAG 观测分支、观测旁路异常以及 Python middleware/pipeline 成功和失败阶段的测试。2026-07-15 最终验证为 Java 46/46、Python 11/11，Python `compileall` 与 `git diff --check` 通过。实际 Prometheus 文本、跨进程日志和数据库行仍必须在本地依赖启动后用真实调用留证；本文不提供虚构运行数据。

当前 P0-2 明确不包含：

- Grafana dashboard、告警和生产级 SLO。
- OpenTelemetry/Jaeger 等分布式 tracing 平台。
- SSE 与 LLM TTFT。
- 模型价格表和 token 金额成本。
- 正式 RAG 评测、实验管理或在线 bad-case 平台；现有固定集只作为轻量回归基线。
- Java→FastAPI 服务 token/mTLS。当前内部接口仍依赖部署网络边界，FastAPI 不能直接暴露到不受信任网络。
