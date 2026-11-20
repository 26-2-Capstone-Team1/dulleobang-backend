"""Fetch an IKEA release from GCS and import it through the backend's local admin mode."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path

ARTICLE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,31}")
ITEM_FILES = ("meta.json", "model.glb", "collision.glb", "photo.jpg")


def articles(index: object) -> list[str]:
    if isinstance(index, dict):
        index = index.get("items", index.get("articles"))
    if not isinstance(index, list):
        raise ValueError("index.json must contain a list of articles (items or articles)")
    result: list[str] = []
    seen: set[str] = set()
    for entry in index:
        article = entry.get("article") if isinstance(entry, dict) else entry
        if not isinstance(article, str) or not ARTICLE.fullmatch(article):
            raise ValueError("index.json contains an invalid article identifier")
        if article in seen:
            raise ValueError(f"index.json contains duplicate article {article}")
        result.append(article)
        seen.add(article)
    return result


def download_catalog(bucket, prefix: str, directory: Path) -> None:
    prefix = prefix.strip("/")
    base = f"{prefix}/" if prefix else ""
    index = json.loads(bucket.blob(f"{base}index.json").download_as_bytes(timeout=60))
    for article in articles(index):
        target = directory / article
        target.mkdir()
        for filename in ITEM_FILES:
            blob = bucket.blob(f"{base}{article}/{filename}")
            blob.download_to_filename(str(target / filename), timeout=120)


def backend_jar(value: Path | None) -> Path:
    if value is None:
        libs = Path(__file__).resolve().parent.parent / "build" / "libs"
        matches = [p for p in libs.glob("dulleobang-backend*.jar") if not p.name.endswith("-plain.jar")]
        if len(matches) != 1:
            raise ValueError("Build a bootJar first or specify --jar / DULLEOBANG_JAR")
        value = matches[0]
    if not value.is_file():
        raise ValueError(f"Backend JAR not found: {value}")
    return value.resolve()


def main() -> None:
    parser = argparse.ArgumentParser(description="둘러방 백엔드 관리 도구")
    commands = parser.add_subparsers(dest="command", required=True)
    catalog = commands.add_parser("import-catalog", help="Import IKEA items from a GCS release")
    catalog.add_argument("--bucket", required=True)
    catalog.add_argument("--prefix", required=True)
    catalog.add_argument("--jar", type=Path, default=os.environ.get("DULLEOBANG_JAR"))
    args = parser.parse_args()

    try:
        jar = backend_jar(args.jar)
    except ValueError as exc:
        parser.error(str(exc))
    try:
        from google.api_core.exceptions import GoogleAPIError
        from google.auth.exceptions import GoogleAuthError
        from google.cloud import storage
    except ImportError:
        parser.error("Install app/requirements.txt in the catalog CLI environment")

    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / "java") if java_home else "java"
    try:
        with tempfile.TemporaryDirectory(prefix="dulleobang-catalog-") as temp:
            directory = Path(temp)
            download_catalog(storage.Client().bucket(args.bucket), args.prefix, directory)
            subprocess.run(
                [
                    java, "-jar", str(jar),
                    "--dulleobang.admin.command=import-catalog",
                    f"--dulleobang.admin.catalog-directory={directory}",
                    "--dulleobang.scheduler.enabled=false",
                ],
                check=True,
            )
    except subprocess.CalledProcessError as exc:
        raise SystemExit(exc.returncode) from None
    except (GoogleAuthError, GoogleAPIError, OSError, ValueError) as exc:
        parser.exit(1, f"import-catalog: {exc}\n")


if __name__ == "__main__":
    main()
