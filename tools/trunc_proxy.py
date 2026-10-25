"""截断代理：模拟弱网「响应体流中途断裂」。

上游为本地代理 127.0.0.1:7897，本代理监听 127.0.0.1:7899。
对前 COUNT 条 CONNECT 隧道：响应方向转发 LIMIT 字节后直接断开
（TLS 流被掐断 → 客户端读到 unexpected end of stream / SSLException），
之后的隧道全量转发（模拟「故障期结束，手动重试能成功」）。
"""
import socket
import threading

UPSTREAM = ("127.0.0.1", 7897)
LISTEN_PORT = 7899
LIMIT = 65536    # 64KB 处掐断（图片通常几百 KB）
COUNT = 60       # 影响前 60 条隧道

count_lock = threading.Lock()
affected = 0


def pump(src, dst, truncate):
    try:
        if truncate:
            sent = 0
            while True:
                data = src.recv(16384)
                if not data:
                    break
                if sent + len(data) > LIMIT:
                    keep = max(0, LIMIT - sent)
                    if keep:
                        dst.sendall(data[:keep])
                    break
                sent += len(data)
                dst.sendall(data)
        else:
            while True:
                data = src.recv(16384)
                if not data:
                    break
                dst.sendall(data)
    except OSError:
        pass
    finally:
        try:
            dst.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass


def handle(client):
    global affected
    req = b""
    while b"\r\n\r\n" not in req:
        chunk = client.recv(4096)
        if not chunk:
            return
        req += chunk
    up = socket.create_connection(UPSTREAM)
    up.sendall(req)
    resp = b""
    while b"\r\n\r\n" not in resp:
        chunk = up.recv(4096)
        if not chunk:
            break
        resp += chunk
    client.sendall(resp)
    with count_lock:
        do_trunc = affected < COUNT
        affected += 1
    t1 = threading.Thread(target=pump, args=(client, up, do_trunc), daemon=True)
    t2 = threading.Thread(target=pump, args=(up, client, do_trunc), daemon=True)
    t1.start()
    t2.start()
    t1.join()
    t2.join()
    client.close()
    up.close()


srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", LISTEN_PORT))
srv.listen(64)
print(f"truncating proxy on {LISTEN_PORT} (first {COUNT} tunnels cut at {LIMIT}B)", flush=True)
while True:
    c, _ = srv.accept()
    threading.Thread(target=handle, args=(c,), daemon=True).start()
