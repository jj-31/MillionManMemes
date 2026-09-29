import os, socket, struct, time

PORT = 8988
os.makedirs("received", exist_ok=True)

srv = socket.socket()
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("0.0.0.0", PORT))
srv.listen(1)
print("Listening on", PORT)

while True:
    conn, addr = srv.accept()
    with conn:
        f = conn.makefile("rb")
        n = struct.unpack(">H", f.read(2))[0]          # Java writeUTF length prefix
        name = os.path.basename(f.read(n).decode("utf-8"))
        if name in ("", ".", ".."):
            name = f"received_{int(time.time())}"
        with open(os.path.join("received", name), "wb") as out:
            while chunk := f.read(65536):               # until the phone closes its side
                out.write(chunk)
        conn.sendall(b"\x01")                           # confirmation the app waits for
        print("Saved", name, "from", addr[0])
