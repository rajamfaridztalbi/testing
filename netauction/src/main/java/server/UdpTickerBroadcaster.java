package server;

import util.AppLogger;
import util.CurrencyService;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class UdpTickerBroadcaster implements Runnable {

    private static final int SOCKET_TIMEOUT_MS = 1000; // 1 detik sekaligus interval live ticker feed
    private static final int MAX_RETRANSMIT = 2;
    private static final long SUBSCRIBER_TTL_MS = 30_000L; // subscriber kedaluwarsa jika 30 detik tanpa heartbeat

    private static class Subscriber {
        final String username;
        final InetSocketAddress address;
        volatile long lastSeen;

        Subscriber(String username, InetSocketAddress address) {
            this.username = username;
            this.address = address;
            this.lastSeen = System.currentTimeMillis();
        }
    }

    private final int udpPort;
    private final AuctionManager auctionManager;
    private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();
    private final Map<Long, Integer> pendingAckRetries = new ConcurrentHashMap<>();
    private final Map<Long, String> pendingPayloads = new ConcurrentHashMap<>();
    private final AtomicLong sequenceGenerator = new AtomicLong(1);
    private volatile boolean running = true;

    public UdpTickerBroadcaster(int udpPort, AuctionManager auctionManager) {
        this.udpPort = udpPort;
        this.auctionManager = auctionManager;
    }

    public void stop() {
        running = false;
    }

    public int getActiveSubscriberCount() {
        return subscribers.size();
    }

    @Override
    public void run() {
        try (DatagramSocket udpSocket = new DatagramSocket(udpPort)) {
            udpSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
            System.out.println("[UDP TICKER] Live Ticker Feed & Heartbeat aktif pada UDP port " + udpPort);
            AppLogger.log("UDP TICKER STARTED | Port=" + udpPort);

            byte[] recvBuf = new byte[1024];

            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
                    udpSocket.receive(packet);
                    handleIncomingPacket(udpSocket, packet);
                } catch (SocketTimeoutException timeout) {
                    // Setiap timeout 1 detik: bersihkan subscriber mati, kirim live ticker feed & retransmisi jika perlu
                    cleanupStaleSubscribers();
                    retransmitUnacked(udpSocket);
                    broadcastLiveTicker(udpSocket);
                }
            }
        } catch (IOException e) {
            System.out.println("[UDP TICKER ERROR] " + e.getMessage());
            AppLogger.log("UDP TICKER ERROR | " + e.getMessage());
        }
    }

    private void handleIncomingPacket(DatagramSocket socket, DatagramPacket packet) throws IOException {
        String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
        InetSocketAddress sender = new InetSocketAddress(packet.getAddress(), packet.getPort());

        if (msg.startsWith("UDP_SUB ")) {
            String user = msg.substring(8).trim();
            if (!user.isEmpty()) {
                subscribers.put(user, new Subscriber(user, sender));
                sendRaw(socket, "UDP_SUB_ACK OK Terdaftar pada Live Ticker Feed", sender);
                AppLogger.log("UDP SUB | User=" + user + " | Addr=" + sender);
            }
        } else if (msg.startsWith("PING ")) {
            String[] parts = msg.split("\\s+");
            String user = parts.length > 1 ? parts[1] : "unknown";
            String pingId = parts.length > 2 ? parts[2] : "0";
            Subscriber sub = subscribers.get(user);
            if (sub != null) {
                sub.lastSeen = System.currentTimeMillis();
            } else if (!user.equals("unknown")) {
                subscribers.put(user, new Subscriber(user, sender));
            }
            sendRaw(socket, "PONG " + pingId + " SUBS=" + subscribers.size(), sender);
        } else if (msg.startsWith("TICK_ACK ")) {
            try {
                long seq = Long.parseLong(msg.substring(9).trim());
                pendingAckRetries.remove(seq);
                pendingPayloads.remove(seq);
            } catch (NumberFormatException ignored) {
            }
        } else if (msg.startsWith("UDP_UNSUB ")) {
            String user = msg.substring(10).trim();
            subscribers.remove(user);
        }
    }

    private static String clock(long sec) {
        return sec >= 3600 ? String.format("%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
                : String.format("%02d:%02d", sec / 60, sec % 60);
    }

    private void broadcastLiveTicker(DatagramSocket socket) {
        if (subscribers.isEmpty()) {
            return;
        }
        List<AuctionInfo> items = auctionManager.getAll();
        StringBuilder tickerData = new StringBuilder();
        for (AuctionInfo item : items) {
            if (tickerData.length() > 0) tickerData.append(" | ");
            tickerData.append("#").append(item.id)
                      .append(" ").append(item.itemName)
                      .append(": ").append(CurrencyService.formatIdr(item.currentBid))
                      .append(" (").append(item.highestBidder).append(")");
            if ("pending".equals(item.status)) {
                tickerData.append(" [mulai ").append(clock(item.startsInSec)).append("]");
            } else if ("running".equals(item.status)) {
                tickerData.append(" [sisa ").append(clock(item.endsInSec)).append("]");
            } else {
                tickerData.append(" [SELESAI").append("-".equals(item.highestBidder) ? ", tidak terjual" : ", pemenang " + item.highestBidder).append("]");
            }
        }

        long seq = sequenceGenerator.getAndIncrement();
        String payload = "TICKER|" + seq + "|" + tickerData;

        // Simpan di buffer retransmisi aplikasi (maksimal 1 retry jika belum di-ACK)
        pendingPayloads.put(seq, payload);
        pendingAckRetries.put(seq, 1);

        for (Subscriber sub : subscribers.values()) {
            try {
                sendRaw(socket, payload, sub.address);
            } catch (IOException ignored) {
            }
        }
    }

    private void retransmitUnacked(DatagramSocket socket) {
        for (Map.Entry<Long, Integer> entry : pendingAckRetries.entrySet()) {
            long seq = entry.getKey();
            int retriesLeft = entry.getValue();
            String payload = pendingPayloads.get(seq);

            if (payload != null && retriesLeft > 0) {
                for (Subscriber sub : subscribers.values()) {
                    try {
                        sendRaw(socket, payload, sub.address);
                    } catch (IOException ignored) {
                    }
                }
                pendingAckRetries.put(seq, retriesLeft - 1);
            } else {
                pendingAckRetries.remove(seq);
                pendingPayloads.remove(seq);
            }
        }
    }

    private void cleanupStaleSubscribers() {
        long now = System.currentTimeMillis();
        subscribers.entrySet().removeIf(e -> (now - e.getValue().lastSeen) > SUBSCRIBER_TTL_MS);
    }

    private void sendRaw(DatagramSocket socket, String text, InetSocketAddress target) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        InetAddress addr = target.getAddress();
        DatagramPacket packet = new DatagramPacket(data, data.length, addr, target.getPort());
        socket.send(packet);
    }
}
