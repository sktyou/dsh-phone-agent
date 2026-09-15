/**
 * Generate the desktop help page from the app's own help content.
 *
 * `HelpContent.kt` is the single source of truth: the phone screen renders it, and
 * this script turns the same data into a wide two-column page for browsing on a
 * computer. Keeping one source is the whole point — a second hand-maintained copy
 * is guaranteed to drift, and a stale reference is worse than none because it is
 * trusted.
 *
 *   node tools/gen-help-html.mjs
 *   → docs/help.html
 */

import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..");
const source = join(
  root,
  "android/app/src/main/java/com/dsh/phoneagent/HelpContent.kt",
);
const output = join(root, "docs/help.html");

const text = readFileSync(source, "utf8");

/** Pull a balanced `(...)` argument list starting at `open`. */
function balanced(input, open) {
  let depth = 0;
  let inString = false;
  for (let i = open; i < input.length; i += 1) {
    const ch = input[i];
    if (inString) {
      if (ch === "\\") i += 1;
      else if (ch === '"') inString = false;
      continue;
    }
    if (ch === '"') inString = true;
    else if (ch === "(") depth += 1;
    else if (ch === ")") {
      depth -= 1;
      if (depth === 0) return input.slice(open + 1, i);
    }
  }
  throw new Error("unbalanced parentheses");
}

/** Read `key = "..."` (possibly concatenated with +) or `key = listOf(...)`. */
function field(body, key) {
  const marker = `${key} = `;
  const at = body.indexOf(marker);
  if (at < 0) return null;
  let i = at + marker.length;

  if (body.startsWith("listOf(", i)) {
    const args = balanced(body, i + "listOf".length);
    const pairs = [];
    const re = /"((?:[^"\\]|\\.)*)"\s*to\s*"((?:[^"\\]|\\.)*)"/g;
    let m;
    while ((m = re.exec(args)) !== null) pairs.push([unescape(m[1]), unescape(m[2])]);
    return { pairs };
  }

  let out = "";
  while (i < body.length) {
    while (i < body.length && /\s/.test(body[i])) i += 1;
    if (body[i] !== '"') break;
    let j = i + 1;
    let chunk = "";
    while (j < body.length && body[j] !== '"') {
      if (body[j] === "\\") {
        chunk += body[j] + body[j + 1];
        j += 2;
      } else {
        chunk += body[j];
        j += 1;
      }
    }
    out += unescape(chunk);
    i = j + 1;
    while (i < body.length && /\s/.test(body[i])) i += 1;
    if (body[i] === "+") i += 1;
    else break;
  }
  return { string: out };
}

function unescape(value) {
  return value
    .replace(/\\n/g, "\n")
    .replace(/\\t/g, "\t")
    .replace(/\\"/g, '"')
    .replace(/\\\\/g, "\\");
}

// ---- parse categories ----
const categories = [];
const catRe = /Category\(\s*\n\s*title = "((?:[^"\\]|\\.)*)"/g;
let catMatch;
const catStarts = [];
while ((catMatch = catRe.exec(text)) !== null) {
  catStarts.push({ title: unescape(catMatch[1]), at: catMatch.index });
}

