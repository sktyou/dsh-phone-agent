#!/usr/bin/env node
/**
 * MCP server for DSH Phone Agent.
 *
 * Exposes the phone as Model Context Protocol tools so any MCP client — Claude Code,
 * Cursor, Codex, Windsurf, Antigravity — can drive a real device, not just DSH.
 *
 * Transport is stdio with JSON-RPC 2.0 framing, which is what MCP uses for local
 * servers. Zero dependencies: the protocol is small enough that a library would add
 * more surface than it removes, and this runs inside other people's editors where a
 * broken dependency is expensive.
 *
 *   node pc/mcp-server/index.mjs --host 192.168.12.138 --port 7912
 *
 * The phone side already speaks a line-delimited JSON protocol over TCP; this file
 * translates MCP calls into those commands and shapes the results for a model.
 */

import net from "node:net";

function argOf(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const HOST = argOf("host", process.env.DSH_PHONE_HOST || "192.168.12.138");
const PORT = Number(argOf("port", process.env.DSH_PHONE_PORT || "7912"));
/** The phone's HTTP console, used for the version hint in --selftest. */
const HOST_PORT = argOf("http-port", process.env.DSH_PHONE_HTTP_PORT || "7913");
const TOKEN = argOf("token", process.env.DSH_PHONE_TOKEN || "");
const CALL_TIMEOUT = Number(argOf("timeout", "120000"));

let nextId = 1;

/** Send one command and resolve its reply. */
function callPhone(command, timeoutMs = CALL_TIMEOUT) {
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
    socket.on("connect", () => {
      const payload = TOKEN ? { ...command, token: TOKEN } : command;
      socket.write(JSON.stringify({ id: nextId++, ...payload }) + "\n");
    });
    socket.on("data", (chunk) => {
      buffer += chunk.toString("utf8");
      const nl = buffer.indexOf("\n");
      if (nl < 0) return;
      try {
        finish(null, JSON.parse(buffer.slice(0, nl)));
      } catch (e) {
        finish(new Error(`手机返回了无法解析的内容: ${buffer.slice(0, 200)}`));
      }
    });
    socket.on("timeout", () =>
      finish(new Error(`手机 ${HOST}:${PORT} 在 ${timeoutMs}ms 内没有响应`)));
    socket.on("error", (e) =>
      finish(new Error(`连接手机失败 (${HOST}:${PORT}): ${e.message}`)));
    socket.on("close", () => finish(new Error("连接被关闭")));
  });
}

/** Run a command, throwing a readable error when the phone reports failure. */
async function run(command) {
  const reply = await callPhone(command);
  if (!reply.ok) {
    const detail = reply.error || reply.code || "未知错误";
    throw new Error(`${command.cmd} 失败: ${detail}`);
  }
  return reply.data;
}

const text = (s) => ({ content: [{ type: "text", text: typeof s === "string" ? s : JSON.stringify(s, null, 2) }] });

const json = (o) => text(JSON.stringify(o, null, 2));

/** Screenshot as an MCP image block, so the model actually sees the screen. */
async function imageResult(command, note) {
  const data = await run(command);
  const content = [];
  if (note) content.push({ type: "text", text: note });
  content.push({
    type: "image",
    data: data.image,
    mimeType: data.format === "png" ? "image/png" : "image/jpeg",
  });
  return { content };
}

/**
 * Tool definitions.
 *
 * Kept deliberately close to the phone's own command set rather than inventing a
 * higher-level abstraction: the caller is usually a capable model, and hiding the
 * primitives behind task-shaped tools removes the control it needs when the happy
 * path does not apply.
 */
