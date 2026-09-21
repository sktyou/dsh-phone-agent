#!/usr/bin/env node
/**
 * Push to GitHub through the REST API instead of the git protocol.
 *
 * `git push` talks to github.com, which is unreachable on some networks while
 * api.github.com answers normally — the two are different hosts, and a proxy that
 * covers one does not necessarily cover the other. GitHub's Git Data API exposes the
 * same objects (blobs, trees, commits, refs) over api.github.com, so a push can be
 * assembled from four HTTP calls without ever resolving github.com.
 *
 *   node tools/push-via-api.mjs --token <PAT> [--repo sktyou/dsh-phone-agent] [--branch main]
 *
 * This is not a general git client. It commits the current working tree as one
 * commit on top of the branch tip — enough to publish a change, not to rewrite
 * history or merge.
 */

import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.join(HERE, "..");

function argOf(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const TOKEN = argOf("token", process.env.GH_TOKEN || "");
const REPO = argOf("repo", "sktyou/dsh-phone-agent");
const BRANCH = argOf("branch", "main");
const MESSAGE = argOf("message", "");

if (!TOKEN) {
  console.error("需要 --token <PAT>(或环境变量 GH_TOKEN)");
  process.exit(1);
}

async function api(method, endpoint, body) {
  const res = await fetch(`https://api.github.com${endpoint}`, {
    method,
    headers: {
      Authorization: `Bearer ${TOKEN}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "dsh-phone-agent",
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  if (!res.ok) {
    throw new Error(`${method} ${endpoint} → ${res.status} ${json?.message ?? text.slice(0, 200)}`);
  }
  return json;
}

/**
 * The files to publish, taken from git's own view of the tree.
 *
 * Using `git ls-files` rather than walking the directory means .gitignore is
 * honoured exactly as `git add` would — no separate ignore logic to keep in sync.
 */
function listFiles() {
  const out = execFileSync("git", ["ls-files", "--cached", "--others", "--exclude-standard"], {
    cwd: ROOT,
    encoding: "utf8",
  });
  return out.split("\n").map((s) => s.trim()).filter(Boolean);
}

const files = listFiles();
console.log(`${files.length} 个文件待发布`);

// 1. blobs — one call per file. Only text and small binaries; the APK is ignored
//    by .gitignore and goes to a Release instead.
const blobs = [];
for (const rel of files) {
  const full = path.join(ROOT, rel);
  const stat = fs.statSync(full);
  if (stat.size > 8 * 1024 * 1024) {
    console.log(`  跳过(>8MB): ${rel}`);
    continue;
  }
  const content = fs.readFileSync(full);
  const isText = !content.includes(0);
  const blob = await api("POST", `/repos/${REPO}/git/blobs`, {
    content: content.toString("base64"),
    encoding: "base64",
  });
  blobs.push({ path: rel.replace(/\\/g, "/"), sha: blob.sha, mode: "100644", type: "blob" });
  process.stdout.write(`  ${rel}  ${isText ? "" : "(binary) "}${stat.size}B\n`);
}

// 2. tree — rooted at the current branch tip so unchanged files stay in place
const ref = await api("GET", `/repos/${REPO}/git/ref/heads/${BRANCH}`);
const baseCommitSha = ref.object.sha;
const baseCommit = await api("GET", `/repos/${REPO}/git/commits/${baseCommitSha}`);

const tree = await api("POST", `/repos/${REPO}/git/trees`, {
  base_tree: baseCommit.tree.sha,
  tree: blobs,
});

// 3. commit
const message = MESSAGE || execFileSync("git", ["log", "-1", "--pretty=%B"], {
  cwd: ROOT, encoding: "utf8",
}).trim() || "Update from push-via-api";

const commit = await api("POST", `/repos/${REPO}/git/commits`, {
  message,
  tree: tree.sha,
  parents: [baseCommitSha],
});
console.log(`\n新 commit: ${commit.sha}`);

// 4. move the branch
await api("PATCH", `/repos/${REPO}/git/refs/heads/${BRANCH}`, { sha: commit.sha, force: false });
console.log(`已更新 ${BRANCH} → ${commit.sha}`);
console.log(`https://github.com/${REPO}/commit/${commit.sha}`);