for (let c = 0; c < catStarts.length; c += 1) {
  const start = catStarts[c].at;
  const end = c + 1 < catStarts.length ? catStarts[c + 1].at : text.length;
  const block = text.slice(start, end);

  const blurb = field(block, "blurb")?.string ?? "";
  const entries = [];
  const entryRe = /Entry\(\s*\n\s*name = "/g;
  let em;
  while ((em = entryRe.exec(block)) !== null) {
    const body = balanced(block, em.index + "Entry".length);
    entries.push({
      name: field(body, "name")?.string ?? "",
      summary: field(body, "summary")?.string ?? "",
      detail: field(body, "detail")?.string ?? "",
      example: field(body, "example")?.string ?? null,
      params: field(body, "params")?.pairs ?? [],
    });
  }
  categories.push({ title: catStarts[c].title, blurb, entries });
}

const total = categories.reduce((n, c) => n + c.entries.length, 0);

// ---- render ----
const escapeHtml = (s) =>
  s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

const navItems = categories
  .map((c) => {
    const items = c.entries
      .map(
        (e) =>
          `<a class="nav-item" href="#${slug(c.title)}-${slug(e.name)}" data-key="${slug(c.title)}-${slug(e.name)}">` +
          `<span class="nav-name">${escapeHtml(e.name)}</span>` +
          `<span class="nav-sum">${escapeHtml(e.summary)}</span></a>`,
      )
      .join("\n");
    return `<div class="nav-group" data-group="${slug(c.title)}"><div class="nav-title">${escapeHtml(c.title)}</div>${items}</div>`;
  })
  .join("\n");

/** Searchable haystack per entry, so filtering can match body text too. */
const searchIndex = JSON.stringify(
  categories.flatMap((c) =>
    c.entries.map((e) => ({
      key: `${slug(c.title)}-${slug(e.name)}`,
      hay: `${e.name} ${e.summary} ${e.detail} ${(e.params ?? [])
        .map(([k, v]) => `${k} ${v}`)
        .join(" ")}`.toLowerCase(),
    })),
  ),
);

const detailSections = categories
  .map((c) => {
    const blocks = c.entries
      .map((e) => {
        const params = e.params.length
          ? `<div class="sub">参数</div><table class="params">${e.params
              .map(
                ([k, v]) =>
                  `<tr><td class="pk">${escapeHtml(k)}</td><td>${escapeHtml(v)}</td></tr>`,
              )
              .join("")}</table>`
          : "";
        const example = e.example
          ? `<div class="sub">示例</div><pre>${escapeHtml(e.example)}</pre>`
          : "";
        return (
          `<section class="entry" id="${slug(c.title)}-${slug(e.name)}" data-key="${slug(c.title)}-${slug(e.name)}">` +
          `<h2>${escapeHtml(e.name)}<span class="sum">${escapeHtml(e.summary)}</span></h2>` +
          `<div class="detail">${escapeHtml(e.detail).replace(/\n/g, "<br>")}</div>` +
          params +
          example +
          `</section>`
        );
      })
      .join("\n");
    return `<h1 class="cat" id="${slug(c.title)}">${escapeHtml(c.title)}<span class="blurb">${escapeHtml(c.blurb)}</span></h1>${blocks}`;
  })
  .join("\n");

function slug(value) {
  return value
    .toLowerCase()
    .replace(/[^a-z0-9\u4e00-\u9fa5]+/g, "-")
    .replace(/^-|-$/g, "");
}

const html = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>DSH Phone Agent — 功能帮助</title>
<style>
  :root {
    --bg:#eef1f6; --card:#fff; --ink:#0f172a; --muted:#667085; --line:#e4e7ec;
    --brand:#2563eb; --brand-soft:#eaf0ff; --code:#0f172a; --code-ink:#f8fafc;
  }
  *{box-sizing:border-box;margin:0;padding:0}
  body{display:flex;height:100vh;background:var(--bg);color:var(--ink);
       font-family:"Microsoft YaHei UI","PingFang SC",system-ui,sans-serif;
       font-size:14px;line-height:1.6;overflow:hidden}
  nav{width:330px;flex:0 0 330px;background:var(--card);border-right:1px solid var(--line);
      overflow-y:auto;padding:18px 12px 40px}
  .brand{padding:4px 12px 14px;border-bottom:1px solid var(--line);margin-bottom:10px}
  .brand h1{font-size:17px;font-weight:700}
  .brand p{font-size:12px;color:var(--muted);margin-top:3px}
  .search{width:100%;padding:9px 12px;border:1px solid var(--line);border-radius:10px;
          font-size:12.5px;font-family:inherit;outline:none;background:var(--bg);
          margin-bottom:10px}
  .search:focus{border-color:var(--brand);background:#fff;
                box-shadow:0 0 0 3px var(--brand-soft)}
  .nav-group{margin-bottom:14px}
  .nav-group.hidden{display:none}
  .nav-title{font-size:11px;font-weight:700;color:var(--muted);letter-spacing:.08em;
             padding:8px 12px 5px}
  .nav-item{display:block;padding:7px 12px;border-radius:9px;text-decoration:none;
            color:var(--ink);cursor:pointer}
  .nav-item:hover{background:var(--bg)}
  .nav-item.active{background:var(--brand-soft)}
  .nav-item.hidden{display:none}
  .nav-name{font-size:13px;font-weight:600;font-family:Consolas,monospace}
  .nav-sum{display:block;font-size:11px;color:var(--muted);margin-top:1px}
  .empty{font-size:12.5px;color:var(--muted);padding:14px 12px}
  main{flex:1;overflow-y:auto;padding:28px 44px 80px}
  .toolbar{display:flex;align-items:center;gap:12px;margin-bottom:20px}
  .toolbar button{padding:7px 15px;border:1px solid var(--line);background:var(--card);
                  border-radius:9px;font-size:12.5px;font-family:inherit;cursor:pointer}
  .toolbar button:hover{border-color:var(--brand);color:var(--brand)}
  .toolbar .picked{font-size:12.5px;color:var(--muted)}
  .entry.hidden{display:none}
  .cat.hidden{display:none}
  .cat{font-size:20px;margin:36px 0 14px;padding-bottom:8px;border-bottom:2px solid var(--line)}
  .cat:first-child{margin-top:0}
  .cat .blurb{display:block;font-size:12px;font-weight:400;color:var(--muted);margin-top:4px}
  .entry{background:var(--card);border-radius:14px;padding:20px 22px;margin-bottom:14px;
         border:1px solid rgba(16,24,40,.05);box-shadow:0 2px 10px rgba(16,24,40,.04)}
  .entry:target{border-color:var(--brand);box-shadow:0 0 0 3px var(--brand-soft)}
  .entry h2{font-size:16px;font-family:Consolas,monospace;display:flex;
            align-items:baseline;gap:12px;flex-wrap:wrap}
  .entry h2 .sum{font-size:12px;font-weight:400;color:var(--muted);
                 font-family:inherit}
  .detail{margin-top:10px;color:#344054;font-size:13px}
  .sub{font-size:11px;font-weight:700;color:var(--muted);letter-spacing:.06em;
       margin:16px 0 7px}
  pre{background:var(--code);color:var(--code-ink);border-radius:10px;padding:14px 16px;
      font-family:Consolas,monospace;font-size:12px;line-height:1.55;overflow-x:auto}
  table.params{width:100%;border-collapse:collapse;font-size:12.5px}
  table.params td{padding:5px 0;vertical-align:top}
  td.pk{width:210px;color:var(--brand);font-family:Consolas,monospace;font-weight:600;
        padding-right:14px}
  .hint{font-size:11.5px;color:var(--muted);padding:10px 12px;background:var(--brand-soft);
        border-radius:9px;margin-bottom:18px}
</style>
</head>
<body>
<nav>
  <div class="brand">
    <h1>DSH Phone Agent</h1>
    <p>${total} 项能力 · 本页由 HelpContent.kt 自动生成</p>
  </div>
  <input id="q" class="search" type="search" placeholder="搜索功能、参数或说明…" autocomplete="off">
  ${navItems}
  <div id="noHit" class="empty" style="display:none">没有匹配的功能</div>
</nav>
<main>
  <div class="toolbar">
    <button id="showAll">显示全部</button>
    <span id="picked" class="picked">正在显示:全部 ${total} 项</span>
  </div>
  ${detailSections}
</main>
<script>
  const INDEX = ${searchIndex};
  const items = [...document.querySelectorAll(".nav-item")];
  const groups = [...document.querySelectorAll(".nav-group")];
  const entries = [...document.querySelectorAll(".entry")];
  const cats = [...document.querySelectorAll(".cat")];
  const picked = document.getElementById("picked");
  const noHit = document.getElementById("noHit");
  const main = document.querySelector("main");

  let only = null; // key of the single entry being shown, or null for all

  function applyVisibility() {
    for (const e of entries) {
      e.classList.toggle("hidden", only !== null && e.dataset.key !== only);
    }
    // A category heading is pointless when only one entry under it is shown.
    for (const c of cats) {
      const slug = c.id;
      const visible = entries.some(
        (e) => !e.classList.contains("hidden") && e.dataset.key.startsWith(slug + "-"),
      );
      c.classList.toggle("hidden", !visible);
    }
  }

  function select(key, label) {
    only = key;
    items.forEach((i) => i.classList.toggle("active", i.dataset.key === key));
    picked.textContent = key === null ? \`正在显示:全部 \${INDEX.length} 项\` : \`正在显示:\${label}\`;
    applyVisibility();
    main.scrollTop = 0;
  }

  for (const item of items) {
    item.addEventListener("click", (ev) => {
      ev.preventDefault();
      const key = item.dataset.key;
      select(only === key ? null : key, item.querySelector(".nav-name").textContent);
    });
  }

  document.getElementById("showAll").addEventListener("click", () => {
    document.getElementById("q").value = "";
    filter("");
    select(null, "");
  });

  function filter(query) {
    const q = query.trim().toLowerCase();
    if (q === "") {
      items.forEach((i) => i.classList.remove("hidden"));
      groups.forEach((g) => g.classList.remove("hidden"));
      noHit.style.display = "none";
      return;
    }
    // Match the body text too, not just the title: people search for "拖拽" or
    // "电量", which appear in descriptions rather than in command names.
    const hits = new Set(INDEX.filter((row) => row.hay.includes(q)).map((row) => row.key));
    items.forEach((i) => i.classList.toggle("hidden", !hits.has(i.dataset.key)));
    groups.forEach((g) => {
      const any = [...g.querySelectorAll(".nav-item")].some((i) => !i.classList.contains("hidden"));
      g.classList.toggle("hidden", !any);
    });
    noHit.style.display = hits.size === 0 ? "block" : "none";
  }

  document.getElementById("q").addEventListener("input", (ev) => filter(ev.target.value));

  applyVisibility();
</script>
</body>
</html>
`;

mkdirSync(dirname(output), { recursive: true });
writeFileSync(output, html, "utf8");
console.log(`parsed ${categories.length} categories, ${total} entries`);
console.log(`written ${output}`);
