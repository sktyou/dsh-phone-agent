/**
 * dsh-phone-agent — control an authorized Android phone over the LAN.
 *
 * This plugin talks to the DSH Phone Agent app (com.dsh.phoneagent) over a plain
 * TCP socket speaking line-delimited JSON. Unlike the adb-backed OpenGUI plugin,
 * nothing here touches adb: the phone opens a port, the PC dials it, and every
 * gesture is injected on the phone through its own AccessibilityService.
 *
 * The practical consequences:
 *   - no USB cable, no wireless-debugging pairing, no adb server to fight over;
 *   - swipes are arcs with an ease-in-out speed profile and tremor, not the
 *     perfectly straight constant-speed stroke `adb shell input swipe` emits;
 *   - nothing on the PC needs the Android SDK at run time.
 *
 * The trade-off is the same one every rootless path shares: the accessibility
 * gesture channel lets us shape the trajectory but not the event attributes —
 * pressure, contact size and the source device id stay framework-chosen.
 */

import { connect } from "node:net";
import { defineTool } from "@deepseek-ai/dsh-tools";
import { AttachmentId } from "@deepseek-ai/dsh-attachment";

export const name = "phone-agent";

export const inject = ["tools", "attachments"];

const DEFAULT_PORT = 7912;
const CONNECT_TIMEOUT_MS = 8_000;
const CALL_TIMEOUT_MS = 60_000;

/** One lazy, self-healing TCP link to the phone. Calls are serialised. */
class PhoneLink {
  #host;
  #port;
  #token;
  #socket = null;
  #buffer = "";
  #seq = 0;
  #tail = Promise.resolve();
  #waiter = null;

  constructor({ host, port, token }) {
    this.#host = host;
    this.#port = port;
    this.#token = token;
  }

  get endpoint() {
    return `${this.#host}:${this.#port}`;
  }

  #reset(cause) {
    if (this.#socket !== null) {
      try {
        this.#socket.destroy();
      } catch {
        // already gone
      }
    }
    this.#socket = null;
    this.#buffer = "";
    const waiter = this.#waiter;
    this.#waiter = null;
    if (waiter !== null) waiter.reject(cause ?? new Error("phone link closed"));
  }

  async #ensure() {
    if (this.#socket !== null && !this.#socket.destroyed) return;
    const socket = await new Promise((resolve, reject) => {
      const candidate = connect({ host: this.#host, port: this.#port });
      const timer = setTimeout(() => {
        candidate.destroy();
        reject(new Error(`connect to ${this.endpoint} timed out`));
      }, CONNECT_TIMEOUT_MS);
      candidate.once("connect", () => {
        clearTimeout(timer);
        resolve(candidate);
      });
      candidate.once("error", (error) => {
        clearTimeout(timer);
        reject(error);
      });
    });
    socket.setNoDelay(true);
    socket.setEncoding("utf8");
    socket.on("data", (chunk) => {
      this.#buffer += chunk;
      let index = this.#buffer.indexOf("\n");
      while (index >= 0) {
        const line = this.#buffer.slice(0, index);
        this.#buffer = this.#buffer.slice(index + 1);
        const waiter = this.#waiter;
        this.#waiter = null;
        if (waiter !== null) waiter.resolve(line);
        index = this.#buffer.indexOf("\n");
      }
    });
    socket.on("error", (error) => this.#reset(error));
    socket.on("close", () => this.#reset());
    this.#socket = socket;
    this.#buffer = "";
  }

  /** Queue one request behind the previous one, so replies stay paired. */
  call(cmd, params = {}) {
    const task = this.#tail.then(() => this.#dispatch(cmd, params));
    this.#tail = task.then(
      () => undefined,
      () => undefined,
    );
    return task;
  }

  async #dispatch(cmd, params) {
    await this.#ensure();
    this.#seq += 1;
    const request = { id: this.#seq, cmd };
    if (this.#token !== "") request.token = this.#token;
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) request[key] = value;
    }

    const raw = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.#waiter = null;
        reject(new Error(`phone reply timed out after ${CALL_TIMEOUT_MS} ms`));
      }, CALL_TIMEOUT_MS);
      this.#waiter = {
        resolve: (line) => {
          clearTimeout(timer);
          resolve(line);
        },
        reject: (error) => {
          clearTimeout(timer);
          reject(error);
        },
      };
      this.#socket.write(`${JSON.stringify(request)}\n`);
    });

    const reply = JSON.parse(raw);
    if (!reply.ok) throw new Error(reply.error ?? "phone agent reported an error");
    return reply.data ?? {};
  }

  dispose() {
    this.#reset();
  }
}

