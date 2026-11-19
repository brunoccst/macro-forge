// Macro relay: gives the phone connector a public HTTPS address.
//
// Claude's custom connectors call a remote MCP server from Anthropic's cloud, and a phone
// has no public address. So the macro app keeps a WebSocket open to this Worker
// (GET /phone), and the Worker passes every MCP request Claude sends (POST /mcp) to the
// phone and the phone's answer back. It logs no request bodies and stores only the phone's
// tool list, so that claude.ai can still add and check the connector while the phone is off.
//
// Secrets (Worker settings -> Variables and Secrets), both made by the app:
//   PHONE_TOKEN      the app sends it to open its socket
//   CONNECTOR_TOKEN  Claude sends it: in the connector address (/mcp/<token>) or as a Bearer header

import { PHONE_TIMEOUT_MS, OFFLINE, safeEqual, rpcError, isNotification, bearer, text, offlineAnswer, rpcMethod } from "./relay.js";

export default {
  async fetch(request, env) {
    if (!env.PHONE_TOKEN || !env.CONNECTOR_TOKEN) return text(500, "Set PHONE_TOKEN and CONNECTOR_TOKEN in the Worker's settings (Variables and Secrets).");
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean);
    const relay = () => env.RELAY.get(env.RELAY.idFromName("phone"));

    if (parts[0] === "phone" && parts.length === 1) {
      if (!safeEqual(bearer(request), env.PHONE_TOKEN)) return text(401, "unauthorized");
      if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") return text(426, "WebSocket only");
      return relay().fetch(request);
    }

    if (parts[0] === "mcp" && parts.length <= 2) {
      const token = parts[1] || bearer(request);
      if (!safeEqual(token, env.CONNECTOR_TOKEN)) return text(401, "unauthorized");
      // no server-to-client stream and no sessions: Streamable HTTP lets a server answer 405 here
      if (request.method !== "POST") return new Response(null, { status: 405, headers: { Allow: "POST" } });
      return relay().fetch(new Request("https://relay/mcp", { method: "POST", body: await request.text() }));
    }

    if (parts.length === 0) return text(200, "Macro relay");
    return text(404, "not found");
  },
};

/**
 * One instance for the one phone: holds its socket (with the hibernation API, so an idle
 * socket costs nothing) and pairs each MCP request with the phone's answer by id.
 */
export class PhoneRelay {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.pending = new Map();
  }

  async fetch(request) {
    const path = new URL(request.url).pathname;
    if (path === "/phone") {
      for (const old of this.state.getWebSockets()) {
        try { old.close(4000, "replaced by a new connection"); } catch { /* already closed */ }
      }
      const pair = new WebSocketPair();
      // tagged with the time it opened: requests go to the newest one (see phone())
      this.state.acceptWebSocket(pair[1], [`opened:${Date.now()}`]);
      return new Response(null, { status: 101, webSocket: pair[0] });
    }
    return this.mcp(await request.text());
  }

  /**
   * The phone's socket: the newest open one. A socket the app never closed (the app was killed,
   * e.g. by an update) can stay listed as CLOSING long after close(), until Cloudflare notices
   * the connection is gone; sending to it gets no answer, so it must never be picked.
   */
  phone() {
    const open = this.state.getWebSockets().filter((ws) => ws.readyState === undefined || ws.readyState === 1);
    const opened = (ws) => {
      const tag = (this.state.getTags?.(ws) || []).find((t) => t.startsWith("opened:"));
      return tag ? Number(tag.slice(7)) : 0;
    };
    return open.reduce((best, ws) => (best === null || opened(ws) >= opened(best) ? ws : best), null);
  }

  async mcp(body) {
    const phone = this.phone();
    if (!phone) return this.offline(body);
    const id = crypto.randomUUID();
    const answer = new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        resolve({ timeout: true });
      }, PHONE_TIMEOUT_MS);
      this.pending.set(id, { resolve, timer, ws: phone });
    });
    try {
      phone.send(JSON.stringify({ id, body }));
    } catch {
      this.settle(id, { offline: true });
    }
    const r = await answer;
    if (r.timeout) return rpcError(body, "The phone didn't answer in time (it may be waiting for you to Allow or Deny a tap on the phone).");
    if (r.offline) return this.offline(body);
    if (r.body === null || r.body === undefined || r.body === "") {
      // a request (not a notification) with no answer: say so, or Claude waits until it times out
      if (!isNotification(body)) return rpcError(body, "The phone could not answer this request: see the macro app's log (System) for the error.");
      return new Response(null, { status: 202 });
    }
    if (rpcMethod(body) === "tools/list") {
      try {
        const tools = JSON.parse(r.body)?.result?.tools;
        if (Array.isArray(tools)) await this.state.storage.put("tools", tools);
      } catch { /* not JSON: nothing to keep */ }
    }
    return new Response(r.body, { status: 200, headers: { "Content-Type": "application/json" } });
  }

  /** With the phone off, answer the handshake (initialize, ping, tools/list) here; other calls get OFFLINE. */
  async offline(body) {
    if (isNotification(body)) return new Response(null, { status: 202 });
    const tools = await this.state.storage.get("tools");
    const answer = offlineAnswer(body, tools);
    if (answer) return new Response(answer, { status: 200, headers: { "Content-Type": "application/json" } });
    return rpcError(body, OFFLINE);
  }

  settle(id, value) {
    const p = this.pending.get(id);
    if (!p) return;
    clearTimeout(p.timer);
    this.pending.delete(id);
    p.resolve(value);
  }

  webSocketMessage(ws, message) {
    let m;
    try { m = JSON.parse(typeof message === "string" ? message : new TextDecoder().decode(message)); } catch { return; }
    if (m && typeof m.id === "string") this.settle(m.id, { body: m.body });
  }

  /** A socket closed: the requests sent on it get the offline error (not those on a newer socket). */
  webSocketClose(ws) {
    for (const [id, p] of [...this.pending]) if (!ws || p.ws === ws) this.settle(id, { offline: true });
  }

  webSocketError(ws) {
    this.webSocketClose(ws);
  }
}
