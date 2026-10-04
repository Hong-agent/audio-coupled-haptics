#!/usr/bin/env python3
"""在 git 传输（github.com:443）不通、但 api.github.com 可达的网络里，用 GitHub
REST API 把本地 HEAD 的内容推送到远程分支。

原理：Git Data API —— 逐个文件建 blob → 建 tree（基于远程 HEAD 的 tree）→
建 commit → 更新 ref。

用法：
    GH_TOKEN=ghp_xxx python3 tools/push_via_api.py [--branch main] [--repo owner/name]

令牌只从环境变量读取，不会写进任何文件。
"""

import argparse
import base64
import concurrent.futures
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"


def api(token, method, path, payload=None, timeout=120):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    request = urllib.request.Request(
        API + path,
        data=data,
        method=method,
        headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "User-Agent": "audio-coupled-haptics-push",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8")
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")
        raise SystemExit(f"GitHub API {method} {path} 失败 {error.code}: {detail[:400]}")


def git(args, cwd):
    return subprocess.check_output(["git"] + args, cwd=cwd).decode("utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--branch", default="main")
    parser.add_argument("--repo", default="Hong-agent/audio-coupled-haptics")
    parser.add_argument("--dir", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    parser.add_argument("--message-file", default=None)
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()

    token = os.environ.get("GH_TOKEN")
    if not token:
        raise SystemExit("请通过环境变量提供 GH_TOKEN")

    repo_dir = args.dir
    message = (open(args.message_file, encoding="utf-8").read()
               if args.message_file else git(["log", "-1", "--pretty=%B"], repo_dir))

    # 用 ls-tree 而非 --name-only：需要保留可执行位（100755），否则远程脚本会丢 +x
    files = []
    for line in git(["ls-tree", "-r", "HEAD"], repo_dir).splitlines():
        meta, _, path = line.partition("\t")
        mode, obj_type, _sha = meta.split()
        if obj_type != "blob":
            continue
        files.append((path, mode))
    print(f"HEAD 共 {len(files)} 个文件，准备上传 blob…")

    ref = api(token, "GET", f"/repos/{args.repo}/git/ref/heads/{args.branch}")
    parent = ref["object"]["sha"]
    base_tree = api(token, "GET", f"/repos/{args.repo}/git/commits/{parent}")["tree"]["sha"]
    print(f"远程 {args.branch} 当前 commit：{parent[:10]}")

    def make_blob(item):
        path, mode = item
        with open(os.path.join(repo_dir, path), "rb") as handle:
            content = base64.b64encode(handle.read()).decode("ascii")
        sha = api(token, "POST", f"/repos/{args.repo}/git/blobs",
                  {"content": content, "encoding": "base64"})["sha"]
        return {"path": path, "mode": mode, "type": "blob", "sha": sha}

    entries = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        for index, entry in enumerate(pool.map(make_blob, files), 1):
            entries.append(entry)
            if index % 25 == 0 or index == len(files):
                print(f"  blob {index}/{len(files)}")

    tree = api(token, "POST", f"/repos/{args.repo}/git/trees",
               {"base_tree": base_tree, "tree": entries})
    commit = api(token, "POST", f"/repos/{args.repo}/git/commits",
                 {"message": message.rstrip() + "\n", "tree": tree["sha"], "parents": [parent]})
    api(token, "PATCH", f"/repos/{args.repo}/git/refs/heads/{args.branch}",
        {"sha": commit["sha"], "force": False})
    print(f"推送完成：{args.branch} -> {commit['sha']}")
    print(f"https://github.com/{args.repo}/commit/{commit['sha']}")


if __name__ == "__main__":
    sys.exit(main())
