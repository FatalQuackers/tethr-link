// Tethr Link relay: lets a phone and its PC reach each other through the internet when they
// can't share Wi-Fi (school or work Wi-Fi, mobile data).
//
// It only passes bytes along. The phone and PC run the same TLS connection through it as on
// Wi-Fi, with the PC's certificate pinned, so the relay can't read or change anything.
//
// Each PC has a room named by a hash of a secret only that PC and its paired phone know.
// The PC waits in its room; when the phone joins, the room tells the PC ("start") and then
// forwards binary messages both ways. One phone and one PC per room.

import { DurableObject } from "cloudflare:workers";

export class Room extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    // Keep-alive pings are answered without waking the room (free, and no duration charges).
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(request) {
    const role = new URL(request.url).searchParams.get("role");
    if (role !== "pc" && role !== "phone") return new Response("role must be pc or phone", { status: 400 });
    if (request.headers.get("Upgrade") !== "websocket") return new Response("expected a WebSocket", { status: 426 });

    // A new connection replaces an old one with the same role (a dropped network leaves stale sockets).
    // Its closing must not end the new session, so mark it first.
    for (const old of this.ctx.getWebSockets(role)) {
      try { old.serializeAttachment({ replaced: true }); old.close(4000, "replaced"); } catch {}
    }

    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server, [role]);

    if (role === "phone") {
      const pcs = this.ctx.getWebSockets("pc");
      if (pcs.length === 0) server.close(4404, "PC offline");
      else for (const pc of pcs) pc.send("start");
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  webSocketMessage(ws, message) {
    if (typeof message === "string") return; // control text ("ping" is auto-answered)
    const other = this.ctx.getTags(ws)[0] === "pc" ? "phone" : "pc";
    for (const peer of this.ctx.getWebSockets(other)) {
      try { peer.send(message); } catch {}
    }
  }

  webSocketClose(ws, code) {
    try { ws.close(code === 1005 || code === 1006 ? 1000 : code, "closed"); } catch {}
    this.endSession(ws);
  }

  webSocketError(ws) {
    this.endSession(ws);
  }

  // When either side leaves, the session is over: close the other side too.
  endSession(ws) {
    if (ws.deserializeAttachment()?.replaced) return; // a stale socket, not the live session
    const other = this.ctx.getTags(ws)[0] === "pc" ? "phone" : "pc";
    for (const peer of this.ctx.getWebSockets(other)) {
      try { peer.close(4001, "other side left"); } catch {}
    }
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const m = url.pathname.match(/^\/r\/([0-9a-f]{64})$/);
    if (!m) return new Response("Tethr Link relay. https://github.com/FatalQuackers/tethr-link\n", { headers: { "content-type": "text/plain" } });
    return env.ROOMS.get(env.ROOMS.idFromName(m[1])).fetch(request);
  },
};
