from __future__ import annotations

import httpx

from app.config.settings import Settings, get_settings
from app.schemas.rag import RagContextChunk, RagGenerateRequest, RagGenerateResponse


SYSTEM_PROMPT = (
    "你是企业知识库问答助手。只能基于用户提供的知识库片段回答；"
    "不要编造片段中不存在的信息。回答要简洁、具体。"
    "引用依据时必须使用 [片段 1]、[片段 2] 这样的标记。"
)


class LlmProviderError(RuntimeError):
    """LLM provider 配置缺失或调用失败。"""


class RagGenerationService:
    """基于已检索 chunk 生成最终答案。

    Java 负责鉴权和检索编排，Python 只处理 LLM 调用细节。这样不会让 Python
    接触登录态或自行访问任意知识库。
    """

    def __init__(self, settings: Settings | None = None) -> None:
        self._settings = settings or get_settings()

    async def generate(self, request: RagGenerateRequest) -> RagGenerateResponse:
        self._ensure_provider_configured()
        model = self._settings.resolved_llm_model()
        base_url = self._settings.resolved_llm_base_url()
        api_key = self._settings.resolved_llm_api_key()
        payload = {
            "model": model,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": _build_user_prompt(request.question, request.contexts)},
            ],
            "temperature": self._settings.llm_temperature,
            "max_tokens": self._settings.llm_max_tokens,
        }

        try:
            async with httpx.AsyncClient(timeout=self._settings.llm_timeout_seconds) as client:
                response = await client.post(
                    _chat_completions_url(base_url),
                    headers={
                        "Authorization": f"Bearer {api_key}",
                        "Content-Type": "application/json",
                    },
                    json=payload,
                )
                response.raise_for_status()
                data = response.json()
        except httpx.HTTPError as exc:
            raise LlmProviderError(f"LLM 调用失败: {exc}") from exc

        answer = _extract_answer(data)
        return RagGenerateResponse(
            answer=answer,
            llm_provider=self._settings.resolved_llm_provider(),
            llm_model=model or "",
        )

    def _ensure_provider_configured(self) -> None:
        if self._settings.resolved_llm_provider() != "openai-compatible":
            raise LlmProviderError("LLM_PROVIDER must be set to openai-compatible or DEEPSEEK_API_KEY must be set")
        if not self._settings.resolved_llm_base_url():
            raise LlmProviderError("LLM_BASE_URL is required")
        if not self._settings.resolved_llm_api_key():
            raise LlmProviderError("LLM_API_KEY or DEEPSEEK_API_KEY is required")
        if not self._settings.resolved_llm_model():
            raise LlmProviderError("LLM_MODEL is required")


def _build_user_prompt(question: str, contexts: list[RagContextChunk]) -> str:
    context_text = "\n\n".join(
        _format_context(index + 1, context)
        for index, context in enumerate(contexts)
    )
    return (
        "请基于以下知识库片段回答问题。\n\n"
        f"{context_text}\n\n"
        f"问题：{question}\n\n"
        "如果片段不足以回答，请直接说明当前知识库内容不足。"
        "如果可以回答，请在相关句子后标注对应片段编号，例如 [片段 1]。"
    )


def _format_context(index: int, context: RagContextChunk) -> str:
    location_parts = []
    if context.file_name:
        location_parts.append(f"file={context.file_name}")
    if context.page_no is not None:
        location_parts.append(f"page={context.page_no}")
    if context.chunk_index is not None:
        location_parts.append(f"chunk={context.chunk_index}")
    location = ", ".join(location_parts) if location_parts else "unknown source"
    return f"[片段 {index}] {location}\n{context.text}"


def _chat_completions_url(base_url: str | None) -> str:
    if not base_url:
        raise LlmProviderError("LLM_BASE_URL is required")
    return base_url.rstrip("/") + "/chat/completions"


def _extract_answer(data: dict) -> str:
    try:
        answer = data["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as exc:
        raise LlmProviderError("LLM 响应缺少 choices[0].message.content") from exc
    if not isinstance(answer, str) or not answer.strip():
        raise LlmProviderError("LLM 响应内容为空")
    return answer.strip()


rag_generation_service = RagGenerationService()
