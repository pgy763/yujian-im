"""最小 IM 客户端：手写 18 字节头的编解码，用来验证协议行为。

设计要点：收到的帧进 backlog 队列，取帧用统一出口，
这样"发送方自己也会收到 PUSH（多端同步）"这类正常行为不会打乱断言。
"""
import json, socket, struct, time

MAGIC = 0xCAFE
HEADER = 18

CMD = dict(LOGIN_REQ=0x01, LOGIN_RESP=0x02, HEARTBEAT_REQ=0x03, HEARTBEAT_RESP=0x04,
           SINGLE_CHAT_REQ=0x05, SINGLE_CHAT_RESP=0x06, GROUP_CHAT_REQ=0x07, GROUP_CHAT_RESP=0x08,
           PUSH_MESSAGE=0x09, ACK_REQ=0x0A, ACK_RESP=0x0B, PULL_OFFLINE_REQ=0x0C,
           PULL_OFFLINE_RESP=0x0D, READ_SYNC_PUSH=0x0E, KICK_PUSH=0x0F, READ_SYNC_REQ=0x10, ERROR=0x7F)
CMD_NAME = {v: k for k, v in CMD.items()}


def encode(cmd, body=None, request_id=1):
    payload = json.dumps(body or {}, ensure_ascii=False).encode()
    return struct.pack(">HBBBBQI", MAGIC, 1, CMD[cmd] if isinstance(cmd, str) else cmd,
                       0, 0, request_id, len(payload)) + payload


class ImClient:
    def __init__(self, host="127.0.0.1", port=9000):
        self.sock = socket.create_connection((host, port), timeout=5)
        self.sock.settimeout(5)
        self.buf = b""
        self.backlog = []

    def send_raw(self, data, split=0.0):
        if split <= 0:
            self.sock.sendall(data)
        else:
            half = len(data) // 2
            self.sock.sendall(data[:half])
            time.sleep(split)
            self.sock.sendall(data[half:])

    def _read_one(self):
        while True:
            if len(self.buf) >= HEADER:
                magic, ver, cmd, ser, status, rid, blen = struct.unpack(">HBBBBQI", self.buf[:HEADER])
                assert magic == MAGIC, f"magic 错: {hex(magic)}"
                if len(self.buf) >= HEADER + blen:
                    body = self.buf[HEADER:HEADER + blen]
                    self.buf = self.buf[HEADER + blen:]
                    return dict(version=ver, cmd=cmd, status=status, requestId=rid,
                                bodyLen=blen, body=json.loads(body.decode() or "{}"),
                                name=CMD_NAME.get(cmd, cmd))
            chunk = self.sock.recv(8192)
            if not chunk:
                raise ConnectionError("对端关闭")
            self.buf += chunk

    def expect(self, cmd_name, timeout=5, request_id=None):
        """取一帧；不匹配的先塞 backlog（顺序保留），不丢。"""
        end = time.time() + timeout
        while time.time() < end:
            for i, f in enumerate(self.backlog):
                if f["name"] == cmd_name and (request_id is None or f["requestId"] == request_id):
                    return self.backlog.pop(i)
            self.backlog.append(self._read_one())
        raise TimeoutError(f"等 {cmd_name} 超时; backlog={[f['name'] for f in self.backlog]}")

    def drain(self, timeout=0.5):
        """把已经到达的帧全部收进 backlog。"""
        end = time.time() + timeout
        while time.time() < end:
            self.sock.settimeout(max(0.05, end - time.time()))
            try:
                self.backlog.append(self._read_one())
            except (socket.timeout, TimeoutError):
                break
            except BlockingIOError:
                break
        self.sock.settimeout(5)

    def pushes(self):
        return [f for f in self.backlog if f["name"] == "PUSH_MESSAGE"]

    def login(self, token, device="py"):
        self.send_raw(encode("LOGIN_REQ", {"token": token, "device": device}))
        return self.expect("LOGIN_RESP")

    def chat(self, conv_id, content, client_msg_id=None, cmd="SINGLE_CHAT_REQ", split=0.0, rid=1):
        import uuid
        cid = client_msg_id or uuid.uuid4().hex
        self.send_raw(encode(cmd, {"conversationId": conv_id, "type": 1,
                                   "content": content, "clientMsgId": cid}, rid), split)
        return cid

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass
