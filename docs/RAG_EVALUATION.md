# RAG 评测基线

本文档记录当前仓库的轻量 RAG 回归方案。它的目标不是论文级评测或正式实验平台，而是为求职展示提供可复现的检索、生成、实际引用、结构化 no-answer 和权限隔离证据。仓库中的固定资产与脚本只是“可执行条件”；只有针对当前提交真实运行并保存结果，才能形成当前证据。

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

当前固定语料包含 10 篇文档。按默认 `chunk_size=800`、`chunk_overlap=120` 会形成多 chunk 语料，避免评测退化成“一个文档一个 chunk”的冒烟测试。`./scripts/verify.sh` 会校验配置、文档、问题 ID/引用关系和 50 条问题的完整性，但不会启动服务或生成新的 baseline。

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
RabbitMQ
MinIO
Qdrant
FastAPI AI Service
Spring Boot Backend
```

如果要评测生成质量，还需要 FastAPI 进程可读取 `DEEPSEEK_API_KEY` 或显式 `LLM_*` 配置。没有 LLM 配置时，可以先运行 retrieval-only 基线。

## 运行命令

完整 RAG 评测：

```bash
python3 scripts/run_rag_eval.py
```

只评测检索，不调用 LLM：

```bash
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

当前脚本直接读取公开 RAG 响应的结构化字段：

- `answerStatus=ANSWERED` 且 `noAnswer=false` 才进入答案与 citation 正确性统计。
- `answerStatus=NO_CONTEXT` 或 `INSUFFICIENT_CONTEXT`、`noAnswer=true`、原因非空且 `citations=[]`，才可能通过 no-answer 检查。
- `retrievedChunks` 是检索候选；`citations` 只包含答案正文实际引用的 `[片段 n]`，两者分别评分。
- 越界/缺失引用不会作为成功响应返回，因此评测不会把全部候选 chunk 当作引用证据。

`bad_cases.md` 用于后续人工复盘，重点判断失败属于：

- `retrieval_miss_expected_doc`
- `retrieval_missing_evidence_terms`
- `answer_terms_missing`
- `citation_missing_expected_doc`
- `no_answer_not_recognized`
- `no_answer_question_echo`
- `rag_api_failed`
- `permission_leak`

## 历史 Baseline 与当前证据边界

`docs/RAG_EVALUATION_BASELINE.md` 保留了 2026-06-28/29 两次运行的历史摘要。`eval/rag/results/` 被 Git 忽略，新 clone 不包含当时原始输出。它们是在旧 RAG 协议和旧固定语料上生成的记录：旧协议用关键词识别拒答，并把检索候选整体映射为 citations；当前协议已经改为结构化状态与实际引用。因此以下仅是当时本地路径，不能标记为当前提交的 baseline，也不能与当前 citation/no-answer 指标直接比较：

```text
eval/rag/results/20260629160711_29294_1789fb/
```

历史核心结果：

- Recall@5：1.0
- MRR：0.8871
- Evidence Hit Rate：0.95
- Answer Correct Rate：0.925
- Citation Correct Rate：1.0
- No-answer Pass Rate：0.5714
- Permission Pass Rate：0.94

当时的 3 个 permission bad case 被人工复盘为问题回显型 false positive，逐题结果显示 `forbidden_leak_retrieval=0`。当前脚本已把 `answer_question_echo`、检索候选泄露、citation 泄露和答案来源泄露拆开；需要重新运行后才能给出当前结论。

第一版 retrieval-only baseline 来自：

```text
eval/rag/results/20260628171204_81528_b62629/
```

该历史结果记录 Recall@5 为 1.0、MRR 为 0.8871、Evidence Hit Rate 为 0.95、权限泄露次数为 0；同样不替代当前提交的重跑证据。

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
- `citation_correct_rate`：答案状态为 `ANSWERED` 时，实际 citations 是否包含期望文档
- `no_answer_pass_rate`：无答案题是否返回结构化 `NO_CONTEXT/INSUFFICIENT_CONTEXT`、非空原因、空 citations，且没有问题回显或 forbidden source leak
- `forbidden_leak_count`：检索、RAG 候选 chunk、实际 citation 或答案来源中是否出现其它用户私有关键词；问题回显单独计数

这些指标不是最终真理，而是用于建立第一版可重复 baseline。后续如果加入人工评分或 LLM judge，必须保留原始逐题结果，避免只看聚合数值。

## 后续实验顺序

第一轮只做 baseline，不直接上 hybrid、rerank 或 query rewrite。

推荐后续实验顺序：

1. 先按当前提交重跑 retrieval-only 与完整 RAG，建立新协议基线。
2. 对比 `topK`，确认实际引用数量和上下文噪声变化。
3. 只在 bad case 证明必要时对比 `chunk_size/chunk_overlap`。

当前已有可配置的 `RAG_MINIMUM_RELEVANCE_SCORE`，默认 `-1` 表示关闭。历史样本中可回答题与无答案题的分数区间存在重叠，所以在新 baseline 校准前不得把任意全局阈值当作默认质量提升。只有当 bad case 显示 dense retrieval 召回不到关键词或编号类事实时，才考虑 hybrid search；只有当 Recall@K 较好但 MRR 较差时，才考虑 rerank；query rewrite 放在最后。
