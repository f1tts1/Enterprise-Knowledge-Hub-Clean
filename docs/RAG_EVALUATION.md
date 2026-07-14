# RAG 评测基线

本文档记录当前仓库新增的最小可信 RAG 评测方案。它的目标不是论文级评测，而是为求职展示提供可复现的检索、生成、引用、无答案和权限隔离证据。

## 评测资产

```text
eval/rag/
  eval_config.json        评测用户、知识库和文档配置
  questions.jsonl         评测问题集
  documents/              用于评测上传的固定文档
  results/                每次评测运行生成的结果目录

scripts/run_rag_eval.py   评测执行脚本
```

当前问题集包含 50 条问题：

- 单段事实型：24 条
- 多段信息组合型：8 条
- 表述改写型：8 条
- 无答案型：4 条
- 权限隔离型：6 条

当前固定语料包含 10 篇文档。按默认 `chunk_size=800`、`chunk_overlap=120` 估算，每篇文档会切成约 2 个 chunk，总计约 20 个 chunk；每篇第二个 chunk 都保留了足够正文，避免评测退化成“一个文档一个 chunk”的冒烟测试。

每条问题记录：

- `question`
- `expected_answer`
- `expected_doc_keys`
- `expected_chunk_indexes`
- `evidence_terms`
- `answer_terms`
- `forbidden_terms`
- `user_key`
- `kb_key`
- `question_type`
- `should_answer`
- `expected_error_code`，仅用于跨用户访问应失败的问题

`expected_doc_keys` 会在脚本上传文档后映射成真实 `docId`。当前评测文档已经扩展到多篇、多主题和多个 chunk。`expected_chunk_indexes` 暂时只作为人工复盘参考，自动指标优先使用文档级 Recall@K 和 `evidence_terms`，避免切分参数变化后让评测集过于脆弱。

## 前置条件

需要启动完整业务链路：

```text
MySQL
Redis
MinIO
Qdrant
FastAPI AI Service
Spring Boot Backend
```

如果要评测生成质量，还需要 FastAPI 进程可读取 `DEEPSEEK_API_KEY` 或显式 `LLM_*` 配置。没有 LLM 配置时，可以先运行 retrieval-only 基线。

## 运行命令

完整 RAG 评测：

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub"
python3 scripts/run_rag_eval.py
```

只评测检索，不调用 LLM：

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub"
python3 scripts/run_rag_eval.py --retrieval-only
```

只跑少量问题做冒烟验证：

```bash
python3 scripts/run_rag_eval.py --retrieval-only --max-questions 5
```

指定后端地址：

```bash
BASE_URL=http://localhost:8081 python3 scripts/run_rag_eval.py --retrieval-only
```

## 输出结果

每次运行会创建：

```text
eval/rag/results/{run_id}/
  run_config.json
  results.jsonl
  summary.json
  bad_cases.md
```

`summary.json` 包含：

- `retrieval.recall_at_k`
- `retrieval.mrr`
- `retrieval.evidence_hit_rate`
- `generation.answer_correct_rate`
- `generation.citation_correct_rate`
- `generation.no_answer_pass_rate`
- `permission.pass_rate`
- `permission.forbidden_leak_count`
- `bad_case_count`

`results.jsonl` 保存逐题结果，包括命中文档、首个相关文档排名、引用是否命中、答案是否包含关键事实、是否出现 forbidden terms。

`bad_cases.md` 用于后续人工复盘，重点判断失败属于：

- `retrieval_miss_expected_doc`
- `retrieval_missing_evidence_terms`
- `answer_terms_missing`
- `citation_missing_expected_doc`
- `no_answer_not_recognized`
- `permission_leak`

## 当前 Baseline

最新已记录 baseline 见 `docs/RAG_EVALUATION_BASELINE.md`。

当前最新完整 RAG baseline 来自：

```text
eval/rag/results/20260629160711_29294_1789fb/
```

核心结果：

- Recall@5：1.0
- MRR：0.8871
- Evidence Hit Rate：0.95
- Answer Correct Rate：0.925
- Citation Correct Rate：1.0
- No-answer Pass Rate：0.5714
- Permission Pass Rate：0.94

需要注意：本次 3 个 permission bad case 是问题回显型 false positive。逐题结果显示 `forbidden_leak_retrieval=0`，没有发现真实跨用户检索泄露。

第一版 retrieval-only baseline 来自：

```text
eval/rag/results/20260628171204_81528_b62629/
```

该结果的核心结论是：Recall@5 为 1.0，MRR 为 0.8871，Evidence Hit Rate 为 0.95，权限泄露次数为 0。

## 指标解释

### Recall@K

如果 topK 检索结果中包含任一 `expected_doc_keys` 对应文档，则该题命中。当前采用文档级 Recall@K，原因是 `docId` 在上传后才生成，而短评测文档的主要证据通常集中在单个 chunk。

### MRR

取第一个相关文档的排名 `rank`，该题得分为 `1 / rank`。如果没有命中，得分为 0。MRR 能反映相关证据是否排在靠前位置。

### Evidence Hit Rate

检查检索结果文本是否包含该题定义的 `evidence_terms`。它比文档级 Recall 更严格，可以暴露“命中了文档但没命中关键证据”的问题。

### 生成指标

当前生成指标采用轻量启发式，不使用 LLM judge：

- `answer_correct_rate`：答案是否包含 `answer_terms`
- `citation_correct_rate`：citations 是否包含期望文档
- `no_answer_pass_rate`：无答案题是否出现“不足、无法、没有”等拒答表达
- `forbidden_leak_count`：检索、引用或答案中是否出现其它用户私有关键词

这些指标不是最终真理，而是用于建立第一版可重复 baseline。后续如果加入人工评分或 LLM judge，必须保留原始逐题结果，避免只看聚合数值。

## 后续实验顺序

第一轮只做 baseline，不直接上 hybrid、rerank 或 query rewrite。

推荐后续实验顺序：

1. 对比 `chunk_size/chunk_overlap`
2. 对比 `topK`
3. 增加相似度阈值和基础 no-answer 策略

只有当 bad case 显示 dense retrieval 召回不到关键词或编号类事实时，才考虑 hybrid search。只有当 Recall@K 较好但 MRR 较差时，才考虑 rerank。query rewrite 放在最后。
