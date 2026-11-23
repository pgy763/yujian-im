"""按前端 ws.js 的真实用法回归一遍：JSON 信封 + requestId 关联 + 推送/ack/已读/离线补拉。

这个脚本和 t_im.py 的分工：
  t_im.py   —— 手写 18 字节二进制头，验证 TCP 通道与协议细节（粘包/半包）
  t_ws.py   —— 只验证握手和一个心跳
  t_ws_flow.py —— 用和浏览器一模一样的 JSON 信封，把前端会走的整条链路走通

跑法：python t_ws_flow.py   （需要后端 8080/9001 已启动、alice/bob 已注册；
     没注册的话先跑 t_http.py）
"""
import json
import time

import websocket

BASE_HTTP = "http://127.0.0.1:8080"
WS_URL = "ws://127.0.0.1:9001/im"

CMD = {
    "LOGIN_REQ": 0x01, "LOGIN_RESP": 0x02,
    "HEARTBEAT_REQ": 0x03, "HEARTBEAT_RESP": 0x04,
    "SINGLE_CHAT_REQ": 0x05, "SINGLE_CHAT_RESP": 0x06,
    "GROUP_CHAT_REQ": 0x07, "GROUP_CHAT_RESP": 0x08,
    "PUSH_MESSAGE": 0x09, "ACK_REQ": 0x0A, "READ_SYNC_PUSH": 0x0E,
    "READ_SYNC_REQ": 0x10, "PULL_OFFLINE_REQ": 0x0C, "PULL_OFFLINE_RESP": 0x0D,
    "ERROR": 0x7F,
}

PASS = 0
FAIL = 0


