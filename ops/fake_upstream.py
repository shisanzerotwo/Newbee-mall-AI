#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
本地假上游（OpenAI 兼容）—— 专为**虚拟线程压测**而写。

为什么需要它（claude 复核 M3 压测时指出）：
  原来的压测只压只读页面，而那些页面 IO 很少、本来就不吃线程
  ——**证明不了虚拟线程的价值**。SSE 长连接（每个请求要等模型数十秒）
  才是虚拟线程的卖点场景。但真打 agnes 会被免费额度 429 限流，
  指标会被上游污染。所以这里起一个本地假上游：
  用**可控的慢速流式响应**替代真模型，从而专测「应用在慢 IO 下的并发能力」。
  （同一手法已在 OpenAiRetryBehaviorTest 里用过：JDK HttpServer 当假上游。）

用法：
    python ops/fake_upstream.py [port] [delay_ms_per_chunk] [chunks]
    默认  20199  150ms  12

要点：
  - ThreadingHTTPServer：每个请求一个线程，先返回 SSE 响应头，再逐块吐 delta，
    每块之间 sleep → 精确模拟「模型慢」。
  - 同时提供 /v1/models（部分客户端启动时会探它）。
  - 统计并发峰值并打印，便于判断瓶颈在应用还是在假上游。
"""

import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 20199
DELAY_MS = int(sys.argv[2]) if len(sys.argv) > 2 else 150
CHUNKS = int(sys.argv[3]) if len(sys.argv) > 3 else 12

_lock = threading.Lock()
_active = 0
_peak = 0
_served = 0


def _enter():
    global _active, _peak
    with _lock:
        _active += 1
        if _active > _peak:
            _peak = _active
        return _active


def _leave():
    global _active, _served
    with _lock:
        _active -= 1
        _served += 1


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    # 关掉每请求的访问日志（压测时会刷屏，且拖慢假上游）
    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        if self.path.startswith("/v1/models"):
            body = json.dumps({"object": "list", "data": [
                {"id": "fake-model", "object": "model", "owned_by": "fake"}]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_error(404)

    def do_POST(self):
        if not self.path.startswith("/v1/chat/completions"):
            self.send_error(404)
            return

        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        try:
            req = json.loads(raw.decode("utf-8", "replace"))
        except Exception:
            req = {}

        streaming = bool(req.get("stream"))
        cur = _enter()
        try:
            if not streaming:
                # 非流式：也慢一点，保持与流式一致的"模型慢"特征
                time.sleep(DELAY_MS * CHUNKS / 1000.0)
                body = json.dumps({
                    "id": "fake-1", "object": "chat.completion", "created": int(time.time()),
                    "model": "fake-model",
                    "choices": [{"index": 0, "finish_reason": "stop",
                                 "message": {"role": "assistant", "content": "这是假上游的回答。"}}],
                    "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
                }).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return

            # ---- 流式：先发响应头，再慢速逐块吐 ----
            # ⚠️ 必须带 Connection: close：SSE 响应没有 Content-Length，
            #    客户端只能靠「连接关闭」判断流结束；若保持 keep-alive，curl/网关会一直等下去
            #    （实测：不加这行，curl 读满 -m 超时、应用侧报「模型流式调用超时」）。
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "close")
            self.close_connection = True
            self.end_headers()

            def send_chunk(obj):
                line = "data: " + json.dumps(obj, ensure_ascii=False) + "\n\n"
                self.wfile.write(line.encode("utf-8"))
                self.wfile.flush()

            # ⭐ 回显收到的 messages 数量与角色序列 —— 用于验证「会话记忆是否真的被注入」。
            # 假上游不读 prompt 的话，注入与不注入在客户端看来完全一样（空洞的链路验证）。
            msgs = req.get("messages") or []
            roles = ",".join(str(m.get("role")) for m in msgs)
            summary = f"[msgs={len(msgs)} roles={roles}] "

            for i in range(CHUNKS):
                text = summary if i == 0 else f"块{i} "
                send_chunk({
                    "id": "fake-1", "object": "chat.completion.chunk",
                    "created": int(time.time()), "model": "fake-model",
                    "choices": [{"index": 0, "delta": {"content": text}, "finish_reason": None}],
                })
                time.sleep(DELAY_MS / 1000.0)

            send_chunk({
                "id": "fake-1", "object": "chat.completion.chunk", "created": int(time.time()),
                "model": "fake-model",
                "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
            })
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            # 显式关闭，让客户端立刻知道流结束（而不是等到超时）
            self.close_connection = True
            try:
                self.wfile.close()
            except Exception:
                pass
        except (BrokenPipeError, ConnectionResetError):
            # 客户端提前断开（压测常见）—— 正常，不计为错误
            pass
        finally:
            _leave()
            with _lock:
                if _served % 50 == 0:
                    print(f"[fake-upstream] served={_served} active={_active} peak={_peak}", flush=True)


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    srv.daemon_threads = True
    print(f"[fake-upstream] listening on http://127.0.0.1:{PORT} "
          f"(delay={DELAY_MS}ms/chunk, chunks={CHUNKS}, "
          f"≈{DELAY_MS * CHUNKS / 1000.0:.1f}s per streaming call)", flush=True)
    srv.serve_forever()
