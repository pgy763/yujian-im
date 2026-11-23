import json, urllib.request

BASE = "http://127.0.0.1:8080"

def post(path, data, token=None):
    req = urllib.request.Request(BASE + path, data=json.dumps(data).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())

def get(path, token):
    req = urllib.request.Request(BASE + path, headers={"Authorization": "Bearer " + token})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())

for u in ("alice", "bob", "carol"):
    try:
        r = post("/api/auth/register", {"username": u, "password": "123456", "nickname": u.capitalize()})
        print("register", u, r)
    except Exception as e:
        print("register", u, "ERR", e)

a = post("/api/auth/login", {"username": "alice", "password": "123456"})
b = post("/api/auth/login", {"username": "bob", "password": "123456"})
c = post("/api/auth/login", {"username": "carol", "password": "123456"})
print("login alice", a)
print("login bob", b)
json.dump({"alice": a["data"], "bob": b["data"], "carol": c["data"]}, open("tokens.json", "w"), indent=2)

ta = a["data"]["token"]
conv = post("/api/conversations/single", {"targetUserId": b["data"]["userId"]}, ta)
print("conv", conv)
conv2 = post("/api/conversations/single", {"targetUserId": b["data"]["userId"]}, ta)
print("conv again (idempotent)", conv2["data"]["id"], conv["data"]["id"])
grp = post("/api/conversations/group", {"name": "考试周自习室", "memberIds": [b["data"]["userId"], c["data"]["userId"]]}, ta)
print("group", grp)
print("list", get("/api/conversations", ta))
print("search", get("/api/users/search?keyword=bo", ta))
print("me", get("/api/users/me", ta))
