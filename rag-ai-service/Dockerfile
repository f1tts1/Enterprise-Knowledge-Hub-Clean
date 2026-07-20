FROM python:3.11-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /opt/ekb

COPY rag-ai-service/requirements.txt ./requirements.txt
RUN python -m pip install --no-cache-dir -r requirements.txt

COPY rag-ai-service/app ./app
COPY scripts/download_embedding_model.py ./scripts/download_embedding_model.py

# 空目录会随首次挂载初始化命名卷，并保留非 root 用户的写权限。
RUN groupadd --system ekb \
    && useradd --system --gid ekb --create-home --home-dir /home/ekb ekb \
    && mkdir -p /models/bge-small-zh-v1.5 \
    && chown -R ekb:ekb /opt/ekb /models /home/ekb

USER ekb
EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]

