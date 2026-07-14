from __future__ import annotations

import argparse
from pathlib import Path


DEFAULT_REPO_ID = "BAAI/bge-small-zh-v1.5"
DEFAULT_LOCAL_DIR = Path(__file__).resolve().parents[1] / ".models" / "bge-small-zh-v1.5"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="从 HuggingFace 下载 V1 使用的本地 embedding 模型。"
    )
    parser.add_argument(
        "--repo-id",
        default=DEFAULT_REPO_ID,
        help=f"HuggingFace 模型仓库，默认 {DEFAULT_REPO_ID}",
    )
    parser.add_argument(
        "--local-dir",
        default=str(DEFAULT_LOCAL_DIR),
        help=f"模型保存目录，默认 {DEFAULT_LOCAL_DIR}",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    local_dir = Path(args.local_dir).expanduser().resolve()
    local_dir.mkdir(parents=True, exist_ok=True)

    try:
        from huggingface_hub import snapshot_download
    except ImportError as exc:
        raise RuntimeError(
            "缺少 huggingface_hub 依赖。请先执行："
            "conda run -n rag-ai-service python -m pip install -r rag-ai-service/requirements.txt"
        ) from exc

    # snapshot_download 会下载模型配置、tokenizer 和权重文件。
    # local_dir 放在项目根目录 .models 下，并已加入 .gitignore，避免误提交模型大文件。
    downloaded_path = snapshot_download(
        repo_id=args.repo_id,
        local_dir=str(local_dir),
        local_dir_use_symlinks=False,
    )

    print(f"模型仓库: {args.repo_id}")
    print(f"保存目录: {downloaded_path}")
    print("下载完成。FastAPI 默认会从该目录加载本地 embedding 模型。")


if __name__ == "__main__":
    main()
