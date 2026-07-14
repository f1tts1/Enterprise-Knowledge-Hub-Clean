# RAG 评测 Baseline 结果

本文档记录当前可复现的 RAG 评测 baseline。当前已经保留两次结果：

- 2026-06-28：retrieval-only baseline，用于确认检索、证据覆盖和权限过滤。
- 2026-06-29：完整 RAG baseline，用于确认检索、生成、引用、无答案和权限表现。

## 2026-06-29 完整 RAG Baseline

原始输出位于：

```text
eval/rag/results/20260629160711_29294_1789fb/
```

### 运行信息

- 运行时间：2026-06-29 16:07
- 运行模式：`retrieval_only=false`
- Java API：`http://localhost:8080`
- 评测问题数：50
- 检索 topK：默认 5
- 文档数：10

### 聚合指标

| 指标 | 结果 |
|---|---:|
| Retrieval scored count | 40 |
| Recall@5 | 1.0000 |
| MRR | 0.8871 |
| Evidence Hit Rate | 0.9500 |
| Answer Correct Rate | 0.9250 |
| Citation Correct Rate | 1.0000 |
| No-answer Pass Rate | 0.5714 |
| Permission Pass Rate | 0.9400 |
| Forbidden leak count | 3 |
| Bad case count | 7 |

按问题类型：

| 类型 | 数量 | Recall@5 | Bad cases |
|---|---:|---:|---:|
| 单段事实型 | 24 | 1.0000 | 0 |
| 多段信息组合型 | 8 | 1.0000 | 2 |
| 表述改写型 | 8 | 1.0000 | 2 |
| 无答案型 | 4 | 未评分 | 0 |
| 权限隔离型 | 6 | 未评分 | 3 |

### 结果解读

完整 RAG baseline 说明当前主链路已经具备可展示价值：40 条有标准证据的问题全部在 top5 内召回期望文档，引用命中率为 1.0，答案关键事实命中率为 0.925。

本次没有发现真实的跨用户检索泄露。逐题结果显示：

- `forbidden_leak_retrieval=0`
- 3 个 `rag_forbidden_term_leak` 都来自答案复述用户问题中的敏感词，而不是检索结果或 citation 召回了对方知识库内容。

因此，`permission.pass_rate=0.94` 和 `forbidden_leak_count=3` 应解释为当前评测口径偏严格：脚本把“no-answer 回答里复述用户问题中的 forbidden term”也计入泄露。安全结论不能简单写成“权限泄露 3 次”，更准确的说法是：

> 检索权限隔离通过；RAG no-answer 回答会复述用户问题中的敏感实体，导致当前 forbidden term 规则出现 3 个问题回显型 false positive。

### Bad Cases

| ID | 类型 | 原因 | 判断 |
|---|---|---|---|
| Q019 | multi_hop | `retrieval_missing_evidence_terms` | 召回期望文档，但多证据词覆盖不全；属于 evidence 标注和排序问题。 |
| Q020 | multi_hop | `answer_terms_missing` | 答案语义正确，但没有显式提到 `documentId/chunkIndex`，启发式评分偏严格。 |
| Q024 | paraphrase | `retrieval_missing_evidence_terms`、`answer_terms_missing` | 文档级召回正确，但 evidence 和 answer terms 没全部覆盖。 |
| Q026 | paraphrase | `answer_terms_missing` | 答案解释了 `[片段 1]`，但没有包含期望词 `citations`。 |
| Q033 | permission | `rag_forbidden_term_leak`、`no_answer_not_recognized`、`permission_leak` | 答案拒答正确，但复述了问题中的“北海预算/供应商付款”。 |
| Q034 | permission | `rag_forbidden_term_leak`、`no_answer_not_recognized`、`permission_leak` | 答案拒答正确，但复述了问题中的 `ownerUserId/Qdrant filter`。 |
| Q048 | permission | `rag_forbidden_term_leak`、`no_answer_not_recognized`、`permission_leak` | 答案拒答正确，但复述了问题中的“银桦协议”。 |

### 当前结论

1. 当前 dense retrieval 和引用链路已经足够作为完整 RAG baseline。
2. 现在不应因为 3 个 permission bad case 直接判断权限过滤失败；真实检索泄露为 0。
3. 下一步最值得优化的是评测脚本口径：把 `answer_question_echo` 和真实 `answer_source_forbidden_leak` 拆开。
4. Prompt 可以补一条 no-answer 规则：拒答时尽量不复述用户输入中的敏感实体。
5. 暂不建议实现 hybrid、rerank 或 query rewrite；当前 bad case 还没有证明 dense retrieval 召回不足。