/** Wrap the phone's `observe` payload into a tool result the model can read. */
function describeTree(ui, limit = 40) {
  if (ui === undefined || ui.root === undefined) return "UI tree unavailable.";
  const lines = [];
  const walk = (node) => {
    if (lines.length >= limit) return;
    const label = node.text || node.desc || "";
    const interactive = node.clickable || node.editable || node.scrollable;
    if (label !== "" || interactive) {
      const bounds = Array.isArray(node.bounds) ? node.bounds : [0, 0, 0, 0];
      const cx = Math.round((bounds[0] + bounds[2]) / 2);
      const cy = Math.round((bounds[1] + bounds[3]) / 2);
      const marks = [
        node.clickable ? "clickable" : "",
        node.editable ? "editable" : "",
        node.scrollable ? "scrollable" : "",
      ]
        .filter(Boolean)
        .join(",");
      lines.push(
        `${"  ".repeat(Math.min(node.depth ?? 0, 6))}${node.cls}` +
          `${label !== "" ? ` "${label}"` : ""}` +
          ` @(${cx},${cy})` +
          `${marks !== "" ? ` [${marks}]` : ""}`,
      );
    }
    for (const child of node.children ?? []) walk(child);
  };
  walk(ui.root);
  const more = ui.truncated ? " (truncated)" : "";
  return `${ui.nodeCount} nodes${more}:\n${lines.join("\n")}`;
}

/** Interactive-only mode returns a flat list; the default mode returns a tree. */
function describeUi(ui) {
  if (Array.isArray(ui.nodes)) {
    const lines = ui.nodes.slice(0, 60).map((n) => {
      const label = n.text || n.desc || n.viewId || n.cls;
      const centre = Array.isArray(n.center) ? n.center : [0, 0];
      const marks = [
        n.clickable ? "clickable" : "",
        n.editable ? "editable" : "",
        n.scrollable ? "scrollable" : "",
      ]
        .filter(Boolean)
        .join(",");
      return `${n.cls} "${label}" @(${centre[0]},${centre[1]})${marks !== "" ? ` [${marks}]` : ""}`;
    });
    const more = ui.returned > lines.length ? `\n… and ${ui.returned - lines.length} more` : "";
    return `${ui.returned} interactive node(s):\n${lines.join("\n")}${more}`;
  }
  return describeTree(ui);
}