const TOOLS = [
  {
    name: "phone_status",
    description: "设备状态与权限自检。返回型号、屏幕、电量、无障碍是否可用,以及 12 项能力检查。第一次连接时先调用它。",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    handler: async () => {
      const [info, perms] = await Promise.all([
        run({ cmd: "info" }),
        run({ cmd: "perms" }),
      ]);
      return json({ info, permissions: perms });
    },
  },
  {
    name: "phone_screenshot",
    description:
      "截取当前屏幕。返回图片,并附带坐标换算元数据(screenWidth/imageWidth/regionOrigin)。" +
      "scale 越小越快(0.35 约 300ms,1.0 约 1200ms);region 可以只截列表区域,减少数据量。",
    inputSchema: {
      type: "object",
      properties: {
        scale: { type: "number", description: "缩放比例 0.05-1,默认 0.5" },
        maxWidth: { type: "number", description: "输出图片的宽度上限,与 scale 二选一" },
        quality: { type: "number", description: "JPEG 质量 1-100,默认 85" },
        region: {
          type: "array", items: { type: "number" },
          description: "可选 [left, top, right, bottom],只截取该区域",
        },
      },
      additionalProperties: false,
    },
    handler: async (a) => {
      const cmd = {
        cmd: "screenshot",
        scale: a.scale ?? 0.5,
        quality: a.quality ?? 85,
        format: "jpeg",
      };
      if (a.maxWidth) cmd.maxWidth = a.maxWidth;
      if (a.region) cmd.region = a.region;
      const data = await run(cmd);
      // The geometry rides along as text: without it a model can see the picture but
      // cannot turn a pixel in it back into a coordinate it is allowed to tap.
      const meta =
        `图像 ${data.imageWidth}x${data.imageHeight} · 屏幕 ${data.screenWidth}x${data.screenHeight}\n` +
        `换算: 手机坐标 = 图像坐标 × ${data.imageToScreenX?.toFixed(4) ?? "?"} + 区域原点 ${JSON.stringify(data.regionOrigin ?? [0, 0])}`;
      return {
        content: [
          { type: "text", text: meta },
          { type: "image", data: data.image, mimeType: data.imageFormat === "png" ? "image/png" : "image/jpeg" },
        ],
      };
    },
  },
  {
    name: "phone_uitree",
    description:
      "读取界面节点树。返回控件层级,含 viewId / text / desc / 是否可点。" +
      "**注意看返回里的 `uiTreeTextRate`**:自绘界面(Flutter / Compose / WebView / 小游戏)" +
      "的节点树可能只有十几个容器、零文本(viewId 也极少,常见只有系统框架 id)," +
      "这时选择器基本找不到东西——不是元素不存在,是控件树根本没描述它。" +
      "返回里会给出提示,看到就改用 ocr / findtext / sweep 这类基于画面的工具。",
    inputSchema: {
      type: "object",
      properties: {
        interactiveOnly: { type: "boolean", description: "只返回可交互节点,默认 false" },
      },
      additionalProperties: false,
    },
    handler: async (a) => json(await run({ cmd: "uitree", interactiveOnly: a.interactiveOnly ?? false })),
  },
  {
    name: "phone_locate",
    description:
      "定位元素。传入一个词(文字、viewId、#颜色),内部按 viewId → text → 包含 → desc → OCR → 颜色 " +
      "依次尝试,返回命中的策略、坐标和置信度。优先用这个而不是直接猜坐标。",
    inputSchema: {
      type: "object",
      properties: {
        target: { type: "string", description: "要找的东西:文字、viewId 或 #RRGGBB 颜色" },
        strategies: {
          type: "array", items: { type: "string" },
          description: "可选,限定尝试顺序。默认全部",
        },
        region: { type: "array", items: { type: "number" }, description: "可选搜索区域" },
      },
      required: ["target"],
      additionalProperties: false,
    },
    handler: async (a) => {
      const cmd = { cmd: "locate", target: a.target };
      if (a.strategies) cmd.strategies = a.strategies;
      if (a.region) cmd.region = a.region;
      return json(await run(cmd));
    },
  },
  {
    name: "phone_tap",
    description:
      "点击坐标。返回里带 safety 字段,四档判定:\n" +
      "· `ok` 顶层或其祖先可点 —— 正常\n" +
      "· `obscured` 被不可点覆盖层挡住(水印/蒙层),或自绘界面里无障碍看不到目标 —— **照常执行**,触摸通常穿透\n" +
      "· `scrollable` 命中可滚动容器 —— 点击无效,**拖动有效**\n" +
      "· `empty` 顶层是有内容的节点但不可点 —— 这才是真的点了没用\n" +
      "`abortOnUnsafe:true` 只在 `empty` / `no-root` 时拦下动作;" +
      "ok / obscured / scrollable 都算安全,不会拦截。",
    inputSchema: {
      type: "object",
      properties: {
        x: { type: "number" },
        y: { type: "number" },
        abortOnUnsafe: { type: "boolean", description: "为 true 时,不安全的位置直接拒绝执行" },
      },
      required: ["x", "y"],
      additionalProperties: false,
    },
    handler: async (a) => json(await run({ cmd: "tap", x: a.x, y: a.y, abortOnUnsafe: a.abortOnUnsafe ?? false })),
  },
  {
    name: "phone_swipe",
    description:
      "滑动。滑完会在末尾停顿约 300ms 再抬手,所以落点是准的(不会因惯性冲过头)。" +
      "durationMs 越短越快,但短于 400ms 时会冲得更远;需要精确落点用 600-1200。",
    inputSchema: {
      type: "object",
      properties: {
        x1: { type: "number" }, y1: { type: "number" },
        x2: { type: "number" }, y2: { type: "number" },
        durationMs: { type: "number", description: "默认 500" },
      },
      required: ["x1", "y1", "x2", "y2"],
      additionalProperties: false,
    },
    handler: async (a) => json(await run({
      cmd: "swipe", x1: a.x1, y1: a.y1, x2: a.x2, y2: a.y2, durationMs: a.durationMs ?? 500,
    })),
  },
  {
    name: "phone_swipe_measure",
    description:
      "滑动并测量内容实际滚动了多少。想知道滑动是否达到预期时用这个 —— " +
      "swipe 返回 completed 只代表手势被接受,不代表内容真的滚了。",
    inputSchema: {
      type: "object",
      properties: {
        x1: { type: "number" }, y1: { type: "number" },
        x2: { type: "number" }, y2: { type: "number" },
        durationMs: { type: "number" },
      },
      required: ["x1", "y1", "x2", "y2"],
      additionalProperties: false,
    },
    handler: async (a) => json(await run({
      cmd: "swipemeasure", x1: a.x1, y1: a.y1, x2: a.x2, y2: a.y2,
      durationMs: a.durationMs ?? 500, settleMs: 900,
    })),
  },
  {
    name: "phone_text",
    description: "在当前焦点输入文本。多数输入框需要先点击获得焦点。",
    inputSchema: {
      type: "object",
      properties: { text: { type: "string" } },
      required: ["text"],
      additionalProperties: false,
    },
    handler: async (a) => json(await run({ cmd: "text", text: a.text })),
  },
  {
    name: "phone_key",
    description:
      "按系统键。支持 HOME / BACK / RECENTS / NOTIFICATIONS / QUICK_SETTINGS / POWER / " +
      "LOCK / SCREENSHOT / VOLUME_UP / VOLUME_DOWN。\n" +
      "**返回格式与其他工具不同**:成功时返回 `{label:\"最近任务\"}` 而不是 `{completed:true}` —— " +
      "系统键没有可确认的手势结果,能回报的只有「这个键被发出去了」。",
    inputSchema: {
      type: "object",
      properties: { key: { type: "string" } },
      required: ["key"],
      additionalProperties: false,
    },
    handler: async (a) => json(await run({ cmd: "key", key: a.key })),
  },
  {
    name: "phone_find_text",
    description:
      "按文字查找控件,融合控件树与 OCR,并自动解析到可点击的父节点。" +
      "比 phone_locate 更细,可以指定只看控件树或只看 OCR。",
    inputSchema: {
      type: "object",
      properties: {
        text: { type: "string" },
        mode: { type: "string", enum: ["contains", "exact", "regex"] },
        source: { type: "string", enum: ["auto", "node", "ocr"] },
      },
      required: ["text"],
      additionalProperties: false,
    },
    handler: async (a) => json(await run({
      cmd: "findtext", text: a.text,
      mode: a.mode ?? "contains", source: a.source ?? "auto", tappable: true,
    })),
  },
  {
    name: "phone_ocr",
    description:
      "对当前屏幕做本地中文 OCR。适合控件树为空的界面(游戏、Canvas、Flutter)。" +
      "返回**结构化行**(含 bounds 和 confidence),不是纯文本 —— 多列布局下" +
      "拿坐标自己配对才可靠。",
    inputSchema: {
      type: "object",
      properties: {
        region: { type: "array", items: { type: "number" } },
        scale: { type: "number", description: "识别前放大倍数,默认 1.0. 小字场景试 1.5" },
        enhance: {
          type: "boolean",
          description: "对比度增强后二次识别并合并 —— 彩色小字压在照片上时明显提升(实测总行 +38%)",
        },
        engine: {
          type: "string", enum: ["mlkit", "ppocr"],
          description:
            "识别引擎。mlkit 快(1.5s)但小字彩色文本易错且不确定;" +
            "ppocr 慢(2.7s)但置信度 0.99 且同图同结果。采文字用 ppocr。",
        },
      },
      additionalProperties: false,
    },
    handler: async (a) => {
      const cmd = { cmd: "ocr" };
      if (a.region) cmd.region = a.region;
      if (a.scale) cmd.scale = a.scale;
      if (a.enhance) cmd.enhance = true;
      if (a.engine) cmd.engine = a.engine;
      const d = await run(cmd);
      return {
        content: [
          { type: "text", text: `识别到 ${d.lineCount ?? 0} 行 / ${d.elementCount ?? 0} 个元素` +
              (d.ocrEnhanced ? " (增强+合并)" : "") },
          // Everything the phone returned, not a hand-picked subset. The bridge is a
          // translator: if it silently drops a field the app added, the caller has no
          // way to know the data ever existed.
          { type: "text", text: JSON.stringify({
            fullText: d.fullText,
            lineCount: d.lineCount,
            elementCount: d.elementCount,
            blockCount: d.blockCount,
            ocrScale: d.ocrScale,
            ocrEnhanced: d.ocrEnhanced,
            ocrLines: d.ocrLines,
          }) },
        ],
      };
    },
  },
  {
    name: "phone_sweep",
    description:
      "滚动并识别整屏长列表,自动去重、自动判断到底。一次调用读完整个菜单/列表。" +
      "返回**结构化行**(ocrLines,含 bounds/capture/confidence),按坐标配对才可靠 —— " +
      "多列布局下相邻条目会互相穿插,用纯文本配对是在掷骰子。region 用来排除侧边导航。",
    inputSchema: {
      type: "object",
      properties: {
        scrolls: { type: "number", description: "最多滚动几次,默认 40" },
        distance: { type: "number", description: "每次滚动像素,默认 1100" },
        region: { type: "array", items: { type: "number" }, description: "识别区域" },
        ocrScale: { type: "number", description: "识别前放大倍数,默认 1.5" },
        ocrEnhance: { type: "boolean", description: "对比度增强二次识别并合并,默认 true" },
      },
      additionalProperties: false,
    },
    handler: async (a) => {
      const cmd = {
        cmd: "sweep", scrolls: a.scrolls ?? 40, distance: a.distance ?? 1100,
        perCapture: 2, settleMs: 700, durationMs: 420,
      };
      if (a.region) cmd.region = a.region;
      if (a.ocrScale) cmd.ocrScale = a.ocrScale;
      if (a.ocrEnhance === false) cmd.ocrEnhance = false;
      const d = await run(cmd);
      return {
        content: [
          {
            type: "text",
            text: `滚动 ${d.scrolls} 次,识别 ${d.lineCount} 行,停止原因 ${d.stopReason}`,
          },
          // Both forms are forwarded. `lines` is what an older caller expects;
          // `ocrLines` carries the geometry that makes price-to-product pairing
          // possible, and unlike `lines` it is NOT de-duplicated by text — a menu
          // repeats ¥7.8 twenty times and each occurrence is a position, not a word.
          { type: "text", text: JSON.stringify({
            scrolls: d.scrolls,
            lineCount: d.lineCount,
            stopReason: d.stopReason,
            stepsPerCapture: d.stepsPerCapture,
            ocrScale: d.ocrScale,
            lines: d.lines,
            ocrLines: d.ocrLines,
          }) },
        ],
      };
    },
  },
  {
    name: "phone_wait",
    description:
      "等待某个文字出现(mode=text/node)或消失(mode=gone)。" +
      "**用它代替 sleep** —— sleep 永远在两个方向上都错:太短则下个动作打进还没渲染的页面," +
      "太长则每一步都在为最坏情况付费。超时返回 appeared:false,不是错误。",
    inputSchema: {
      type: "object",
      properties: {
        target: { type: "string", description: "要等待的文字" },
        mode: {
          type: "string", enum: ["text", "node", "gone"],
          description: "text=树+OCR(默认) / node=仅控件树(更快) / gone=等它消失",
        },
        timeoutMs: { type: "number", description: "默认 8000" },
      },
      required: ["target"],
      additionalProperties: false,
    },
    handler: async (a) => json(await run({
      cmd: "wait", target: a.target, mode: a.mode ?? "text", timeoutMs: a.timeoutMs ?? 8000,
    })),
  },
  {
    name: "phone_sequence",
    description:
      "一次执行多个动作,中间不做网络往返(微秒级间隔而不是往返延迟)。" +
      "用于处理会自动消失的控件:toast、淡出的控制栏、自动关闭的弹窗。",
    inputSchema: {
      type: "object",
      properties: {
        steps: {
          type: "array",
          description: "每步是一个普通命令。{\"action\":\"tap\",\"x\":1,\"y\":2} 或 {\"action\":\"wait\",\"ms\":300}",
          items: { type: "object" },
        },
        stopOnError: { type: "boolean", description: "默认 true" },
      },
      required: ["steps"],
      additionalProperties: false,
    },
    handler: async (a) => json(await run({
      cmd: "sequence", steps: a.steps, stopOnError: a.stopOnError ?? true,
    })),
  },
  {
    name: "phone_incidents",
    description:
      "查询未解决的执行事故。动作失败会开启一条事故记录,直到同类动作成功才关闭。" +
      "用它可以知道之前是否留下了未完成的事(没关掉的弹窗、没通过的权限提示)。",
    inputSchema: {
      type: "object",
      properties: { clear: { type: "boolean", description: "先清空再返回" } },
      additionalProperties: false,
    },
    handler: async (a) => json(await run({ cmd: "incidents", clear: a.clear ?? false })),
  },
  {
    name: "phone_launch",
    description: "启动应用。fresh=true 会先清空任务栈,从首页开始。",
    inputSchema: {
      type: "object",
      properties: {
        package: { type: "string" },
        fresh: { type: "boolean", description: "默认 true" },
      },
      required: ["package"],
      additionalProperties: false,
    },
    handler: async (a) => json(await run({ cmd: "launch", package: a.package, fresh: a.fresh ?? true })),
  },
  {
    name: "phone_apps",
    description: "列出已安装应用。可按名称或包名过滤。",
    inputSchema: {
      type: "object",
      properties: { query: { type: "string" } },
      additionalProperties: false,
    },
    handler: async (a) => {
      const cmd = { cmd: "apps" };
      if (a.query) cmd.query = a.query;
      return json(await run(cmd));
    },
  },
  {
    name: "phone_find_image",
    description: "在当前屏幕上找一张模板图片。template 是 base64 编码的 PNG/JPEG。",
    inputSchema: {
      type: "object",
      properties: {
        template: { type: "string", description: "base64(不含 data: 前缀)" },
        threshold: { type: "number", description: "相似度 0-1,默认 0.85" },
      },
      required: ["template"],
      additionalProperties: false,
    },
    handler: async (a) => json(await run({
      cmd: "findimage", template: a.template, threshold: a.threshold ?? 0.85, max: 20,
    })),
  },
  {
    name: "phone_raw",
    description:
      "执行任意手机命令,返回原始 JSON。上面这些工具覆盖不了时用这个。" +
      "完整命令列表见 phone_status 的返回或项目里的 docs/PROTOCOL.md。",
    inputSchema: {
      type: "object",
      properties: { command: { type: "object", description: "完整的命令对象,含 cmd 字段" } },
      required: ["command"],
      additionalProperties: false,
    },
    handler: async (a) => json(await run(a.command)),
  },
];

