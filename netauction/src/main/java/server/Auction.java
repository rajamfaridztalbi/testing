package server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
public class Auction {

    public enum State { PENDING, RUNNING, ENDED }

    /** Satu "menit" lelang dalam milidetik. Dapat diperkecil untuk pengujian: -Dnetauction.minuteMs=2000 */
    public static final long MINUTE_MS = Long.getLong("netauction.minuteMs", 60_000L);
    /** Anti-sniping: tawaran pada 30 detik terakhir memperpanjang waktu menjadi 30 detik lagi. */
    public static final long SNIPE_WINDOW_MS = MINUTE_MS / 2;
    public static final int MAX_EXTENSIONS = 10;
    private static final int MAX_HISTORY = 30;

    /** Satu catatan tawaran (untuk riwayat). */
    public static final class BidRecord {
        public final String bidder;
        public final long amount;
        public final long at;

        BidRecord(String bidder, long amount, long at) {
            this.bidder = bidder;
            this.amount = amount;
            this.at = at;
        }
    }

    private final int id;
    private final String itemName;
    private final String catalogFileName;
    private final String seller;
    private final String category;
    private final long startingBid;
    private final long startAt;
    private long endAt;
    private State state = State.PENDING;
    private boolean startAnnounced;
    private boolean endAnnounced;
    private int extensions;
    private boolean closed;
    private long currentBid;
    private String highestBidder = "-";
    private int bidCount;
    private final ArrayDeque<BidRecord> history = new ArrayDeque<>();

    public Auction(int id, String itemName, long startingBid, String catalogFileName, String seller,
                   String category, long startAt, long endAt) {
        this.id = id;
        this.itemName = itemName;
        this.startingBid = startingBid;
        this.currentBid = startingBid;
        this.catalogFileName = catalogFileName;
        this.seller = seller;
        this.category = category;
        this.startAt = startAt;
        this.endAt = endAt;
    }

    public int getId() { return id; }
    public String getItemName() { return itemName; }
    public String getCatalogFileName() { return catalogFileName; }
    public String getSeller() { return seller; }
    public String getCategory() { return category; }

    /** Barang yang sudah dihapus penjual tidak boleh menerima tawaran lagi. */
    public synchronized boolean isClosed() { return closed; }
    public synchronized void close() { closed = true; }
    public synchronized boolean hasBids() { return !"-".equals(highestBidder); }
    public synchronized long getCurrentBid() { return currentBid; }
    public synchronized String getHighestBidder() { return highestBidder; }
    public synchronized long getEndAt() { return endAt; }

    /** Memperbarui status berdasarkan waktu. Dipanggil penjadwal dan setiap kali tawaran masuk. */
    public synchronized State tick(long now) {
        if (state == State.PENDING && now >= startAt) {
            state = State.RUNNING;
        }
        if (state == State.RUNNING && now >= endAt) {
            state = State.ENDED;
        }
        return state;
    }

    /** True sekali saja: saat barang sudah berjalan tetapi pengumuman "mulai" belum disiarkan. */
    public synchronized boolean claimStartAnnouncement() {
        if (state == State.RUNNING && !startAnnounced) {
            startAnnounced = true;
            return true;
        }
        return false;
    }

    /** True sekali saja: saat barang sudah selesai tetapi pengumuman pemenang belum disiarkan. */
    public synchronized boolean claimEndAnnouncement() {
        if (state == State.ENDED && !endAnnounced) {
            startAnnounced = true;
            endAnnounced = true;
            return true;
        }
        return false;
    }

    /** Anti-sniping. Mengembalikan true bila waktu berakhir diperpanjang. */
    public synchronized boolean extendIfSniping(long now) {
        if (state == State.RUNNING && endAt - now <= SNIPE_WINDOW_MS && extensions < MAX_EXTENSIONS) {
            endAt = now + SNIPE_WINDOW_MS;
            extensions++;
            return true;
        }
        return false;
    }

    public synchronized void update(long newBid, String bidder, long now) {
        this.currentBid = newBid;
        this.highestBidder = bidder;
        this.bidCount++;
        history.addFirst(new BidRecord(bidder, newBid, now));
        while (history.size() > MAX_HISTORY) {
            history.removeLast();
        }
    }

    public synchronized List<BidRecord> getHistory() {
        return new ArrayList<>(history);
    }

    public synchronized AuctionInfo toInfo() {
        long now = System.currentTimeMillis();
        tick(now);
        long startsIn = state == State.PENDING ? Math.max(0, (startAt - now + 999) / 1000) : 0;
        long endsIn = state == State.ENDED ? 0 : Math.max(0, (endAt - now + 999) / 1000);
        long inc = AuctionManager.minIncrement(currentBid);
        long minBid = "-".equals(highestBidder) ? currentBid : currentBid + inc;
        return new AuctionInfo(id, itemName, currentBid, highestBidder, catalogFileName, seller, category,
                state.name().toLowerCase(), startsIn, endsIn, inc, startingBid, bidCount, minBid);
    }
}
