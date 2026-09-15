/**
 * Local web console for the DSH Phone Agent.
 *
 * A development tool, not part of the product: it lives on the PC so the page can
 * be edited and reloaded without rebuilding and reinstalling the app.
 *
 * Browsers cannot open a raw TCP socket, and the phone speaks line-delimited
 * JSON over plain TCP, so this process is the bridge. It also avoids adding a
 * WebSocket dependency — plain HTTP is enough for request/response, and the
 * screenshot endpoint streams bytes straight through.
 *
 *   node tools/webui/server.mjs [--host 192.168.12.138] [--port 7912] [--web 8099]
 */

import http from "node:http";
import net from "node:net";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));

function argOf(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const PHONE_HOST = argOf("host", "192.168.12.138");
const PHONE_PORT = Number(argOf("port", "7912"));
/** The phone's HTTP console, used only for the MJPEG stream. */
const PHONE_HTTP_PORT = Number(argOf("http-port", "7913"));
const WEB_PORT = Number(argOf("web", "8099"));

/** How long a single phone command may take before the bridge gives up. */
const COMMAND_TIMEOUT_MS = 300_000;

let nextId = 1;

/**
 * Send one command and read exactly one reply line.
 *
 * A fresh connection per command rather than a pooled socket: the phone closes
 * or resets idle connections under memory pressure, and a stale pooled socket
 * would surface as a confusing timeout on an unrelated later request.
 */
function callPhone(command) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(PHONE_PORT, PHONE_HOST);
    let buffer = "";
    let settled = false;

    const finish = (err, value) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      err ? reject(err) : resolve(value);
    };

    socket.setTimeout(COMMAND_TIMEOUT_MS);
    socket.on("connect", () => {
      socket.write(JSON.stringify({ id: nextId++, ...command }) + "\n");
    });
    socket.on("data", (chunk) => {
      buffer += chunk.toString("utf8");
      const nl = buffer.indexOf("\n");
      if (nl < 0) return;
      const line = buffer.slice(0, nl);
      try {
        finish(null, JSON.parse(line));
      } catch (e) {
        finish(new Error(`bad reply: ${line.slice(0, 200)}`));
      }
    });
    socket.on("timeout", () => finish(new Error("phone did not reply in time")));
    socket.on("error", (e) => finish(e));
    socket.on("close", () => finish(new Error("phone closed the connection")));
  });
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = "";
    req.on("data", (c) => {
      data += c;
      // Guard against a runaway body; the largest legitimate payload is a small
      // command object.
      if (data.length > 1_000_000) reject(new Error("body too large"));
    });
    req.on("end", () => resolve(data));
    req.on("error", reject);
  });
}

function sendJson(res, status, value) {
  const body = JSON.stringify(value);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
  });
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  try {
    // --- the bridge itself -------------------------------------------------
    if (url.pathname === "/rpc" && req.method === "POST") {
      const raw = await readBody(req);
      let command;
      try {
        command = JSON.parse(raw);
      } catch {
        return sendJson(res, 400, { ok: false, error: "body must be JSON" });
      }
      const started = Date.now();
      const reply = await callPhone(command);
      return sendJson(res, 200, { ...reply, elapsedMs: Date.now() - started });
    }

    /**
     * Live screen stream.
     *
     * Proxied straight through to the phone's HTTP console (7913) rather than
     * rebuilt from the raw protocol on 7912 — the phone already speaks MJPEG, and
     * piping the response keeps this side trivial. Nothing is buffered: a stream
     * that is collected before forwarding is not a stream.
     */
    if (url.pathname === "/stream") {
      const upstream = http.request(
        {
          host: PHONE_HOST,
          port: PHONE_HTTP_PORT,
          path: "/stream" + (url.search || ""),
          method: "GET",
        },
        (up) => {
          res.writeHead(up.statusCode ?? 502, up.headers);
          up.pipe(res);
        },
      );
      upstream.on("error", (e) => {
        if (!res.headersSent) sendJson(res, 502, { ok: false, error: String(e.message) });
        else res.end();
      });
      // If the browser goes away, stop pulling frames from the phone.
      req.on("close", () => upstream.destroy());
      upstream.end();
      return;
    }

    /**
     * Screenshot as raw bytes, so the page can drop it straight into an <img>.
     * Going through /rpc would mean base64 inside JSON — a third more bytes and
     * a decode step in the browser for no benefit.
     */
    if (url.pathname === "/shot") {
      const scale = Number(url.searchParams.get("scale") ?? "1");
      const quality = Number(url.searchParams.get("quality") ?? "85");
      const region = url.searchParams.get("region");
      const command = { cmd: "screenshot", scale, quality, format: "jpeg" };
      if (region) command.region = region.split(",").map(Number);

      const reply = await callPhone(command);
      if (!reply.ok) return sendJson(res, 502, reply);

      const bytes = Buffer.from(reply.data.image, "base64");
      res.writeHead(200, {
        "content-type": "image/jpeg",
        "content-length": bytes.length,
        "cache-control": "no-store",
        // Lets the page read natural dimensions and the phone geometry without a
        // second request.
        "x-phone-width": String(reply.data.screenWidth ?? ""),
        "x-phone-height": String(reply.data.screenHeight ?? ""),
        "x-image-width": String(reply.data.imageWidth ?? ""),
        "x-image-height": String(reply.data.imageHeight ?? ""),
      });
      return res.end(bytes);
    }

    // --- static page -------------------------------------------------------
    const rel = url.pathname === "/" ? "/index.html" : url.pathname;
    const file = path.join(HERE, path.normalize(rel).replace(/^([/\\])+/, ""));
    if (!file.startsWith(HERE)) return sendJson(res, 403, { ok: false, error: "forbidden" });
    if (!fs.existsSync(file) || !fs.statSync(file).isFile()) {
      return sendJson(res, 404, { ok: false, error: "not found" });
    }
    const type = file.endsWith(".html")
      ? "text/html; charset=utf-8"
      : file.endsWith(".js")
        ? "text/javascript; charset=utf-8"
        : file.endsWith(".css")
          ? "text/css; charset=utf-8"
          : "application/octet-stream";
    res.writeHead(200, { "content-type": type, "cache-control": "no-store" });
    return res.end(fs.readFileSync(file));
  } catch (e) {
    return sendJson(res, 500, { ok: false, error: String(e?.message ?? e) });
  }
});

server.listen(WEB_PORT, "127.0.0.1", () => {
  console.log(`DSH Phone Agent console → http://127.0.0.1:${WEB_PORT}`);
  console.log(`bridging to phone at ${PHONE_HOST}:${PHONE_PORT}`);
});
