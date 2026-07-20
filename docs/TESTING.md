# 测试与证据分层

本文档定义仓库中“测试通过”的准确含义。脚本或配置文件存在不代表依赖故障、容器启动或完整业务链路已经实际验证。

## 第一层：确定性核心验证

从仓库根目录执行：

```bash
python -m pip install -r rag-ai-service/requirements-test.txt
./scripts/verify.sh
docker compose --env-file .env.example config --quiet
```

`verify.sh` 包含：

- `scripts/**/*.sh` 的 Bash 语法检查。
- Java Maven 单元测试，包括状态 fencing、删除恢复、RAG 状态与引用映射。
- Python `unittest`，包括 loader、splitter、Qdrant filter/payload、生成协议和固定数据集完整性。
- `contracts/ai/*.json` 在 Java Jackson DTO 与 Python Pydantic DTO 两端的解析/序列化检查。

这些检查不启动 MySQL、Redis、RabbitMQ、MinIO、Qdrant、FastAPI 或 Spring Boot，不调用真实 LLM，也不证明数据库隔离级别、网络故障恢复或容器镜像可以在当前机器构建。GitHub Actions 运行相同核心入口，并额外静态解析 Compose。

## 第二层：本地完整环境回归

完整依赖和两个应用均已启动后，按需运行：

```bash
./scripts/test_retrieval_permission.sh
./scripts/test_document_reupload_delete.sh
./scripts/test_rag_ask.sh
./scripts/test_rag_empty_kb.sh
./scripts/test_rag_permission.sh
```

它们分别验证检索权限与删除后不可召回、软删除后同文件重传、正常 RAG、空知识库短路、RAG 权限隔离。生成类脚本依赖真实 LLM 配置，结果受模型和网络影响，不放入核心 CI。

## 第三层：轻量 RAG 回归

固定资产位于 `eval/rag/`：10 份文档、50 条问题及配置。结果写入被 Git 忽略的 `eval/rag/results/`。

```bash
python3 scripts/run_rag_eval.py --retrieval-only
# 已配置 LLM 时才去掉 --retrieval-only
```

当前脚本使用 `answerStatus`/`noAnswer` 判断拒答，分别记录检索、RAG 候选 chunk、实际 citation、答案来源泄露与问题回显。它是轻量回归，不是带实验版本、人工/LLM judge、在线采样或统计显著性的正式评测系统。

历史 baseline 只证明当时提交、配置、模型和协议下的运行结果。RAG 协议或固定资产变化后，必须重新运行才能形成当前证据。

## 第四层：人工故障演练

`scripts/test_rabbitmq_reliability.sh` 与 `scripts/test_document_delete_failure_recovery.sh` 会在固定注入点等待操作者停止或恢复外部依赖。详细前置条件、只读 SQL 和验收项见 `RELIABILITY_MATRIX.md`。

报告结果时使用以下口径：

- “单元测试通过”：只说明进程内确定性断言。
- “Compose 配置解析通过”：只说明 YAML、插值和依赖图可解析。
- “脚本已就绪”：只说明演练自动断言已实现。
- “本地演练通过”：必须附日期、环境版本、命令和关键输出。
- “生产级可靠性”：当前项目没有这项证据，不得使用该表述。

## 变更后的最小选择

| 变更类型 | 必跑检查 |
| --- | --- |
| Java 业务、状态、权限、DTO | `./scripts/verify.sh` |
| Python loader/splitter/Qdrant/生成协议 | `./scripts/verify.sh` |
| Java/Python 内部字段 | 更新 `contracts/ai` 并运行 `./scripts/verify.sh` |
| Compose、Dockerfile、环境变量 | Compose 静态解析；有条件再做实际 build/up |
| 权限 filter 或二次过滤 | 核心测试 + 对应本地权限脚本 |
| RabbitMQ 状态/重试/DLQ | 核心测试 + RabbitMQ 人工故障演练 |
| 删除 generation 或外部清理 | 核心测试 + Qdrant/MinIO 人工故障演练 |
| RAG 状态、引用或 prompt | 核心测试 + RAG 脚本；指标结论需重跑固定集 |

