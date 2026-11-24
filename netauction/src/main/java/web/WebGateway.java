package web;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class WebGateway {
    static final Gson GSON = new Gson();
    static String serverHost = "127.0.0.1";
    static final int TCP_PORT = 5000, FILE_PORT = 5001, UDP_PORT = 5002;
    static final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    /** Satu sesi pengguna web = satu koneksi TCP + satu soket UDP ke AuctionServer. */
    static class Session {
        final String user;
        final Socket tcp;
        final PrintWriter out;
        final BufferedReader in;
        final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        final DatagramSocket udp;
        volatile boolean open = true;
        volatile long lastSeen = System.currentTimeMillis();

        Session(String user, Socket tcp, BufferedReader in, PrintWriter out, DatagramSocket udp) {
            this.user = user; this.tcp = tcp; this.in = in; this.out = out; this.udp = udp;
        }

        void push(String type, String data) {
            events.offer(GSON.toJson(Map.of("t", type, "d", data)));
        }

        void close() {
            open = false;
            try { tcp.close(); } catch (IOException ignored) { }
            udp.close();
            sessions.values().remove(this);
            events.offer(GSON.toJson(Map.of("t", "closed", "d", "Sesi berakhir.")));
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) serverHost = args[0];
        int httpPort = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        HttpServer http = HttpServer.create(new InetSocketAddress(httpPort), 0);
        http.setExecutor(Executors.newCachedThreadPool());
        http.createContext("/", WebGateway::handleStatic);
        http.createContext("/api/connect", ex -> guard(ex, WebGateway::connect));
        http.createContext("/api/cmd", ex -> guard(ex, WebGateway::command));
        http.createContext("/api/events", ex -> guard(ex, WebGateway::events));
        http.createContext("/api/download", ex -> guard(ex, WebGateway::download));
        http.createContext("/api/upload", ex -> guard(ex, WebGateway::upload));
        http.createContext("/api/exit", ex -> guard(ex, WebGateway::exit));
        http.start();
        startReaper();
        System.out.println("[WEB GATEWAY] Aktif di http://localhost:" + httpPort
                + "  (AuctionServer: " + serverHost + ":" + TCP_PORT + ")");
    }

    interface Route { void run(HttpExchange ex) throws Exception; }

    /** Penangkap error terpusat: request tidak valid tidak boleh membuat gateway crash. */
    static void guard(HttpExchange ex, Route r) throws IOException {
        try { r.run(ex); }
        catch (Exception e) {
            System.out.println("[WEB ERROR] " + ex.getRequestURI().getPath() + " : " + e);
            try { json(ex, 500, Map.of("ok", false, "reply", "500 ERROR Gateway: " + e.getMessage())); }
            catch (IOException ignored) { }
        }
    }

    // ---------- /api/connect : buka TCP, LOGIN, lalu mulai thread penerima ----------
    static void connect(HttpExchange ex) throws Exception {
        String user = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        Socket tcp;
        try {
            tcp = new Socket();
            tcp.connect(new InetSocketAddress(serverHost, TCP_PORT), 3000);
        } catch (IOException e) {
            json(ex, 200, Map.of("ok", false, "reply", "503 ERROR AuctionServer tidak dapat dihubungi."));
            return;
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(tcp.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(tcp.getOutputStream(), StandardCharsets.UTF_8), false);
        tcp.setSoTimeout(5000);
        out.print("LOGIN " + user + "\r\n"); out.flush();
        String reply;
        try { reply = in.readLine(); } catch (IOException e) { reply = null; }
        if (reply == null || !reply.startsWith("200")) {
            tcp.close();
            json(ex, 200, Map.of("ok", false, "reply", reply == null ? "503 ERROR Tidak ada balasan dari server." : reply));
            return;
        }
        tcp.setSoTimeout(0);
        DatagramSocket udp = new DatagramSocket();
        Session s = new Session(user, tcp, in, out, udp);
        String sid = UUID.randomUUID().toString();
        sessions.put(sid, s);
        Thread tr = new Thread(() -> tcpReader(s), "tcp-reader-" + user); tr.setDaemon(true); tr.start();
        Thread ur = new Thread(() -> udpWorker(s), "udp-" + user); ur.setDaemon(true); ur.start();
        json(ex, 200, Map.of("ok", true, "sid", sid, "reply", reply));
    }

    /** Background receiver: pisahkan balasan sinkron dan pesan ASYNC dari server. */
    static void tcpReader(Session s) {
        try {
            String line;
            while (s.open && (line = s.in.readLine()) != null) {
                if (line.startsWith("ASYNC ")) s.push("async", line.substring(6));
                else s.replies.offer(line);
            }
        } catch (IOException ignored) { }
        if (s.open) s.push("async", "SYSTEM: Koneksi ke server terputus.");
        s.close();
    }

    /** Subsistem UDP: subscribe (dengan retransmisi), terima TICKER + kirim ACK, PING heartbeat berkala. */
    static void udpWorker(Session s) {
        try {
            InetAddress addr = InetAddress.getByName(serverHost);
            s.udp.setSoTimeout(1500);
            boolean subscribed = false;
            for (int attempt = 1; attempt <= 3 && !subscribed && s.open; attempt++) {
                udpSend(s, "UDP_SUB " + s.user, addr);
                try {
                    String m = udpRecv(s);
                    if (m.startsWith("UDP_SUB_ACK")) subscribed = true;
                } catch (SocketTimeoutException e) { /* retransmisi */ }
            }
            s.push("udp", subscribed ? "UDP live ticker aktif" : "UDP live ticker gagal terdaftar");
            long lastPing = 0, pingId = 0; int missed = 0;
            while (s.open) {
                if (System.currentTimeMillis() - lastPing > 5000) {
                    udpSend(s, "PING " + s.user + " " + (++pingId), addr);
                    lastPing = System.currentTimeMillis();
                    if (++missed > 3) s.push("udp", "Heartbeat UDP tidak dibalas");
                }
                try {
                    String m = udpRecv(s);
                    if (m.startsWith("TICKER|")) {
                        String[] p = m.split("\\|", 3);
                        udpSend(s, "TICK_ACK " + p[1], addr);
                        s.push("ticker", p.length > 2 ? p[2] : "");
                    } else if (m.startsWith("PONG")) { missed = 0; s.push("pong", m); }
                } catch (SocketTimeoutException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    static void udpSend(Session s, String text, InetAddress addr) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        s.udp.send(new DatagramPacket(b, b.length, addr, UDP_PORT));
    }

    static String udpRecv(Session s) throws IOException {
        DatagramPacket p = new DatagramPacket(new byte[4096], 4096);
        s.udp.receive(p);
        return new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim();
    }

    // ---------- /api/cmd : kirim satu baris perintah, tunggu satu balasan ----------
    static void command(HttpExchange ex) throws Exception {
        Session s = session(ex);
        if (s == null) return;
        String cmd = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).replaceAll("[\\r\\n]+", " ").trim();
        String reply = request(s, cmd);
        json(ex, 200, Map.of("ok", reply != null && reply.startsWith("200"),
                "reply", reply == null ? "504 ERROR Server tidak membalas." : reply));
    }

    /** Mengirim satu perintah ke server dan menunggu satu balasan (satu perintah aktif per sesi). */
    static String request(Session s, String cmd) throws InterruptedException {
        synchronized (s) {
            s.replies.clear();
            s.out.print(cmd + "\r\n"); s.out.flush();
            return s.replies.poll(8, TimeUnit.SECONDS);
        }
    }

    // ---------- /api/events : Server-Sent Events (push ke browser) ----------
    static void events(HttpExchange ex) throws Exception {
        Session s = session(ex);
        if (s == null) return;
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        try {
            while (true) {
                String ev = s.events.poll(10, TimeUnit.SECONDS);
                s.lastSeen = System.currentTimeMillis();
                os.write((ev == null ? ": keepalive\n\n" : "data: " + ev + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                if (ev != null && ev.contains("\"t\":\"closed\"")) break;
            }
        } catch (IOException ignored) { /* tab ditutup */ }
        finally { ex.close(); }
    }

    // ---------- /api/download : alirkan berkas biner dari port 5001 ke browser ----------
    static void download(HttpExchange ex) throws Exception {
        Session s = session(ex);
        if (s == null) return;
        String name = query(ex, "name");
        try (Socket f = new Socket(serverHost, FILE_PORT)) {
            DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(f.getOutputStream()));
            DataInputStream dis = new DataInputStream(new BufferedInputStream(f.getInputStream()));
            dos.writeUTF("DOWNLOAD"); dos.writeUTF(name); dos.flush();
            f.shutdownOutput();
            String status = dis.readUTF();
            if (!status.equals("OK")) { json(ex, 404, Map.of("ok", false, "reply", dis.readUTF())); return; }
            String fileName = dis.readUTF(); long size = dis.readLong();
            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + fileName.replace("\"", "") + "\"");
            ex.sendResponseHeaders(200, size);
            try (OutputStream os = ex.getResponseBody()) {
                byte[] buf = new byte[8192]; long left = size; int n;
                while (left > 0 && (n = dis.read(buf, 0, (int) Math.min(buf.length, left))) != -1) { os.write(buf, 0, n); left -= n; }
            }
        }
    }

    // ---------- /api/upload : kirim berkas biner dari browser ke port 5001 ----------
    static void upload(HttpExchange ex) throws Exception {
        Session s = session(ex);
        if (s == null) return;
        String name = new File(query(ex, "name")).getName();
        // Batasi pembacaan body (maks 5 MB + 1 byte) lalu validasi tipe, ukuran, dan isi sebelum menyentuh server
        byte[] data = ex.getRequestBody().readNBytes((int) server.FileTransferHandler.MAX_UPLOAD_BYTES + 1);
        String err = server.FileTransferHandler.validateUpload(name, data.length);
        if (err == null && !server.FileTransferHandler.validSignature(name, data, data.length)) {
            err = "Isi berkas tidak cocok dengan tipenya (" + name + ").";
        }
        if (err != null) {
            json(ex, 200, Map.of("ok", false, "reply", "400 ERROR " + err));
            return;
        }
        // Minta tiket unggah agar server mencatat peserta ini sebagai pemilik berkas
        String ticketReply = request(s, "UPTICKET");
        if (ticketReply == null || !ticketReply.startsWith("200 SUCCESS ")) {
            json(ex, 200, Map.of("ok", false, "reply", ticketReply == null ? "504 ERROR Server tidak membalas." : ticketReply));
            return;
        }
        String token = ticketReply.substring("200 SUCCESS ".length()).trim();
        try (Socket f = new Socket(serverHost, FILE_PORT)) {
            DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(f.getOutputStream()));
            DataInputStream dis = new DataInputStream(new BufferedInputStream(f.getInputStream()));
            dos.writeUTF("UPLOAD_AUTH"); dos.writeUTF(token); dos.writeUTF(name); dos.writeLong(data.length);
            for (int off = 0; off < data.length; off += 8192) dos.write(data, off, Math.min(8192, data.length - off));
            dos.flush(); f.shutdownOutput();
            String status = dis.readUTF(); String msg = dis.readUTF();
            json(ex, 200, Map.of("ok", status.equals("OK"), "reply", msg));
        }
    }

    static void exit(HttpExchange ex) throws Exception {
        Session s = session(ex);
        if (s == null) return;
        s.out.print("EXIT\r\n"); s.out.flush();
        s.close();
        json(ex, 200, Map.of("ok", true, "reply", "200 SUCCESS Keluar."));
    }

    // ---------- util ----------
    static Session session(HttpExchange ex) throws IOException {
        Session s = sessions.get(query(ex, "sid"));
        if (s == null) { json(ex, 401, Map.of("ok", false, "reply", "401 ERROR Sesi tidak valid. Muat ulang halaman.")); return null; }
        s.lastSeen = System.currentTimeMillis();
        return s;
    }

    static String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return "";
        for (String kv : q.split("&")) {
            String[] p = kv.split("=", 2);
            if (p[0].equals(key)) return p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "";
        }
        return "";
    }

    static void json(HttpExchange ex, int code, Object body) throws IOException {
        byte[] b = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    /** Berkas statis yang boleh dilayani (daftar putih), dimuat dari classpath: src/main/resources/web. */
    private static final Map<String, String[]> STATIC = Map.of(
            "/", new String[]{"index.html", "text/html; charset=utf-8"},
            "/index.html", new String[]{"index.html", "text/html; charset=utf-8"},
            "/style.css", new String[]{"style.css", "text/css; charset=utf-8"},
            "/app.js", new String[]{"app.js", "text/javascript; charset=utf-8"});

    static void handleStatic(HttpExchange ex) throws IOException {
        String[] entry = STATIC.get(ex.getRequestURI().getPath());
        byte[] b = null;
        if (entry != null) {
            try (InputStream is = WebGateway.class.getResourceAsStream("/web/" + entry[0])) {
                if (is != null) b = is.readAllBytes();
            }
        }
        if (b == null) {
            byte[] nf = ("404: halaman tidak ditemukan. Buka http://localhost:8080/ . Jika tetap gagal, pastikan folder "
                    + "src/main/resources ada di classpath (otomatis bila dijalankan lewat Maven/IntelliJ).")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(404, nf.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(nf); }
            return;
        }
        ex.getResponseHeaders().set("Content-Type", entry[1]);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    /** Menutup sesi yang ditinggalkan (tab ditutup tanpa EXIT) agar slot thread pool server dibebaskan. */
    static void startReaper() {
        ScheduledExecutorService r = Executors.newSingleThreadScheduledExecutor(t -> { Thread th = new Thread(t); th.setDaemon(true); return th; });
        r.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (Session s : new ArrayList<>(sessions.values()))
                if (now - s.lastSeen > 45_000) { System.out.println("[WEB] Sesi idle ditutup: " + s.user); s.close(); }
        }, 15, 15, TimeUnit.SECONDS);
    }
}
