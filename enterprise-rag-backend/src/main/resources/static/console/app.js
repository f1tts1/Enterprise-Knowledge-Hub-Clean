(() => {
  "use strict";

  const STORAGE = {
    token: "ekb.console.accessToken",
    // refresh token 只用于刷新登录态和退出撤销，不直接作为业务 API 的 Authorization 凭证。
    refreshToken: "ekb.console.refreshToken",
    apiBase: "ekb.console.apiBase",
    selectedKbId: "ekb.console.selectedKbId",
  };

  const DEFAULT_PASSWORD = "Password123";
  const ACTIVE_STATUSES = new Set(["PENDING_INDEX", "INDEXING", "DELETING"]);
  const FAILURE_STATUSES = new Set(["INDEX_FAILED", "DELETE_FAILED"]);
  const PAGE_TITLES = {
    kbs: "知识库",
    documents: "文档与索引",
    retrieval: "向量检索",
    rag: "同步问答",
    login: "登录",
    register: "注册",
    notFound: "页面不存在",
  };

  const state = {
    token: localStorage.getItem(STORAGE.token) || "",
    // 刷新页面后保留 refresh token，便于 access token 过期时静默续期。
    refreshToken: localStorage.getItem(STORAGE.refreshToken) || "",
    apiBase: localStorage.getItem(STORAGE.apiBase) || "",
    currentUser: null,
    route: parseRoute(),
    kbs: [],
    kbsLoaded: false,
    selectedKbId: localStorage.getItem(STORAGE.selectedKbId) || "",
    documents: [],
    docsLoadedForKbId: "",
    busy: new Set(),
    notices: [],
    lastRequestId: "-",
    lastSyncAt: "",
    pollTimer: null,
    forms: {
      username: "",
      password: DEFAULT_PASSWORD,
      nickname: "",
      email: "",
      apiBase: localStorage.getItem(STORAGE.apiBase) || "",
      kbName: "",
      kbDescription: "",
      retrievalQuery: "",
      retrievalTopK: 5,
      ragQuestion: "",
      ragTopK: 5,
    },
    retrieval: {
      result: null,
    },
    rag: {
      result: null,
    },
  };

  const app = document.getElementById("app");

  window.addEventListener("hashchange", handleRouteChange);
  document.addEventListener("submit", handleSubmit);
  document.addEventListener("click", handleClick);
  document.addEventListener("input", handleInput);

  boot();

  function boot() {
    if (!location.hash) {
      navigate(state.token ? "/app/kbs" : "/login", true);
      return;
    }
    handleRouteChange();
  }

  async function handleRouteChange() {
    state.route = parseRoute();

    if (state.route.name === "kb") {
      navigate(`/app/kbs/${state.route.kbId}/documents`, true);
      return;
    }

    if (routeNeedsAuth(state.route) && !state.token) {
      notify("warning", "请先登录。");
      navigate("/login", true);
      return;
    }

    if (state.token && routeIsAuth(state.route)) {
      navigate("/app/kbs", true);
      return;
    }

    render();

    if (!routeNeedsAuth(state.route)) {
      stopPolling();
      return;
    }

    try {
      await ensureSession();
      await ensureKnowledgeBases();
      if (state.route.kbId) {
        selectKnowledgeBase(state.route.kbId, { persist: true, navigate: false });
        await ensureDocuments(state.route.kbId);
      }
      render();
      updatePolling();
    } catch (error) {
      handleError(error);
    }
  }

  function parseRoute() {
    const hashPath = (location.hash || "#/login").replace(/^#/, "");
    const normalized = hashPath.startsWith("/") ? hashPath : `/${hashPath}`;
    const parts = normalized.split("/").filter(Boolean);

    if (parts.length === 1 && parts[0] === "login") {
      return { name: "login", path: normalized };
    }
    if (parts.length === 1 && parts[0] === "register") {
      return { name: "register", path: normalized };
    }
    if (parts.length === 2 && parts[0] === "app" && parts[1] === "kbs") {
      return { name: "kbs", path: normalized };
    }
    if (parts.length === 3 && parts[0] === "app" && parts[1] === "kbs") {
      return { name: "kb", path: normalized, kbId: parts[2] };
    }
    if (parts.length === 4 && parts[0] === "app" && parts[1] === "kbs") {
      const page = parts[3];
      if (["documents", "retrieval", "rag"].includes(page)) {
        return { name: page, path: normalized, kbId: parts[2] };
      }
    }
    return { name: "notFound", path: normalized };
  }

  function routeNeedsAuth(route) {
    return !routeIsAuth(route);
  }

  function routeIsAuth(route) {
    return route.name === "login" || route.name === "register";
  }

  function navigate(path, replace = false) {
    const next = `#${path}`;
    if (location.hash === next) {
      handleRouteChange();
      return;
    }
    if (replace) {
      history.replaceState(null, "", next);
      handleRouteChange();
      return;
    }
    location.hash = path;
  }

  function render() {
    if (state.route.name === "login") {
      app.innerHTML = renderAuthPage("login");
      return;
    }
    if (state.route.name === "register") {
      app.innerHTML = renderAuthPage("register");
      return;
    }
    app.innerHTML = renderConsole();
  }

  function renderAuthPage(mode) {
    const isLogin = mode === "login";
    return `
      <main class="auth-page">
        <section class="auth-product">
          <div class="brand-row">
            <div class="brand-logo">KH</div>
            <div>
              <h1>Enterprise Knowledge Hub</h1>
              <p>Java 后端 + AI 工程化知识库</p>
            </div>
          </div>
          <div class="auth-metrics" aria-label="项目链路">
            <div><strong>JWT</strong><span>鉴权</span></div>
            <div><strong>Redis</strong><span>异步索引</span></div>
            <div><strong>Qdrant</strong><span>向量检索</span></div>
          </div>
          <div class="pipeline-strip">
            <span>Upload</span>
            <span>Index</span>
            <span>Search</span>
            <span>Delete</span>
          </div>
        </section>
        <section class="auth-card">
          <div class="auth-card-head">
            <p class="section-kicker">${isLogin ? "欢迎回来" : "创建账号"}</p>
            <h2>${isLogin ? "登录控制台" : "注册新用户"}</h2>
          </div>
          ${renderNotices()}
          <form id="${isLogin ? "loginForm" : "registerForm"}" class="form-stack">
            <label>
              <span>用户名</span>
              <input id="usernameInput" autocomplete="username" value="${escapeAttr(state.forms.username)}" required />
            </label>
            <label>
              <span>密码</span>
              <input id="passwordInput" type="password" autocomplete="${isLogin ? "current-password" : "new-password"}" value="${escapeAttr(state.forms.password)}" required />
            </label>
            ${isLogin ? "" : `
              <label>
                <span>昵称</span>
                <input id="nicknameInput" value="${escapeAttr(state.forms.nickname)}" />
              </label>
              <label>
                <span>邮箱</span>
                <input id="emailInput" type="email" value="${escapeAttr(state.forms.email)}" />
              </label>
            `}
            <button class="button primary wide" type="submit" ${isBusy(mode) ? "disabled" : ""}>
              ${isBusy(mode) ? "处理中..." : isLogin ? "登录" : "注册"}
            </button>
          </form>
          <div class="auth-actions">
            <button class="text-button" type="button" data-action="fill-demo-user">填入演示账号</button>
            ${isLogin ? `<a href="#/register">注册账号</a>` : `<a href="#/login">返回登录</a>`}
          </div>
          <form id="apiBaseForm" class="api-base-card">
            <label>
              <span>API 地址</span>
              <input id="apiBaseInput" placeholder="默认同源" value="${escapeAttr(state.forms.apiBase)}" />
            </label>
            <button class="button ghost" type="submit">保存</button>
          </form>
        </section>
      </main>
      ${renderToastHost()}
    `;
  }

  function renderConsole() {
    const selectedKb = getSelectedKb();
    return `
      <div class="console-shell">
        <aside class="side-nav">
          <a class="brand-row compact" href="#/app/kbs">
            <div class="brand-logo small">KH</div>
            <div>
              <strong>Knowledge Hub</strong>
              <span>Engineering Console</span>
            </div>
          </a>
          <nav class="nav-list" aria-label="主导航">
            <a class="${navClass("kbs")}" href="#/app/kbs"><span>知识库</span><small>${state.kbs.length}</small></a>
            ${selectedKb ? `
              <a class="${navClass("documents")}" href="#/app/kbs/${selectedKb.id}/documents"><span>文档与索引</span><small>${documentStats().total}</small></a>
              <a class="${navClass("retrieval")}" href="#/app/kbs/${selectedKb.id}/retrieval"><span>向量检索</span><small>TopK</small></a>
              <a class="${navClass("rag")}" href="#/app/kbs/${selectedKb.id}/rag"><span>同步问答</span><small>RAG</small></a>
            ` : ""}
          </nav>
          <section class="side-section">
            <div class="side-section-head">
              <span>当前知识库</span>
              <button class="icon-button" type="button" data-action="refresh-kbs" title="刷新知识库" aria-label="刷新知识库">↻</button>
            </div>
            <div class="kb-switcher">
              ${renderKbSwitcher()}
            </div>
          </section>
          <div class="side-footer">
            <div class="user-panel">
              <strong>${escapeHtml(state.currentUser?.username || "未加载")}</strong>
              <span>${escapeHtml(state.currentUser?.email || state.currentUser?.status || "ACTIVE")}</span>
            </div>
            <button class="button ghost wide" type="button" data-action="logout">退出登录</button>
          </div>
        </aside>
        <main class="workspace">
          <header class="topbar">
            <div>
              <p class="breadcrumb">${renderBreadcrumb()}</p>
              <h1>${escapeHtml(PAGE_TITLES[state.route.name] || "控制台")}</h1>
            </div>
            <div class="topbar-tools">
              <form id="apiBaseForm" class="api-base-inline">
                <label>
                  <span>API</span>
                  <input id="apiBaseInput" placeholder="同源" value="${escapeAttr(state.forms.apiBase)}" />
                </label>
                <button class="icon-button framed" type="submit" title="保存 API 地址" aria-label="保存 API 地址">✓</button>
              </form>
              <div class="request-chip">
                <span>Request ID</span>
                <strong>${escapeHtml(state.lastRequestId)}</strong>
              </div>
            </div>
          </header>
          ${renderNotices()}
          ${renderWorkspacePage()}
        </main>
      </div>
      ${renderToastHost()}
    `;
  }

  function renderWorkspacePage() {
    if (state.route.name === "kbs") {
      return renderKnowledgeBasesPage();
    }
    if (state.route.name === "notFound") {
      return renderNotFoundPage();
    }
    if (!getSelectedKb()) {
      return renderNoKnowledgeBasePage();
    }
    if (state.route.name === "documents") {
      return renderDocumentsPage();
    }
    if (state.route.name === "retrieval") {
      return renderRetrievalPage();
    }
    if (state.route.name === "rag") {
      return renderRagPage();
    }
    return renderNotFoundPage();
  }

  function renderKnowledgeBasesPage() {
    const total = state.kbs.length;
    const activeKb = getSelectedKb();
    return `
      <section class="summary-grid">
        ${renderMetric("知识库", total, "当前账号")}
        ${renderMetric("已选择", activeKb ? activeKb.name : "未选择", activeKb ? `#${activeKb.id}` : "进入详情后管理文档")}
        ${renderMetric("权限模型", "Owner", "私有知识库")}
      </section>
      <section class="content-grid kbs-grid">
        <div class="surface">
          <div class="surface-head">
            <div>
              <p class="section-kicker">Knowledge Bases</p>
              <h2>知识库列表</h2>
            </div>
            <button class="button ghost" type="button" data-action="refresh-kbs" ${isBusy("kbs") ? "disabled" : ""}>刷新</button>
          </div>
          ${isBusy("kbs") ? renderLoading("正在加载知识库") : ""}
          ${state.kbsLoaded && state.kbs.length === 0 ? renderEmpty("还没有知识库", "创建后即可上传文档。") : ""}
          <div class="kb-list">
            ${state.kbs.map(renderKbListItem).join("")}
          </div>
        </div>
        <div class="surface accent-surface">
          <div class="surface-head">
            <div>
              <p class="section-kicker">Create</p>
              <h2>新建知识库</h2>
            </div>
          </div>
          <form id="createKbForm" class="form-stack">
            <label>
              <span>名称</span>
              <input id="kbNameInput" maxlength="128" value="${escapeAttr(state.forms.kbName)}" required />
            </label>
            <label>
              <span>描述</span>
              <textarea id="kbDescriptionInput" maxlength="512" rows="5">${escapeHtml(state.forms.kbDescription)}</textarea>
            </label>
            <button class="button primary" type="submit" ${isBusy("createKb") ? "disabled" : ""}>
              ${isBusy("createKb") ? "创建中..." : "创建知识库"}
            </button>
          </form>
        </div>
      </section>
    `;
  }

  function renderKbListItem(kb) {
    const active = String(kb.id) === String(state.selectedKbId);
    return `
      <article class="kb-list-item ${active ? "active" : ""}">
        <div>
          <div class="item-title-row">
            <h3>${escapeHtml(kb.name)}</h3>
            <span class="status-pill neutral">${escapeHtml(kb.status || "ACTIVE")}</span>
          </div>
          <p>${escapeHtml(kb.description || "暂无描述")}</p>
          <dl class="meta-row">
            <div><dt>ID</dt><dd>${kb.id}</dd></div>
            <div><dt>可见性</dt><dd>${escapeHtml(kb.visibility || "PRIVATE")}</dd></div>
            <div><dt>更新时间</dt><dd>${formatDate(kb.updatedAt || kb.createdAt)}</dd></div>
          </dl>
        </div>
        <div class="item-actions">
          <a class="button primary" href="#/app/kbs/${kb.id}/documents">进入</a>
          <button class="button danger ghost" type="button" data-action="delete-kb" data-kb-id="${kb.id}" data-kb-name="${escapeAttr(kb.name)}">删除</button>
        </div>
      </article>
    `;
  }

  function renderDocumentsPage() {
    const kb = getSelectedKb();
    const stats = documentStats();
    return `
      ${renderKbHeader(kb, "documents")}
      <section class="summary-grid">
        ${renderMetric("全部文档", stats.total, "当前知识库")}
        ${renderMetric("已索引", stats.indexed, "可用于检索")}
        ${renderMetric("处理中", stats.running, stats.running > 0 ? "自动刷新中" : "无进行中任务")}
        ${renderMetric("失败", stats.failed, "可重试或删除")}
      </section>
      <section class="content-grid docs-grid">
        <div class="surface">
          <div class="surface-head">
            <div>
              <p class="section-kicker">Upload</p>
              <h2>文档上传</h2>
            </div>
          </div>
          <form id="uploadDocumentForm" class="upload-box">
            <input id="documentFileInput" type="file" accept=".txt,.md,.markdown,.pdf,.docx" />
            <div class="upload-copy">
              <strong>TXT / Markdown / PDF / DOCX</strong>
              <span>最大 20MB</span>
            </div>
            <button class="button primary" type="submit" ${isBusy("upload") ? "disabled" : ""}>
              ${isBusy("upload") ? "上传中..." : "上传文档"}
            </button>
          </form>
          <div class="flow-steps">
            <span>MinIO</span>
            <span>RabbitMQ</span>
            <span>FastAPI</span>
            <span>Qdrant</span>
          </div>
        </div>
        <div class="surface docs-surface">
          <div class="surface-head">
            <div>
              <p class="section-kicker">Documents</p>
              <h2>文档列表</h2>
            </div>
            <div class="head-actions">
              ${state.lastSyncAt ? `<span class="sync-time">${escapeHtml(state.lastSyncAt)}</span>` : ""}
              <button class="button ghost" type="button" data-action="refresh-docs" ${isBusy("docs") ? "disabled" : ""}>刷新</button>
            </div>
          </div>
          ${isBusy("docs") ? renderLoading("正在同步文档状态") : ""}
          ${state.docsLoadedForKbId === String(kb.id) && state.documents.length === 0
            ? renderEmpty("暂无文档", "上传后会显示索引状态。")
            : renderDocumentTable()}
        </div>
      </section>
    `;
  }

  function renderRetrievalPage() {
    const kb = getSelectedKb();
    const records = state.retrieval.result?.records || [];
    return `
      ${renderKbHeader(kb, "retrieval")}
      <section class="query-grid">
        <div class="surface query-panel">
          <div class="surface-head">
            <div>
              <p class="section-kicker">Retrieval</p>
              <h2>向量检索</h2>
            </div>
          </div>
          <form id="retrievalForm" class="form-stack">
            <label>
              <span>检索问题</span>
              <textarea id="retrievalQueryInput" maxlength="512" rows="6" required>${escapeHtml(state.forms.retrievalQuery)}</textarea>
            </label>
            <label class="short-field">
              <span>TopK</span>
              <input id="retrievalTopKInput" type="number" min="1" max="20" value="${state.forms.retrievalTopK}" />
            </label>
            <button class="button primary" type="submit" ${isBusy("retrieval") ? "disabled" : ""}>
              ${isBusy("retrieval") ? "检索中..." : "开始检索"}
            </button>
          </form>
        </div>
        <div class="surface result-panel">
          <div class="surface-head">
            <div>
              <p class="section-kicker">Results</p>
              <h2>命中片段</h2>
            </div>
            ${state.retrieval.result ? `<span class="status-pill blue">${records.length} 条</span>` : ""}
          </div>
          ${isBusy("retrieval") ? renderLoading("正在请求 Java 检索入口") : ""}
          ${!state.retrieval.result && !isBusy("retrieval") ? renderEmpty("暂无结果", "提交查询后展示 chunk。") : ""}
          ${state.retrieval.result && records.length === 0 ? renderEmpty("没有命中", "确认文档已索引完成。") : ""}
          <div class="result-list">
            ${records.map(renderChunkCard).join("")}
          </div>
        </div>
      </section>
    `;
  }

  function renderRagPage() {
    const kb = getSelectedKb();
    const response = state.rag.result;
    const citations = response?.citations || [];
    const answerStatus = response ? formatRagAnswerStatus(response.answerStatus) : "";
    const noAnswerReason = response ? formatRagNoAnswerReason(response.noAnswerReason) : "";
    return `
      ${renderKbHeader(kb, "rag")}
      <section class="query-grid">
        <div class="surface query-panel">
          <div class="surface-head">
            <div>
              <p class="section-kicker">Synchronous RAG</p>
              <h2>同步问答</h2>
            </div>
            <span class="status-pill amber">非流式</span>
          </div>
          <form id="ragForm" class="form-stack">
            <label>
              <span>问题</span>
              <textarea id="ragQuestionInput" maxlength="512" rows="6" required>${escapeHtml(state.forms.ragQuestion)}</textarea>
            </label>
            <label class="short-field">
              <span>TopK</span>
              <input id="ragTopKInput" type="number" min="1" max="20" value="${state.forms.ragTopK}" />
            </label>
            <button class="button primary" type="submit" ${isBusy("rag") ? "disabled" : ""}>
              ${isBusy("rag") ? "生成中..." : "提交问题"}
            </button>
          </form>
        </div>
        <div class="surface result-panel">
          <div class="surface-head">
            <div>
              <p class="section-kicker">Answer</p>
              <h2>答案与引用</h2>
            </div>
            ${response ? `<span class="status-pill ${response.noAnswer ? "amber" : "blue"}">${escapeHtml(answerStatus)} · ${citations.length} 引用</span>` : ""}
          </div>
          ${isBusy("rag") ? renderLoading("正在同步生成答案") : ""}
          ${!response && !isBusy("rag") ? renderEmpty("暂无答案", "提交问题后返回 answer 和 citations。") : ""}
          ${response ? `
            <article class="answer-block">
              <div class="answer-meta">
                <span>${escapeHtml(response.llmProvider || "no-llm")}</span>
                <span>${escapeHtml(response.llmModel || "no-model")}</span>
                <span>${escapeHtml(response.vectorCollection || "-")}</span>
                ${noAnswerReason ? `<span>${escapeHtml(noAnswerReason)}</span>` : ""}
              </div>
              <p>${escapeHtml(response.answer || "未返回答案。")}</p>
            </article>
            ${citations.length === 0 ? renderEmpty("没有实际引用", "拒答结果不会把全部检索命中伪装成引用。") : ""}
            <div class="result-list">
              ${citations.map(renderCitationCard).join("")}
            </div>
          ` : ""}
        </div>
      </section>
    `;
  }

  function formatRagAnswerStatus(answerStatus) {
    if (answerStatus === "ANSWERED") return "已回答";
    if (answerStatus === "NO_CONTEXT") return "无可用上下文";
    if (answerStatus === "INSUFFICIENT_CONTEXT") return "上下文不足";
    return answerStatus || "未知状态";
  }

  function formatRagNoAnswerReason(reason) {
    if (reason === "NO_RETRIEVED_CONTEXT") return "没有检索候选";
    if (reason === "NO_USABLE_CONTEXT") return "候选正文不可用";
    if (reason === "LOW_RELEVANCE") return "候选相关性不足";
    if (reason === "MODEL_REPORTED_INSUFFICIENT_CONTEXT") return "模型判断上下文不足";
    return reason || "";
  }

  function renderKbHeader(kb, active) {
    return `
      <section class="kb-header">
        <div>
          <p class="section-kicker">Knowledge Base #${kb.id}</p>
          <h2>${escapeHtml(kb.name)}</h2>
          <p>${escapeHtml(kb.description || "暂无描述")}</p>
        </div>
        <nav class="tab-list" aria-label="知识库页面">
          <a class="${active === "documents" ? "active" : ""}" href="#/app/kbs/${kb.id}/documents">文档索引</a>
          <a class="${active === "retrieval" ? "active" : ""}" href="#/app/kbs/${kb.id}/retrieval">向量检索</a>
          <a class="${active === "rag" ? "active" : ""}" href="#/app/kbs/${kb.id}/rag">同步问答</a>
        </nav>
      </section>
    `;
  }

  function renderMetric(label, value, caption) {
    return `
      <div class="metric">
        <span>${escapeHtml(label)}</span>
        <strong>${escapeHtml(value)}</strong>
        <small>${escapeHtml(caption)}</small>
      </div>
    `;
  }

  function renderDocumentTable() {
    return `
      <div class="table-shell">
        <table class="data-table">
          <thead>
            <tr>
              <th>文件</th>
              <th>索引状态</th>
              <th>Chunk</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            ${state.documents.map(renderDocumentRow).join("")}
          </tbody>
        </table>
      </div>
    `;
  }

  function renderDocumentRow(doc) {
    return `
      <tr>
        <td>
          <div class="file-cell">
            <strong>${escapeHtml(doc.fileName || `Document ${doc.id}`)}</strong>
            <span>docId=${doc.id} · ${formatBytes(doc.fileSize)} · ${escapeHtml(doc.contentType || "-")}</span>
            ${doc.errorMessage ? `<em>${escapeHtml(doc.errorMessage)}</em>` : ""}
          </div>
        </td>
        <td>${renderStatus(doc.indexStatus)}</td>
        <td>${doc.chunkCount || 0}</td>
        <td>${formatDate(doc.updatedAt || doc.createdAt)}</td>
        <td>
          <div class="row-actions">
            <button class="button tiny ghost" type="button" data-action="refresh-doc-status" data-doc-id="${doc.id}">状态</button>
            <button class="button tiny ghost" type="button" data-action="retry-doc" data-doc-id="${doc.id}" ${canRetry(doc) ? "" : "disabled"}>${doc.indexStatus === "PENDING_INDEX" ? "重投" : "重试"}</button>
            <button class="button tiny danger ghost" type="button" data-action="delete-doc" data-doc-id="${doc.id}" data-file-name="${escapeAttr(doc.fileName || "")}">删除</button>
          </div>
        </td>
      </tr>
    `;
  }

  function renderChunkCard(record) {
    return `
      <article class="result-card">
        <header>
          <div>
            <h3>${escapeHtml(record.fileName || "unknown")}</h3>
            <p>docId=${record.docId || "-"} · chunk=${escapeHtml(record.chunkId || "-")} · page=${record.pageNo ?? "-"}</p>
          </div>
          <span class="score">score ${formatScore(record.score)}</span>
        </header>
        <div class="chunk-text">${escapeHtml(record.text || "")}</div>
      </article>
    `;
  }

  function renderCitationCard(record) {
    return `
      <article class="result-card citation">
        <header>
          <div>
            <h3>片段 ${record.index || "-"}</h3>
            <p>${escapeHtml(record.fileName || "unknown")} · docId=${record.docId || "-"} · chunk=${escapeHtml(record.chunkId || "-")}</p>
          </div>
          <span class="score">score ${formatScore(record.score)}</span>
        </header>
        <div class="chunk-text">${escapeHtml(record.text || "")}</div>
      </article>
    `;
  }

  function renderKbSwitcher() {
    if (!state.kbsLoaded && isBusy("kbs")) {
      return `<div class="side-empty">加载中</div>`;
    }
    if (!state.kbs.length) {
      return `<div class="side-empty">暂无知识库</div>`;
    }
    return state.kbs.map((kb) => `
      <a class="switcher-item ${String(kb.id) === String(state.selectedKbId) ? "active" : ""}" href="#/app/kbs/${kb.id}/documents">
        <span>${escapeHtml(kb.name)}</span>
        <small>#${kb.id}</small>
      </a>
    `).join("");
  }

  function renderStatus(status) {
    const value = status || "UNKNOWN";
    let type = "neutral";
    if (value === "INDEXED" || value === "DELETED") {
      type = "success";
    } else if (ACTIVE_STATUSES.has(value)) {
      type = "amber";
    } else if (FAILURE_STATUSES.has(value)) {
      type = "danger";
    }
    return `<span class="status-pill ${type}">${escapeHtml(value)}</span>`;
  }

  function renderNotices() {
    if (!state.notices.length) {
      return "";
    }
    return `
      <div class="notice-stack">
        ${state.notices.map((notice) => `
          <div class="notice ${notice.type}">
            <span>${escapeHtml(notice.message)}</span>
            <button type="button" data-action="dismiss-notice" data-notice-id="${notice.id}" aria-label="关闭">×</button>
          </div>
        `).join("")}
      </div>
    `;
  }

  function renderLoading(text) {
    return `
      <div class="loading-row">
        <span class="spinner" aria-hidden="true"></span>
        <span>${escapeHtml(text)}</span>
      </div>
    `;
  }

  function renderEmpty(title, text) {
    return `
      <div class="empty">
        <strong>${escapeHtml(title)}</strong>
        <span>${escapeHtml(text)}</span>
      </div>
    `;
  }

  function renderNoKnowledgeBasePage() {
    return `
      <div class="surface">
        ${renderEmpty("请选择知识库", "从左侧列表进入知识库。")}
        <a class="button primary inline-action" href="#/app/kbs">返回知识库</a>
      </div>
    `;
  }

  function renderNotFoundPage() {
    return `
      <div class="surface">
        ${renderEmpty("页面不存在", "请通过左侧导航进入。")}
        <a class="button primary inline-action" href="#/app/kbs">返回知识库</a>
      </div>
    `;
  }

  function renderBreadcrumb() {
    if (state.route.name === "kbs") {
      return "控制台 / 知识库";
    }
    const kb = getSelectedKb();
    if (!kb) {
      return "控制台";
    }
    return `控制台 / ${kb.name} / ${PAGE_TITLES[state.route.name] || ""}`;
  }

  function renderToastHost() {
    return `<div id="toastHost" class="toast-host"></div>`;
  }

  async function handleSubmit(event) {
    const form = event.target;
    if (!(form instanceof HTMLFormElement)) {
      return;
    }
    event.preventDefault();

    if (form.id === "loginForm") {
      syncAuthForm();
      await withBusy("login", login);
      return;
    }
    if (form.id === "registerForm") {
      syncAuthForm();
      await withBusy("register", register);
      return;
    }
    if (form.id === "apiBaseForm") {
      syncApiBaseForm();
      saveApiBase();
      return;
    }
    if (form.id === "createKbForm") {
      syncKbForm();
      await withBusy("createKb", createKnowledgeBase);
      return;
    }
    if (form.id === "uploadDocumentForm") {
      const file = document.getElementById("documentFileInput")?.files?.[0] || null;
      await withBusy("upload", () => uploadDocument(file));
      return;
    }
    if (form.id === "retrievalForm") {
      syncRetrievalForm();
      await withBusy("retrieval", searchRetrieval);
      return;
    }
    if (form.id === "ragForm") {
      syncRagForm();
      await withBusy("rag", askRag);
    }
  }

  function handleClick(event) {
    const target = event.target.closest("[data-action]");
    if (!target) {
      return;
    }

    const action = target.dataset.action;
    if (action === "fill-demo-user") {
      fillDemoUser();
      render();
    } else if (action === "logout") {
      withBusy("logout", logout);
    } else if (action === "refresh-kbs") {
      withBusy("kbs", () => loadKnowledgeBases({ force: true }));
    } else if (action === "refresh-docs") {
      withBusy("docs", () => loadDocuments(state.selectedKbId, { force: true }));
    } else if (action === "refresh-doc-status") {
      withBusy(`doc-${target.dataset.docId}`, () => refreshDocumentStatus(target.dataset.docId));
    } else if (action === "retry-doc") {
      withBusy(`doc-${target.dataset.docId}`, () => retryDocument(target.dataset.docId));
    } else if (action === "delete-doc") {
      deleteDocument(target.dataset.docId, target.dataset.fileName || "");
    } else if (action === "delete-kb") {
      deleteKnowledgeBase(target.dataset.kbId, target.dataset.kbName || "");
    } else if (action === "dismiss-notice") {
      dismissNotice(target.dataset.noticeId);
      render();
    }
  }

  function handleInput(event) {
    const input = event.target;
    if (!(input instanceof HTMLInputElement || input instanceof HTMLTextAreaElement)) {
      return;
    }

    const map = {
      usernameInput: "username",
      passwordInput: "password",
      nicknameInput: "nickname",
      emailInput: "email",
      apiBaseInput: "apiBase",
      kbNameInput: "kbName",
      kbDescriptionInput: "kbDescription",
      retrievalQueryInput: "retrievalQuery",
      ragQuestionInput: "ragQuestion",
    };
    const key = map[input.id];
    if (key) {
      state.forms[key] = input.value;
    } else if (input.id === "retrievalTopKInput") {
      state.forms.retrievalTopK = clampTopK(input.value);
    } else if (input.id === "ragTopKInput") {
      state.forms.ragTopK = clampTopK(input.value);
    }
  }

  async function withBusy(key, action) {
    state.busy.add(key);
    render();
    try {
      await action();
    } catch (error) {
      handleError(error);
    } finally {
      state.busy.delete(key);
      render();
      updatePolling();
    }
  }

  async function login() {
    const data = await api("/api/v1/auth/login", {
      method: "POST",
      auth: false,
      body: {
        username: state.forms.username.trim(),
        password: state.forms.password,
      },
    });
    applyAuthData(data);
    state.kbsLoaded = false;
    state.documents = [];
    notify("success", "登录成功。");
    navigate("/app/kbs", true);
  }

  async function register() {
    await api("/api/v1/auth/register", {
      method: "POST",
      auth: false,
      body: {
        username: state.forms.username.trim(),
        password: state.forms.password,
        nickname: state.forms.nickname.trim() || null,
        email: state.forms.email.trim() || null,
      },
    });
    notify("success", "注册成功，请登录。");
    navigate("/login", true);
  }

  async function ensureSession() {
    if (state.currentUser) {
      return;
    }
    try {
      state.currentUser = await api("/api/v1/users/me", { method: "GET", quiet: true });
    } catch (error) {
      clearSession();
      navigate("/login", true);
      throw error;
    }
  }

  async function ensureKnowledgeBases() {
    if (state.kbsLoaded) {
      return;
    }
    await loadKnowledgeBases({ quiet: true });
  }

  async function loadKnowledgeBases(options = {}) {
    const data = await api("/api/v1/knowledge-bases?page=1&size=100", {
      method: "GET",
      quiet: options.quiet,
    });
    state.kbs = data.records || [];
    state.kbsLoaded = true;
    if (state.selectedKbId && !state.kbs.some((kb) => String(kb.id) === String(state.selectedKbId))) {
      state.selectedKbId = "";
      state.documents = [];
      state.docsLoadedForKbId = "";
      localStorage.removeItem(STORAGE.selectedKbId);
    }
  }

  async function createKnowledgeBase() {
    if (!state.forms.kbName.trim()) {
      throw new Error("知识库名称不能为空。");
    }
    const kb = await api("/api/v1/knowledge-bases", {
      method: "POST",
      body: {
        name: state.forms.kbName.trim(),
        description: state.forms.kbDescription.trim() || null,
      },
    });
    state.forms.kbName = "";
    state.forms.kbDescription = "";
    await loadKnowledgeBases({ force: true });
    selectKnowledgeBase(kb.id, { persist: true, navigate: true });
    notify("success", "知识库已创建。");
  }

  async function deleteKnowledgeBase(kbId, kbName) {
    if (!window.confirm(`确认删除知识库：${kbName || kbId}？`)) {
      return;
    }
    await withBusy(`kb-${kbId}`, async () => {
      await api(`/api/v1/knowledge-bases/${kbId}`, { method: "DELETE" });
      if (String(state.selectedKbId) === String(kbId)) {
        state.selectedKbId = "";
        state.documents = [];
        state.docsLoadedForKbId = "";
        localStorage.removeItem(STORAGE.selectedKbId);
      }
      await loadKnowledgeBases({ force: true });
      notify("success", "知识库已删除。");
      navigate("/app/kbs", true);
    });
  }

  function selectKnowledgeBase(kbId, options = {}) {
    state.selectedKbId = String(kbId);
    if (options.persist) {
      localStorage.setItem(STORAGE.selectedKbId, state.selectedKbId);
    }
    if (options.navigate) {
      navigate(`/app/kbs/${kbId}/documents`);
    }
  }

  async function ensureDocuments(kbId) {
    if (state.docsLoadedForKbId === String(kbId)) {
      return;
    }
    await loadDocuments(kbId, { quiet: true });
  }

  async function loadDocuments(kbId = state.selectedKbId, options = {}) {
    if (!kbId) {
      state.documents = [];
      state.docsLoadedForKbId = "";
      return;
    }
    const data = await api(`/api/v1/knowledge-bases/${kbId}/documents?page=1&size=100`, {
      method: "GET",
      quiet: options.quiet,
    });
    state.documents = data.records || [];
    state.docsLoadedForKbId = String(kbId);
    state.lastSyncAt = `同步于 ${new Date().toLocaleTimeString()}`;
  }

  async function uploadDocument(file) {
    ensureSelectedKb();
    if (!file) {
      throw new Error("请选择文档。");
    }
    const formData = new FormData();
    formData.append("file", file);
    await api(`/api/v1/knowledge-bases/${state.selectedKbId}/documents`, {
      method: "POST",
      multipart: true,
      body: formData,
    });
    const input = document.getElementById("documentFileInput");
    if (input) {
      input.value = "";
    }
    await loadDocuments(state.selectedKbId, { force: true });
    notify("success", "文档已上传。");
  }

  async function refreshDocumentStatus(documentId) {
    const data = await api(`/api/v1/documents/${documentId}/index-status`, { method: "GET" });
    await loadDocuments(state.selectedKbId, { quiet: true });
    notify("success", `文档 ${documentId}: ${data.documentIndexStatus}/${data.taskStatus}`);
  }

  async function retryDocument(documentId) {
    await api(`/api/v1/documents/${documentId}/index-retry`, {
      method: "POST",
      body: {},
    });
    await loadDocuments(state.selectedKbId, { force: true });
    notify("success", "已提交索引重试。");
  }

  async function deleteDocument(documentId, fileName) {
    if (!window.confirm(`确认删除文档：${fileName || documentId}？`)) {
      return;
    }
    await withBusy(`doc-${documentId}`, async () => {
      await api(`/api/v1/documents/${documentId}`, { method: "DELETE" });
      state.documents = state.documents.filter((doc) => String(doc.id) !== String(documentId));
      notify("success", "文档已删除。");
      await loadDocuments(state.selectedKbId, { quiet: true });
    });
  }

  async function searchRetrieval() {
    ensureSelectedKb();
    if (!state.forms.retrievalQuery.trim()) {
      throw new Error("检索问题不能为空。");
    }
    state.retrieval.result = await api(`/api/v1/knowledge-bases/${state.selectedKbId}/retrieval/search`, {
      method: "POST",
      body: {
        query: state.forms.retrievalQuery.trim(),
        topK: state.forms.retrievalTopK,
      },
    });
  }

  async function askRag() {
    ensureSelectedKb();
    if (!state.forms.ragQuestion.trim()) {
      throw new Error("问题不能为空。");
    }
    state.rag.result = await api(`/api/v1/knowledge-bases/${state.selectedKbId}/rag/ask`, {
      method: "POST",
      body: {
        question: state.forms.ragQuestion.trim(),
        topK: state.forms.ragTopK,
      },
    });
  }

  async function api(path, options = {}) {
    const headers = new Headers();
    const multipart = Boolean(options.multipart);

    if (!multipart && options.body !== undefined) {
      headers.set("Content-Type", "application/json");
    }
    if (options.auth !== false) {
      ensureToken();
      headers.set("Authorization", `Bearer ${state.token}`);
    }

    const response = await fetch(apiUrl(path), {
      method: options.method || "GET",
      headers,
      body: multipart ? options.body : options.body === undefined ? undefined : JSON.stringify(options.body),
    });

    const text = await response.text();
    const headerRequestId = response.headers.get("X-Request-Id");
    let payload = null;
    if (text) {
      try {
        payload = JSON.parse(text);
      } catch (error) {
        throw new Error(`HTTP ${response.status} 返回非 JSON 响应。`);
      }
    }

    state.lastRequestId = payload?.requestId || headerRequestId || "-";

    // 业务请求拿到 401 时，先尝试用 refresh token 静默刷新一次。
    // skipRefresh 防止刷新失败后递归重试；一次失败就清理本地登录态。
    if (response.status === 401 && options.auth !== false && !options.skipRefresh && state.refreshToken) {
      const refreshed = await refreshSession();
      if (refreshed) {
        return api(path, { ...options, skipRefresh: true });
      }
    }

    if (response.status === 401 && options.auth !== false) {
      clearSession();
      navigate("/login", true);
      throw new Error("登录已失效，请重新登录。");
    }

    if (!response.ok || payload?.success === false) {
      const code = payload?.code ? `（${payload.code}）` : "";
      throw new Error(`${payload?.message || `HTTP ${response.status}`}${code}`);
    }

    if (payload && Object.prototype.hasOwnProperty.call(payload, "data")) {
      return payload.data;
    }
    return payload;
  }

  async function refreshSession() {
    if (!state.refreshToken) {
      return false;
    }

    try {
      // 后端刷新接口会轮换 refresh token：旧 token 被消费，新 token 写入本地。
      // 因此这里必须调用 applyAuthData 覆盖本地保存的两类 token。
      const data = await api("/api/v1/auth/refresh", {
        method: "POST",
        auth: false,
        skipRefresh: true,
        body: { refreshToken: state.refreshToken },
      });
      applyAuthData(data);
      return true;
    } catch (error) {
      return false;
    }
  }

  function applyAuthData(data) {
    // 登录和刷新共用同一份响应结构。这里集中处理 token 落地，
    // 避免登录、刷新两个入口各自维护 localStorage 造成状态漂移。
    state.token = data.accessToken || "";
    state.refreshToken = data.refreshToken || "";
    state.currentUser = data.user || state.currentUser || null;
    if (state.token) {
      localStorage.setItem(STORAGE.token, state.token);
    } else {
      localStorage.removeItem(STORAGE.token);
    }
    if (state.refreshToken) {
      localStorage.setItem(STORAGE.refreshToken, state.refreshToken);
    } else {
      localStorage.removeItem(STORAGE.refreshToken);
    }
  }

  function updatePolling() {
    stopPolling();
    const shouldPoll =
      state.token &&
      state.route.name === "documents" &&
      state.selectedKbId &&
      state.documents.some((doc) => ACTIVE_STATUSES.has(doc.indexStatus));

    if (!shouldPoll) {
      return;
    }

    state.pollTimer = window.setInterval(async () => {
      try {
        await loadDocuments(state.selectedKbId, { quiet: true });
        render();
        if (!state.documents.some((doc) => ACTIVE_STATUSES.has(doc.indexStatus))) {
          stopPolling();
        }
      } catch (error) {
        stopPolling();
        handleError(error);
      }
    }, 2500);
  }

  function stopPolling() {
    if (state.pollTimer) {
      window.clearInterval(state.pollTimer);
      state.pollTimer = null;
    }
  }

  function syncAuthForm() {
    state.forms.username = document.getElementById("usernameInput")?.value || "";
    state.forms.password = document.getElementById("passwordInput")?.value || "";
    state.forms.nickname = document.getElementById("nicknameInput")?.value || "";
    state.forms.email = document.getElementById("emailInput")?.value || "";
  }

  function syncKbForm() {
    state.forms.kbName = document.getElementById("kbNameInput")?.value || "";
    state.forms.kbDescription = document.getElementById("kbDescriptionInput")?.value || "";
  }

  function syncRetrievalForm() {
    state.forms.retrievalQuery = document.getElementById("retrievalQueryInput")?.value || "";
    state.forms.retrievalTopK = clampTopK(document.getElementById("retrievalTopKInput")?.value || 5);
  }

  function syncRagForm() {
    state.forms.ragQuestion = document.getElementById("ragQuestionInput")?.value || "";
    state.forms.ragTopK = clampTopK(document.getElementById("ragTopKInput")?.value || 5);
  }

  function syncApiBaseForm() {
    state.forms.apiBase = document.getElementById("apiBaseInput")?.value || "";
  }

  function saveApiBase() {
    state.apiBase = normalizeApiBase(state.forms.apiBase);
    state.forms.apiBase = state.apiBase;
    localStorage.setItem(STORAGE.apiBase, state.apiBase);
    notify("success", "API 地址已保存。");
    render();
  }

  function fillDemoUser() {
    const stamp = new Date().toISOString().replace(/[-:]/g, "").replace("T", "_").slice(0, 15);
    const username = `demo_${stamp}`;
    state.forms.username = username;
    state.forms.password = DEFAULT_PASSWORD;
    state.forms.nickname = "Demo User";
    state.forms.email = `${username}@example.com`;
    state.forms.kbName = `Enterprise KB ${stamp}`;
    state.forms.kbDescription = "企业知识库演示数据";
  }

  async function logout() {
    const refreshToken = state.refreshToken;
    if (refreshToken) {
      try {
        // 普通退出撤销 refresh token，阻止后续继续续期。
        // access token 不做黑名单，依靠较短 TTL 自然过期。
        await api("/api/v1/auth/logout", {
          method: "POST",
          auth: false,
          body: { refreshToken },
        });
      } catch (error) {
        // 即使 Redis 或后端暂时不可用，用户点击退出后也要清理本地 token。
        // 这样至少能保证当前浏览器不再继续携带旧凭证发起业务请求。
        notify("warning", `本地已退出，服务端撤销 refresh token 失败：${error.message}`);
      }
    }

    clearSession();
    notify("success", "已退出登录。");
    navigate("/login", true);
  }

  function clearSession() {
    // 清理本地所有会影响登录态和当前工作台上下文的状态。
    // 注意：这不是服务端撤销，真正撤销 refresh token 在 logout() 中完成。
    state.token = "";
    state.refreshToken = "";
    state.currentUser = null;
    state.kbs = [];
    state.kbsLoaded = false;
    state.selectedKbId = "";
    state.documents = [];
    state.docsLoadedForKbId = "";
    state.retrieval.result = null;
    state.rag.result = null;
    localStorage.removeItem(STORAGE.token);
    localStorage.removeItem(STORAGE.refreshToken);
    localStorage.removeItem("ekb.console.token");
    localStorage.removeItem(STORAGE.selectedKbId);
    stopPolling();
  }

  function notify(type, message) {
    const notice = {
      id: String(Date.now() + Math.random()),
      type,
      message,
    };
    state.notices = [notice, ...state.notices].slice(0, 4);
    showToast(message, type);
  }

  function dismissNotice(id) {
    state.notices = state.notices.filter((notice) => notice.id !== id);
  }

  function handleError(error) {
    notify("error", error?.message || String(error));
    render();
  }

  function showToast(message, type = "success") {
    const host = document.getElementById("toastHost");
    if (!host) {
      return;
    }
    const toast = document.createElement("div");
    toast.className = `toast ${type}`;
    toast.textContent = message;
    host.appendChild(toast);
    window.setTimeout(() => toast.remove(), 3600);
  }

  function getSelectedKb() {
    return state.kbs.find((kb) => String(kb.id) === String(state.selectedKbId)) || null;
  }

  function documentStats() {
    return state.documents.reduce(
      (acc, doc) => {
        acc.total += 1;
        if (doc.indexStatus === "INDEXED") {
          acc.indexed += 1;
        }
        if (ACTIVE_STATUSES.has(doc.indexStatus)) {
          acc.running += 1;
        }
        if (FAILURE_STATUSES.has(doc.indexStatus)) {
          acc.failed += 1;
        }
        return acc;
      },
      { total: 0, indexed: 0, running: 0, failed: 0 },
    );
  }

  function canRetry(doc) {
    return doc.indexStatus === "INDEX_FAILED" || doc.indexStatus === "PENDING_INDEX";
  }

  function ensureToken() {
    if (!state.token) {
      throw new Error("请先登录。");
    }
  }

  function ensureSelectedKb() {
    ensureToken();
    if (!state.selectedKbId) {
      throw new Error("请先选择知识库。");
    }
  }

  function apiUrl(path) {
    const base = normalizeApiBase(state.apiBase);
    return base ? `${base}${path}` : path;
  }

  function normalizeApiBase(value) {
    return (value || "").trim().replace(/\/+$/, "");
  }

  function navClass(name) {
    return state.route.name === name ? "active" : "";
  }

  function isBusy(key) {
    return state.busy.has(key);
  }

  function clampTopK(value) {
    const parsed = Number(value);
    if (!Number.isFinite(parsed)) {
      return 5;
    }
    return Math.min(20, Math.max(1, Math.floor(parsed)));
  }

  function formatDate(value) {
    if (!value) {
      return "-";
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return escapeHtml(value);
    }
    return date.toLocaleString();
  }

  function formatBytes(value) {
    const size = Number(value || 0);
    if (size < 1024) {
      return `${size} B`;
    }
    if (size < 1024 * 1024) {
      return `${(size / 1024).toFixed(1)} KB`;
    }
    return `${(size / 1024 / 1024).toFixed(1)} MB`;
  }

  function formatScore(value) {
    if (value == null || Number.isNaN(Number(value))) {
      return "-";
    }
    return Number(value).toFixed(4);
  }

  function escapeHtml(value) {
    return String(value ?? "")
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;")
      .replaceAll("'", "&#039;");
  }

  function escapeAttr(value) {
    return escapeHtml(value);
  }
})();
