"""极简 WebSocket 客户端（和前端 ws.js 等价的实现），供各测试脚本复用。

为什么单独抽一个文件：
  t_ws_flow.py 要一口气走完"登录→发消息→多端已读→离线补拉"，
  而 t_friend_search.py 只需要"连上、发几条消息造数据"。
  两边都需要同一套 JSON 信封 + requestId 关联，抽出来避免复制两遍。

依赖：pip install websocket-client
"""

import json
import time

WS_URL = "ws://127.0.0.1:9001/im"

CMD = {
    "LOGIN_REQ": 0x01, "LOGIN_RESP": 0x02,
    "HEARTBEAT_REQ": 0x03, "HEARTBEAT_RESP": 0x04,
    "SINGLE_CHAT_REQ": 0x05, "SINGLE_CHAT_RESP": 0x06,
    "GROUP_CHAT_REQ": 0x07, "GROUP_CHAT_RESP": 0x08,
    "PUSH_MESSAGE": 0x09, "ACK_REQ": 0x0A, "ACK_RESP": 0x0B,
    "PULL_OFFLINE_REQ": 0x0C, "PULL_OFFLINE_RESP": 0x0D,
    "READ_SYNC_PUSH": 0x0E, "KICK_PUSH": 0x0F,
    "READ_SYNC_REQ": 0x10, "ERROR": 0x7F,
    # A 档新增：撤回、已读回执、群事件
    "REVOKE_REQ": 0x11, "REVOKE_RESP": 0x12, "REVOKE_PUSH": 0x13,
    "READ_RECEIPT_REQ": 0x14, "READ_RECEIPT_RESP": 0x15, "READ_RECEIPT_PUSH": 0x16,
    "GROUP_EVENT_PUSH": 0x17,
}


class Client:
    def __init__(self, token, device="web", url=WS_URL):
        self.token = token
        self.device = device
        self.url = url
        self.ws = None
        self.seq = 0
        self.backlog = []

    def connect(self):
        import websocket

        self.ws = websocket.create_connection(self.url, timeout=10)
        frame = self.request(CMD["LOGIN_REQ"], {"token": self.token, "device": self.device})
        assert frame["body"]["success"], frame
        return frame["body"]

    def send(self, cmd, body, request_id=None):
        if request_id is None:
            self.seq += 1
            request_id = self.seq
        self.ws.send(json.dumps({"cmd": cmd, "requestId": request_id, "body": body}))
        return request_id

    def _read(self, timeout=8):
        self.ws.settimeout(timeout)
        return json.loads(self.ws.recv())

    def expect(self, cmd, request_id=None, timeout=8):
        """在 backlog 里找匹配帧，找不到就继续读。
        不匹配的帧塞回 backlog —— 发送方自己也会收到 PUSH（多端同步），
        顺序读帧会把断言打乱，误判成服务端 bug。"""
        deadline = time.time() + timeout
        for i, f in enumerate(self.backlog):
            if f.get("cmd") == cmd and (request_id is None or f.get("requestId") == request_id):
                return self.backlog.pop(i)
        while time.time() < deadline:
            f = self._read(deadline - time.time())
            if f.get("cmd") == cmd and (request_id is None or f.get("requestId") == request_id):
                return f
            self.backlog.append(f)
        raise AssertionError(f"等 cmd={cmd} requestId={request_id} 超时")

    def request(self, cmd, body, timeout=8):
        rid = self.send(cmd, body)
        return self.expect(cmd + 1, request_id=rid, timeout=timeout)

    def post(self, cmd, body):
        """单向指令（ACK / READ_SYNC 这种不回帧的）。"""
        self.ws.send(json.dumps({"cmd": cmd, "body": body}))

    def drain(self, sec=0.5):
        try:
            self.ws.settimeout(sec)
            while True:
                self.backlog.append(json.loads(self.ws.recv()))
        except Exception:
            pass

    def close(self):
        try:
            self.ws.close()
        except Exception:
            pass
