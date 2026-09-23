/**
 * Drive the MCP server the way a client does: spawn it, speak JSON-RPC over its
 * stdio, and check the replies.
 *
 *   node tools/test-mcp.mjs [--host 192.168.12.138]
 *
 * A shell pipeline cannot do this reliably — the server keeps stdout open for the
 * life of the session, so `echo | node` reads only what happens to be flushed before
 * the shell decides the command is done.
 */

import { spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));

/**
 * Locate the server.
 *
 * Two layouts have to work: inside the repository (`tools/` next to `pc/mcp-server/`)
 * and after being downloaded from the phone, where the server lands in the same
 * directory as this file. A script that only handles the repo layout is useless in
 * the case it was written for.
 */
const SERVER = (() => {
  const beside = path.join(HERE, "index.mjs");
  if (fs.existsSync(beside)) return beside;
  const inRepo = path.join(HERE, "..", "pc", "mcp-server", "index.mjs");
  if (fs.existsSync(inRepo)) return inRepo;
  return beside;   // report the natural location in the error
})();

function argOf(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}
const HOST = argOf("host", "192.168.12.138");

const child = spawn(process.execPath, [SERVER, "--host", HOST], {
  stdio: ["pipe", "pipe", "pipe"],
});

let buffer = "";
const pending = new Map();
let nextId = 1;

child.stdout.setEncoding("utf8");
child.stdout.on("data", (chunk) => {
  buffer += chunk;
  let nl;
  while ((nl = buffer.indexOf("\n")) >= 0) {
    const line = buffer.slice(0, nl).trim();
    buffer = buffer.slice(nl + 1);
    if (!line) continue;
    let msg;
    try {
      msg = JSON.parse(line);
    } catch {
      continue;
    }
    const waiter = pending.get(msg.id);
    if (waiter) {
      pending.delete(msg.id);
      waiter(msg);
    }
  }
});

child.stderr.setEncoding("utf8");
child.stderr.on("data", (d) => process.stderr.write(`  [server] ${d}`));

function rpc(method, params, timeoutMs = 180_000) {
  const id = nextId++;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`${method} 超时`));
    }, timeoutMs);
    pending.set(id, (msg) => {
      clearTimeout(timer);
      resolve(msg);
    });
    child.stdin.write(JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n");
  });
}

const call = async (name, args = {}) => {
  const r = await rpc("tools/call", { name, arguments: args });
  if (r.result?.isError) return { ok: false, text: r.result.content[0].text };
  const c = r.result?.content ?? [];
  const t = c.find((x) => x.type === "text");
  const img = c.find((x) => x.type === "image");
  return { ok: true, text: t?.text ?? "", image: img, raw: r.result };
};

let pass = 0;
let fail = 0;
function check(label, condition, detail = "") {
  if (condition) {
    pass++;
    console.log(`  ✅ ${label}${detail ? "  " + detail : ""}`);
  } else {
    fail++;
    console.log(`  ❌ ${label}${detail ? "  " + detail : ""}`);
  }
}

console.log(`MCP server: ${SERVER}\nPhone: ${HOST}\n`);

// 1. handshake
const init = await rpc("initialize", {
  protocolVersion: "2024-11-05",
  capabilities: {},
  clientInfo: { name: "test", version: "1" },
});
console.log("① 握手");
check("initialize 返回 serverInfo", init.result?.serverInfo?.name === "dsh-phone-agent",
  JSON.stringify(init.result?.serverInfo));

// 2. tool list
const list = await rpc("tools/list", {});
const tools = list.result?.tools ?? [];
console.log("\n② 工具列表");
check(`列出 ${tools.length} 个工具`, tools.length >= 15);
for (const want of ["phone_status", "phone_locate", "phone_sequence",
                    "phone_incidents", "phone_screenshot", "phone_tap"]) {
  check(`含 ${want}`, tools.some((t) => t.name === want));
}