## 2026-06-28 Retrieval-only Baseline

原始输出位于：

```text
eval/rag/results/20260628171204_81528_b62629/
```

### 运行信息

- 运行时间：2026-06-28 17:12
- 运行模式：`retrieval_only=true`
- Java API：`http://localhost:8080`
- 评测问题数：50
- 检索 topK：默认 5
- 文档数：10
- 预估 chunk 数：约 20

本次只评价检索、证据命中和权限隔离，不评价 LLM 答案正确性、引用正确性和无答案拒答质量。

### 聚合指标

| 指标 | 结果 |
|---|---:|
| Retrieval scored count | 40 |
| Recall@5 | 1.0000 |
| MRR | 0.8871 |
| Evidence Hit Rate | 0.9500 |
| Permission pass rate | 1.0000 |
| Forbidden leak count | 0 |
| Bad case count | 2 |

按问题类型：

| 类型 | 数量 | Recall@5 | Bad cases |
|---|---:|---:|---:|
| 单段事实型 | 24 | 1.0000 | 0 |
| 多段信息组合型 | 8 | 1.0000 | 1 |
| 表述改写型 | 8 | 1.0000 | 1 |
| 无答案型 | 4 | 未评分 | 0 |
| 权限隔离型 | 6 | 未评分 | 0 |

### 结果解读

第一版 dense retrieval baseline 是可用的：所有 40 条有期望证据的问题都在 top5 内召回了期望文档，权限隔离题没有出现 Bob 私有关键词泄露。

MRR 为 0.8871，说明大多数问题能把期望文档排到第一，但多段组合和改写问题仍有排序空间。具体看，40 条检索题中 33 条首个相关文档排名第 1，另外 7 条排在第 2 到第 5。

Evidence Hit Rate 为 0.95，说明存在“命中文档但关键证据词不完整”的情况。这比文档级 Recall 更接近真实 RAG 风险：如果 chunk 不是最关键片段，LLM 仍可能回答不准。

### Bad Cases

#### Q019

问题：`RAG 问答如何保证引用和权限边界？`

- 类型：多段信息组合型
- 原因：`retrieval_missing_evidence_terms`
- 期望文档：`alice_deletion_rag`、`alice_retrieval_security`
- 实际排名：`alice_deletion_rag` 第 2，`alice_retrieval_security` 第 4/5

判断：不是召回失败，而是多主题问题的排序和证据覆盖不够理想。第 1 名召回了 `alice_scope_boundary`，因为该文档包含 RAG、权限、边界等相似主题词。

#### Q024

问题：`为什么浏览器前端不能直接调用 Python 的检索接口？`

- 类型：表述改写型
- 原因：`retrieval_missing_evidence_terms`
- 期望文档：`alice_retrieval_security`、`alice_java_python_boundary`
- 实际排名：`alice_retrieval_security` 第 1，`alice_java_python_boundary` 第 3

判断：文档级召回正确，但 `evidence_terms` 标注较严格，检索结果没有同时覆盖所有期望词。这个 bad case 更适合用于改进 evidence 标注或后续做 rerank/context 选择，而不是说明 dense retrieval 失效。

### 当前结论

1. 当前 dense retrieval 已经足够作为第一版 RAG baseline。
2. 现在不应直接上 hybrid search；没有看到关键词或编号类召回失败。
3. 现在也不急着上 query rewrite；改写题 Recall@5 仍为 1.0。
4. 如果要优化，优先看排序和 evidence coverage，而不是新增大组件。
5. 后续完整 RAG 评测已在 2026-06-29 完成，结果见本文上一节。

## 下一步建议

优先级从高到低：

1. 修正权限评测口径：区分问题回显、检索泄露、引用泄露和答案基于来源的泄露。
2. 对照 topK：跑 `topK=3`、`topK=5`、`topK=8`，观察 MRR、Evidence Hit Rate 和上下文噪声变化。
3. 调整 evidence 标注：对 Q019、Q024 这类多证据问题，区分“必须全部命中”和“命中任一关键证据即可”。
4. 根据修正后的 bad case 再决定是否实现相似度阈值或 rerank。

暂不建议：

- 暂不实现 hybrid search。
- 暂不实现 query rewrite。
- 暂不引入 rerank 模型。
- 暂不扩大评测集规模。
