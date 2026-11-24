package server;

import server.AuctionManager.BidResult;
import util.AppLogger;
import util.CurrencyService;
import util.CurrencyService.CurrencyException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public class ClientHandler implements Runnable {

    private static final int IDLE_TIMEOUT_MS = 10 * 60 * 1000; // 10 menit idle timeout
    private static final int MAX_LINE_LENGTH = 512;
    private static final String CRLF = "\r\n";

    private final Socket socket;
    private final String clientIp;
    private PrintWriter output;
    private String username = null;

    public ClientHandler(Socket socket) {
        this.socket = socket;
        this.clientIp = socket.getInetAddress().getHostAddress();
    }

    public String getUsername() {
        return username;
    }

    private String who() {
        return username == null ? "(belum login)" : username;
    }

    /** Mengirim respons sinkron atas perintah klien (thread-safe). */
    public synchronized void reply(int code, String message) {
        if (output != null) {
            String status = (code == 200) ? "SUCCESS" : "ERROR";
            output.print(code + " " + status + " " + message + CRLF);
            output.flush();
        }
    }

    /** Mengirim event/pesan push asynchronous ke Background Receiver Thread klien (thread-safe). */
    public synchronized void sendAsync(String asyncPayload) {
        if (output != null) {
            output.print("ASYNC " + asyncPayload + CRLF);
            output.flush();
        }
    }

    @Override
    public void run() {
        try (
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), false)
        ) {
            this.output = out;
            socket.setSoTimeout(IDLE_TIMEOUT_MS);

            String line;
            while ((line = in.readLine()) != null) {
                if (!process(line)) {
                    break;
                }
            }
        } catch (SocketTimeoutException e) {
            reply(408, "Koneksi diputus karena tidak ada aktivitas selama 10 menit.");
            System.out.println("[TIMEOUT] " + who() + " (" + clientIp + ")");
            AppLogger.log("TIMEOUT | User=" + who() + " | IP=" + clientIp);
        } catch (IOException e) {
            System.out.println("[DISCONNECT ABRUPT] " + who() + " : " + e.getMessage());
            AppLogger.log("CONNECTION ERROR | User=" + who() + " | IP=" + clientIp + " | " + e.getMessage());
        } finally {
            try {
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException ignored) {
            }
            if (username != null) {
                AuctionServer.removeSession(username);
                AuctionServer.broadcastAsync("SYSTEM: Peserta lelang '" + username + "' telah keluar dari ruang lelang.", username);
            }
            System.out.println("[DISCONNECT] " + who() + " (" + clientIp + ")");
            AppLogger.log("DISCONNECT | User=" + who() + " | IP=" + clientIp);
            AuctionServer.clientDisconnected();
        }
    }

    private boolean process(String rawLine) {
        String line = rawLine.trim();
        if (line.isEmpty()) {
            reply(400, "Perintah kosong. Gunakan: LOGIN, LIST, BID, SELL, DELLOT, DELFILE, UPTICKET, HISTORY, STATUS, CONVERT, BROADCAST, PRIVATE, CATALOG, DOWNLOAD, EXIT.");
            return true;
        }
        if (line.length() > MAX_LINE_LENGTH) {
            reply(400, "Perintah terlalu panjang (maksimal " + MAX_LINE_LENGTH + " karakter).");
            return true;
        }

        String[] p = line.split("\\s+");
        String command = p[0].toUpperCase(Locale.ROOT);

        switch (command) {
            case "LOGIN":
                return doLogin(p);
            case "LIST":
                return doList(p);
            case "USERS":
                return doUsers();
            case "BID":
                return doBid(p);
            case "SELL":
                return doSell(p);
            case "HISTORY":
                return doHistory(p);
            case "DELLOT":
                return doDelLot(p);
            case "DELFILE":
                return doDelFile(p);
            case "UPTICKET":
                if (!requireLogin()) return true;
                reply(200, FileRegistry.issueTicket(username));
                return true;
            case "STATUS":
                return doStatus(p);
            case "CONVERT":
                return doConvert(p);
            case "BROADCAST":
                return doBroadcast(line);
            case "PRIVATE":
            case "PRIVATE_MSG":
            case "MSG":
                return doPrivateMsg(line);
            case "CATALOG":
                return doCatalog();
            case "DOWNLOAD":
                return doDownloadInfo(p);
            case "EXIT":
            case "QUIT":
                reply(200, "Terima kasih telah menggunakan NetAuction Live. Sampai jumpa!");
                AppLogger.log("EXIT | User=" + who());
                return false;
            default:
                reply(400, "Perintah \"" + p[0] + "\" tidak dikenal.");
                return true;
        }
    }

    private boolean requireLogin() {
        if (username == null) {
            reply(401, "Anda belum login. Kirim: LOGIN <username>");
            return false;
        }
        return true;
    }

    // ---------------- LOGIN ----------------
    private boolean doLogin(String[] p) {
        if (username != null) {
            reply(409, "Anda sudah login sebagai " + username + ".");
            return true;
        }
        if (p.length != 2) {
            reply(400, "Format salah. Gunakan: LOGIN <username>");
            return true;
        }
        String name = p[1];
        if (!name.matches("[A-Za-z0-9_]{3,15}")) {
            reply(400, "Username harus 3-15 karakter (huruf, angka, atau _).");
            return true;
        }
        if (!AuctionServer.registerSession(name, this)) {
            reply(409, "Username \"" + name + "\" sedang dipakai pengguna lain.");
            AppLogger.log("LOGIN DITOLAK | User=" + name + " | IP=" + clientIp);
            return true;
        }
        username = name;
        System.out.println("[LOGIN] " + username + " (" + clientIp + ")");
        AppLogger.log("LOGIN | User=" + username + " | IP=" + clientIp);
        reply(200, "Login Berhasil. Selamat datang di NetAuction Live, " + username + "!");
        AuctionServer.broadcastAsync("SYSTEM: '" + username + "' bergabung ke ruang lelang.", username);
        return true;
    }

    // ---------------- LIST (Barang Lelang & Peserta Aktif) ----------------
    private boolean doList(String[] p) {
        if (!requireLogin()) return true;
        if (p.length > 1 && "USERS".equalsIgnoreCase(p[1])) {
            return doUsers();
        }
        if (p.length != 1) {
            reply(400, "Format salah. Gunakan: LIST");
            return true;
        }
        List<AuctionInfo> all = AuctionServer.auctionManager.getAll();
        Double idrToUsd = idrToUsdRate();

        StringBuilder sb = new StringBuilder();
        for (AuctionInfo a : all) {
            if (sb.length() > 0) sb.append('#');
            sb.append(encodeItem(a, idrToUsd));
        }
        reply(200, sb.toString());
        AppLogger.log("LIST | User=" + username);
        return true;
    }

    private boolean doUsers() {
        if (!requireLogin()) return true;
        String activeUsers = String.join(", ", AuctionServer.getActiveUsernames());
        reply(200, "Pengguna aktif (" + AuctionServer.getActiveSessionCount() + "): " + activeUsers);
        return true;
    }

    // ---------------- BROADCAST (Pilar 2) ----------------
    private boolean doBroadcast(String rawLine) {
        if (!requireLogin()) return true;
        int firstSpace = rawLine.indexOf(' ');
        if (firstSpace == -1 || rawLine.substring(firstSpace + 1).trim().isEmpty()) {
            reply(400, "Format salah. Gunakan: BROADCAST <pesan>");
            return true;
        }
        String msg = rawLine.substring(firstSpace + 1).trim();
        AuctionServer.broadcastAsync("BROADCAST [" + username + "]: " + msg, username);
        reply(200, "Pesan broadcast terkirim ke seluruh peserta aktif.");
        AppLogger.log("BROADCAST | User=" + username + " | Msg=" + msg);
        return true;
    }

    // ---------------- PRIVATE MSG (Pilar 2) ----------------
    private boolean doPrivateMsg(String rawLine) {
        if (!requireLogin()) return true;
        String[] parts = rawLine.split("\\s+", 3);
        if (parts.length < 3 || parts[2].trim().isEmpty()) {
            reply(400, "Format salah. Gunakan: PRIVATE <username_tujuan> <pesan>");
            return true;
        }
        String targetUser = parts[1].trim();
        String msg = parts[2].trim();

        if (targetUser.equals(username)) {
            reply(400, "Tidak dapat mengirim pesan pribadi ke diri sendiri.");
            return true;
        }

        boolean sent = AuctionServer.sendPrivateAsync(targetUser, "PRIVATE [dari " + username + "]: " + msg);
        if (!sent) {
            reply(404, "Pengguna '" + targetUser + "' tidak ditemukan atau sedang offline.");
            return true;
        }
        reply(200, "Pesan pribadi terkirim ke " + targetUser + ".");
        AppLogger.log("PRIVATE | From=" + username + " | To=" + targetUser);
        return true;
    }

    // ---------------- BID + OUTBID BROADCAST (Skenario Kelompok 7) ----------------
    private boolean doBid(String[] p) {
        if (!requireLogin()) return true;
        if (p.length != 3 || !p[1].matches("\\d{1,9}") || !p[2].matches("\\d{1,15}")) {
            reply(400, "Format salah. Gunakan: BID <auction_id> <nominal_Rupiah>. Contoh: BID 1 5000000");
            return true;
        }
        int id = Integer.parseInt(p[1]);
        long bid = Long.parseLong(p[2]);

        BidResult result = AuctionServer.auctionManager.placeBid(id, bid, username);

        if (result.code == 200) {
            AuctionInfo a = result.info;
            String formattedPrice = CurrencyService.formatIdr(a.currentBid) + usdSuffix(a.currentBid);
            reply(200, "Penawaran diterima! " + a.itemName + " sekarang " + formattedPrice
                    + ", penawar tertinggi: " + a.highestBidder + "."
                    + (result.extended ? " Waktu lelang diperpanjang (anti-sniping)." : ""));

            // Kirim peringatan OUTBID khusus ke penawar tertinggi sebelumnya jika berbeda user
            if (result.previousBidder != null && !result.previousBidder.equals("-")
                    && !result.previousBidder.equals(username)) {
                AuctionServer.sendPrivateAsync(result.previousBidder,
                        "OUTBID! Tawaran Anda pada [" + a.itemName + "] telah dilampaui oleh "
                                + username + " sebesar " + formattedPrice + "!");
            }

            // Siarkan pembaruan bid ke seluruh peserta ruang lelang secara asynchronous
            AuctionServer.broadcastAsync("BID_UPDATE [Barang #" + a.id + " - " + a.itemName
                    + "] Tawaran baru: " + formattedPrice + " oleh " + username
                    + (result.extended ? " (waktu diperpanjang)" : ""), username);
        } else {
            reply(result.code, result.message);
        }
        AppLogger.log("BID | User=" + username + " | Barang=" + id + " | IDR=" + bid + " | Hasil=" + result.code);
        return true;
    }

    // ---------------- SELL (peserta mengajukan barang untuk dilelang) ----------------
    private static final java.util.regex.Pattern LOT_FILE =
            java.util.regex.Pattern.compile("[A-Za-z0-9_.-]{1,60}\\.(png|jpg|jpeg|pdf)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Format: SELL <nama_barang> <harga_awal> [nama_berkas]. Berkas harus sudah diunggah ke katalog. */
    private boolean doSell(String[] p) {
        if (!requireLogin()) return true;
        int end = p.length;
        String file = "-";
        String category = AuctionManager.DEFAULT_CATEGORY;
        int startMin = 0;      // mulai sekarang
        int durationMin = 15;  // durasi bawaan untuk klien lama
        // Opsi jadwal di ujung perintah (urutan bebas): +S<menit_sampai_mulai> dan +D<durasi_menit>
        while (end >= 4 && p[end - 1].matches("\\+[SD]\\d{1,3}")) {
            int v = Integer.parseInt(p[end - 1].substring(2));
            if (p[end - 1].charAt(1) == 'S') startMin = v; else durationMin = v;
            end--;
        }
        if (!java.util.Set.of(0, 1, 5, 15, 30, 60).contains(startMin)) {
            reply(400, "Waktu mulai tidak valid. Pilihan (menit): +S0, +S1, +S5, +S15, +S30, +S60.");
            return true;
        }
        if (!java.util.Set.of(1, 5, 15, 30, 60).contains(durationMin)) {
            reply(400, "Durasi tidak valid. Pilihan (menit): +D1, +D5, +D15, +D30, +D60.");
            return true;
        }
        if (end >= 2 && p[end - 1].startsWith("@")) {
            String c = p[end - 1].substring(1).toLowerCase(Locale.ROOT);
            if (!AuctionManager.CATEGORIES.contains(c)) {
                reply(400, "Kategori tidak dikenal. Pilihan: @smartphone, @pclaptop, @aksesoris.");
                return true;
            }
            category = c;
            end--;
        }
        if (end >= 4 && LOT_FILE.matcher(p[end - 1]).matches()) {
            file = p[end - 1];
            end--;
        }
        if (end < 3 || !p[end - 1].matches("\\d{4,12}")) {
            reply(400, "Format salah. Gunakan: SELL <nama_barang> <harga_awal> [nama_berkas]. Contoh: SELL Jam Tangan Seiko 750000 jam.png");
            return true;
        }
        long price = Long.parseLong(p[end - 1]);
        String name = String.join(" ", java.util.Arrays.asList(p).subList(1, end - 1));
        if (!name.matches("[A-Za-z0-9 ().,'-]{3,40}")) {
            reply(400, "Nama barang harus 3-40 karakter (huruf, angka, spasi, dan tanda ().,'-).");
            return true;
        }
        if (price < 1_000) {
            reply(400, "Harga awal minimal Rp 1.000.");
            return true;
        }
        if (!"-".equals(file) && !new File("catalog_files", file).isFile()) {
            reply(404, "Berkas '" + file + "' belum ada di katalog server. Unggah dulu sebelum menjual.");
            return true;
        }
        BidResult r = AuctionServer.auctionManager.addAuction(name, price, file, username, category, startMin, durationMin);
        if (r.code != 200) {
            reply(r.code, r.message);
            return true;
        }
        AuctionInfo a = r.info;
        String harga = CurrencyService.formatIdr(a.currentBid);
        String jadwal = startMin == 0 ? "langsung berjalan" : "mulai dalam " + startMin + " menit";
        reply(200, "Barang #" + a.id + " '" + a.itemName + "' diajukan dengan harga awal " + harga + ": " + jadwal
                + ", durasi " + durationMin + " menit.");
        AuctionServer.broadcastAsync("NEW_LOT [Barang #" + a.id + " - " + a.itemName + "] dijual oleh "
                + username + ", harga awal " + harga + " (" + jadwal + ", durasi " + durationMin + " menit)", username);
        AppLogger.log("SELL | User=" + username + " | Barang=" + a.id + " | Nama=" + a.itemName + " | IDR=" + price + " | File=" + file + " | Kategori=" + category + " | Mulai=" + startMin + "m | Durasi=" + durationMin + "m");
        System.out.println("[SELL] " + username + " melelang '" + a.itemName + "' (" + harga + ")");
        return true;
    }

    // ---------------- HISTORY ----------------
    /** Format: HISTORY <auction_id>  ->  penawar;nominal;detik_yang_lalu#... (terbaru lebih dulu). */
    private boolean doHistory(String[] p) {
        if (!requireLogin()) return true;
        if (p.length != 2 || !p[1].matches("\\d{1,9}")) {
            reply(400, "Format salah. Gunakan: HISTORY <auction_id>");
            return true;
        }
        java.util.List<Auction.BidRecord> h = AuctionServer.auctionManager.getHistory(Integer.parseInt(p[1]));
        if (h == null) {
            reply(404, "Barang dengan ID " + p[1] + " tidak ditemukan.");
            return true;
        }
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        for (Auction.BidRecord b : h) {
            if (sb.length() > 0) sb.append('#');
            sb.append(b.bidder).append(';').append(b.amount).append(';').append(Math.max(0, (now - b.at) / 1000));
        }
        reply(200, sb.toString());
        return true;
    }

    // ---------------- DELLOT / DELFILE ----------------
    private boolean doDelLot(String[] p) {
        if (!requireLogin()) return true;
        if (p.length != 2 || !p[1].matches("\\d{1,9}")) {
            reply(400, "Format salah. Gunakan: DELLOT <auction_id>");
            return true;
        }
        int id = Integer.parseInt(p[1]);
        BidResult r = AuctionServer.auctionManager.deleteAuction(id, username);
        if (r.code != 200) {
            reply(r.code, r.message);
        } else {
            reply(200, "Barang #" + id + " '" + r.info.itemName + "' dihapus dari ruang lelang.");
            AuctionServer.broadcastAsync("LOT_REMOVED [Barang #" + id + " - " + r.info.itemName
                    + "] dihapus oleh penjualnya, " + username, username);
        }
        AppLogger.log("DELLOT | User=" + username + " | Barang=" + id + " | Hasil=" + r.code);
        return true;
    }

    private boolean doDelFile(String[] p) {
        if (!requireLogin()) return true;
        if (p.length != 2) {
            reply(400, "Format salah. Gunakan: DELFILE <nama_berkas>");
            return true;
        }
        String name = new File(p[1]).getName();
        BidResult r = AuctionServer.auctionManager.deleteFile(name, username);
        reply(r.code, r.message);
        if (r.code == 200) {
            AuctionServer.broadcastAsync("FILE_REMOVED Berkas '" + name + "' dihapus dari katalog oleh " + username, username);
        }
        AppLogger.log("DELFILE | User=" + username + " | File=" + name + " | Hasil=" + r.code);
        return true;
    }

    // ---------------- STATUS ----------------
    private boolean doStatus(String[] p) {
        if (!requireLogin()) return true;
        if (p.length != 2 || !p[1].matches("\\d{1,9}")) {
            reply(400, "Format salah. Gunakan: STATUS <auction_id>");
            return true;
        }
        int id = Integer.parseInt(p[1]);
        AuctionInfo info = AuctionServer.auctionManager.get(id);
        if (info == null) {
            reply(404, "Barang dengan ID " + id + " tidak ditemukan.");
            return true;
        }
        reply(200, encodeItem(info, idrToUsdRate()));
        AppLogger.log("STATUS | User=" + username + " | Barang=" + id);
        return true;
    }

    // ---------------- CONVERT (Pilar 4) ----------------
    private boolean doConvert(String[] p) {
        if (!requireLogin()) return true;
        if (p.length != 4 || !p[1].matches("[A-Za-z]{3}") || !p[2].matches("[A-Za-z]{3}")
                || !p[3].matches("\\d{1,12}(\\.\\d{1,2})?")) {
            reply(400, "Format salah. Gunakan: CONVERT <dari> <ke> <nominal>. Contoh: CONVERT USD IDR 100");
            return true;
        }
        String from = p[1].toUpperCase(Locale.ROOT);
        String to = p[2].toUpperCase(Locale.ROOT);
        double amount = Double.parseDouble(p[3]);

        try {
            double rate = CurrencyService.getRate(from, to);
            double result = amount * rate;
            reply(200, String.format(Locale.US, "%.2f %s = %.2f %s (kurs 1 %s = %s %s)",
                    amount, from, result, to, from, CurrencyService.plain(rate), to));
            AppLogger.log("CONVERT | User=" + username + " | " + p[3] + " " + from + " -> " + to
                    + " | Hasil=" + String.format(Locale.US, "%.2f", result));
        } catch (CurrencyException e) {
            reply(e.unsupported ? 404 : 503, e.getMessage());
            AppLogger.log("CONVERT GAGAL | User=" + username + " | " + from + " -> " + to + " | " + e.getMessage());
        }
        return true;
    }

    // ---------------- CATALOG & DOWNLOAD METADATA (Pilar 3) ----------------
    private boolean doCatalog() {
        if (!requireLogin()) return true;
        File dir = new File("catalog_files");
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            reply(404, "Belum ada berkas katalog yang tersedia di server.");
            return true;
        }
        StringBuilder sb = new StringBuilder();
        for (File f : files) {
            if (f.isFile()) {
                if (sb.length() > 0) sb.append('#');
                sb.append(f.getName()).append(';').append(f.length()).append(';').append(FileRegistry.ownerOf(f.getName()));
            }
        }
        reply(200, sb.toString());
        return true;
    }

    private boolean doDownloadInfo(String[] p) {
        if (!requireLogin()) return true;
        if (p.length != 2) {
            reply(400, "Format salah. Gunakan: DOWNLOAD <nama_berkas>");
            return true;
        }
        String safeName = new File(p[1]).getName();
        File file = new File("catalog_files", safeName);
        if (!file.exists() || !file.isFile()) {
            reply(404, "Berkas '" + safeName + "' tidak tersedia di server.");
            return true;
        }
        // Beri tahu klien port transfer biner (5001), nama berkas, dan ukuran dalam byte
        reply(200, AuctionServer.FILE_PORT + ";" + file.getName() + ";" + file.length());
        return true;
    }

    private Double idrToUsdRate() {
        try {
            return CurrencyService.getRate("IDR", "USD");
        } catch (CurrencyException e) {
            return null;
        }
    }

    private String usdSuffix(long idr) {
        Double rate = idrToUsdRate();
        return rate == null ? "" : String.format(Locale.US, " (setara USD %.2f)", idr * rate);
    }

    private String encodeItem(AuctionInfo a, Double idrToUsd) {
        String usd = idrToUsd == null ? "-" : String.format(Locale.US, "%.2f", a.currentBid * idrToUsd);
        return a.id + ";" + a.itemName + ";" + a.currentBid + ";" + a.highestBidder + ";" + usd + ";" + a.catalogFileName + ";" + a.seller + ";" + a.category
                + ";" + a.status + ";" + a.startsInSec + ";" + a.endsInSec + ";" + a.increment
                + ";" + a.startPrice + ";" + a.bidCount + ";" + a.minBid;
    }
}