export function apply(ctx, config = {}) {
  console.log(
    `[phone-agent] loaded (host=${config.host ?? "unset"}, port=${config.port ?? DEFAULT_PORT}, ` +
      `toolName=${config.toolName ?? "phone_agent"})`,
  );
  const host = typeof config.host === "string" && config.host !== "" ? config.host : undefined;
  const port = Number.isInteger(config.port) ? config.port : DEFAULT_PORT;
  const token = typeof config.token === "string" ? config.token : "";
  const toolName = typeof config.toolName === "string" && config.toolName !== ""
    ? config.toolName
    : "phone_agent";

  let link = null;
  const requireLink = () => {
    if (host === undefined) {
      throw new Error(
        "dsh-phone-agent: no phone host configured. Set `host` (the phone's LAN IP) " +
          "on the phone-agent row in the profile patch.",
      );
    }
    if (link === null) link = new PhoneLink({ host, port, token });
    return link;
  };

  ctx.tools.register(
    defineTool({
      name: toolName,
      description:
        "Observe and operate one authorized Android phone over the LAN, through the " +
        "phone's own accessibility service. Use this for any request that inspects or " +
        "drives an Android phone or mobile app. Observe before changing anything, then " +
        "echo coordinates from the latest screenshot. Swipes are human-shaped " +
        "(arced path, ease-in-out speed, tremor) by default; pass human:false for a " +
        "mechanical straight stroke.",
      parameters: {
        action: {
          type: "string",
          enum: [
            "status",
            "device",
            "observe",
            "uitree",
            "find",
            "findtext",
            "ocr",
            "findcolor",
            "findimage",
            "colorat",
            "tap",
            "doubletap",
            "swipe",
            "flick",
            "pinch",
            "longpress",
            "scroll",
            "waitstable",
            "text",
            "send",
            "delete",
            "clear",
            "clipboard",
            "key",
            "launch",
            "deeplink",
            "apps",
            "stopapp",
            "wake",
            "wait",
            "volume",
            "brightness",
          ],
          required: true,
          description:
            "status: link info. device: full device state (battery, volume, brightness, " +
            "network, storage, memory, whether service and accessibility are up). " +
            "observe: screenshot plus UI tree. uitree: tree only. " +
            "find: locate widgets by text/id/state, returning only the hits. " +
            "findtext: search text on screen, fusing the tree with OCR and resolving the " +
            "clickable widget under it — use when you know the label but not the widget. " +
            "ocr: read all on-screen text including WebViews/games/image text. " +
            "findcolor/findimage/colorat: locate by colour or template, or sample one pixel. " +
            "tap/doubletap/swipe/flick/pinch/longpress: touch input in full-screen " +
            "screenshot pixels; all human-shaped by default. " +
            "scroll: scroll a real container one viewport. waitstable: wait until the UI " +
            "stops changing. text/clipboard: enter text or use the clipboard (mode and " +
            "typing parameters control replace/append/clear and instant/natural typing). " +
            "key: a system button. launch/deeplink/apps/stopapp: app control. " +
            "wake: turn the screen on before touching. volume/brightness: system levels.",
        },
        x: { type: "number", description: "Tap x, in current screenshot pixels." },
        y: { type: "number", description: "Tap y, in current screenshot pixels." },
        x1: { type: "number", description: "Swipe start x." },
        y1: { type: "number", description: "Swipe start y." },
        x2: { type: "number", description: "Swipe end x." },
        y2: { type: "number", description: "Swipe end y." },
        durationMs: { type: "integer", description: "Swipe duration, 60-4000 ms. Default 320." },
        human: {
          type: "boolean",
          description:
            "Defaults to true: arced path, ease-in-out speed, tremor. False emits the " +
            "straight constant-speed stroke used by adb input, for comparison.",
        },
        text: { type: "string", description: "Text for the text action." },
        key: {
          type: "string",
          enum: [
            "BACK",
            "HOME",
            "RECENTS",
            "NOTIFICATIONS",
            "QUICK_SETTINGS",
            "POWER",
            "LOCK",
            "SCREENSHOT",
          ],
          description: "System button for the key action.",
        },
        package: { type: "string", description: "Android package id for launch." },
        fresh: {
          type: "boolean",
          description:
            "For launch: discard the app's existing task and start from its root screen. " +
            "Default true. A non-fresh launch only brings the old task forward, so the app " +
            "reappears wherever it was left and every coordinate lands on the wrong screen.",
        },
        ms: { type: "integer", description: "Wait duration for the wait action, 0-15000 ms." },
        includeUi: {
          type: "boolean",
          description: "For observe: attach the UI tree. Default true.",
        },
        selector: {
          type: "object",
          additionalProperties: true,
          description:
            "For find: match criteria, ANDed together. Any of text, textContains, " +
            "textRegex, desc, descContains, viewId (suffix-matched), viewIdContains, cls, " +
            "clickable, scrollable, editable, enabled, checked, selected, focused, " +
            "depthMin, depthMax. Example: {textContains:'登录', clickable:true}",
        },
        color: {
          type: "string",
          description: "For findcolor: #RRGGBB or #AARRGGBB.",
        },
        tolerance: {
          type: "integer",
          description: "For findcolor: per-channel tolerance 0-255. Default 16.",
        },
        template: {
          type: "string",
          description: "For findimage: base64 PNG/JPEG template, max 512px per edge.",
        },
        threshold: {
          type: "number",
          description: "For findimage: similarity threshold 0-1. Default 0.85.",
        },
        region: {
          type: "array",
          items: { type: "number" },
          description: "Optional [left, top, right, bottom] search box for findcolor/findimage.",
        },
        max: {
          type: "integer",
          description: "For find/findtext/findcolor/findimage: maximum results. Default 10.",
        },
        mode: {
          type: "string",
          enum: ["contains", "exact", "regex"],
          description: "For findtext: text comparison. Default contains.",
        },
        source: {
          type: "string",
          enum: ["auto", "node", "ocr"],
          description:
            "For findtext: node (accessibility tree only, fastest), ocr (recognition " +
            "only), or auto (tree first, OCR as fallback — default).",
        },
        tappable: {
          type: "boolean",
          description:
            "For findtext: resolve each hit to the clickable widget under it. Default true.",
        },
        stream: {
          type: "string",
          enum: ["music", "ring", "alarm", "notification", "voice"],
          description: "For volume: which stream to adjust. Default music.",
        },
        volumeAction: {
          type: "string",
          enum: ["up", "down", "mute", "unmute", "set"],
          description: "For volume: what to do. Default up. 'set' requires level.",
        },
        level: {
          type: "integer",
          description: "For volume with volumeAction:'set', or for brightness (0-255).",
        },
        percent: {
          type: "integer",
          description: "For brightness: 0-100, an alternative to level.",
        },
        retry: {
          type: "integer",
          description:
            "For gestures: how many times to retry a cancelled gesture, 1-5. Default 1. " +
            "A cancelled gesture is usually transient (mid-animation, or a system window " +
            "briefly took the touch stream).",
        },
        minIntervalMs: {
          type: "integer",
          description:
            "For tap/doubletap: minimum gap enforced between taps, default 110 ms. The " +
            "phone throttles taps so a burst cannot queue into a visible hang. Set 0 to " +
            "disable. Swipes and long presses are never throttled.",
        },
        scale: {
          type: "number",
          description:
            "For observe/screenshot: 0.05-1, shrink the frame. Responses still report the " +
            "full screen size, so map back with: touch = image / scale.",
        },
        interactiveOnly: {
          type: "boolean",
          description:
            "For observe/uitree: return only actionable nodes as a flat list. Shrinks the " +
            "payload by roughly an order of magnitude on list-heavy screens.",
        },
        typing: {
          type: "string",
          enum: ["instant", "natural"],
          description:
            "For text: instant (default) sets the whole value atomically; natural commits " +
            "one character at a time with a randomised gap, for fields that watch input " +
            "timing. Natural is slower by design.",
        },
        textMode: {
          type: "string",
          enum: ["replace", "append", "clear"],
          description: "For text: replace (default) / append to existing / clear the field.",
        },
        count: {
          type: "integer",
          description: "For delete: how many characters to remove from the end. Default 1.",
        },
        clipboardAction: {
          type: "string",
          enum: ["get", "set", "paste"],
          description:
            "For clipboard: get reads, set writes, paste injects into the focused field.",
        },
        uri: {
          type: "string",
          description: "For deeplink: the URI to open, e.g. weixin:// or https://...",
        },
        query: { type: "string", description: "For apps: filter by label or package substring." },
        includeSystem: {
          type: "boolean",
          description: "For apps: include preinstalled system apps. Default false.",
        },
        direction: {
          type: "string",
          enum: ["forward", "backward"],
          description: "For scroll: which way to scroll the container. Default forward.",
        },
        index: {
          type: "integer",
          description: "For scroll: which scrollable container, depth-first. Default 0.",
        },
        startSpread: {
          type: "number",
          description: "For pinch: initial distance between the two fingers in px. Default 200.",
        },
        endSpread: {
          type: "number",
          description:
            "For pinch: final distance in px. Larger than startSpread zooms in. Default 600.",
        },
        stableFrames: {
          type: "integer",
          description: "For waitstable: identical frames required to call it settled. Default 2.",
        },
        timeoutMs: {
          type: "integer",
          description: "For waitstable: how long to wait for stability, 200-30000 ms. Default 5000.",
        },
      },
      output: {
        schema: {
          type: "object",
          additionalProperties: false,
          properties: {
            summary: { type: "string", required: true },
            width: { type: "integer" },
            height: { type: "integer" },
            ui: { type: "string" },
            // Carries the saved attachment through to render(). It has to be
            // declared here: the schema is strict, so an undeclared key fails the
            // whole tool call rather than being ignored.
            __image: { type: "object", additionalProperties: true },
          },
        },
        render(_args, value) {
          const parts = [{ type: "text", text: value.summary }];
          if (value.__image !== undefined) {
            parts.push({ type: "image", attachment: value.__image });
          }
          if (typeof value.ui === "string" && value.ui !== "") {
            parts.push({ type: "text", text: value.ui });
          }
          return parts;
        },
      },
      isConcurrencySafe: () => true,

      async execute(args) {
        const phone = requireLink();

        switch (args.action) {
          case "status": {
            const info = await phone.call("info");
            return {
              summary:
                `Phone ${info.model} (${info.manufacturer}) · Android ${info.android} ` +
                `(API ${info.sdk}) · screen ${info.screenWidth}x${info.screenHeight} · ` +
                `accessibility ${info.accessibility ? "granted" : "NOT granted"} · ` +
                `endpoint ${phone.endpoint}`,
            };
          }

          case "device": {
            const d = await phone.call("device");
            const svc = d.service ?? {};
            const bat = d.battery ?? {};
            const vol = d.volume ?? {};
            const temp = bat.temperatureC;
            return {
              summary: [
                `${d.manufacturer} ${d.model} · Android ${d.android} (API ${d.sdk}) · ${d.abi}`,
                `屏幕 ${d.screen?.width}x${d.screen?.height} @${d.screen?.densityDpi}dpi · 旋转 ${d.screen?.rotation}°`,
                `电量 ${bat.percent}%${bat.charging ? ` (充电中/${bat.plugged})` : ""}` +
                  `${temp !== null && temp !== undefined ? ` · ${temp}°C` : ""}`,
                `屏幕${d.screenOn ? "亮" : "灭"} · ${d.locked ? "已锁屏" : "未锁屏"}`,
                `亮度 ${d.brightness?.percent}%${d.brightness?.auto ? "(自动)" : ""}` +
                  `${d.brightness?.writable ? "" : " · 不可写(缺 WRITE_SETTINGS)"}`,
                `音量 媒体 ${vol.music?.current}/${vol.music?.max} · 铃声 ${vol.ring?.current}/${vol.ring?.max} · 模式 ${vol.mode}`,
                `网络 ${d.network?.type}${d.network?.online ? "" : "(离线)"} · 存储 ${d.storage?.usedPercent}% · 内存 ${d.memory?.usedPercent}%`,
                `服务 ${svc.running ? "运行中" : "未运行"}:${svc.port} · 已运行 ${Math.round((svc.uptimeMs ?? 0) / 1000)}s · 客户端 ${svc.clients} · 操作 ${svc.operations}`,
                `无障碍 ${d.accessibility ? "已连接" : "未连接 — 需在手机设置中开启"}`,
              ].join("\n"),
            };
          }

          case "observe": {
            const data = await phone.call("observe", {
              includeUi: args.includeUi !== false,
              interactiveOnly: args.interactiveOnly === true,
              format: "jpeg",
              quality: 80,
              scale: args.scale,
              region: args.region,
            });
            const raw = Buffer.from(data.image, "base64");
            const ref = await ctx.attachments.saveImage({
              data: raw,
              mediaType: "image/jpeg",
              name: `phone-${Date.now()}.jpg`,
            });
            return {
              summary:
                `Screenshot ${data.imageWidth}x${data.imageHeight} ` +
                `(${Math.round(raw.length / 1024)} KiB). Use these pixel coordinates for tap/swipe.`,
              width: data.imageWidth,
              height: data.imageHeight,
              ui: data.ui === undefined ? "" : describeUi(data.ui),
              __image: {
                attachmentId: AttachmentId(ref.attachmentId),
                mediaType: "image/jpeg",
                bytes: ref.bytes,
                width: ref.width,
                height: ref.height,
                name: ref.name,
              },
            };
          }

          case "uitree": {
            const tree = await phone.call("uitree", {
              maxDepth: args.max,
              interactiveOnly: args.interactiveOnly === true,
            });
            return {
              summary:
                `UI tree: ${tree.nodeCount} node(s)` +
                `${tree.truncated ? " (truncated)" : ""}` +
                `${tree.returned !== undefined ? `, ${tree.returned} interactive` : ""}.`,
            };
          }

          case "colorat": {
            if (args.x === undefined || args.y === undefined) {
              throw new Error("phone_agent: colorat requires x and y");
            }
            const data = await phone.call("colorat", { x: args.x, y: args.y });
            return { summary: `Pixel (${data.x},${data.y}) is ${data.color} (rgba ${data.r},${data.g},${data.b},${data.alpha}).` };
          }

          case "wake": {
            const data = await phone.call("wake");
            return {
              summary:
                `Screen ${data.screenOn ? "on" : "still off"}` +
                `${data.wasOn ? " (was already on)" : ""}` +
                `${data.locked ? " · LOCKED, gestures will not reach the app" : " · unlocked"}.`,
            };
          }

          case "send": {
            const data = await phone.call("send");
            return {
              summary: data.sent
                ? "Submitted the focused field via the IME action (send / search / done)."
                : `The field refused an IME action — ${data.note}`,
            };
          }

          case "delete": {
            const data = await phone.call("delete", { count: args.count ?? 1 });
            return {
              summary: data.applied
                ? `Deleted ${data.deleted} character(s); ${data.remaining} remain.`
                : "Deletion was refused — focus an editable field first.",
            };
          }

          case "clear": {
            const data = await phone.call("clear");
            return {
              summary: data.cleared
                ? `Field cleared (had ${data.clearedLength} characters).`
                : "Clearing was refused — focus an editable field first.",
            };
          }

          case "clipboard": {
            const data = await phone.call("clipboard", {
              action: args.clipboardAction ?? "get",
              text: args.text,
            });
            if (args.clipboardAction === "set") {
              return { summary: `Clipboard written (${data.length} chars).` };
            }
            if (args.clipboardAction === "paste") {
              return { summary: data.pasted ? "Pasted into the focused field." : "Paste was refused." };
            }
            return { summary: `Clipboard (${data.length} chars): "${data.text}"` };
          }

          case "deeplink": {
            if (typeof args.uri !== "string") throw new Error("phone_agent: deeplink requires uri");
            const data = await phone.call("deeplink", { uri: args.uri, package: args.package });
            return { summary: `Opened ${data.opened}.` };
          }

          case "apps": {
            const data = await phone.call("apps", {
              query: args.query,
              includeSystem: args.includeSystem === true,
            });
            if (data.count === 0) return { summary: "No matching apps." };
            const lines = data.apps
              .slice(0, 40)
              .map((a, i) => `${i + 1}. ${a.label} — ${a.package}`);
            return {
              summary: `${data.count} app(s):\n${lines.join("\n")}` +
                `${data.count > 40 ? `\n… and ${data.count - 40} more` : ""}`,
            };
          }

          case "stopapp": {
            if (typeof args.package !== "string") throw new Error("phone_agent: stopapp requires package");
            const data = await phone.call("stopapp", { package: args.package });
            return {
              summary:
                `Requested stop of ${data.requested}. ` +
                `${data.stillRunning ? "It is still running — " : ""}${data.note}`,
            };
          }

          case "pinch": {
            const data = await phone.call("pinch", {
              x: args.x ?? 540,
              y: args.y ?? 1200,
              startSpread: args.startSpread ?? 200,
              endSpread: args.endSpread ?? 600,
              durationMs: args.durationMs ?? 400,
              retry: args.retry,
            });
            return {
              summary:
                `Pinch ${data.startSpread} -> ${data.endSpread} px ` +
                `${data.completed ? "completed" : "was CANCELLED"}` +
                `${data.endSpread > data.startSpread ? " (zoom in)" : " (zoom out)"}.`,
            };
          }

          case "flick": {
            const missing = ["x1", "y1", "x2", "y2"].filter((f) => args[f] === undefined);
            if (missing.length > 0) throw new Error(`phone_agent: flick requires ${missing.join(", ")}`);
            const data = await phone.call("flick", {
              x1: args.x1, y1: args.y1, x2: args.x2, y2: args.y2,
              durationMs: args.durationMs ?? 90,
              retry: args.retry,
            });
            return {
              summary: `Flick over ${data.durationMs} ms ${data.completed ? "completed" : "was CANCELLED"}.`,
            };
          }

          case "scroll": {
            const data = await phone.call("scroll", {
              direction: args.direction ?? "forward",
              index: args.index ?? 0,
            });
            const c = data.container ?? {};
            return {
              summary:
                `Scroll ${data.direction} on ${c.cls ?? "container"}` +
                `${c.viewId ? ` (${c.viewId})` : ""} at [${c.bounds}] — ` +
                `${data.scrolled ? "the container reported movement" : "the container refused to scroll"}.`,
            };
          }

          case "waitstable": {
            const data = await phone.call("waitstable", {
              timeoutMs: args.timeoutMs ?? 5000,
              stableFrames: args.stableFrames ?? 2,
            });
            return {
              summary: data.stable
                ? `UI settled after ${data.elapsedMs} ms (${data.samples} samples).`
                : `UI was still changing after ${data.elapsedMs} ms — expect a transitional screen.`,
            };
          }

          case "find": {
            const data = await phone.call("find", {
              selector: args.selector ?? {},
              max: args.max ?? 10,
            });
            if (data.count === 0) {
              return { summary: `No widget matched. Scanned ${data.scanned} nodes.` };
            }
            const lines = data.matches.map((m, i) => {
              const centre = Array.isArray(m.center) ? m.center : [0, 0];
              const label = m.text || m.desc || m.viewId || m.cls;
              const id = m.viewId !== "" && m.viewId !== undefined ? ` id=${m.viewId}` : "";
              return (
                `${i + 1}. ${m.cls} "${label}"${id} @(${centre[0]},${centre[1]})` +
                `${m.clickable ? " [clickable]" : ""}${m.editable ? " [editable]" : ""}`
              );
            });
            return {
              summary:
                `${data.count} match(es) from ${data.scanned} nodes` +
                `${data.truncated ? " (scan truncated)" : ""}:\n${lines.join("\n")}\n` +
                "Tap using the @(x,y) centre directly.",
            };
          }

          case "findtext": {
            if (typeof args.text !== "string" || args.text === "") {
              throw new Error("phone_agent: findtext requires a text value");
            }
            const data = await phone.call("findtext", {
              text: args.text,
              mode: args.mode ?? "contains",
              source: args.source ?? "auto",
              tappable: args.tappable !== false,
              max: args.max ?? 5,
            });
            if (data.count === 0) {
              return {
                summary:
                  `No "${args.text}" found via ${data.source} (mode ${data.mode}). ` +
                  "Try source:'ocr', or observe the screen to see what is actually there.",
              };
            }
            const lines = data.matches.map((m, i) => {
              const centre = Array.isArray(m.center) ? m.center : [0, 0];
              const target = m.tapTarget;
              const aim = target !== undefined && Array.isArray(target.center)
                ? `tap @(${target.center[0]},${target.center[1]}) [${target.cls}]`
                : `tap @(${centre[0]},${centre[1]}) (no clickable ancestor)`;
              return `${i + 1}. "${m.text}" via ${m.from} -> ${aim}`;
            });
            return {
              summary:
                `Found ${data.count} via ${data.source}:\n${lines.join("\n")}\n` +
                "Tap the resolved tapTarget centre, not the raw text centre.",
            };
          }

          case "ocr": {
            const data = await phone.call("ocr", { region: args.region });
            if (data.blockCount === 0) return { summary: "OCR found no text on screen." };
            const lines = data.blocks.slice(0, 25).map((b, i) => {
              const centre = Array.isArray(b.center) ? b.center : [0, 0];
              const text = String(b.text).replace(/\n/g, " / ");
              return `${i + 1}. "${text}" @(${centre[0]},${centre[1]})`;
            });
            return {
              summary: `OCR: ${data.blockCount} block(s), ${data.lineCount} line(s)\n${lines.join("\n")}`,
            };
          }

          case "findcolor": {
            if (typeof args.color !== "string") {
              throw new Error("phone_agent: findcolor requires a color such as #FF5722");
            }
            const tol = args.tolerance ?? 16;
            const data = await phone.call("findcolor", {
              color: args.color,
              tolerance: tol,
              region: args.region,
              maxClusters: args.max ?? 20,
            });
            if (data.count === 0) {
              return { summary: `No pixel matched ${args.color} (per-channel tolerance ${tol}).` };
            }
            const lines = data.clusters.map((c, i) => {
              const centre = Array.isArray(c.center) ? c.center : [0, 0];
              return `${i + 1}. @(${centre[0]},${centre[1]}) · ${c.pixels} px · bounds [${c.bounds}]`;
            });
            return {
              summary:
                `${data.count} matching pixel(s) across ${data.clusterCount} region(s):\n` +
                lines.join("\n"),
            };
          }

          case "findimage": {
            if (typeof args.template !== "string") {
              throw new Error("phone_agent: findimage requires a base64 template image");
            }
            const threshold = args.threshold ?? 0.85;
            const data = await phone.call("findimage", {
              template: args.template,
              threshold,
              region: args.region,
              maxResults: args.max ?? 5,
            });
            if (data.count === 0) {
              return { summary: `Template not found above similarity ${threshold}.` };
            }
            const lines = data.matches.map((m, i) => {
              const centre = Array.isArray(m.center) ? m.center : [0, 0];
              return `${i + 1}. @(${centre[0]},${centre[1]}) · similarity ${Number(m.similarity).toFixed(3)}`;
            });
            return {
              summary: `${data.count} match(es), coarse scale ${data.coarseScale}:\n${lines.join("\n")}`,
            };
          }

          case "tap": {
            if (args.x === undefined || args.y === undefined) {
              throw new Error("phone_agent: tap requires x and y");
            }
            const data = await phone.call("tap", {
              x: args.x,
              y: args.y,
              human: args.human !== false,
              retry: args.retry,
              minIntervalMs: args.minIntervalMs,
            });
            return {
              summary:
                `Tap (${args.x}, ${args.y}) ${data.completed ? "completed" : "was CANCELLED"}` +
                `${args.human === false ? " [mechanical]" : " [human-like]"}.`,
            };
          }

          case "longpress": {
            if (args.x === undefined || args.y === undefined) {
              throw new Error("phone_agent: longpress requires x and y");
            }
            const data = await phone.call("longpress", {
              x: args.x,
              y: args.y,
              holdMs: args.durationMs ?? 650,
            });
            return {
              summary: `Long press (${args.x}, ${args.y}) for ${data.holdMs} ms ` +
                `${data.completed ? "completed" : "was CANCELLED"}.`,
            };
          }

          case "doubletap": {
            if (args.x === undefined || args.y === undefined) {
              throw new Error("phone_agent: doubletap requires x and y");
            }
            const data = await phone.call("doubletap", {
              x: args.x,
              y: args.y,
              minIntervalMs: args.minIntervalMs,
            });
            return {
              summary:
                `Double tap (${args.x}, ${args.y}) ` +
                `${data.completed ? "completed" : "was CANCELLED"} [human-like].`,
            };
          }

          case "swipe": {
            const missing = ["x1", "y1", "x2", "y2"].filter((field) => args[field] === undefined);
            if (missing.length > 0) {
              throw new Error(`phone_agent: swipe requires ${missing.join(", ")}`);
            }
            const data = await phone.call("swipe", {
              x1: args.x1,
              y1: args.y1,
              x2: args.x2,
              y2: args.y2,
              durationMs: args.durationMs ?? 320,
              human: args.human !== false,
            });
            return {
              summary:
                `Swipe (${args.x1}, ${args.y1}) -> (${args.x2}, ${args.y2}) over ` +
                `${data.durationMs} ms ${data.completed ? "completed" : "was CANCELLED"}` +
                `${data.human ? " [human-like]" : " [mechanical straight]"}.`,
            };
          }

          case "text": {
            if (typeof args.text !== "string" || args.text === "") {
              throw new Error("phone_agent: text requires a non-empty text value");
            }
            const data = await phone.call("text", {
              text: args.text,
              mode: args.textMode ?? "replace",
              typing: args.typing ?? "instant",
            });
            return {
              summary: data.cleared !== undefined
                ? `Field cleared: ${data.cleared}.`
                : data.inserted
                  ? `Typed ${data.length} characters (${data.mode}, ${data.typing}).`
                  : "Typing was refused — focus an editable field first (tap it, then observe).",
            };
          }

          case "key": {
            if (typeof args.key !== "string") throw new Error("phone_agent: key requires a key name");
            const data = await phone.call("key", { key: args.key });
            return { summary: `${args.key} ${data.performed ? "sent" : "was refused by the system"}.` };
          }

          case "launch": {
            if (typeof args.package !== "string" || args.package === "") {
              throw new Error("phone_agent: launch requires a package name");
            }
            const data = await phone.call("launch", {
              package: args.package,
              fresh: args.fresh !== false,
            });
            if (data.started === false) {
              return {
                summary:
                  `FAILED to start ${data.launched} — the start was blocked (foreground is ` +
                  `still "${data.foreground}"). ${data.hint}`,
              };
            }
            return {
              summary:
                `Launched ${data.launched}` +
                `${data.fresh ? " from a clean task" : " (resuming previous state)"}.`,
            };
          }

          case "wait": {
            const data = await phone.call("wait", { ms: args.ms ?? 800 });
            return { summary: `Waited ${data.waitedMs} ms.` };
          }

          case "volume": {
            const data = await phone.call("volume", {
              stream: args.stream ?? "music",
              action: args.volumeAction ?? "up",
              level: args.level,
            });
            return { summary: `Volume now ${data.current}/${data.max}.` };
          }

          case "brightness": {
            if (args.percent === undefined && args.level === undefined) {
              throw new Error("phone_agent: brightness requires percent or level");
            }
            const data = await phone.call("brightness", {
              percent: args.percent,
              level: args.level,
            });
            return { summary: `Brightness set to ${data.percent}% (level ${data.level}).` };
          }

          default:
            throw new Error(`phone_agent: unsupported action "${args.action}"`);
        }
      },
    }),
  );

  ctx.effect(() => () => {
    link?.dispose();
    link = null;
  });
}
