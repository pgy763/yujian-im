"""WebSocket 通道验证：手写握手（不依赖第三方库）。"""
import base64, hashlib, json, os, socket, struct, urllib.request

def http_login(u, p="123456"):
    req = urllib.request.Request("http://127.0.0.1:8080/api/auth/login",
        data=json.dumps({"username": u, "password": p}).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    return json.loads(urllib.request.urlopen(req, timeout=10).read())["data"]

tok = http_login("alice")["token"]
s = socket.create_connection(("127.0.0.1", 9001), timeout=5)
key = base64.b64encode(os.urandom(16)).decode()
s.sendall(("GET /im HTTP/1.1\r\nHost: 127.0.0.1:9001\r\nUpgrade: websocket\r\n"
           "Connection: Upgrade\r\nSec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n" % key).encode())
resp = b""
while b"\r\n\r\n" not in resp:
    resp += s.recv(4096)
print("握手:", resp.split(b"\r\n")[0].decode())

def send_text(payload):
    data = payload.encode()
    mask = os.urandom(4)
    header = bytearray([0x81])
    n = len(data)
    header.append(0x80 | n if n < 126 else 0x80 | 126)
    if n >= 126:
        header += struct.pack(">H", n)
    header += mask
    s.sendall(bytes(header) + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

def recv_text():
    h = s.recv(2)
    if len(h) < 2:
        raise ConnectionError("closed")
    ln = h[1] & 0x7F
    if ln == 126:
        ln = struct.unpack(">H", s.recv(2))[0]
    elif ln == 127:
        ln = struct.unpack(">Q", s.recv(8))[0]
    buf = b""
    while len(buf) < ln:
        buf += s.recv(ln - len(buf))
    return buf.decode()

send_text(json.dumps({"cmd": 1, "body": {"token": tok, "device": "web"}}))
print("LOGIN_RESP:", recv_text())
send_text(json.dumps({"cmd": 3, "requestId": 42, "body": {}}))
print("HEARTBEAT:", recv_text())
s.close()
