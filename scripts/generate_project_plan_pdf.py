from __future__ import annotations

from pathlib import Path
from xml.sax.saxutils import escape

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT, TA_RIGHT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (
    PageBreak,
    Paragraph,
    Preformatted,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
    ListFlowable,
    ListItem,
)
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.cidfonts import UnicodeCIDFont


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "企业知识库RAG-Agent工作流平台规划.pdf"

FONT = "STSong-Light"
pdfmetrics.registerFont(UnicodeCIDFont(FONT))


def e(text: str) -> str:
    return escape(str(text)).replace("\n", "<br/>")


styles = getSampleStyleSheet()
styles.add(
    ParagraphStyle(
        name="CnTitle",
        fontName=FONT,
        fontSize=24,
        leading=31,
        textColor=colors.HexColor("#0B2545"),
        alignment=TA_CENTER,
        spaceAfter=8,
        wordWrap="CJK",
    )
)
styles.add(
    ParagraphStyle(
        name="CnSubtitle",
        fontName=FONT,
        fontSize=11,
        leading=16,
        textColor=colors.HexColor("#4B5D73"),
        alignment=TA_CENTER,
        spaceAfter=4,
        wordWrap="CJK",
    )
)
styles.add(
    ParagraphStyle(
        name="H1",
        fontName=FONT,
        fontSize=15.5,
        leading=21,
        textColor=colors.HexColor("#2E74B5"),
        spaceBefore=12,
        spaceAfter=7,
        keepWithNext=True,
        wordWrap="CJK",
    )
)
styles.add(
    ParagraphStyle(
        name="H2",
        fontName=FONT,
        fontSize=12.5,
        leading=17,
        textColor=colors.HexColor("#2E74B5"),
        spaceBefore=9,
        spaceAfter=5,
        keepWithNext=True,
        wordWrap="CJK",
    )
)
styles.add(
    ParagraphStyle(
        name="H3",
        fontName=FONT,
        fontSize=11.3,
        leading=15,
        textColor=colors.HexColor("#1F4D78"),
        spaceBefore=7,
        spaceAfter=4,
        keepWithNext=True,
        wordWrap="CJK",
    )
)
styles.add(
    ParagraphStyle(
        name="BodyCn",
        fontName=FONT,
        fontSize=9.7,
        leading=14.5,
        textColor=colors.HexColor("#202C39"),
        spaceAfter=5,
        wordWrap="CJK",
    )
)
styles.add(
    ParagraphStyle(
        name="Small",
        fontName=FONT,
        fontSize=8.5,
        leading=12,
        textColor=colors.HexColor("#202C39"),
        spaceAfter=3,
        wordWrap="CJK",
    )
)
styles.add(
    ParagraphStyle(
        name="Muted",
        fontName=FONT,
        fontSize=8.2,
        leading=11.5,
        textColor=colors.HexColor("#687385"),
        spaceAfter=3,
        wordWrap="CJK",
    )
)
styles.add(
    ParagraphStyle(
        name="Cell",
        fontName=FONT,
        fontSize=7.6,
        leading=10.5,
        textColor=colors.HexColor("#202C39"),
        wordWrap="CJK",
    )
)
styles.add(
    ParagraphStyle(
        name="CodeBlock",
        fontName="Courier",
        fontSize=7.5,
        leading=9.2,
        textColor=colors.HexColor("#1F2937"),
        leftIndent=0,
        rightIndent=0,
        spaceBefore=3,
        spaceAfter=6,
    )
)


def h1(story, text):
    story.append(Paragraph(e(text), styles["H1"]))


def h2(story, text):
    story.append(Paragraph(e(text), styles["H2"]))


def h3(story, text):
    story.append(Paragraph(e(text), styles["H3"]))


def p(story, text, style="BodyCn"):
    story.append(Paragraph(e(text), styles[style]))


def bullets(story, items):
    story.append(
        ListFlowable(
            [ListItem(Paragraph(e(item), styles["BodyCn"]), leftIndent=10) for item in items],
            bulletType="bullet",
            start="circle",
            leftIndent=14,
            bulletFontName=FONT,
            bulletFontSize=8,
        )
    )
    story.append(Spacer(1, 2))


def numbers(story, items):
    story.append(
        ListFlowable(
            [ListItem(Paragraph(e(item), styles["BodyCn"]), leftIndent=10) for item in items],
            bulletType="1",
            leftIndent=15,
            bulletFontName=FONT,
            bulletFontSize=8,
        )
    )
    story.append(Spacer(1, 2))


def code(story, text):
    story.append(
        Table(
            [[Preformatted(text, styles["CodeBlock"])]],
            colWidths=[170 * mm],
            style=TableStyle(
                [
                    ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#F4F6F9")),
                    ("BOX", (0, 0), (-1, -1), 0.35, colors.HexColor("#D7DEE8")),
                    ("LEFTPADDING", (0, 0), (-1, -1), 6),
                    ("RIGHTPADDING", (0, 0), (-1, -1), 6),
                    ("TOPPADDING", (0, 0), (-1, -1), 5),
                    ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
                ]
            ),
        )
    )
    story.append(Spacer(1, 4))


def table(story, rows, widths=None, font_size=7.4):
    if widths is None:
        widths = [170 / len(rows[0])] * len(rows[0])
    col_widths = [w * mm for w in widths]
    cell_style = ParagraphStyle(
        name=f"Cell{font_size}",
        parent=styles["Cell"],
        fontSize=font_size,
        leading=font_size + 2.5,
    )
    data = [[Paragraph(e(cell), cell_style) for cell in row] for row in rows]
    t = Table(data, colWidths=col_widths, repeatRows=1, splitByRow=1)
    t.setStyle(
        TableStyle(
            [
                ("FONTNAME", (0, 0), (-1, -1), FONT),
                ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#E8EEF5")),
                ("TEXTCOLOR", (0, 0), (-1, 0), colors.HexColor("#0B2545")),
                ("GRID", (0, 0), (-1, -1), 0.25, colors.HexColor("#C8D4E3")),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (-1, -1), 4),
                ("RIGHTPADDING", (0, 0), (-1, -1), 4),
                ("TOPPADDING", (0, 0), (-1, -1), 3),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
            ]
        )
    )
    story.append(t)
    story.append(Spacer(1, 6))


def on_page(canvas, doc):
    canvas.saveState()
    canvas.setFont(FONT, 8)
    canvas.setFillColor(colors.HexColor("#687385"))
    canvas.drawString(18 * mm, 287 * mm, "企业知识库 RAG + Agent 工作流平台规划")
    canvas.drawRightString(192 * mm, 287 * mm, f"第 {doc.page} 页")
    canvas.setStrokeColor(colors.HexColor("#D7DEE8"))
    canvas.setLineWidth(0.3)
    canvas.line(18 * mm, 283.8 * mm, 192 * mm, 283.8 * mm)
    canvas.restoreState()


def build_story():
    story = []

    story.append(Spacer(1, 30 * mm))
    story.append(Paragraph("企业知识库 RAG + Agent 工作流平台", styles["CnTitle"]))
    story.append(Paragraph("从零到一工程化项目规划与面试交付手册", styles["CnSubtitle"]))
    story.append(Spacer(1, 6 * mm))
    table(
        story,
        [
            ["定位", "面向 Java 后端 / AI 工程化 / RAG 应用工程的大模型应用后端项目"],
            ["核心目标", "不是 LangChain Demo，而是可部署、可评估、可扩展的真实业务系统"],
            ["主线", "Spring Boot 负责业务系统，FastAPI 负责 RAG / Agent 能力，Qdrant / MinIO / MySQL 支撑工程闭环"],
            ["第一版验收", "登录、建知识库、上传文档、异步索引、检索问答、引用溯源、SSE 流式输出、Docker Compose 依赖启动"],
        ],
        widths=[28, 142],
        font_size=8.2,
    )
    story.append(PageBreak())

    h1(story, "目录")
    numbers(
        story,
        [
            "项目总览：解决什么问题、为什么适合求职、如何避免做成普通 Demo",
            "MVP 范围：必须做、不做、可 mock、必须真实实现、最小演示路径",
            "完整架构：前端、Spring Boot、FastAPI、MySQL、Redis、RabbitMQ、MinIO、Qdrant、模型 API",
            "项目目录：Spring Boot 与 FastAPI 从空项目开始的模块拆分",
            "接口设计：Spring Boot 对前端接口与 FastAPI AI 服务接口",
            "Java 调 Python：HTTP、WebClient、超时、错误、SSE 转发与 DTO 边界",
            "数据设计：MySQL 表、Qdrant collection、payload metadata",
            "核心流程：文档上传异步索引、RAG 普通问答、SSE 流式问答、权限过滤",
            "V2 评测、V0 到 V4 路线、30 步执行清单、学习清单、简历描述、面试讲法",
        ],
    )

    h1(story, "一、项目总览")
    p(story, "这个项目解决的是企业内部文档分散、检索低效、答案不可追溯的问题。系统允许用户上传企业文档，将文件保存到对象存储，再异步解析、切分、向量化并写入向量数据库。用户提问时，系统按知识库和权限过滤检索相关 chunk，调用大模型生成答案，并返回引用来源。")
    h2(story, "为什么适合 Java 后端 / AI 工程化求职")
    table(
        story,
        [
            ["能力维度", "项目体现"],
            ["Java 后端", "REST API、鉴权、业务建模、状态机、异常处理、MySQL 事务、SSE 转发"],
            ["Spring Boot 业务系统", "用户、知识库、文档、索引任务、会话、消息、引用、反馈完整落库"],
            ["AI 工程化", "FastAPI AI 服务化，Java 通过 HTTP/SSE 调用，模型、embedding、Qdrant 都有清晰适配层"],
            ["RAG 应用工程", "文档解析、文本切分、embedding、向量检索、prompt 组装、引用溯源、no-answer 扩展"],
            ["部署能力", "Docker Compose 启动 MySQL、Redis、MinIO、Qdrant 等依赖，具备现场演示能力"],
        ],
        widths=[38, 132],
    )
    h2(story, "和普通 LangChain Demo 的区别")
    table(
        story,
        [
            ["普通 Demo", "工程化项目"],
            ["本地读一个 PDF", "用户上传、多知识库、MinIO 存储、文档元数据落 MySQL"],
            ["直接调用 LangChain", "Spring Boot 业务后端调用 FastAPI AI 服务"],
            ["无权限", "Java 鉴权 + Qdrant metadata filter 双层隔离"],
            ["无状态", "document、indexing_task、conversation、chat_message、citation 全部持久化"],
            ["无评测", "V2 建检索评测集，记录 Recall@K、MRR、bad case"],
            ["不可部署", "Docker Compose 管理基础组件，README 可复现"],
        ],
        widths=[45, 125],
    )
    h2(story, "面试官看重什么")
    bullets(
        story,
        [
            "你是否真的把 AI 能力接入业务系统，而不是只会调模型 API。",
            "文档上传、对象存储、异步索引、失败状态、重试链路是否讲得清楚。",
            "Java 和 Python 的职责边界是否合理，服务调用、超时、SSE 是否真实可运行。",
            "权限过滤是否进入 Qdrant metadata filter，而不是只靠前端或 Java 口头保证。",
            "引用来源是否来自召回 chunk，并且能落库、展示、追溯。",
            "项目是否能 Docker Compose 启动，并能现场走完最小演示路径。",
        ],
    )
    h2(story, "容易做烂的地方")
    bullets(
        story,
        [
            "只做聊天页面，没有文档、任务、权限、索引状态，Java 价值很弱。",
            "引用是假造的，不来自检索结果，面试会被追问穿。",
            "Python 包办所有业务，最后像 Python RAG 项目，不像 Java 后端项目。",
            "一上来做 Agent、RBAC、监控，最小闭环迟迟跑不通。",
            "Docker Compose 跑不起来，现场演示失败。",
        ],
    )
    p(story, "一个人做最合理的取舍：先完成 V1 的完整工程闭环，个人知识库 + 基础 JWT + PDF/TXT/Markdown + Qdrant dense 检索 + 引用溯源 + SSE。RBAC、多租户、RabbitMQ、hybrid、rerank、Agent、监控全部后置。")

    h1(story, "二、MVP 范围")
    h2(story, "MVP 必须做")
    bullets(
        story,
        [
            "Spring Boot 主后端、FastAPI AI 服务、MySQL、MinIO、Qdrant。",
            "用户登录、创建知识库、上传文档、创建索引任务。",
            "Java 异步触发 Python 索引；Python 从 MinIO 下载文件。",
            "Python 解析文档、切分文本、生成 embedding、写入 Qdrant。",
            "用户选择知识库提问；Python 从 Qdrant 检索 chunk、组装 prompt、调用 LLM。",
            "返回答案和引用来源；Spring Boot 保存会话、消息、引用和反馈。",
            "基础 SSE 流式输出；Docker Compose 启动基础依赖。",
        ],
    )
    h2(story, "MVP 不做")
    bullets(
        story,
        [
            "完整 RBAC、多租户、团队空间、审批流、企业微信/钉钉集成。",
            "RabbitMQ 强依赖、分布式任务调度、Kubernetes、Prometheus/Grafana。",
            "hybrid search、rerank、query rewrite、LangGraph Agent、复杂评测页面。",
            "OCR、知识图谱、Elasticsearch、多模型自动路由。",
        ],
    )
    h2(story, "可 mock 与必须真实实现")
    table(
        story,
        [
            ["类型", "内容"],
            ["可以 mock / 简化", "前端样式；role/user_role 建表但不启用；Redis 只进 Compose；feedback 只保存；evaluate 接口 V2 再做"],
            ["必须真实实现", "MinIO 上传；document/indexing_task 状态；FastAPI 解析、embedding、Qdrant upsert/search；LLM 问答；citation 从检索 chunk 来；Java 保存会话和消息；SSE 流式输出"],
        ],
        widths=[38, 132],
    )
    h2(story, "最小可演示路径")
    numbers(
        story,
        [
            "登录系统。",
            "创建知识库。",
            "上传一份 PDF/TXT/Markdown。",
            "看到索引状态从 PENDING 到 COMPLETED，失败时能看到错误。",
            "进入会话并提问。",
            "流式展示答案。",
            "展示引用来源。",
            "提交有用/无用反馈。",
        ],
    )

    h1(story, "三、完整架构")
    code(
        story,
        """Vue3 管理台
  | 登录、知识库、文档上传、索引状态、聊天、引用展示
  v
Spring Boot Backend
  |-- Auth/User：登录、当前用户
  |-- Knowledge：知识库管理、权限校验
  |-- Document：上传、MinIO、文档状态
  |-- Indexing：索引任务、异步触发、重试
  |-- Conversation：会话、消息、引用、反馈
  |-- AI Client：WebClient 调 FastAPI
  |-- MySQL / Redis / MinIO
  v
FastAPI AI Service
  |-- 文档下载、解析、切分
  |-- Embedding、Qdrant 写入与检索
  |-- Prompt 组装、LLM 调用、SSE 输出
  v
Qdrant Vector DB
  |-- chunk 向量与 kbId/docId/ownerId/pageNo/text metadata
  v
LLM API / Embedding API"""
    )
    table(
        story,
        [
            ["组件", "职责"],
            ["前端", "管理台、上传文件、轮询状态、聊天、展示引用和反馈"],
            ["Spring Boot", "系统入口、鉴权、权限、业务数据、文件管理、任务编排、会话落库"],
            ["FastAPI", "AI 能力：解析、切分、embedding、检索、prompt、LLM、Agent 扩展"],
            ["MySQL", "用户、知识库、文档、索引任务、会话、消息、引用、反馈"],
            ["Redis", "V1 可轻量使用；V2 用于任务锁、缓存、限流"],
            ["RabbitMQ", "V1 不必须；V2 用于可靠索引队列和失败重试"],
            ["MinIO", "保存用户上传的原始文件"],
            ["Qdrant", "保存 chunk 向量和 metadata，支持 filter 检索"],
            ["LLM API", "生成答案"],
            ["Embedding API", "将 chunk/query 转成向量"],
        ],
        widths=[34, 136],
    )
    h2(story, "关键架构取舍")
    bullets(
        story,
        [
            "业务后端用 Java：突出求职方向中的后端工程能力，承载权限、事务、状态流转和 API。",
            "AI 服务用 Python：文档解析、LangChain/LangGraph、embedding/rerank 生态成熟，迭代更快。",
            "Java 不直接写 RAG：可以写，但工具链和 AI 生态弱于 Python，且会混乱业务与模型能力边界。",
            "Python 不做全部业务：否则 Java 后端能力不突出，项目更像 Python Demo。",
            "Java 与 Python 通信：普通 JSON HTTP 用于 health、index、retrieve、chat；SSE 用于流式 chat。",
            "索引任务异步：文档解析和 embedding 可能耗时较长，上传接口必须快速返回 taskId/documentId。",
            "V1 不强制 MQ：先用 Spring TaskExecutor 异步调用 FastAPI；V2 再升级 RabbitMQ。",
        ],
    )

    h1(story, "四、项目目录设计")
    h2(story, "Spring Boot 项目")
    code(
        story,
        """enterprise-rag-backend/
src/main/java/com/example/ekb/
  common/        统一响应、异常、枚举、工具
  config/        WebClient、MinIO、MyBatis、Async、Security
  auth/          登录、JWT、认证 DTO
  user/          用户实体、Mapper、Service
  knowledge/     知识库 CRUD、权限校验
  document/      上传、查询、删除、索引状态
  indexing/      索引任务、异步触发、状态更新
  conversation/  会话、消息、引用、反馈
  chat/          普通问答、流式问答入口
  ai/            FastAPI WebClient 和 DTO
  storage/       MinIO 封装
  security/      JWT filter、LoginUser 上下文"""
    )
    table(
        story,
        [
            ["包", "V1 是否必须", "说明"],
            ["common/config/security", "必须", "统一响应、异常、基础配置、JWT 是所有接口的基础"],
            ["auth/user", "必须", "登录和当前用户上下文，支撑 owner 权限模型"],
            ["knowledge/document/indexing", "必须", "知识库、文档上传、索引任务是 MVP 主链路"],
            ["conversation/chat/ai/storage", "必须", "问答、会话落库、FastAPI 调用、MinIO 文件存储"],
            ["role/user_role/tenant", "后置", "V1 可建表不启用，避免 RBAC 和多租户拖慢闭环"],
        ],
        widths=[45, 35, 90],
    )
    h2(story, "FastAPI 项目")
    code(
        story,
        """rag-ai-service/
app/
  main.py
  api/v1/           health、documents、retrieve、chat、evaluate、agent
  config/           settings.py
  schemas/          Pydantic 请求/响应 DTO
  document_loader/  pdf/text/markdown/word loader
  splitter/         文本切分
  embedding/        embedding provider 适配
  vector_store/     Qdrant client
  retriever/        检索逻辑
  prompt/           RAG prompt
  generator/        LLM provider 适配
  services/         indexing/chat/retrieve 编排
  utils/            MinIO、logger、requestId"""
    )
    table(
        story,
        [
            ["模块", "V1 是否必须", "说明"],
            ["api/schemas/config", "必须", "REST/SSE 接口、DTO 校验、配置管理"],
            ["document_loader/splitter", "必须", "PDF/TXT/Markdown 必须；Word 可随后补"],
            ["embedding/vector_store/retriever", "必须", "embedding、Qdrant 写入、metadata filter 检索"],
            ["prompt/generator/services", "必须", "prompt 组装、LLM 调用、索引和问答流程编排"],
            ["evaluate/agent", "后置", "V2 做评测，V3 做 LangGraph Agent"],
        ],
        widths=[45, 35, 90],
    )

    h1(story, "五、Spring Boot 对前端接口")
    table(
        story,
        [
            ["功能", "URL / Method", "Controller / Service", "依赖", "V1"],
            ["登录", "POST /api/v1/auth/login", "AuthController / AuthService", "user", "是"],
            ["当前用户", "GET /api/v1/users/me", "UserController / UserService", "user", "是"],
            ["创建知识库", "POST /api/v1/knowledge-bases", "KnowledgeController / KnowledgeService", "knowledge_base", "是"],
            ["知识库列表", "GET /api/v1/knowledge-bases", "KnowledgeController / KnowledgeService", "knowledge_base", "是"],
            ["知识库详情", "GET /api/v1/knowledge-bases/{id}", "KnowledgeController / KnowledgeService", "knowledge_base", "是"],
            ["删除知识库", "DELETE /api/v1/knowledge-bases/{id}", "KnowledgeController / KnowledgeService", "document、AI delete vectors", "是"],
            ["上传文档", "POST /api/v1/knowledge-bases/{kbId}/documents", "DocumentController / DocumentService", "MinIO、document、indexing_task、FastAPI", "是"],
            ["文档列表", "GET /api/v1/knowledge-bases/{kbId}/documents", "DocumentController / DocumentService", "document", "是"],
            ["文档详情", "GET /api/v1/documents/{id}", "DocumentController / DocumentService", "document", "是"],
            ["索引状态", "GET /api/v1/documents/{id}/index-status", "DocumentController / IndexingService", "document、indexing_task", "是"],
            ["删除文档", "DELETE /api/v1/documents/{id}", "DocumentController / DocumentService", "MinIO、FastAPI delete-vectors", "是"],
            ["创建会话", "POST /api/v1/conversations", "ConversationController / ConversationService", "conversation", "是"],
            ["会话列表", "GET /api/v1/conversations", "ConversationController / ConversationService", "conversation", "是"],
            ["会话消息", "GET /api/v1/conversations/{id}/messages", "ConversationController / ConversationService", "chat_message、answer_citation", "是"],
            ["普通问答", "POST /api/v1/chat", "ChatController / ChatService", "FastAPI、conversation、chat_message、citation", "是"],
            ["流式问答", "POST /api/v1/chat/stream", "ChatController / ChatService", "FastAPI SSE、SseEmitter", "是"],
            ["引用来源", "GET /api/v1/messages/{messageId}/citations", "ChatController / ConversationService", "answer_citation", "是"],
            ["回答反馈", "POST /api/v1/messages/{messageId}/feedback", "ChatController / FeedbackService", "feedback", "是"],
        ],
        widths=[28, 48, 42, 40, 12],
        font_size=6.8,
    )
    p(story, "所有业务接口默认需要鉴权，登录除外。Spring Boot 不直接访问 Qdrant，而是通过 FastAPI 间接使用 Qdrant，这样 AI 能力边界清楚。")

    h1(story, "六、FastAPI AI 服务接口")
    table(
        story,
        [
            ["接口", "Request 核心字段", "Response", "调用方", "涉及能力", "V1"],
            ["GET /health", "无", "status", "Java/运维", "无", "是"],
            ["POST /api/v1/documents/index", "taskId, docId, kbId, ownerId, bucket, objectKey, fileName, callbackUrl", "status, chunkCount, error", "Java", "MinIO 下载、解析、embedding、Qdrant 写入", "是"],
            ["POST /api/v1/retrieve", "query, kbId, ownerId, topK", "chunks", "Java/调试", "query embedding、Qdrant filter 检索", "是"],
            ["POST /api/v1/chat", "question, kbId, ownerId, history, topK", "answer, citations, usage", "Java", "Qdrant、LLM、Embedding", "是"],
            ["POST /api/v1/chat/stream", "question, kbId, ownerId, history, topK", "SSE token/citations/done", "Java", "Qdrant、流式 LLM", "是"],
            ["POST /api/v1/documents/delete-vectors", "kbId, docId, ownerId", "deletedCount", "Java", "Qdrant 删除", "是"],
            ["POST /api/v1/evaluate", "datasetId, config", "metrics", "Java/后台", "检索评测", "V2"],
            ["POST /api/v1/documents/summary", "docId/kbId", "summary", "Java", "LLM 总结", "后置"],
            ["POST /api/v1/agent/run", "taskType, input, kbId", "result, steps", "Java", "LangGraph Agent", "V3"],
        ],
        widths=[43, 50, 26, 23, 36, 12],
        font_size=6.5,
    )
    h2(story, "失败返回与 Java 处理")
    code(
        story,
        """{
  "success": false,
  "code": "INDEX_FAILED",
  "message": "PDF parse failed",
  "requestId": "xxx",
  "detail": "..."
}"""
    )
    bullets(
        story,
        [
            "索引失败：Java 更新 indexing_task.status=FAILED，document.index_status=FAILED，保存 error_message。",
            "问答失败：用户消息已保存，assistant 消息可标记 FAILED 或不保存，接口返回统一错误。",
            "超时：索引进入 FAILED/RETRYING；流式问答发送 event:error 并记录日志。",
        ],
    )

    h1(story, "七、Java 如何调用 Python")
    p(story, "Java 调 Python 本质就是 HTTP 调接口。FastAPI 暴露 REST API 和 SSE 流，Spring Boot 用 WebClient 作为 HTTP client。Java 不需要知道 LangChain 的内部细节，只需要维护清晰 DTO：传 userId、kbId、ownerId、question、history、topK，拿 answer、citations、usage 或 SSE token。")
    h2(story, "最小 DTO")
    code(
        story,
        """{
  "requestId": "uuid",
  "userId": 1,
  "kbId": 100,
  "ownerId": 1,
  "question": "报销流程是什么？",
  "topK": 5,
  "history": [
    {"role": "USER", "content": "..."},
    {"role": "ASSISTANT", "content": "..."}
  ]
}"""
    )
    h2(story, "FastAPI /health 与 Spring WebClient")
    code(
        story,
        """# FastAPI
@app.get("/health")
def health():
    return {"status": "ok"}

// Spring Boot
Mono<HealthResp> resp = webClient.get()
    .uri("/health")
    .retrieve()
    .bodyToMono(HealthResp.class)
    .timeout(Duration.ofSeconds(2));"""
    )
    h2(story, "普通 chat 调用")
    code(
        story,
        """# FastAPI
@app.post("/api/v1/chat")
def chat(req: ChatRequest):
    chunks = retrieve_from_qdrant(req.question, req.kbId, req.ownerId, req.topK)
    answer = call_llm(build_prompt(req.question, chunks))
    return {"answer": answer, "citations": build_citations(chunks)}

// Spring Boot
ChatAiResp resp = webClient.post()
    .uri("/api/v1/chat")
    .bodyValue(req)
    .retrieve()
    .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class)
        .map(body -> new AiServiceException(body)))
    .bodyToMono(ChatAiResp.class)
    .timeout(Duration.ofSeconds(60))
    .block();"""
    )
    h2(story, "SSE 流式调用与转发")
    code(
        story,
        """# FastAPI
@app.post("/api/v1/chat/stream")
def chat_stream(req: ChatRequest):
    async def event_generator():
        chunks = retrieve_from_qdrant(req.question, req.kbId, req.ownerId, req.topK)
        async for token in stream_llm(build_prompt(req.question, chunks)):
            yield f"event: token\\ndata: {json.dumps({'text': token})}\\n\\n"
        yield f"event: citations\\ndata: {json.dumps({'citations': citations})}\\n\\n"
        yield "event: done\\ndata: {}\\n\\n"
    return StreamingResponse(event_generator(), media_type="text/event-stream")

// Spring Boot
@PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@RequestBody ChatReq req) {
    SseEmitter emitter = new SseEmitter(120_000L);
    StringBuilder answerBuffer = new StringBuilder();
    aiWebClient.post().uri("/api/v1/chat/stream")
        .bodyValue(toAiReq(req))
        .accept(MediaType.TEXT_EVENT_STREAM)
        .retrieve()
        .bodyToFlux(String.class)
        .timeout(Duration.ofSeconds(120))
        .subscribe(chunk -> {
            answerBuffer.append(parseTokenIfNeeded(chunk));
            emitter.send(chunk);
        }, error -> emitter.completeWithError(error),
           () -> { saveAssistantMessage(answerBuffer.toString()); emitter.complete(); });
    return emitter;
}"""
    )
    p(story, "普通问答实现简单，等完整答案返回后再保存；流式问答用户体验更好，但 Java 需要边转发边缓存完整答案，最后落库。前端如果是 POST SSE，建议用 fetch + ReadableStream；GET SSE 可用 EventSource。")
    p(story, "权限逻辑不应完全交给 Python。Java 先校验用户是否能访问知识库，Python 接收已校验的 kbId/ownerId，并在 Qdrant filter 中强制使用这些 metadata。")

    h1(story, "八、MySQL 表设计")
    table(
        story,
        [
            ["表名", "用途", "关键字段/状态/索引", "V1"],
            ["user", "登录用户", "id, username, password_hash, nickname, email, status, created_at, updated_at, is_deleted；username 唯一", "必须"],
            ["role", "RBAC 角色", "id, code, name；code 唯一；V1 可建表不启用", "后置"],
            ["user_role", "用户角色关联", "id, user_id, role_id；user_id+role_id 唯一", "后置"],
            ["knowledge_base", "知识库", "id, owner_user_id, name, description, visibility, status, is_deleted；owner_user_id/name 索引", "必须"],
            ["document", "文档元信息和索引状态", "id, kb_id, owner_user_id, file_name, content_type, file_size, bucket, object_key, checksum_sha256, index_status, chunk_count, error_message；状态 PENDING/PARSING/INDEXING/COMPLETED/FAILED", "必须"],
            ["indexing_task", "索引任务状态和重试", "id, document_id, kb_id, owner_user_id, status, retry_count, max_retry, error_message, started_at, finished_at；状态 PENDING/RUNNING/SUCCESS/FAILED/RETRYING", "必须"],
            ["conversation", "聊天会话", "id, user_id, kb_id, title, created_at, updated_at, is_deleted", "必须"],
            ["chat_message", "用户/助手消息", "id, conversation_id, user_id, role, content, status, created_at；role USER/ASSISTANT/SYSTEM", "必须"],
            ["answer_citation", "回答引用来源", "id, assistant_message_id, doc_id, chunk_id, file_name, page_no, chunk_index, quote_text, score, created_at", "必须"],
            ["feedback", "答案反馈", "id, user_id, assistant_message_id, rating, comment, created_at；rating USEFUL/NOT_USEFUL", "必须"],
            ["model_call_log", "模型调用日志和成本基础", "id, request_id, user_id, provider, model, call_type, tokens, latency_ms, success, error_message", "建议"],
            ["tenant", "多租户", "id, name, status, created_at；V4 扩展", "后置"],
        ],
        widths=[31, 31, 92, 16],
        font_size=6.5,
    )
    p(story, "V1 不建议建 document_chunk 表。chunk text 存 Qdrant payload，citation 再落库。V2 做评测、审计或重建索引时，再增加 document_chunk 表。")

    h1(story, "九、Qdrant Collection 设计")
    table(
        story,
        [
            ["设计项", "建议"],
            ["collection 名称", "rag_chunks_v1"],
            ["vector size", "由 embedding 模型输出维度决定，必须固定；不能在同一 vector field 混用不同维度"],
            ["distance metric", "Cosine，适合文本语义相似度"],
            ["chunkId", "{docId}:{chunkIndex}:{textHash}；point id 可用 UUIDv5(chunkId)，重建索引可 upsert"],
            ["docId/kbId", "docId 关联 MySQL document；kbId 用于知识库过滤"],
            ["ownerId/visibility", "V1 用 ownerId 做个人权限；后续扩展 TEAM/PUBLIC"],
            ["pageNo", "用于引用来源展示"],
            ["text", "V1 直接存 payload；V2 可迁移到 document_chunk 表"],
            ["V2 hybrid", "Qdrant dense + sparse，或引入 BM25/Elasticsearch；个人项目优先 Qdrant sparse，避免组件爆炸"],
        ],
        widths=[38, 132],
    )
    h2(story, "payload 示例")
    code(
        story,
        """{
  "tenantId": null,
  "kbId": 100,
  "docId": 200,
  "chunkId": "200:3:abc123",
  "ownerId": 1,
  "visibility": "PRIVATE",
  "fileName": "员工报销制度.pdf",
  "pageNo": 5,
  "chunkIndex": 3,
  "text": "报销申请需要在...",
  "createdAt": "2026-06-06T10:00:00"
}"""
    )
    p(story, "V1 最小检索 filter：kbId == request.kbId 且 ownerId == request.ownerId。这个 filter 必须真实进入 Qdrant 查询。")

    h1(story, "十、文档上传与异步索引流程")
    numbers(
        story,
        [
            "前端上传文件到 Spring Boot。",
            "Spring Boot 校验文件类型、大小、知识库归属。",
            "Spring Boot 上传文件到 MinIO，生成 bucket/objectKey。",
            "写 document 表，index_status=PENDING。",
            "写 indexing_task 表，status=PENDING。",
            "V1 使用 Spring TaskExecutor 异步调用 FastAPI /documents/index。",
            "Python 从 MinIO 下载文件。",
            "Python 解析 PDF/TXT/Markdown，提取文本和 pageNo。",
            "Python 切分文本，生成 chunkIndex/chunkId。",
            "Python 调 embedding，写入 Qdrant。",
            "Java 根据 FastAPI 返回结果更新 document 和 indexing_task。",
            "前端轮询 /documents/{id}/index-status。",
        ],
    )
    h2(story, "状态流转")
    code(
        story,
        """document.index_status:
PENDING -> PARSING -> INDEXING -> COMPLETED
                         |
                         v
                       FAILED

indexing_task.status:
PENDING -> RUNNING -> SUCCESS
             |
             v
          RETRYING -> RUNNING
             |
             v
           FAILED"""
    )
    h2(story, "失败、重试、去重")
    bullets(
        story,
        [
            "解析失败、embedding 失败、Qdrant 写入失败都要记录 error_message。",
            "Java 超时应将任务置为 FAILED 或 RETRYING，不能一直 RUNNING。",
            "重试前可按 docId 删除旧向量，或者用稳定 chunkId upsert 覆盖。",
            "上传时计算 checksum_sha256，同一知识库同一文件可提示重复。",
            "同一 document 同时只能有一个 RUNNING task。",
        ],
    )
    h2(story, "V2 升级 MQ")
    p(story, "V1 用 Java 本地异步降低复杂度；V2 使用 RabbitMQ：Java 发布 DocumentIndexRequested 消息，Python 消费，处理完成后回调 Java 状态接口。这样能支持 ack、retry、dead-letter 和横向扩展。")

    h1(story, "十一、RAG 问答流程")
    h2(story, "普通非流式问答")
    code(
        story,
        """前端选择知识库并提问
  -> Spring Boot 鉴权
  -> 校验 kb.owner_user_id == currentUser.id
  -> 保存 USER 消息
  -> 组装 AI ChatRequest(kbId, ownerId, question, history)
  -> FastAPI embedding query
  -> Qdrant filter(kbId, ownerId) topK
  -> 组装 prompt
  -> 调 LLM
  -> 返回 answer + citations
  -> Spring 保存 ASSISTANT 消息
  -> Spring 保存 answer_citation
  -> 前端展示答案和引用"""
    )
    h2(story, "SSE 流式问答")
    code(
        story,
        """前端 POST /chat/stream
  -> Spring 保存 USER 消息
  -> Spring 调 FastAPI SSE
  -> FastAPI 先检索 chunks
  -> FastAPI 流式调用 LLM
  -> token 事件转发给 Spring
  -> Spring 边转发前端，边缓存完整 answer
  -> FastAPI 最后发送 citations + done
  -> Spring 保存 ASSISTANT 消息和 citations"""
    )
    h2(story, "V1 与 V2")
    table(
        story,
        [
            ["版本", "实现"],
            ["V1 最小实现", "topK=5；dense vector search；prompt 要求基于资料回答；citation 来自 topK chunks；最高 score 低于阈值时简单拒答"],
            ["V2 增强", "query rewrite；hybrid search；召回 top20 后 rerank top5；no-answer；bad case log；Recall@K/MRR 评测"],
        ],
        widths=[35, 135],
    )

    h1(story, "十二、权限过滤方案")
    bullets(
        story,
        [
            "第一版不需要完整 RBAC，做个人知识库权限即可。",
            "user 创建 knowledge_base，document 属于 knowledge_base，chunk payload 带 kbId 和 ownerId。",
            "Java 查询前校验 knowledge_base.owner_user_id == currentUser.id。",
            "FastAPI 检索时强制 Qdrant filter：kbId + ownerId。",
            "如果只在 Java 层鉴权而不做 Qdrant filter，未来多知识库检索或参数误传时可能召回其他用户 chunk。",
            "tenantId 第一版不需要；V4 再扩展租户、团队、PRIVATE/TEAM/PUBLIC visibility。",
        ],
    )

    h1(story, "十三、V2 检索质量评测")
    table(
        story,
        [
            ["评测项", "做法"],
            ["测试集", "手工创建 30 到 50 条问题，每条绑定 expectedDocId 和 expectedChunkId"],
            ["Recall@K", "topK 召回中包含任一 ground truth chunk/doc 的问题数 / 总问题数"],
            ["MRR", "所有问题的 1 / 第一个相关结果排名 的平均值"],
            ["answer hit rate", "简单版人工标注 PASS/FAIL；不要直接把 LLM judge 当唯一真值"],
            ["bad case", "记录 question、expected、retrieved、answer、failureType、config"],
            ["参数对比", "chunk size 300/500/800；topK 3/5/10/20；rerank on/off；hybrid on/off"],
            ["简历写法", "构建检索评测集，使用 Recall@K、MRR 和 bad case 分析优化 chunk size/topK/rerank"],
            ["不要瞎写", "不要写 99% 准确率，不要写消除幻觉，不要写无数据支撑的提升百分比"],
        ],
        widths=[36, 134],
    )

    h1(story, "十四、V0 到 V4 版本路线")
    table(
        story,
        [
            ["版本", "目标", "任务", "验收标准", "不做什么"],
            ["V0", "环境与空项目搭建", "建 Spring Boot、FastAPI、Compose、健康检查", "Java 能调 Python /health，依赖能启动", "不做 RAG"],
            ["V1", "工程化 RAG MVP", "上传、MinIO、索引任务、FastAPI 索引、Qdrant、问答、引用、SSE", "最小演示路径完整跑通", "不做 MQ、Agent、rerank、RBAC"],
            ["V2", "检索质量增强", "query rewrite、hybrid、rerank、no-answer、评测集、bad case", "能跑评测并对比参数", "不做复杂 Agent"],
            ["V3", "Agent 工作流", "LangGraph、文档总结、会议纪要、工单草稿、人工确认", "有可控工作流和执行日志", "不做万能 Agent"],
            ["V4", "生产化能力", "RBAC、多租户、RabbitMQ、Redis 缓存、限流、监控、成本统计", "系统稳定性和可运维能力增强", "不做 Kubernetes 级复杂部署"],
        ],
        widths=[17, 30, 52, 45, 26],
        font_size=6.3,
    )

    h1(story, "十五、从第 1 步开始的执行清单")
    table(
        story,
        [
            ["步", "具体做什么", "为什么现在做", "涉及项目/技术", "完成标准与常见坑"],
            ["1", "明确仓库结构", "先定边界", "根目录、Git、README", "有 backend 和 ai-service；坑：混成一个项目"],
            ["2", "创建 Docker Compose", "先有依赖", "MySQL/Redis/MinIO/Qdrant", "容器启动；坑：端口冲突"],
            ["3", "创建 Spring Boot 空项目", "业务入口", "Spring Web/MyBatis/JWT", "/health 可访问；坑：依赖乱加"],
            ["4", "创建 FastAPI 空项目", "AI 服务入口", "FastAPI/Uvicorn", "/health 可访问；坑：虚拟环境混乱"],
            ["5", "Java 调 Python health", "打通服务通信", "WebClient", "返回 ok；坑：baseUrl 配错"],
            ["6", "统一响应和异常", "后续少返工", "ControllerAdvice", "错误格式统一；坑：异常被吞"],
            ["7", "建 MySQL 表", "系统状态基础", "DDL/Migration", "核心表创建；坑：缺状态字段"],
            ["8", "接入 MyBatis-Plus", "CRUD 基础", "entity/mapper", "user/kb 可查；坑：字段映射"],
            ["9", "做登录 JWT", "建立用户上下文", "auth/security", "登录拿 token；坑：明文密码"],
            ["10", "知识库 CRUD", "建立权限主体", "knowledge", "用户只能看自己 kb；坑：漏 owner 条件"],
            ["11", "接入 MinIO", "上传前置", "MinIO SDK", "能上传下载；坑：bucket 不存在"],
            ["12", "文档上传接口", "形成文档记录", "multipart/document", "document=PENDING；坑：大小限制"],
            ["13", "创建 indexing_task", "索引可追踪", "indexing/MySQL", "task=PENDING；坑：上传成功没任务"],
            ["14", "Java 异步任务", "上传不阻塞", "TaskExecutor", "上传立即返回；坑：异常丢失"],
            ["15", "FastAPI 接 MinIO", "Python 拿文件", "minio client", "下载成功；坑：凭证不一致"],
            ["16", "文档解析", "索引输入", "PyMuPDF/text loader", "拿到文本和页码；坑：PDF 空文本"],
            ["17", "文本切分", "生成 chunk", "splitter", "chunk 有 pageNo/index；坑：chunk 太大"],
            ["18", "embedding adapter", "向量生成", "模型 API", "维度正确；坑：与 Qdrant 不一致"],
            ["19", "创建 Qdrant collection", "存向量", "Qdrant client", "collection 存在；坑：维度建错"],
            ["20", "写入 chunk vectors", "完成索引核心", "Qdrant upsert", "chunk_count>0；坑：payload 丢 kbId"],
            ["21", "回写索引状态", "前端可见", "Java/FastAPI", "COMPLETED/FAILED；坑：失败没状态"],
            ["22", "索引状态查询", "闭环展示", "document API", "能轮询；坑：只查 document 不查 task"],
            ["23", "retrieve 接口", "RAG 前置", "query embedding + filter", "返回 chunks；坑：没 filter"],
            ["24", "普通 chat", "先非流式", "prompt + LLM", "answer/citations；坑：citation 假造"],
            ["25", "Java 接普通 chat", "会话落库", "WebClient/MySQL", "保存 USER/ASSISTANT/citations；坑：失败半截"],
            ["26", "SSE chat", "改善体验", "FastAPI SSE + SseEmitter", "逐字显示；坑：缓冲不刷新"],
            ["27", "最简 Vue 页面", "现场演示", "Vue3/Element Plus", "上传和聊天可用；坑：页面太花"],
            ["28", "删除文档", "清理资源", "MinIO/Qdrant", "删除后搜不到；坑：只删 DB"],
            ["29", "日志和 requestId", "排错能力", "Java/Python logging", "跨服务能关联；坑：没有 taskId"],
            ["30", "README 和演示脚本", "面试展示", "Markdown/Compose", "别人能启动；坑：缺环境变量"],
        ],
        widths=[9, 33, 29, 34, 65],
        font_size=5.8,
    )
    p(story, "每一步卡住时优先分层验证：接口是否通、DB 是否有状态、MinIO 是否有对象、Qdrant 是否有 payload、FastAPI 日志是否有 requestId、模型 API 是否报错。")

    h1(story, "十六、学习清单")
    table(
        story,
        [
            ["功能", "需要学什么", "最低掌握标准"],
            ["Spring Boot REST", "Controller/Service/DTO/异常", "能写 CRUD 和统一错误"],
            ["MyBatis-Plus", "Entity/Mapper/分页/条件查询", "能按 ownerId 安全查询"],
            ["MySQL 表设计", "主键、索引、状态字段", "能解释每张表用途"],
            ["文件上传", "multipart、大小限制", "能保存文件元信息"],
            ["MinIO", "bucket/objectKey/SDK", "能上传、下载、删除"],
            ["WebClient", "GET/POST/超时/错误处理", "能调用 FastAPI"],
            ["SSE", "text/event-stream/SseEmitter", "能转发 token"],
            ["FastAPI", "route/uvicorn/异常", "能提供 REST API"],
            ["Pydantic", "request/response schema", "DTO 清晰可校验"],
            ["LangChain", "splitter/prompt 基础", "不依赖黑盒链条"],
            ["Qdrant", "collection/upsert/search/filter", "能按 kbId/ownerId 检索"],
            ["Embedding", "向量维度/模型选择", "知道维度决定 collection"],
            ["文本切分", "chunk size/overlap/pageNo", "citation 能追溯页码"],
            ["Prompt", "context/question/拒答", "能控制输出和引用"],
            ["LLM API", "chat/stream/error", "能普通和流式调用"],
            ["Redis", "key/value/lock/ttl", "V1 可选，V2 用任务锁"],
            ["RabbitMQ", "exchange/queue/ack/retry", "V2 能替代本地异步"],
            ["Docker Compose", "service/env/volume/network", "一键启动依赖"],
            ["日志", "requestId/taskId/error stack", "能定位跨服务问题"],
            ["异常处理", "业务异常/超时/重试", "失败状态不丢"],
            ["权限校验", "owner 模型/JWT", "用户只能看自己数据"],
            ["RAG 评测", "Recall@K/MRR/bad case", "能跑 30 到 50 条测试"],
        ],
        widths=[35, 60, 75],
        font_size=6.5,
    )

    h1(story, "十七、简历描述")
    h2(story, "V1 完成后")
    p(story, "设计并实现企业知识库 RAG 平台，基于 Spring Boot + FastAPI 拆分业务后端与 AI 服务。后端支持用户登录、知识库管理、文档上传、MinIO 对象存储、异步索引任务、会话消息与引用来源落库；AI 服务负责文档解析、文本切分、embedding 生成、Qdrant 向量写入与检索、Prompt 组装和 LLM 问答，支持基于 SSE 的流式回答和 Docker Compose 本地部署。")
    h2(story, "V2 完成后")
    p(story, "在企业知识库 RAG 平台中建设检索质量优化与评测模块，支持 query rewrite、向量召回、metadata filter、rerank/no-answer 判断和引用溯源；构建 30+ 条检索评测集，使用 Recall@K、MRR 和 bad case 日志对 chunk size、topK、rerank 策略进行对比分析，提升知识库问答的可解释性和可调优能力。")
    h2(story, "V3/V4 完成后")
    p(story, "扩展企业知识库 RAG 平台为 RAG + Agent 工作流系统，基于 LangGraph 实现文档总结、会议纪要、工单草稿等可控多步骤工作流，并加入人工确认、任务执行日志、多租户/RBAC、RabbitMQ 异步索引、Redis 缓存限流、模型调用日志和成本统计，形成可部署、可观测、可扩展的大模型应用后端系统。")

    h1(story, "十八、面试讲解框架")
    table(
        story,
        [
            ["主题", "讲法"],
            ["项目背景", "企业文档分散，传统搜索难以直接回答，也缺少来源追溯"],
            ["为什么做", "做一个真实后端工程项目，把 RAG 接入用户、权限、文件、任务、会话、部署链路"],
            ["技术架构", "Vue3 + Spring Boot + FastAPI + MySQL + MinIO + Qdrant + Redis + LLM/Embedding API"],
            ["为什么拆 Java + Python", "Java 承载业务工程，Python 承载 AI 生态，HTTP/SSE 通信"],
            ["上传索引链路", "Spring 校验上传到 MinIO，写 document/task，异步调用 FastAPI，Python 解析、切分、embedding、写 Qdrant，回写状态"],
            ["RAG 问答链路", "Spring 鉴权和落库，FastAPI 检索 Qdrant、组 prompt、调 LLM，返回 answer/citations"],
            ["流式回答", "FastAPI 返回 SSE，Spring 作为代理转发给前端，同时缓存完整答案落库"],
            ["权限隔离", "Java 校验 owner，Qdrant filter 强制 kbId + ownerId"],
            ["引用溯源", "Qdrant payload 保存 docId、chunkId、fileName、pageNo、text，回答后写 answer_citation"],
            ["状态一致性", "document 和 indexing_task 双状态，失败写 error_message，重试前清理或 upsert"],
            ["检索质量", "V2 用评测集、Recall@K、MRR、bad case 调参数"],
            ["不是套 LangChain", "LangChain 只是 AI 服务内部工具，项目重点是业务系统、服务拆分、异步索引、权限过滤、状态流转、引用落库、SSE、评测和部署"],
        ],
        widths=[35, 135],
        font_size=6.8,
    )
    h2(story, "常见追问")
    table(
        story,
        [
            ["追问", "回答思路"],
            ["为什么不用 Java 直接做 RAG", "Python AI 生态成熟，Java 做业务主体更适合求职方向"],
            ["为什么 V1 不用 MQ", "V1 用本地异步降低复杂度，V2 用 RabbitMQ 提升可靠性"],
            ["如何防止越权", "Java 鉴权 + Qdrant metadata filter 双层保证"],
            ["citation 怎么来的", "来自检索 chunk payload，不是模型生成"],
            ["索引失败怎么办", "task/document 状态 FAILED，记录 error，支持 retry"],
            ["如何评估效果", "Recall@K、MRR、bad case、参数对比"],
            ["模型胡说怎么办", "低相似度拒答、prompt 约束、引用必须来自 chunk、V2 做 no-answer 和自检"],
        ],
        widths=[48, 122],
    )

    h1(story, "十九、严格要求与风险控制")
    table(
        story,
        [
            ["类别", "内容"],
            ["必须真实实现", "MinIO 上传、MySQL 状态表、FastAPI 解析、embedding、Qdrant upsert/search/filter、LLM 问答、citation 落库、Java 调 Python、SSE、Docker Compose"],
            ["可以 mock", "前端样式、完整 RBAC、多租户、评测页面、Agent 工具结果、模型成本精确费用"],
            ["现在过度设计", "Kubernetes、注册中心、分布式 tracing、多 Agent 协作、审批流、企业级 RBAC、多模型自动调度"],
            ["做假会被问穿", "假引用、假异步状态、假权限过滤、假 Qdrant 检索、假 embedding、假评测、不能启动的 Docker Compose"],
            ["最容易烂尾", "一上来做 Agent；前端太复杂；模型配置混乱；文档解析追求过高；没日志；没先跑通最小链路"],
            ["必须有日志", "上传、MinIO objectKey、indexing_task、调 FastAPI、Python 解析/切分/embedding/Qdrant、LLM 耗时、SSE、状态更新和重试"],
            ["必须有状态流转", "document.index_status 与 indexing_task.status 必须真实更新，失败必须可见"],
            ["必须能现场演示", "docker compose up、登录、建知识库、上传文档、看索引状态、提问、流式回答、引用、反馈"],
        ],
        widths=[38, 132],
        font_size=6.8,
    )
    h2(story, "最终路线图")
    p(story, "总体路线：V0 打通空项目和服务通信，V1 做完整工程化 RAG MVP，V2 做检索质量，V3 做可控 Agent，V4 做生产化能力。确认后从 V0 开始，第一步先搭根目录、Compose、Spring Boot 空项目和 FastAPI /health，把 Java 调 Python 这条线先打通。")

    return story


def main():
    OUT.parent.mkdir(parents=True, exist_ok=True)
    doc = SimpleDocTemplate(
        str(OUT),
        pagesize=A4,
        rightMargin=18 * mm,
        leftMargin=18 * mm,
        topMargin=18 * mm,
        bottomMargin=16 * mm,
        title="企业知识库 RAG + Agent 工作流平台规划",
        author="Codex",
    )
    doc.build(build_story(), onFirstPage=on_page, onLaterPages=on_page)
    print(OUT)


if __name__ == "__main__":
    main()
