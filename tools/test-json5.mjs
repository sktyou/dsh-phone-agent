/**
 * Reproduce the Kotlin JSON5 normaliser in JS so the failure can be reproduced and
 * inspected without rebuilding the APK each time.
 */
import fs from "node:fs";

function normaliseJson5(text) {
  const out = [];
  let i = 0;
  let inString = false;
  let quote = " ";

  while (i < text.length) {
    const c = text[i];

    if (inString) {
      if (c === "\\" && i + 1 < text.length) {
        out.push(c, text[i + 1]);
        i += 2;
        continue;
      }
      if (c === quote) {
        inString = false;
        out.push('"');
        i++;
        continue;
      }
      if (quote === "'" && c === '"') {
        out.push('\\"');
        i++;
        continue;
      }
      out.push(c);
      i++;
      continue;
    }

    if (c === "/" && text[i + 1] === "/") {
      while (i < text.length && text[i] !== "\n") i++;
      continue;
    }
    if (c === "/" && text[i + 1] === "*") {
      i += 2;
      while (i + 1 < text.length && !(text[i] === "*" && text[i + 1] === "/")) i++;
      i += 2;
      continue;
    }
    if (c === '"' || c === "'") {
      inString = true;
      quote = c;
      out.push('"');
      i++;
      continue;
    }
    out.push(c);
    i++;
  }

  let result = out.join("");
  result = result.replace(/,\s*([}\]])/g, "$1");
  result = result.replace(/([{,]\s*)([A-Za-z_][A-Za-z0-9_]*)(\s*:)/g, '$1"$2"$3');
  return result;
}

const raw = fs.readFileSync(process.argv[2], "utf8");
const norm = normaliseJson5(raw);
console.log("原文长度:", raw.length, "转换后:", norm.length);
console.log("\n=== 转换后前 300 字符 ===");
console.log(norm.slice(0, 300));

// Locate the first structural error the way org.json would: walk the text and
// track string/escape state, then report where the parser's expectations break.
let depth = 0;
let inStr = false;
let esc = false;
let firstBad = -1;
for (let i = 0; i < norm.length; i++) {
  const c = norm[i];
  if (inStr) {
    if (esc) { esc = false; continue; }
    if (c === "\\") { esc = true; continue; }
    if (c === '"') inStr = false;
    continue;
  }
  if (c === '"') { inStr = true; continue; }
  if (c === "{" || c === "[") depth++;
  if (c === "}" || c === "]") depth--;
}
console.log("\n=== 状态 ===");
console.log("结尾仍在字符串内:", inStr);
console.log("结尾深度:", depth, depth === 0 ? "(平衡)" : "(不平衡!)");

console.log("\n=== 前 5 个可疑位置 ===");
try {
  JSON.parse(norm);
  console.log("  严格 JSON 解析通过");
} catch (e) {
  console.log("  严格解析失败:", e.message);
  const m = e.message.match(/position (\d+)/);
  if (m) {
    const p = Number(m[1]);
    console.log("  位置", p, "附近:");
    console.log("  ..." + norm.slice(Math.max(0, p - 120), p + 120) + "...");
  }
}