def ok(name, cond, extra=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {name}")
    else:
        FAIL += 1
        print(f"  [FAIL] {name}  {extra}")


def login(u, p="123456"):
    import urllib.request
    req = urllib.request.Request(
        BASE_HTTP + "/api/auth/login",
        data=json.dumps({"username": u, "password": p}).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    d = json.loads(urllib.request.urlopen(req, timeout=10).read())["data"]
    return d["token"], d["userId"]


class WsClient:
    """和前端 ImSocket 等价的极简实现：发请求等响应、按 cmd 分发主动推送。"""

    def __init__(self, name):
        self.name = name
        self.ws = None
        self.seq = 0
        self.backlog = []

    def connect(self, token):
        self.ws = websocket.create_connection(WS_URL, timeout=10)
        frame = self.request(CMD["LOGIN_REQ"], {"token": token, "device": "web"})
        # request() 返回整帧（含 cmd/requestId/body），登录结果在 body 里
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
        deadline = time.time() + timeout
        # 先在已经收到的帧里找
        for i, f in enumerate(self.backlog):
            if f.get("cmd") == cmd and (request_id is None or f.get("requestId") == request_id):
                return self.backlog.pop(i)
        while time.time() < deadline:
            f = self._read(deadline - time.time())
            if f.get("cmd") == cmd and (request_id is None or f.get("requestId") == request_id):
                return f
            self.backlog.append(f)
        raise AssertionError(f"{self.name} 等 {cmd} 超时")

    def request(self, cmd, body, timeout=8):
        rid = self.send(cmd, body)
        return self.expect(cmd + 1, request_id=rid, timeout=timeout)

    def post(self, cmd, body):
        self.ws.send(json.dumps({"cmd": cmd, "body": body}))

    def drain(self, sec=0.5):
        try:
            self.ws.settimeout(sec)
            while True:
                self.backlog.append(json.loads(self.ws.recv()))
        except Exception:
            pass


def main():
    ta, ua = login("alice")
    tb, ub = login("bob")

    print("== 1. WebSocket 登录（JSON 信封） ==")
    a = WsClient("alice")
    ra = a.connect(ta)
    ok("alice 登录成功", ra["success"] is True, ra)
    ok("回包带 userId", ra["userId"] == ua, (ra.get("userId"), ua))

    b = WsClient("bob")
    rb = b.connect(tb)
    ok("bob 登录成功", rb["success"] is True)

    print("== 2. requestId 关联（前端靠它把响应和请求对上） ==")
    rid = a.send(CMD["HEARTBEAT_REQ"], {})
    hb = a.expect(CMD["HEARTBEAT_RESP"], request_id=rid)
    ok("心跳回包 requestId 与请求一致", hb.get("requestId") == rid, hb)
    ok("心跳回 body 是时间戳", isinstance(hb.get("body"), (int, str)), hb.get("body"))

    print("== 3. 建单聊会话 ==")
    import urllib.request
    req = urllib.request.Request(
        BASE_HTTP + "/api/conversations/single",
        data=json.dumps({"targetUserId": ub}).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + ta},
        method="POST",
    )
    conv = json.loads(urllib.request.urlopen(req, timeout=10).read())["data"]
    conv_id = conv["id"]
    # 雪花 ID 超出 JS 安全整数范围，后端全局把 Long 序列化成字符串（JsonModules.java），
    # 所以这里断言的是"是字符串且能按数字解析"，而不是 int。
    ok("会话创建成功（ID 是字符串，避免 JS 精度丢失）",
       isinstance(conv_id, str) and conv_id.isdigit(), conv)
    ok("会话 seq 是数字（前端要做 Math.max）", isinstance(conv.get("seq"), int), conv)

    print("== 4. 发消息：响应 + 双方推送 ==")
    cid = f"wsflow-{time.time()}"
    rid = a.send(CMD["SINGLE_CHAT_REQ"],
                 {"conversationId": conv_id, "type": 1, "content": "前端链路测试", "clientMsgId": cid})
    resp = a.expect(CMD["SINGLE_CHAT_RESP"], request_id=rid)
    ok("发送方拿到响应", resp.get("status") == 0, resp)
    ok("响应 body 含 seq", isinstance(resp["body"].get("seq"), int), resp["body"])
    seq = resp["body"]["seq"]
    msg_id = resp["body"]["msgId"]

    push_b = b.expect(CMD["PUSH_MESSAGE"])
    ok("接收方收到推送", push_b["body"].get("content") == "前端链路测试", push_b["body"])
    ok("推送的 msgId 与响应一致", push_b["body"].get("msgId") == msg_id, push_b["body"])

    push_a = a.expect(CMD["PUSH_MESSAGE"], timeout=3)
    ok("发送方自己也收到推送（多端同步）", push_a["body"].get("msgId") == msg_id, push_a["body"])

    print("== 5. ACK（单向，服务端不回帧） ==")
    b.post(CMD["ACK_REQ"], {"conversationId": conv_id, "seq": seq, "msgId": msg_id})
    # 用一个心跳做栅栏：如果服务端会给 ACK 回帧，这之前就会先收到
    rid2 = b.send(CMD["HEARTBEAT_REQ"], {})
    fence = b.expect(CMD["HEARTBEAT_RESP"], request_id=rid2)
    stray = [f for f in b.backlog if f.get("cmd") == 0x0B]
    ok("ACK 没有产生下行帧", len(stray) == 0, stray)
    ok("栅栏心跳正常", fence.get("requestId") == rid2, fence)

    print("== 6. 已读同步（推给同账号其他端，不推给对方） ==")
    # alice 开第二个端
    a2 = WsClient("alice2")
    a2.connect(ta)
    a.post(CMD["READ_SYNC_REQ"], {"conversationId": conv_id, "seq": seq})
    try:
        sync = a2.expect(CMD["READ_SYNC_PUSH"], timeout=5)
        ok("另一端收到 READ_SYNC_PUSH", sync["body"].get("conversationId") == conv_id, sync["body"])
        ok("同步的 lastReadSeq 正确", sync["body"].get("lastReadSeq") == seq, sync["body"])
    except AssertionError as e:
        ok("另一端收到 READ_SYNC_PUSH", False, str(e))

    # bob 不应该收到任何 READ_SYNC（那是"已读回执"，本项目不做）
    b.drain(0.6)
    ok("对方不会收到已读回执", all(f.get("cmd") != CMD["READ_SYNC_PUSH"] for f in b.backlog),
       b.backlog)

    print("== 7. 离线补拉（关掉 bob 的连接，发一条，再拉） ==")
    b.ws.close()
    time.sleep(0.5)
    cid2 = f"offline-{time.time()}"
    rid3 = a.send(CMD["SINGLE_CHAT_REQ"],
                  {"conversationId": conv_id, "type": 1, "content": "离线消息", "clientMsgId": cid2})
    r3 = a.expect(CMD["SINGLE_CHAT_RESP"], request_id=rid3)
    offline_seq = r3["body"]["seq"]

    b2 = WsClient("bob2")
    b2.connect(tb)
    pull = b2.request(CMD["PULL_OFFLINE_REQ"],
                      {"conversationId": conv_id, "afterSeq": seq, "limit": 50})
    got = pull["body"]
    ok("补到了离线消息", isinstance(got, list) and len(got) >= 1, got)
    if isinstance(got, list) and got:
        ok("补拉内容正确", got[-1]["content"] == "离线消息", got[-1])
        ok("补拉 seq 与发送时一致", got[-1]["seq"] == offline_seq, (got[-1]["seq"], offline_seq))
        ok("补拉结果按 seq 有序", [m["seq"] for m in got] == sorted(m["seq"] for m in got), got)

    print("== 8. 幂等：同 clientMsgId 再发一次 ==")
    rid4 = a.send(CMD["SINGLE_CHAT_REQ"],
                  {"conversationId": conv_id, "type": 1, "content": "离线消息", "clientMsgId": cid2})
    r4 = a.expect(CMD["SINGLE_CHAT_RESP"], request_id=rid4)
    ok("第二次命中幂等", r4["body"].get("duplicate") is True, r4["body"])
    ok("msgId 未变", r4["body"]["msgId"] == r3["body"]["msgId"], (r4["body"]["msgId"], r3["body"]["msgId"]))

    print("== 9. 未授权：不带登录直接发消息 ==")
    c = WsClient("anon")
    c.ws = websocket.create_connection(WS_URL, timeout=10)
    c.send(CMD["SINGLE_CHAT_REQ"],
           {"conversationId": conv_id, "type": 1, "content": "x", "clientMsgId": "anon"})
    try:
        f = c.expect(CMD["ERROR"], timeout=5)
        ok("未登录被拒", True, f)
    except AssertionError as e:
        ok("未登录被拒", False, str(e))

    for cli in (a, a2, b2, c):
        try:
            cli.ws.close()
        except Exception:
            pass

    print()
    print(f"全部用例执行完毕：PASS={PASS}  FAIL={FAIL}")


if __name__ == "__main__":
    main()