const SERVER_INFO = { name: "dsh-phone-agent", version: "0.1.0" };

/**
 * `--selftest` talks to the phone once and prints what it found.
 *
 * A client-side check that a config file parses proves nothing about reachability;
 * this proves the whole path — address, port, token, accessibility grant — with one
 * command, before any IDE is involved.
 */
if (process.argv.includes("--selftest")) {
  const line = (s) => process.stdout.write(s + "\n");
  try {
    line(`连接 ${HOST}:${PORT} …`);
    const info = await run({ cmd: "info" });
    line(`  型号     ${info.model ?? "?"}`);
    line(`  屏幕     ${info.screenWidth}x${info.screenHeight}`);
    line(`  前台     ${info.foregroundPackage || "(无)"}`);
    line(`  无障碍   ${info.accessibility ? "已连接" : "未连接 ← 手机上需要开启"}`);
    // The app version is how a stale bridge is detected. This file is only a
    // translator for the phone's API; when a command changes shape it keeps
    // connecting and starts failing on one call, which reads as a phone bug.
    line(`  App 版本 ${info.appVersion || "?"} (build ${info.appVersionCode ?? "?"})`);
    const perms = await run({ cmd: "perms" });
    line(`  权限     ${perms.okCount}/${perms.count} 项正常`);
    for (const item of (perms.items ?? []).filter((i) => !i.ok)) {
      line(`             ✗ ${item.name}: ${item.detail}`);
    }
    line(`\n链路正常,可以把下面的配置加进 IDE:`);
    line(JSON.stringify(
      { mcpServers: { phone: { command: "node", args: ["<此文件路径>", "--host", HOST] } } },
      null, 2,
    ));
    line(`\n提示: 手机 App 更新后请重新下载本文件 —— 打开手机上的`);
    line(`      http://${HOST}:${HOST_PORT}/mcp/ 对比版本号。`);
    process.exit(0);
  } catch (e) {
    line(`\n❌ ${e.message}`);
    line(`\n排查:1) 手机与电脑在同一局域网  2) 手机上无障碍已开启  3) IP 是否变化`);
    process.exit(1);
  }
}

