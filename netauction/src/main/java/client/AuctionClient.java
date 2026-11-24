package client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class AuctionClient {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_TCP_PORT = 5000;
    private static final int DEFAULT_FILE_PORT = 5001;
    private static final int DEFAULT_UDP_PORT = 5002;
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int CHUNK_SIZE = 8192; // 8KB
    private static final String CRLF = "\r\n";

    // Batas unggahan berkas (harus sama dengan aturan server: FileTransferHandler)
    private static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024; // 5 MB
    private static final java.util.Set<String> ALLOWED_EXT = java.util.Set.of("png", "jpg", "jpeg", "pdf");

    private static PrintWriter serverOut;
    private static BufferedReader keyboard;
    private static final BlockingQueue<String> syncResponseQueue = new LinkedBlockingQueue<>();
    private static final AtomicBoolean connected = new AtomicBoolean(false);
    private static final AtomicBoolean showLiveTicker = new AtomicBoolean(false);
    private static volatile String latestTickerSnapshot = "Menunggu pembaruan ticker dari server...";
    private static volatile String currentUsername = null;

    private static class Response {
        final int code;
        final String message;

        Response(int code, String message) {
            this.code = code;
            this.message = message;
        }

        boolean ok() {
            return code == 200;
        }
    }

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0].trim() : DEFAULT_HOST;
        int tcpPort = DEFAULT_TCP_PORT;

        System.out.println();
        System.out.println("=======================================================");
        System.out.println("          NETAUCTION LIVE CLIENT (CLI)");
        System.out.println("=======================================================");

        if (!host.matches("[A-Za-z0-9._:\\-]{1,253}")) {
            System.out.println("[ERROR] Format alamat server tidak valid: \"" + host + "\"");
            return;
        }
        if (args.length > 1) {
            try {
                tcpPort = Integer.parseInt(args[1].trim());
            } catch (NumberFormatException e) {
                tcpPort = -1;
            }
            if (tcpPort < 1 || tcpPort > 65535) {
                System.out.println("[ERROR] Port harus berupa angka 1 - 65535.");
                return;
            }
        }

        try {
            System.out.println("[DNS] Memeriksa alamat server: " + host);
            InetAddress serverAddr = InetAddress.getByName(host);
            System.out.println("[DNS] IP server terdeteksi: " + serverAddr.getHostAddress());

            System.out.println("[KONEKSI] Menghubungi " + serverAddr.getHostAddress() + ":" + tcpPort + " ...");
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(serverAddr, tcpPort), CONNECT_TIMEOUT);
                connected.set(true);
                System.out.println("[KONEKSI] Terhubung ke server NetAuction Live.");

                try (
                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        PrintWriter out = new PrintWriter(
                                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), false);
                        BufferedReader kb = new BufferedReader(new InputStreamReader(System.in))
                ) {
                    serverOut = out;
                    keyboard = kb;

                    // Pilar 2: Background Receiver Thread untuk memproses respons & pesan push asynchronous
                    Thread receiverThread = new Thread(() -> runReceiverLoop(in), "tcp-async-receiver");
                    receiverThread.setDaemon(true);
                    receiverThread.start();

                    if (login()) {
                        // Pilar 5: Jalankan UDP Live Ticker & Heartbeat Subsystem setelah login berhasil
                        Thread udpThread = new Thread(() -> runUdpSubsystem(serverAddr, DEFAULT_UDP_PORT), "udp-ticker-client");
                        udpThread.setDaemon(true);
                        udpThread.start();

                        runMenu(serverAddr);
                    }

                    connected.set(false);
                    try {
                        socket.shutdownOutput();
                    } catch (IOException ignored) {
                    }
                    System.out.println("\n[INFO] Koneksi ditutup. Terima kasih telah menggunakan NetAuction Live!");
                }
            }
        } catch (UnknownHostException e) {
            System.out.println("[ERROR] Alamat server \"" + host + "\" tidak ditemukan.");
        } catch (ConnectException e) {
            System.out.println("[ERROR] Server menolak koneksi di port " + tcpPort + ". Pastikan AuctionServer sudah aktif.");
        } catch (SocketTimeoutException e) {
            System.out.println("[ERROR] Waktu koneksi habis (timeout).");
        } catch (IOException e) {
            System.out.println("[ERROR] Masalah jaringan: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  Pilar 2: Background Receiver Thread (Asynchronous Message Handler)
    // ------------------------------------------------------------------
    private static void runReceiverLoop(BufferedReader in) {
        try {
            String line;
            while (connected.get() && (line = in.readLine()) != null) {
                if (line.startsWith("ASYNC ")) {
                    String event = line.substring(6);
                    System.out.println("\n>>> [NOTIFIKASI REAL-TIME] " + event);
                    System.out.print(">> ");
                } else {
                    syncResponseQueue.offer(line);
                }
            }
        } catch (IOException e) {
            if (connected.get()) {
                System.out.println("\n[KONEKSI TERPUTUS] Koneksi ke server terhenti: " + e.getMessage());
                connected.set(false);
            }
        }
    }

    // ------------------------------------------------------------------
    //  Pilar 5: UDP Telemetry & Heartbeat Subsystem (Client Side)
    // ------------------------------------------------------------------
    private static void runUdpSubsystem(InetAddress serverAddr, int udpPort) {
        AtomicLong pingSeq = new AtomicLong(1);
        long lastPingSent = 0;

        try (DatagramSocket udpSocket = new DatagramSocket()) {
            udpSocket.setSoTimeout(1500); // socket timeout 1.5 detik untuk retransmisi heartbeat
            byte[] buf = new byte[1024];

            // Registrasi awal ke UDP Live Ticker Server
            sendUdp(udpSocket, "UDP_SUB " + currentUsername, serverAddr, udpPort);

            while (connected.get()) {
                long now = System.currentTimeMillis();
                // Kirim Heartbeat PING setiap 5 detik
                if (now - lastPingSent >= 5000) {
                    long id = pingSeq.getAndIncrement();
                    sendUdp(udpSocket, "PING " + currentUsername + " " + id, serverAddr, udpPort);
                    lastPingSent = now;
                }

                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();

                    if (msg.startsWith("TICKER|")) {
                        String[] parts = msg.split("\\|", 3);
                        if (parts.length == 3) {
                            String seq = parts[1];
                            latestTickerSnapshot = parts[2];
                            // Kirim ACK aplikasi untuk reliabilitas paket UDP
                            sendUdp(udpSocket, "TICK_ACK " + seq, serverAddr, udpPort);
                            if (showLiveTicker.get()) {
                                System.out.println("[LIVE TICKER #" + seq + "] " + latestTickerSnapshot);
                            }
                        }
                    }
                } catch (SocketTimeoutException timeout) {
                    // Retransmisi heartbeat/registrasi apabila belum ada balasan dalam batas timeout
                    sendUdp(udpSocket, "PING " + currentUsername + " " + pingSeq.get(), serverAddr, udpPort);
                }
            }

            sendUdp(udpSocket, "UDP_UNSUB " + currentUsername, serverAddr, udpPort);
        } catch (IOException ignored) {
        }
    }

    private static void sendUdp(DatagramSocket socket, String text, InetAddress addr, int port) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(data, data.length, addr, port));
    }

    // ------------------------------------------------------------------
    //  Komunikasi Sinkron dengan Server
    // ------------------------------------------------------------------
    private static synchronized Response request(String command) throws IOException {
        if (!connected.get()) {
            throw new EOFException("Tidak terhubung ke server.");
        }
        syncResponseQueue.clear();
        serverOut.print(command + CRLF);
        serverOut.flush();

        try {
            String line = syncResponseQueue.poll(15, TimeUnit.SECONDS);
            if (line == null) {
                throw new SocketTimeoutException("Server tidak merespons perintah dalam 15 detik.");
            }
            String[] parts = line.split(" ", 3);
            int code = Integer.parseInt(parts[0]);
            return new Response(code, parts.length > 2 ? parts[2] : "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Permintaan terinterupsi.");
        } catch (NumberFormatException e) {
            return new Response(500, "Respons server tidak valid.");
        }
    }

    private static String ask(String prompt) throws IOException {
        System.out.print("\n>> " + prompt);
        String answer = keyboard.readLine();
        return answer == null ? null : answer.trim();
    }

    private static void showResult(Response r) {
        if (r.ok()) {
            System.out.println("[OK] " + r.message);
        } else {
            System.out.println("[!] (" + r.code + ") " + r.message);
        }
    }

    // ------------------------------------------------------------------
    //  Alur Login & Menu Utama
    // ------------------------------------------------------------------
    private static boolean login() throws IOException {
        System.out.println("\nSilakan login terlebih dahulu untuk masuk ke ruang lelang.");
        for (int attempt = 1; attempt <= 3; attempt++) {
            String name = ask("Masukkan username (3-15 karakter huruf/angka/_ ; contoh: raja_01): ");
            if (name == null) return false;
            if (!name.matches("[A-Za-z0-9_]{3,15}")) {
                System.out.println("[!] Username tidak valid. Gunakan 3-15 karakter tanpa spasi.");
                continue;
            }
            Response r = request("LOGIN " + name);
            showResult(r);
            if (r.ok()) {
                currentUsername = name;
                return true;
            }
        }
        System.out.println("[!] Gagal login setelah 3 kali percobaan.");
        return false;
    }

    private static void runMenu(InetAddress serverAddr) throws IOException {
        while (connected.get()) {
            System.out.println();
            System.out.println("=================== MENU UTAMA NETAUCTION LIVE ===================");
            System.out.println("  1 = Lihat daftar barang lelang (LIST)");
            System.out.println("  2 = Ajukan penawaran barang (BID)");
            System.out.println("  3 = Cek status detail satu barang (STATUS)");
            System.out.println("  4 = Konversi mata uang real-time via Gson API (CONVERT)");
            System.out.println("  5 = Lihat daftar peserta aktif (USERS)");
            System.out.println("  6 = Kirim pesan Broadcast ke semua peserta (BROADCAST)");
            System.out.println("  7 = Kirim pesan Pribadi ke peserta tertentu (PRIVATE)");
            System.out.println("  8 = Unduh berkas katalog/gambar biner - Chunking 8KB (DOWNLOAD)");
            System.out.println("  9 = Unggah berkas ke katalog server - Chunking 8KB (UPLOAD)");
            System.out.println(" 10 = Pantau Live Ticker Feed UDP (10 detik / snapshot)");
            System.out.println(" 11 = Jual barang Anda ke ruang lelang (SELL)");
            System.out.println("  0 = Keluar dari aplikasi (EXIT)");
            System.out.println("==================================================================");

            String choice = ask("Pilih nomor menu (0-11): ");
            if (choice == null) return;

            switch (choice) {
                case "1":
                    showItems(request("LIST"));
                    break;
                case "2":
                    menuBid();
                    break;
                case "3":
                    menuStatus();
                    break;
                case "4":
                    menuConvert();
                    break;
                case "5":
                    showResult(request("USERS"));
                    break;
                case "6":
                    menuBroadcast();
                    break;
                case "7":
                    menuPrivate();
                    break;
                case "8":
                    menuDownloadCatalog(serverAddr);
                    break;
                case "9":
                    menuUploadCatalog(serverAddr);
                    break;
                case "10":
                    menuLiveTicker();
                    break;
                case "11":
                    menuSell();
                    break;
                case "0":
                    showResult(request("EXIT"));
                    return;
                default:
                    System.out.println("[!] Pilihan tidak tersedia. Masukkan angka 0 sampai 11.");
                    break;
            }
        }
    }

    // ------------------------------------------------------------------
    //  SELL: peserta mengajukan barang untuk dilelang
    // ------------------------------------------------------------------
    private static void menuSell() throws IOException {
        String name = ask("Nama barang (3-40 karakter: huruf, angka, spasi, dan tanda ().,'-): ");
        if (name == null) return;
        name = name.trim().replaceAll("\\s+", " ");
        if (!name.matches("[A-Za-z0-9 ().,'-]{3,40}")) {
            System.out.println("[!] Nama barang harus 3-40 karakter (huruf, angka, spasi, dan tanda ().,'-).");
            return;
        }
        String price = ask("Harga awal dalam RUPIAH tanpa titik/koma (minimal 1000): ");
        if (price == null || !price.matches("\\d{4,12}") || Long.parseLong(price) < 1000) {
            System.out.println("[!] Harga awal harus angka bulat minimal 1000 (maksimal 12 digit).");
            return;
        }

        // Berkas katalog bersifat opsional dan harus sudah ada di server (unggah dulu lewat menu 9)
        List<String> files = new ArrayList<>();
        Response cat = request("CATALOG");
        if (cat.ok()) {
            for (String entry : cat.message.split("#")) {
                String[] p = entry.split(";");
                if (p.length >= 1 && p[0].toLowerCase(Locale.ROOT).matches(".+\\.(png|jpe?g|pdf)")) {
                    files.add(p[0]);
                }
            }
        }
        String fileArg = "";
        if (!files.isEmpty()) {
            System.out.println("\nBerkas katalog yang tersedia (unggah dulu lewat menu 9 bila belum ada):");
            for (int i = 0; i < files.size(); i++) {
                System.out.println("  " + (i + 1) + " = " + files.get(i));
            }
            System.out.println("  Enter = tanpa berkas katalog");
            String pick = ask("Pilih nomor berkas: ");
            if (pick == null) return;
            if (!pick.isEmpty()) {
                if (!pick.matches("\\d{1,3}") || Integer.parseInt(pick) < 1 || Integer.parseInt(pick) > files.size()) {
                    System.out.println("[!] Nomor berkas tidak valid.");
                    return;
                }
                fileArg = " " + files.get(Integer.parseInt(pick) - 1);
            }
        }
        showResult(request("SELL " + name + " " + price + fileArg));
    }

    /** Validasi berkas sebelum diunggah (sama dengan aturan server). Mengembalikan pesan error, atau null bila lolos. */
    private static String validateUploadFile(File f) {
        String name = f.getName();
        if (!name.matches("[A-Za-z0-9_.-]{1,60}")) {
            return "Nama berkas hanya boleh huruf, angka, titik, garis bawah, dan strip (maks. 60 karakter). Ubah nama berkas dulu.";
        }
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXT.contains(ext)) {
            return "Tipe berkas tidak diizinkan. Hanya PNG, JPG, atau PDF.";
        }
        if (f.length() <= 0) {
            return "Berkas kosong.";
        }
        if (f.length() > MAX_UPLOAD_BYTES) {
            return "Ukuran berkas melebihi batas 5 MB.";
        }
        byte[] h = new byte[8];
        int n;
        try (FileInputStream in = new FileInputStream(f)) {
            n = in.readNBytes(h, 0, h.length);
        } catch (IOException e) {
            return "Berkas tidak dapat dibaca: " + e.getMessage();
        }
        boolean ok;
        if (ext.equals("png")) {
            ok = n >= 4 && h[0] == (byte) 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
        } else if (ext.equals("pdf")) {
            ok = n >= 5 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F' && h[4] == '-';
        } else {
            ok = n >= 3 && h[0] == (byte) 0xFF && h[1] == (byte) 0xD8 && h[2] == (byte) 0xFF;
        }
        return ok ? null : "Isi berkas tidak cocok dengan tipenya (" + ext.toUpperCase(Locale.ROOT) + ").";
    }

    private static void menuBid() throws IOException {
        String id = ask("Masukkan ID barang yang ingin ditawar (contoh: 1): ");
        if (id == null || !id.matches("\\d{1,9}")) {
            System.out.println("[!] ID barang harus berupa angka bulat.");
            return;
        }
        String nominal = ask("Masukkan nominal tawaran dalam RUPIAH tanpa titik/koma (contoh: 5000000): ");
        if (nominal == null || !nominal.matches("\\d{1,15}")) {
            System.out.println("[!] Nominal tawaran harus berupa angka bulat positif.");
            return;
        }
        showResult(request("BID " + id + " " + nominal));
    }

    private static void menuStatus() throws IOException {
        String id = ask("Masukkan ID barang yang ingin dicek (contoh: 1): ");
        if (id == null || !id.matches("\\d{1,9}")) {
            System.out.println("[!] ID barang harus berupa angka bulat.");
            return;
        }
        showItems(request("STATUS " + id));
    }

    private static void menuConvert() throws IOException {
        String from = ask("Kode mata uang ASAL (3 huruf, misal USD): ");
        if (from == null) return;
        String to = ask("Kode mata uang TUJUAN (3 huruf, misal IDR): ");
        if (to == null) return;
        String amount = ask("Nominal yang dikonversi (misal 100 atau 100.50): ");
        if (amount == null) return;

        if (!from.matches("[A-Za-z]{3}") || !to.matches("[A-Za-z]{3}")) {
            System.out.println("[!] Kode mata uang harus 3 huruf.");
            return;
        }
        if (!amount.matches("\\d{1,12}(\\.\\d{1,2})?")) {
            System.out.println("[!] Format nominal angka tidak valid.");
            return;
        }
        showResult(request("CONVERT " + from + " " + to + " " + amount));
    }

    private static void menuBroadcast() throws IOException {
        String msg = ask("Ketik pesan broadcast untuk seluruh peserta ruang lelang: ");
        if (msg == null || msg.isEmpty()) return;
        showResult(request("BROADCAST " + msg));
    }

    private static void menuPrivate() throws IOException {
        String target = ask("Ketik username tujuan: ");
        if (target == null || target.isEmpty()) return;
        String msg = ask("Ketik isi pesan pribadi: ");
        if (msg == null || msg.isEmpty()) return;
        showResult(request("PRIVATE " + target + " " + msg));
    }

    // ------------------------------------------------------------------
    //  Pilar 3: Binary File Transfer & Chunking Stream (8KB)
    // ------------------------------------------------------------------
    private static void menuDownloadCatalog(InetAddress serverAddr) throws IOException {
        Response catResp = request("CATALOG");
        if (!catResp.ok()) {
            showResult(catResp);
            return;
        }
        System.out.println("\n=== DAFTAR BERKAS KATALOG DI SERVER ===");
        for (String entry : catResp.message.split("#")) {
            String[] parts = entry.split(";");
            if (parts.length >= 2) {
                System.out.println(" - " + parts[0] + " (" + parts[1] + " bytes)");
            }
        }

        String fileName = ask("Ketik nama berkas yang ingin diunduh (contoh: katalog_lelang.pdf): ");
        if (fileName == null || fileName.isEmpty()) return;

        Response infoResp = request("DOWNLOAD " + fileName);
        if (!infoResp.ok()) {
            showResult(infoResp);
            return;
        }

        String[] meta = infoResp.message.split(";");
        int filePort = Integer.parseInt(meta[0]);
        String serverFileName = meta[1];

        File downloadDir = new File("downloads");
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }
        File outFile = new File(downloadDir, serverFileName);

        System.out.println("[TRANSFER BINER] Membuka koneksi ke port " + filePort + " (buffer 8KB)...");
        try (
                Socket fileSocket = new Socket(serverAddr, filePort);
                DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(fileSocket.getOutputStream()));
                DataInputStream dis = new DataInputStream(new BufferedInputStream(fileSocket.getInputStream()))
        ) {
            // Kirim header permintaan unduhan & tutup arah kirim dengan shutdownOutput()
            dos.writeUTF("DOWNLOAD");
            dos.writeUTF(serverFileName);
            dos.flush();
            fileSocket.shutdownOutput();

            String status = dis.readUTF();
            if (!"OK".equals(status)) {
                System.out.println("[ERROR TRANSFER] " + dis.readUTF());
                return;
            }

            String confirmedName = dis.readUTF();
            long fileSize = dis.readLong();
            System.out.println("[METADATA] Berkas: " + confirmedName + " | Ukuran: " + fileSize + " bytes");

            byte[] buffer = new byte[CHUNK_SIZE];
            long remaining = fileSize;
            long totalRead = 0;

            try (BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(outFile))) {
                while (remaining > 0) {
                    int toRead = (int) Math.min(buffer.length, remaining);
                    int read = dis.read(buffer, 0, toRead);
                    if (read == -1) {
                        throw new EOFException("Stream berkas terputus sebelum selesai.");
                    }
                    fos.write(buffer, 0, read);
                    remaining -= read;
                    totalRead += read;
                }
            }
            System.out.println("[SUKSES] Berkas tersimpan di: " + outFile.getAbsolutePath()
                    + " (" + totalRead + " bytes diterima).");
        }
    }

    private static void menuUploadCatalog(InetAddress serverAddr) throws IOException {
        String localPath = ask("Path berkas lokal yang akan diunggah (PNG/JPG/PDF, maksimal 5 MB): ");
        if (localPath == null || localPath.isEmpty()) return;

        File file = new File(localPath);
        if (!file.exists() || !file.isFile()) {
            System.out.println("[!] Berkas lokal tidak ditemukan: " + localPath);
            return;
        }
        String problem = validateUploadFile(file);
        if (problem != null) {
            System.out.println("[!] " + problem);
            return;
        }

        System.out.println("[TRANSFER BINER] Mengunggah " + file.getName() + " (" + file.length() + " bytes)...");
        try (
                Socket fileSocket = new Socket(serverAddr, DEFAULT_FILE_PORT);
                DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(fileSocket.getOutputStream()));
                DataInputStream dis = new DataInputStream(new BufferedInputStream(fileSocket.getInputStream()))
        ) {
            // 1. Header metadata (mode, nama, ukuran)
            dos.writeUTF("UPLOAD");
            dos.writeUTF(file.getName());
            dos.writeLong(file.length());
            dos.flush();

            // 2. Chunking stream 8KB
            byte[] buffer = new byte[CHUNK_SIZE];
            try (BufferedInputStream fis = new BufferedInputStream(new FileInputStream(file))) {
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    dos.write(buffer, 0, read);
                }
            }
            dos.flush();

            // 3. Sinyal selesai kirim tanpa menutup soket
            fileSocket.shutdownOutput();

            String status = dis.readUTF();
            String msg = dis.readUTF();
            System.out.println("[" + status + "] " + msg);
        } catch (java.net.SocketException e) {
            System.out.println("[!] Server menutup koneksi saat unggah (berkas kemungkinan ditolak: nama sudah ada atau melanggar batas).");
        }
    }

    private static void menuLiveTicker() {
        System.out.println("\n=== LIVE TICKER FEED UDP (PEMBARUAN HARGA PER DETIK) ===");
        System.out.println("Snapshot Terakhir: " + latestTickerSnapshot);
        System.out.println("Memantau stream UDP selama 5 detik...");
        showLiveTicker.set(true);
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            showLiveTicker.set(false);
        }
        System.out.println("=== PEMANTAUAN TICKER SELESAI ===");
    }

    private static void showItems(Response r) {
        if (!r.ok()) {
            showResult(r);
            return;
        }
        System.out.println();
        System.out.println("==================== DATA BARANG LELANG ====================");
        for (String item : r.message.split("#")) {
            String[] f = item.split(";");
            if (f.length < 5) continue;
            String rupiah = String.format(Locale.forLanguageTag("id-ID"), "Rp %,d", Long.parseLong(f[2]));
            String usd = f[4].equals("-") ? "" : " (setara USD " + f[4] + ")";
            String catalog = f.length >= 6 ? f[5] : "-";
            String seller = f.length >= 7 ? f[6] : "-";
            if (seller.equals(currentUsername)) seller += " (Anda)";
            System.out.println("ID Barang          : " + f[0]);
            System.out.println("Nama Barang        : " + f[1]);
            System.out.println("Penjual            : " + seller);
            System.out.println("Tawaran Tertinggi  : " + rupiah + usd);
            System.out.println("Penawar Tertinggi  : " + f[3]);
            System.out.println("Berkas Katalog     : " + catalog);
            System.out.println("------------------------------------------------------------");
        }
    }
}
