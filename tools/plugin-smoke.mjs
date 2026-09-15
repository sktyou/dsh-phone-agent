/**
 * Standalone harness for the dsh-phone-agent plugin.
 *
 * DSH loads a bundle at process start, so a freshly installed plugin cannot be
 * exercised inside the running session. This test drives the plugin's own code
 * against the real phone with a minimal fake Cordis context, which validates the
 * link, the tool schema wiring, and every action branch — the only thing it does
 * not cover is DSH's loader.
 *
 *   node tools/plugin-smoke.mjs 192.168.12.138
 */

import { apply } from "../pc/dsh-plugin/lib/index.js";

const host = process.argv[2];
if (host === undefined) {
  console.error("usage: node tools/plugin-smoke.mjs <phone-ip> [port]");
  process.exit(1);
}
const port = process.argv[3] === undefined ? 7912 : Number(process.argv[3]);

let registered = null;
const savedImages = [];

const fakeCtx = {
  tools: {
    register(tool) {
      registered = tool;
    },
  },
  attachments: {
    async saveImage({ data, mediaType, name }) {
      savedImages.push({ bytes: data.length, mediaType, name });
      return {
        attachmentId: `att-${savedImages.length}`,
        mediaType,
        bytes: data.length,
        width: 1080,
        height: 2400,
        name,
      };
    },
  },
  effect(factory) {
    factory();
    return () => {};
  },
  get: () => undefined,
};

apply(fakeCtx, { host, port });

if (registered === null) {
  console.error("FAIL: plugin registered no tool");
  process.exit(1);
}
console.log(`tool registered: ${registered.name}`);
console.log(`parameters: ${Object.keys(registered.parameters).join(", ")}`);

const call = async (label, args) => {
  try {
    const value = await registered.execute(args, { signal: AbortSignal.timeout(60_000) });
    const rendered = registered.output.render(args, value);
    const kinds = rendered.map((part) => part.type).join("+");
    console.log(`\n[${label}] OK  (render: ${kinds})`);
    console.log(`  ${value.summary}`);
    if (typeof value.ui === "string" && value.ui !== "") {
      console.log(
        value.ui
          .split("\n")
          .slice(0, 6)
          .map((line) => `  | ${line}`)
          .join("\n"),
      );
    }
    return value;
  } catch (error) {
    console.log(`\n[${label}] FAILED: ${error.message}`);
    return null;
  }
};

console.log("\n--- 1. status ---");
await call("status", { action: "status" });

console.log("\n--- 2. observe (screenshot + ui tree) ---");
await call("observe", { action: "observe", includeUi: true });

console.log("\n--- 3. launch settings ---");
await call("launch", { action: "launch", package: "com.android.settings" });
await new Promise((r) => setTimeout(r, 1800));

console.log("\n--- 4. human-like swipe ---");
await call("swipe", { action: "swipe", x1: 540, y1: 1700, x2: 540, y2: 700, durationMs: 380 });
await new Promise((r) => setTimeout(r, 900));

console.log("\n--- 5. mechanical swipe (A/B baseline) ---");
await call("swipe-mech", { action: "swipe", x1: 540, y1: 1700, x2: 540, y2: 700, durationMs: 380, human: false });
await new Promise((r) => setTimeout(r, 900));

console.log("\n--- 6. home ---");
await call("home", { action: "key", key: "HOME" });

console.log(`\nimages handed to attachments: ${savedImages.length}`);
console.log("done");