// ---------------------------------------------------------------- JSON-RPC

function send(message) {
  process.stdout.write(JSON.stringify(message) + "\n");
}

function reply(id, result) {
  send({ jsonrpc: "2.0", id, result });
}

function replyError(id, code, message) {
  send({ jsonrpc: "2.0", id, error: { code, message } });
}

async function handle(message) {
  const { id, method, params } = message;

  switch (method) {
    case "initialize":
      reply(id, {
        // Echo the client's requested version when it sent one; MCP expects the
        // server to answer with a version it supports, and clients handle a
        // mismatch by downgrading themselves.
        protocolVersion: params?.protocolVersion ?? "2024-11-05",
        capabilities: { tools: { listChanged: false } },
        serverInfo: SERVER_INFO,
      });
      return;

    case "notifications/initialized":
    case "initialized":
      return;   // notification, no reply

    case "tools/list":
      reply(id, {
        tools: TOOLS.map((t) => ({
          name: t.name,
          description: t.description,
          inputSchema: t.inputSchema,
        })),
      });
      return;

    case "tools/call": {
      const tool = TOOLS.find((t) => t.name === params?.name);
      if (!tool) {
        replyError(id, -32602, `未知工具: ${params?.name}`);
        return;
      }
      try {
        const result = await tool.handler(params.arguments ?? {});
        reply(id, result);
      } catch (e) {
        // Tool failures are reported as content, not as protocol errors: the model
        // needs to see "the phone said no" and adapt, which a JSON-RPC error hides.
        reply(id, {
          content: [{ type: "text", text: `❌ ${e.message}` }],
          isError: true,
        });
      }
      return;
    }

    case "ping":
      reply(id, {});
      return;

    default:
      if (id !== undefined) replyError(id, -32601, `不支持的方法: ${method}`);
  }
}

