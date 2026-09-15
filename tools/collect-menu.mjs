/**
 * Collect a store's full menu from the phone.
 *
 * Assumes the phone is ALREADY showing the store's menu page — navigating there
 * depends on the app, and a half-automated tap sequence is more fragile than just
 * asking the operator to open the store. Everything after that is mechanical.
 *
 *   node tools/collect-menu.mjs --name "星耀天都店" [--host 192.168.12.138] [--scrolls 80]
 *
 * Outputs, into docs/:
 *   menu-<name>.txt   raw OCR lines, in the order they were first seen
 *   menu-<name>.csv   best-effort name/price extraction, opens straight in Excel
 *
 * The CSV is deliberately a first pass. OCR mangles characters ("莴皮" comes back
 * as "莒皮", "鸡柳" as "鸿柳") and de-duplication loses the line ordering, so
 * pairings can drift. Treat it as a draft to correct against the screenshots, not
 * as ground truth.
 */

import net from "node:net";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DOCS = path.join(HERE, "..", "docs");

function argOf(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const HOST = argOf("host", "192.168.12.138");
const PORT = Number(argOf("port", "7912"));
const NAME = argOf("name", "menu");
const SCROLLS = Number(argOf("scrolls", "80"));
const DISTANCE = Number(argOf("distance", "1100"));
// Right-hand column only: the left sidebar is a fixed category list that repeats
// on every screen and would drown the real content.
const REGION = argOf("region", "280,400,1080,2100");

let nextId = 1;

function callPhone(command, timeoutMs = 600_000) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(PORT, HOST);
    let buffer = "";
    let settled = false;
    const finish = (err, value) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      err ? reject(err) : resolve(value);
    };
    socket.setTimeout(timeoutMs);
    socket.on("connect", () => socket.write(JSON.stringify({ id: nextId++, ...command }) + "\n"));
    socket.on("data", (chunk) => {
      buffer += chunk.toString("utf8");
      const nl = buffer.indexOf("\n");
      if (nl < 0) return;
      try {
        finish(null, JSON.parse(buffer.slice(0, nl)));
      } catch (e) {
        finish(new Error(`bad reply: ${buffer.slice(0, 200)}`));
      }
    });
    socket.on("timeout", () => finish(new Error("phone did not reply in time")));
    socket.on("error", finish);
    socket.on("close", () => finish(new Error("connection closed")));
  });
}

function csvCell(value) {
  const s = value == null ? "" : String(value);
  // Prefix a quote when the cell starts with a character Excel would treat as a
  // number or formula — "¥9.9" and "=..." both land wrong otherwise.
  if (/^[=+\-@]/.test(s)) return `"'${s.replace(/"/g, '""')}"`;
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

/**
 * Best-effort pairing of a name with the price that follows it.
 *
 * The OCR output is a flat list of lines with prices on their own lines, so this
 * walks forward from each name and takes the first price within a few lines. It is
 * a heuristic; the caller is expected to verify against the screenshots.
 */
function extract(lines) {
  const priceRe = /^[¥y￥]\s*([0-9]+(?:\.[0-9]+)?)/;
  const saleRe = /月售\s*([0-9]+\+?|\d+)/;
  const rows = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    // A menu item line: has a name-like length, is not a price, not a badge.
    if (line.length < 3 || line.length > 40) continue;
    if (priceRe.test(line)) continue;
    if (/^(选规格|选套餐|月售|已|免费入会|领取|比单点|套餐|¥|y\d)/.test(line)) continue;

    let price = "";
    let sale = "";
    for (let j = i + 1; j < Math.min(i + 8, lines.length); j++) {
      const next = lines[j].trim();
      const pm = next.match(priceRe);
      if (pm && !price) price = pm[1];
      const sm = next.match(saleRe);
      if (sm && !sale) sale = sm[1];
      if (price && sale) break;
    }
    if (price) rows.push([line, price, sale]);
  }
  return rows;
}

const reply = await callPhone({
  cmd: "sweep",
  scrolls: SCROLLS,
  distance: DISTANCE,
  perCapture: 2,
  durationMs: 420,
  settleMs: 700,
  region: REGION.split(",").map(Number),
});

if (!reply.ok) {
  console.error("sweep failed:", reply.error ?? reply.code);
  process.exit(1);
}

const d = reply.data;
console.log(
  `scrolled ${d.scrolls}x, ${d.lineCount} lines, stop=${d.stopReason}`,
);

const txtPath = path.join(DOCS, `menu-${NAME}.txt`);
fs.writeFileSync(txtPath, d.lines.join("\n"), "utf8");
console.log("raw  ->", txtPath);

const rows = extract(d.lines);
const header = ["菜品(OCR原文)", "价格(元)", "月售"];
const csv =
  "\uFEFF" + // BOM so Excel reads UTF-8 Chinese correctly on a double-click
  [header, ...rows].map((r) => r.map(csvCell).join(",")).join("\r\n") + "\r\n";

const csvPath = path.join(DOCS, `menu-${NAME}.csv`);
fs.writeFileSync(csvPath, csv, "utf8");
console.log(`csv  -> ${csvPath}  (${rows.length} rows, needs review)`);
