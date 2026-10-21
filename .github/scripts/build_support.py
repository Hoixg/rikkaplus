"""Build metadata, package checks and Chinese release notes for branch 1.0."""
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

BASE_COMMIT = "76e25d952efa5de3289d916787c7f129fb1b1f1f"


def git(*args):
    return subprocess.check_output(["git", *args], text=True, encoding="utf-8").strip()


def prepare():
    source = Path("app/build.gradle.kts").read_text(encoding="utf-8")
    def value(key, pattern):
        match = re.search(r"^\s*" + key + r"\s*=\s*" + pattern, source, re.M)
        if not match:
            raise ValueError("Missing build field: " + key)
        return match.group(1)
    variant = os.environ["BUILD_VARIANT"]
    if variant not in ("debug", "release"):
        raise ValueError("Unsupported build variant")
    info = {
        "version": value("versionName", r'"([A-Za-z0-9.+-]+)"'),
        "version_code": int(value("versionCode", r"([0-9]+)")),
        "application_id": value("applicationId", r'"([A-Za-z0-9_.]+)"'),
        "variant": variant,
        "distribution": "github",
        "commit": git("rev-parse", "HEAD"),
    }
    if variant == "debug":
        info["application_id"] += ".debug"
    config = json.loads(os.environ["GOOGLE_SERVICES_JSON"])
    clients = config.get("client", [])
    package = info["application_id"]
    if not any(c["client_info"]["android_client_info"]["package_name"] == package for c in clients):
        # Reuse the existing Firebase project for compilation of this fork.
        # A dedicated registered Android client can be supplied via GOOGLE_SERVICES_JSON_1_0.
        candidates = [c for c in clients if c["client_info"]["android_client_info"]["package_name"].endswith(".debug") == (variant == "debug")]
        if not candidates:
            raise ValueError("Google Services has no compatible Android client")
        client = copy.deepcopy(candidates[0])
        client["client_info"]["android_client_info"]["package_name"] = package
        config["client"].append(client)
        print("Google Services 构建配置已匹配当前包名；Firebase 服务可使用分支专属配置。")
    Path("app/google-services.json").write_text(json.dumps(config), encoding="utf-8")
    Path("build-info.json").write_text(json.dumps(info, indent=2), encoding="utf-8")
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as stream:
            stream.write("version=" + info["version"] + "\ncommit=" + info["commit"][:8] + "\n")
    print("Building", info["version"], "(" + str(info["version_code"]) + ")", variant, info["application_id"])


def verify():
    info = json.loads(Path("build-info.json").read_text())
    directory = Path("app/build/outputs/apk/github") / info["variant"]
    metadata = json.loads((directory / "output-metadata.json").read_text())
    if metadata["applicationId"] != info["application_id"]:
        raise ValueError("APK application ID mismatch")
    elements = metadata["elements"]
    if len(elements) != 1 or any(e["versionName"] != info["version"] or e["versionCode"] != info["version_code"] for e in elements):
        raise ValueError("APK version or output count mismatch")
    names = {"app-github-" + info["variant"] + ".apk"}
    if {e["outputFile"] for e in elements} != names or {p.name for p in directory.glob("*.apk")} != names:
        raise ValueError("Unexpected APK outputs")
    sums = []
    for name in sorted(names):
        path = directory / name
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError("Missing APK: " + name)
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        sums.append(digest + "  " + name)
    Path("SHA256SUMS.txt").write_text("\n".join(sums) + "\n", encoding="utf-8")


def notes():
    info = json.loads(Path("build-info.json").read_text())
    release = os.environ.get("GITHUB_REF_NAME", "").startswith("1.0-v")
    tags = git("tag", "--list", "1.0-v*", "--merged", "HEAD", "--sort=-version:refname").splitlines()
    previous = next((tag for tag in tags if tag != os.environ.get("GITHUB_REF_NAME")), BASE_COMMIT)
    if not release:
        result = subprocess.run(["git", "rev-parse", "--verify", "refs/tags/1.0-nightly^{commit}"], capture_output=True, text=True)
        if result.returncode == 0 and subprocess.run(["git", "merge-base", "--is-ancestor", result.stdout.strip(), "HEAD"], capture_output=True).returncode == 0:
            previous = result.stdout.strip()
    lines = [
        "## RikkaPlus 1.0 · " + info["version"],
        "",
        "分支：`1.0` · 版本代码：`" + str(info["version_code"]) + "` · 源码：`" + info["commit"][:8] + "`",
        "",
        "包名：`" + info["application_id"] + "`。提供包含 ARM64 和 x86_64 的通用安装包。",
        "",
    ]
    if not release:
        lines += ["这是重构分支的开发构建，功能正在逐步整理。", ""]
    curated = Path("docs/releases") / (info["version"] + ".md")
    if release and curated.is_file():
        lines += [curated.read_text(encoding="utf-8").strip(), ""]
    else:
        groups = {"新增": [], "修复": [], "优化": [], "维护": []}
        for entry in git("log", "--no-merges", "--format=%s%n%b%x00", previous + "..HEAD").split("\x00"):
            entry = entry.strip()
            if not entry:
                continue
            subject, _, body = entry.partition("\n")
            category = "新增" if subject.startswith("feat") else "修复" if subject.startswith("fix") else "优化" if subject.startswith(("refactor", "perf")) else "维护"
            summary = re.sub(r"^[a-z]+(?:\([^)]*\))?!?:\s*", "", subject)
            groups[category].append("- " + summary + ("\n" + "\n".join("  " + line for line in body.strip().splitlines()) if body.strip() else ""))
        for category, entries in groups.items():
            if entries:
                lines += ["### " + category, "", "\n".join(entries), ""]
        if not any(groups.values()):
            lines += ["### 更新内容", "", "- 重新构建当前提交，源码未变化。", ""]
    repository = os.environ.get("GITHUB_REPOSITORY", "Hoixg/rikkaplus")
    lines += [
        "[查看全部提交](https://github.com/" + repository + "/compare/" + previous + "..." + info["commit"] + ")",
        "", "### SHA-256", "", "```text", Path("SHA256SUMS.txt").read_text().strip(), "```", "",
    ]
    Path("release-notes.md").write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    {"prepare": prepare, "verify": verify, "notes": notes}[sys.argv[1]]()
