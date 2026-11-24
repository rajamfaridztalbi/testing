package server;

public class AuctionInfo {
    public final int id;
    public final String itemName;
    public final long currentBid;
    public final String highestBidder;
    public final String catalogFileName;
    public final String seller;
    public final String category;
    public final String status;       // pending | running | ended
    public final long startsInSec;    // detik sampai mulai (0 bila sudah mulai)
    public final long endsInSec;      // detik sampai berakhir (0 bila sudah selesai)
    public final long increment;      // kelipatan tawaran minimum
    public final long startPrice;
    public final int bidCount;
    public final long minBid;         // nominal tawaran terendah yang diterima saat ini

    public AuctionInfo(int id, String itemName, long currentBid, String highestBidder, String catalogFileName,
                       String seller, String category, String status, long startsInSec, long endsInSec,
                       long increment, long startPrice, int bidCount, long minBid) {
        this.id = id;
        this.itemName = itemName;
        this.currentBid = currentBid;
        this.highestBidder = highestBidder;
        this.catalogFileName = catalogFileName;
        this.seller = seller;
        this.category = category;
        this.status = status;
        this.startsInSec = startsInSec;
        this.endsInSec = endsInSec;
        this.increment = increment;
        this.startPrice = startPrice;
        this.bidCount = bidCount;
        this.minBid = minBid;
    }
}
