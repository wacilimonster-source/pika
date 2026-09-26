# -*- coding: utf-8 -*-
"""哔咔 API 探测脚本：复刻 PiKA App (PicaHttpEngine/PicaConfig) 的签名与请求头，
用于实测 comics/{id}/recommendation 接口的真实行为。
密钥从 PicaConfig.kt 的 XOR 表运行时还原，与 App 内逻辑一致。"""
import hmac, hashlib, json, time, uuid, urllib.request, urllib.error, urllib.parse

KEY_MASK = 0x6F
API_KEY_XOR = [
    0x2C,0x59,0x56,0x2D,0x2E,0x29,0x5B,0x5E,0x2B,0x2E,0x5A,0x2E,0x2D,0x2B,0x5E,0x29,
    0x29,0x2A,0x2B,0x2C,0x59,0x2B,0x5D,0x29,0x2A,0x2E,0x5A,0x59,0x2D]
SECRET_KEY_XOR = [
    0x11,0x0B,0x12,0x4B,0x3E,0x58,0x4B,0x0A,0x26,0x01,0x06,0x52,0x39,0x46,0x56,0x33,
    0x3D,0x24,0x40,0x3F,0x41,0x3D,0x22,0x5B,0x54,0x56,0x34,0x58,0x13,0x2F,0x40,0x2C,
    0x2E,0x12,0x0D,0x11,0x20,0x38,0x4E,0x5C,0x50,0x2A,0x39,0x0F,0x55,0x53,0x51,0x22,
    0x58,0x1F,0x0B,0x0B,0x3A,0x2D,0x23,0x5A,0x01,0x13,0x5F,0x40,0x45,0x2C,0x01]
API_KEY = ''.join(chr(b ^ KEY_MASK) for b in API_KEY_XOR)
SECRET  = ''.join(chr(b ^ KEY_MASK) for b in SECRET_KEY_XOR)

HOSTS = {
    "picacomic": "https://picaapi.picacomic.com/",
    "go2778":    "https://picaapi.go2778.com/",
}
APP_UUID = str(uuid.uuid4())

def sign(path_for_sign, ts, nonce, method):
    key = (path_for_sign + ts + nonce + method + API_KEY).lower()
    return hmac.new(SECRET.encode(), key.encode(), hashlib.sha256).hexdigest()

def call(host, method, path, query=None, body=None, token=None, timeout=20):
    query = dict(query or {})
    qs = '&'.join(f"{urllib.parse.quote(k, safe='')}={urllib.parse.quote(v, safe='')}"
                  for k, v in query.items())
    pfs = path if not qs else f"{path}?{qs}"
    ts = str(int(time.time()))
    nonce = uuid.uuid4().hex
    sig = sign(pfs, ts, nonce, method.upper())
    url = host.rstrip('/') + '/' + path.lstrip('/') + (('?' + qs) if qs else '')
    headers = {
        'accept': 'application/vnd.picacomic.com.v1+json',
        'User-Agent': 'okhttp/3.8.1',
        'Content-Type': 'application/json; charset=UTF-8',
        'api-key': API_KEY,
        'app-build-version': '44',
        'app-platform': 'android',
        'app-uuid': APP_UUID,
        'app-version': '2.2.1.2.3.3',
        'app-channel': '1',
        'time': ts,
        'nonce': nonce,
        'signature': sig,
        'image-quality': 'original',
    }
    if token:
        headers['authorization'] = token
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method.upper())
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode('utf-8', errors='replace')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', errors='replace')
    except Exception as e:
        return -1, f"{type(e).__name__}: {e}"

def show(label, code, body, limit=300):
    b = body if len(body) <= limit else body[:limit] + f"...(共{len(body)}字节)"
    print(f"[{label}] HTTP {code}\n  {b}\n")

if __name__ == "__main__":
    print(f"API_KEY 还原: {API_KEY[:6]}...({len(API_KEY)}字符)  SECRET: ({len(SECRET)}字符)  app-uuid: {APP_UUID[:8]}...\n")

    # ── 1. 无 token 探测：401 应一致；若 recommendation 返回 404/其他则说明端点不存在 ──
    for name, host in HOSTS.items():
        c, b = call(host, "GET", "categories")
        show(f"{name} GET categories (无token)", c, b, 200)
    FAKE = "5c0f2c5c3d5f4d2a1c9f0e1b"  # 合法 ObjectId 形状的假 id
    for name, host in HOSTS.items():
        c, b = call(host, "GET", f"comics/{FAKE}")
        show(f"{name} GET comics/{FAKE} (无token)", c, b, 200)
    for name, host in HOSTS.items():
        c, b = call(host, "GET", f"comics/{FAKE}/recommendation")
        show(f"{name} GET comics/{FAKE}/recommendation (无token)", c, b, 200)

    # ── 2. 尝试注册一次性测试账号（App 内 RegisterPayload 同构） ──
    tag = uuid.uuid4().hex[:8]
    reg = {
        "email": f"pika.probe.{tag}@gmail.com",
        "password": "Pp!8" + tag,
        "name": "pika_probe_" + tag[:6],
        "gender": "m",
        "birthday": "2000-01-01",
        "question1": "1", "question2": "2", "question3": "3",
        "answer1": "4", "answer2": "5", "answer3": "6",
    }
    for name, host in HOSTS.items():
        c, b = call(host, "POST", "auth/register", body=reg)
        show(f"{name} POST auth/register", c, b, 300)