// 3. real call
console.log("\n③ phone_status(端到端)");
const status = await call("phone_status");
if (status.ok) {
  const d = JSON.parse(status.text);
  check("拿到设备信息", !!d.info?.model, `${d.info?.model} ${d.info?.screenWidth}x${d.info?.screenHeight}`);
  check("拿到权限自检", !!d.permissions, `${d.permissions?.okCount}/${d.permissions?.count} 项正常`);
} else {
  check("phone_status 成功", false, status.text);
}

// 4. screenshot returns an actual image block
console.log("\n④ phone_screenshot");
const shot = await call("phone_screenshot", { scale: 0.35 });
check("返回 image content", !!shot.image, shot.image ? `${Math.round(shot.image.data.length / 1024)}KB base64` : "");
check("mimeType 正确", /^image\//.test(shot.image?.mimeType ?? ""), shot.image?.mimeType);

// 5. the new commands
console.log("\n⑤ 新增命令");

// Sweep refuses to run while the agent's own screen is in front — that guard is
// deliberate, because reading your own UI produces an empty list that looks like an
// empty menu. The test therefore has to put something else in front first, or it
// reports a failure for a check that is working correctly.
await call("phone_launch", { package: "com.android.settings" });
await new Promise((r) => setTimeout(r, 2500));

const loc = await call("phone_locate", { target: "设置" });
if (loc.ok) {
  const d = JSON.parse(loc.text);
  check("locate 返回策略链", Array.isArray(d.attempts), `${d.attempts?.length} 种策略尝试过, 命中=${d.found}`);
} else {
  check("locate 可用", false, loc.text.slice(0, 120));
}

// Structured fields must survive the bridge.
//
// The MCP server is a translator, and a translator that forwards only the fields it
// knows about silently drops every field added later. That is exactly what happened
// to `ocrLines`: the phone returned it, the tool printed `lines` and threw the
// geometry away — and the caller had no way to tell the data had ever existed.
function payloadOf(result) {
  const blocks = result.raw?.content ?? [];
  for (const b of blocks) {
    if (b.type !== "text") continue;
    try {
      const j = JSON.parse(b.text);
      if (j && typeof j === "object") return j;
    } catch { /* the summary block is not JSON */ }
  }
  return null;
}

const sw = await call("phone_sweep", { scrolls: 2, distance: 1100 });
if (sw.ok) {
  const p = payloadOf(sw);
  check("sweep 透传 lines(兼容字段)", Array.isArray(p?.lines), `${p?.lines?.length} 条`);
  check("sweep 透传 ocrLines(结构化)", Array.isArray(p?.ocrLines), `${p?.ocrLines?.length} 条`);
  const withBounds = (p?.ocrLines ?? []).filter((l) => Array.isArray(l.bounds)).length;
  check("ocrLines 带 bounds", withBounds > 0 && withBounds === p?.ocrLines?.length,
    `${withBounds}/${p?.ocrLines?.length}`);
  const withCapture = (p?.ocrLines ?? []).filter((l) => typeof l.capture === "number").length;
  check("ocrLines 带 capture", withCapture > 0, `${withCapture} 条`);
} else {
  check("sweep 可用", false, sw.text.slice(0, 120));
}

const oc = await call("phone_ocr");
if (oc.ok) {
  const p = payloadOf(oc);
  check("ocr 透传 ocrLines", Array.isArray(p?.ocrLines), `${p?.ocrLines?.length} 条`);
  check("ocr 透传 fullText(兼容字段)", typeof p?.fullText === "string");
} else {
  check("ocr 可用", false, oc.text.slice(0, 120));
}

// Template matching, round trip.
//
// A crop taken from a known region must be found at that same region. This is the one
// check that would have caught the inverted threshold that made find_image return
// nothing at every setting: a pixel-identical template is the easiest possible case,
// so if it is not found, the search is not merely imprecise — it is broken.
const CROP = [60, 300, 400, 560];
// `phone_screenshot` reports geometry as prose, so the raw command is used to get the
// crop size numerically and compute where the centre should land.
const cropRaw = await call("phone_raw", {
  command: { cmd: "screenshot", region: CROP, scale: 1.0, format: "png" },
});
const cropData = cropRaw.ok ? JSON.parse(cropRaw.text) : null;
const cropImage = cropData?.image;
if (cropImage) {
  const expectX = CROP[0] + cropData.imageWidth / 2;
  const expectY = CROP[1] + cropData.imageHeight / 2;
  const found = await call("phone_find_image", { template: cropImage, threshold: 0.9, max: 3 });
  if (found.ok) {
    const p = JSON.parse(found.text);
    const first = p.matches?.[0];
    check("find_image 命中同源模板", (p.count ?? 0) > 0, `count=${p.count}`);
    if (first) {
      const dx = Math.abs(first.center[0] - expectX);
      const dy = Math.abs(first.center[1] - expectY);
      check("find_image 中心误差 ≤4px", dx <= 4 && dy <= 4,
        `误差 (${dx.toFixed(0)},${dy.toFixed(0)})px, 相似度 ${first.similarity?.toFixed(4)}`);
    } else {
      check("find_image 返回坐标", false, found.text.slice(0, 140));
    }
  } else {
    check("find_image 可用", false, found.text.slice(0, 120));
  }
} else {
  check("find_image 前置截图", false, cropRaw.text?.slice(0, 140) ?? "无输出");
}

// A viewId copied straight out of uitree must be findable in node mode — the tree
// advertises it, so failing to match it is a contradiction a caller cannot resolve.
const tree = await call("phone_uitree", { maxDepth: 30, maxNodes: 400 });
if (tree.ok) {
  const t = JSON.parse(tree.text);
  const ids = [];
  (function walk(n) {
    if (!n) return;
    if (n.viewId) ids.push(n.viewId);
    for (const c of n.children ?? []) walk(c);
  })(t.root);
  if (ids.length > 0) {
    const probe = ids.find((i) => i.includes("action_bar")) ?? ids[0];
    const w = await call("phone_wait", { mode: "node", target: probe, timeoutMs: 4000 });
    const p = JSON.parse(w.text);
    check("wait node 模式匹配 viewId", p.appeared === true, `${probe} → appeared=${p.appeared}`);
  } else {
    check("wait node 模式匹配 viewId", false, "当前界面没有 viewId 可测(改用 OCR 路线)");
  }
} else {
  check("wait node 模式匹配 viewId", false, "uitree 不可用");
}

const seq = await call("phone_sequence", {
  steps: [{ action: "wait", ms: 100 }, { action: "screenshot", scale: 0.25 }],
});
if (seq.ok) {
  const d = JSON.parse(seq.text);
  check("sequence 逐步执行", d.executed === 2, `执行 ${d.executed}/${d.total}, 成功 ${d.okCount}`);
} else {
  check("sequence 可用", false, seq.text.slice(0, 120));
}

const inc = await call("phone_incidents");
if (inc.ok) {
  const d = JSON.parse(inc.text);
  check("incidents 可查询", typeof d.openCount === "number", `未解决 ${d.openCount} 条`);
} else {
  check("incidents 可用", false, inc.text.slice(0, 120));
}

// 6. safety net surfaces in a tap reply
console.log("\n⑥ Safety Net(tap 返回安全检查)");
const tap = await call("phone_tap", { x: 540, y: 300 });
if (tap.ok) {
  const d = JSON.parse(tap.text);
  check("tap 带 safety 字段", !!d.safety, d.safety ? `code=${d.safety.code} · ${d.safety.hint}` : "");
} else {
  check("tap 可用", false, tap.text.slice(0, 120));
}

// 7. error path is readable, not a protocol error
console.log("\n⑦ 错误路径");
const bad = await call("phone_raw", { command: { cmd: "definitely_not_a_command" } });
check("未知命令返回可读错误", !bad.ok && /失败|未知/.test(bad.text), bad.text.slice(0, 100));

console.log(`\n${"=".repeat(50)}\n通过 ${pass} / 失败 ${fail}\n`);
child.kill();
process.exit(fail === 0 ? 0 : 1);
