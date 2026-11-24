package server;

import util.AppLogger;
import util.CurrencyService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AuctionManager {

    public static class BidResult {
        public final int code;
        public final String message;
        public final AuctionInfo info;
        public final String previousBidder;
        public boolean extended; // true bila anti-sniping memperpanjang waktu

        BidResult(int code, String message, AuctionInfo info, String previousBidder) {
            this.code = code;
            this.message = message;
            this.info = info;
            this.previousBidder = previousBidder;
        }
    }

    /** Penjual barang bawaan (mengandung spasi, sehingga tidak bisa dipakai sebagai username). */
    public static final String HOUSE = "Balai Lelang";
    /** Kategori yang dikenali server. Barang tanpa kategori (klien lama) masuk "lainnya". */
    public static final java.util.Set<String> CATEGORIES = java.util.Set.of("smartphone", "pclaptop", "aksesoris");
    public static final String DEFAULT_CATEGORY = "lainnya";
    public static final int MAX_LOTS = 20;
    public static final int MAX_LOTS_PER_SELLER = 3;

    private final Map<Integer, Auction> auctions = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(4); // ID barang baru dibuat atomik
    private final Object addLock = new Object();

    public AuctionManager() {
        // Barang bawaan langsung berjalan dan berlangsung 30 menit sejak server menyala
        long now = System.currentTimeMillis();
        long end = now + 30 * Auction.MINUTE_MS;
        auctions.put(1, new Auction(1, "Laptop ASUS ROG (bekas)", 4_000_000L, "foto_laptop_rog.png", HOUSE, "pclaptop", now, end));
        auctions.put(2, new Auction(2, "iPhone 15", 10_000_000L, "katalog_lelang.pdf", HOUSE, "smartphone", now, end));
        auctions.put(3, new Auction(3, "Keyboard Mechanical", 500_000L, "brosur_lengkap_lelang.pdf", HOUSE, "aksesoris", now, end));
    }

    /** Menambah barang lelang baru oleh peserta (perintah SELL). Pengecekan kuota + penambahan atomik. */
    public BidResult addAuction(String name, long startingBid, String catalogFile, String seller, String category,
                                int startDelayMin, int durationMin) {
        long now = System.currentTimeMillis();
        synchronized (addLock) {
            if (!"-".equals(catalogFile) && !new java.io.File("catalog_files", catalogFile).isFile()) {
                return new BidResult(404, "Berkas '" + catalogFile + "' tidak ditemukan di katalog server.", null, null);
            }
            long active = auctions.values().stream().filter(a -> a.tick(now) != Auction.State.ENDED).count();
            if (active >= MAX_LOTS) {
                return new BidResult(409, "Ruang lelang penuh (maksimal " + MAX_LOTS + " barang).", null, null);
            }
            long mine = auctions.values().stream()
                    .filter(a -> seller.equals(a.getSeller()) && a.tick(now) != Auction.State.ENDED).count();
            if (mine >= MAX_LOTS_PER_SELLER) {
                return new BidResult(409, "Maksimal " + MAX_LOTS_PER_SELLER + " barang aktif per penjual.", null, null);
            }
            int id = nextId.getAndIncrement();
            Auction a = new Auction(id, name, startingBid, catalogFile, seller, category,
                    now + startDelayMin * Auction.MINUTE_MS, now + (startDelayMin + durationMin) * Auction.MINUTE_MS);
            auctions.put(id, a);
            return new BidResult(200, "Barang ditambahkan.", a.toInfo(), null);
        }
    }

    /** Penjual menghapus barang miliknya. Hanya boleh sebelum ada penawaran (adil bagi penawar). */
    public BidResult deleteAuction(int auctionId, String user) {
        Auction auction = auctions.get(auctionId);
        if (auction == null) {
            return new BidResult(404, "Barang dengan ID " + auctionId + " tidak ditemukan.", null, null);
        }
        if (!user.equals(auction.getSeller())) {
            return new BidResult(403, "Hanya penjual yang dapat menghapus barang ini.", auction.toInfo(), null);
        }
        synchronized (auction) { // atomik terhadap placeBid pada barang yang sama
            if (auction.isClosed()) {
                return new BidResult(404, "Barang dengan ID " + auctionId + " sudah dihapus.", null, null);
            }
            if (auction.hasBids()) {
                return new BidResult(409, "Barang sudah memiliki penawaran sehingga tidak dapat dihapus.", auction.toInfo(), null);
            }
            auction.close();
            auctions.remove(auctionId);
            return new BidResult(200, "Barang dihapus.", auction.toInfo(), null);
        }
    }

    /** Menghapus berkas katalog. Hanya pengunggahnya, dan hanya bila tidak dipakai barang lelang aktif. */
    public BidResult deleteFile(String fileName, String user) {
        synchronized (addLock) { // sama dengan addAuction: cegah berkas dihapus saat sedang ditautkan ke barang
            java.io.File f = new java.io.File("catalog_files", fileName);
            if (!f.isFile()) {
                return new BidResult(404, "Berkas '" + fileName + "' tidak ditemukan di katalog.", null, null);
            }
            String owner = FileRegistry.ownerOf(fileName);
            if (!user.equals(owner)) {
                String why = (owner.equals(HOUSE) || owner.equals("-"))
                        ? "Berkas bawaan/tanpa pemilik tidak dapat dihapus."
                        : "Hanya pengunggah berkas (" + owner + ") yang dapat menghapusnya.";
                return new BidResult(403, why, null, null);
            }
            for (Auction a : auctions.values()) {
                if (fileName.equals(a.getCatalogFileName()) && a.tick(System.currentTimeMillis()) != Auction.State.ENDED) {
                    return new BidResult(409, "Berkas dipakai oleh barang #" + a.getId()
                            + ". Hapus barangnya dulu.", null, null);
                }
            }
            if (!f.delete()) {
                return new BidResult(500, "Berkas gagal dihapus dari server.", null, null);
            }
            FileRegistry.removeOwner(fileName);
            return new BidResult(200, "Berkas '" + fileName + "' dihapus dari katalog.", null, null);
        }
    }

    /** Kelipatan tawaran minimum menurut harga saat ini: Rp 10.000 di bawah Rp 1 juta, Rp 50.000 di atasnya. */
    public static long minIncrement(long currentBid) {
        return currentBid < 1_000_000L ? 10_000L : 50_000L;
    }

    public static String formatDuration(long sec) {
        if (sec >= 3600) return (sec / 3600) + " jam " + ((sec % 3600) / 60) + " menit";
        if (sec >= 60) return (sec / 60) + " menit " + (sec % 60) + " detik";
        return sec + " detik";
    }

    /** Riwayat tawaran barang (terbaru lebih dulu), atau null bila barang tidak ada. */
    public List<Auction.BidRecord> getHistory(int auctionId) {
        Auction a = auctions.get(auctionId);
        return a == null ? null : a.getHistory();
    }

    // ---------------- Penjadwal otomatis ----------------
    private static final int MAX_ENDED_HISTORY = 20;

    /** Menjalankan thread penjadwal: membuka barang saat waktunya tiba dan menutupnya saat waktu habis. */
    public void startScheduler() {
        java.util.concurrent.ScheduledExecutorService ses = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auction-scheduler");
            t.setDaemon(true);
            return t;
        });
        ses.scheduleAtFixedRate(() -> {
            try {
                tickAll();
            } catch (Exception e) {
                System.out.println("[SCHEDULER ERROR] " + e);
            }
        }, 500, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    void tickAll() {
        long now = System.currentTimeMillis();
        List<Auction> ended = new ArrayList<>();
        for (Auction a : auctions.values()) {
            a.tick(now);
            if (a.claimStartAnnouncement()) {
                announceStart(a.toInfo());
            }
            if (a.claimEndAnnouncement()) {
                announceEnd(a.toInfo());
            }
            if (a.tick(now) == Auction.State.ENDED) {
                ended.add(a);
            }
        }
        // Batasi riwayat barang selesai agar memori tidak membesar
        if (ended.size() > MAX_ENDED_HISTORY) {
            ended.sort(java.util.Comparator.comparingLong(Auction::getEndAt));
            for (int i = 0; i < ended.size() - MAX_ENDED_HISTORY; i++) {
                auctions.remove(ended.get(i).getId());
            }
        }
    }

    private void announceStart(AuctionInfo i) {
        String harga = CurrencyService.formatIdr(i.currentBid);
        AuctionServer.broadcastAsync("AUCTION_START [Barang #" + i.id + " - " + i.itemName + "] lelang DIMULAI! Harga awal "
                + harga + ", berakhir dalam " + formatDuration(i.endsInSec) + ".", null);
        System.out.println("[AUCTION START] #" + i.id + " " + i.itemName);
        AppLogger.log("AUCTION START | Barang=" + i.id + " | Nama=" + i.itemName);
    }

    private void announceEnd(AuctionInfo i) {
        if (!"-".equals(i.highestBidder)) {
            String harga = CurrencyService.formatIdr(i.currentBid);
            AuctionServer.broadcastAsync("AUCTION_END [Barang #" + i.id + " - " + i.itemName + "] SELESAI. Pemenang: "
                    + i.highestBidder + " dengan " + harga + ".", null);
            AuctionServer.sendPrivateAsync(i.highestBidder, "WINNER Selamat! Anda memenangkan [" + i.itemName + "] seharga "
                    + harga + ". Hubungi penjual: " + i.seller + ".");
            AuctionServer.sendPrivateAsync(i.seller, "SOLD Barang Anda [" + i.itemName + "] terjual kepada "
                    + i.highestBidder + " seharga " + harga + ".");
            System.out.println("[AUCTION END] #" + i.id + " " + i.itemName + " -> pemenang " + i.highestBidder + " (" + harga + ")");
            AppLogger.log("AUCTION END | Barang=" + i.id + " | Pemenang=" + i.highestBidder + " | IDR=" + i.currentBid);
        } else {
            AuctionServer.broadcastAsync("AUCTION_END [Barang #" + i.id + " - " + i.itemName
                    + "] SELESAI tanpa penawaran (tidak terjual).", null);
            AuctionServer.sendPrivateAsync(i.seller, "UNSOLD Barang Anda [" + i.itemName + "] tidak terjual karena tidak ada penawaran.");
            System.out.println("[AUCTION END] #" + i.id + " " + i.itemName + " -> tidak terjual");
            AppLogger.log("AUCTION END | Barang=" + i.id + " | Pemenang=- (tidak terjual)");
        }
    }

    public List<AuctionInfo> getAll() {
        List<AuctionInfo> list = new ArrayList<>();
        // Urutkan berdasarkan ID barang agar tampilan konsisten
        auctions.keySet().stream().sorted().forEach(id -> {
            Auction a = auctions.get(id);
            if (a != null) {
                list.add(a.toInfo());
            }
        });
        return list;
    }

    public AuctionInfo get(int auctionId) {
        Auction a = auctions.get(auctionId);
        return a == null ? null : a.toInfo();
    }

    /**
     * Memproses tawaran lelang secara atomik per item barang.
     * Mencatat previousBidder agar server dapat mengirim notifikasi OUTBID secara real-time.
     */
    public BidResult placeBid(int auctionId, long bid, String username) {
        Auction auction = auctions.get(auctionId);

        if (auction == null) {
            return new BidResult(404, "Barang dengan ID " + auctionId + " tidak ditemukan.", null, null);
        }

        if (username.equals(auction.getSeller())) {
            return new BidResult(403, "Anda tidak boleh menawar barang yang Anda jual sendiri.", auction.toInfo(), null);
        }

        synchronized (auction) {
            if (auction.isClosed()) {
                return new BidResult(404, "Barang dengan ID " + auctionId + " sudah dihapus penjual.", null, null);
            }
            long now = System.currentTimeMillis();
            Auction.State st = auction.tick(now);
            if (st == Auction.State.PENDING) {
                AuctionInfo i = auction.toInfo();
                return new BidResult(409, "Lelang belum dimulai. Dibuka dalam " + formatDuration(i.startsInSec) + ".", i, null);
            }
            if (st == Auction.State.ENDED) {
                return new BidResult(409, "Lelang untuk barang ini sudah berakhir.", auction.toInfo(), null);
            }
            AuctionInfo cur = auction.toInfo();
            if (bid < cur.minBid) {
                return new BidResult(409, "Penawaran ditolak. Nominal minimal " + CurrencyService.formatIdr(cur.minBid)
                        + (auction.hasBids() ? " (tawaran tertinggi + kelipatan " + CurrencyService.formatIdr(cur.increment) + ")." : " (harga awal)."),
                        cur, null);
            }

            String prevBidder = auction.getHighestBidder();
            auction.update(bid, username, now);
            boolean extended = auction.extendIfSniping(now); // anti-sniping
            BidResult res = new BidResult(200, "Penawaran diterima.", auction.toInfo(), prevBidder);
            res.extended = extended;
            return res;
        }
    }
}