// Newline-delimited JSON on stdin. A partial line is buffered until its newline
// arrives, which is what makes multi-megabyte screenshot payloads safe.
let inputBuffer = "";
let inFlight = 0;
let stdinClosed = false;

/**
 * Exit only once nothing is outstanding.
 *
 * `echo '{...}' | node server.mjs` closes stdin the instant the line is read, which
 * used to kill the process before the phone had answered — the reply was written to a
 * stdout nobody was reading any more, so the documented one-liner always printed
 * nothing. A pipe delivering a single request is a legitimate way to use this server,
 * so EOF now means "no more requests", not "stop working".
 */
function exitWhenDrained() {
  if (!stdinClosed) return;
  if (inFlight === 0) process.exit(0);
  setTimeout(exitWhenDrained, 100);
}

process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
  inputBuffer += chunk;
  let nl;
  while ((nl = inputBuffer.indexOf("\n")) >= 0) {
    const line = inputBuffer.slice(0, nl).trim();
    inputBuffer = inputBuffer.slice(nl + 1);
    if (!line) continue;
    let message;
    try {
      message = JSON.parse(line);
    } catch {
      continue;   // not JSON: ignore rather than crash the server
    }
    inFlight++;
    handle(message)
      .catch((e) => {
        if (message.id !== undefined) replyError(message.id, -32603, e.message);
      })
      .finally(() => {
        inFlight--;
        exitWhenDrained();
      });
  }
});

process.stdin.on("end", () => {
  stdinClosed = true;
  exitWhenDrained();
});

process.stderr.write(
  `[dsh-phone-agent] MCP server ready → phone at ${HOST}:${PORT}` +
  (TOKEN ? " (token auth)" : "") + "\n",
);
