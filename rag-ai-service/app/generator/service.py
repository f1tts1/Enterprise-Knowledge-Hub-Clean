from __future__ import annotations

import logging
import re
from time import perf_counter

import httpx

from app.config.settings import Settings, get_settings
from app.observability.request_context import (
    REQUEST_ID_HEADER,
    elapsed_ms,
    get_request_id,
    request_id_for_log,
)
from app.schemas.rag import RagContextChunk, RagGenerateRequest, RagGenerateResponse


SYSTEM_PROMPT = (
    "你是企业知识库问答助手。只能基于用户提供的知识库片段回答；"
    "不要编造片段中不存在的信息。回答要简洁、具体。"
    "如果知识库片段不足以回答，只能输出 __EKB_NO_ANSWER__，不得添加其它文字，"
    "也不得复述用户问题。可以回答时，每个关键结论必须使用 [片段 1]、"
    "[片段 2] 这样的标记指出依据。"
)

NO_ANSWER_SENTINEL = "__EKB_NO_ANSWER__"
NO_ANSWER_TEXT = "当前知识库内容不足，无法回答该问题。"
ANSWERED = "ANSWERED"
INSUFFICIENT_CONTEXT = "INSUFFICIENT_CONTEXT"
MODEL_REPORTED_INSUFFICIENT_CONTEXT = "MODEL_REPORTED_INSUFFICIENT_CONTEXT"
CITATION_PATTERN = re.compile(r"\[片段\s+(\d+)\]")

logger = logging.getLogger(__name__)


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
        total_started_at = perf_counter()
        try:
            self._ensure_provider_configured()
            model = self._settings.resolved_llm_model()
            base_url = self._settings.resolved_llm_base_url()
            api_key = self._settings.resolved_llm_api_key()
            chat_completions_url = _chat_completions_url(base_url)
        except Exception as exc:
            _log_failure("configuration", total_started_at, exc)
            raise

        payload = {
            "model": model,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": _build_user_prompt(request.question, request.contexts)},
            ],
            "temperature": self._settings.llm_temperature,
            "max_tokens": self._settings.llm_max_tokens,
        }

        llm_started_at = perf_counter()
        try:
            async with httpx.AsyncClient(timeout=self._settings.llm_timeout_seconds) as client:
                headers = {
                    "Authorization": f"Bearer {api_key}",
                    "Content-Type": "application/json",
                }
                request_id = get_request_id()
                if request_id:
                    headers[REQUEST_ID_HEADER] = request_id
                response = await client.post(
                    chat_completions_url,
                    headers=headers,
                    json=payload,
                )
                response.raise_for_status()
        except httpx.HTTPError as exc:
            _log_failure("llm_http", total_started_at, exc)
            raise LlmProviderError(f"LLM 调用失败: {exc}") from exc
        except Exception as exc:
            _log_failure("llm_http", total_started_at, exc)
            raise

        try:
            data = response.json()
            raw_answer = _extract_answer(data)
            answer, answer_status, cited_context_indexes, no_answer_reason = (
                _parse_generated_answer(raw_answer, len(request.contexts))
            )
            prompt_tokens, completion_tokens, total_tokens = _extract_usage(data)
        except Exception as exc:
            _log_failure("response_parse", total_started_at, exc)
            raise
        llm_latency_ms = elapsed_ms(llm_started_at)
        logger.info(
            "request_id=%s operation=rag_generate provider=%s model=%s context_count=%s "
            "answer_status=%s citation_count=%s llm_latency_ms=%s prompt_tokens=%s "
            "completion_tokens=%s total_tokens=%s",
            request_id_for_log(),
            self._settings.resolved_llm_provider(),
            model,
            len(request.contexts),
            answer_status,
            len(cited_context_indexes),
            llm_latency_ms,
            prompt_tokens,
            completion_tokens,
            total_tokens,
        )
        return RagGenerateResponse(
            answer=answer,
            answer_status=answer_status,
            cited_context_indexes=cited_context_indexes,
            no_answer_reason=no_answer_reason,
            llm_provider=self._settings.resolved_llm_provider(),
            llm_model=model or "",
            llm_latency_ms=llm_latency_ms,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            total_tokens=total_tokens,
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
        f"如果片段不足以回答，只输出 {NO_ANSWER_SENTINEL}，不要复述问题。"
        "如果可以回答，请在相关句子后标注对应片段编号，例如 [片段 1]；"
        "不得引用不存在的片段编号。"
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


def _parse_generated_answer(
        raw_answer: str,
        context_count: int,
) -> tuple[str, str, list[int], str | None]:
    """把模型文本收敛为稳定的 no-answer 与引用协议。

    citations 只能来自答案实际出现的 ``[片段 n]``，不能把全部检索命中伪装成
    引用。sentinel 必须是完整响应；与其它文字或引用混用都属于 provider 契约错误。
    """
    answer = raw_answer.strip()
    if answer == NO_ANSWER_SENTINEL:
        return (
            NO_ANSWER_TEXT,
            INSUFFICIENT_CONTEXT,
            [],
            MODEL_REPORTED_INSUFFICIENT_CONTEXT,
        )
    if NO_ANSWER_SENTINEL in answer:
        raise LlmProviderError("LLM no-answer sentinel 不能与其它内容混用")

    cited_context_indexes: list[int] = []
    seen_indexes: set[int] = set()
    for matched_index in CITATION_PATTERN.findall(answer):
        index = int(matched_index)
        if index < 1 or index > context_count:
            raise LlmProviderError("LLM 引用了不存在的知识库片段")
        if index not in seen_indexes:
            cited_context_indexes.append(index)
            seen_indexes.add(index)

    if not cited_context_indexes:
        raise LlmProviderError("LLM 回答缺少有效的知识库片段引用")
    return answer, ANSWERED, cited_context_indexes, None


def _extract_usage(data: dict) -> tuple[int | None, int | None, int | None]:
    """提取 OpenAI-compatible usage；提供方未返回时保留为未知而不是伪造 0。"""
    usage = data.get("usage")
    if not isinstance(usage, dict):
        return None, None, None

    prompt_tokens = _non_negative_int(usage.get("prompt_tokens"))
    completion_tokens = _non_negative_int(usage.get("completion_tokens"))
    total_tokens = _non_negative_int(usage.get("total_tokens"))
    if total_tokens is None and prompt_tokens is not None and completion_tokens is not None:
        total_tokens = prompt_tokens + completion_tokens
    return prompt_tokens, completion_tokens, total_tokens


def _non_negative_int(value: object) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        return None
    return value


def _log_failure(failure_stage: str, total_started_at: float, exc: Exception) -> None:
    """不写入 prompt/question/context/answer/API key 和异常 message。"""
    logger.error(
        "request_id=%s failure_stage=%s total_latency_ms=%s error_type=%s",
        request_id_for_log(),
        failure_stage,
        elapsed_ms(total_started_at),
        type(exc).__name__,
    )


rag_generation_service = RagGenerationService()
