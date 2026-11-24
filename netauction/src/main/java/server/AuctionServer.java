package server;

import util.AppLogger;
import util.CurrencyService;
import util.CurrencyService.CurrencyException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class AuctionServer {

    public static final int TCP_PORT = 5000;
    public static final int FILE_PORT = 5001;
    public static final int UDP_PORT = 5002;
    private static final int THREAD_POOL_SIZE = 32;

    public static final AuctionManager auctionManager = new AuctionManager();

    // Thread-safe session registry: username -> ClientHandler aktif
    private static final ConcurrentHashMap<String, ClientHandler> sessions = new ConcurrentHashMap<>();
    private static final AtomicInteger connectedClients = new AtomicInteger(0);

    // Pilar 1: Thread Pool terkelola untuk seluruh koneksi klien & transfer file
    private static final ExecutorService clientPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    private static final ExecutorService fileTransferPool = Executors.newCachedThreadPool();

    public static boolean registerSession(String username, ClientHandler handler) {
        return sessions.putIfAbsent(username, handler) == null;
    }

    public static void removeSession(String username) {
        sessions.remove(username);
    }

    public static int getActiveSessionCount() {
        return sessions.size();
    }

    public static List<String> getActiveUsernames() {
        List<String> users = new ArrayList<>(sessions.keySet());
        Collections.sort(users);
        return users;
    }

    public static void broadcastAsync(String message, String excludeUser) {
        for (MapEntryHolder entry : getSessionSnapshot()) {
            if (excludeUser == null || !entry.username.equals(excludeUser)) {
                entry.handler.sendAsync(message);
            }
        }
    }

    public static boolean sendPrivateAsync(String targetUsername, String message) {
        ClientHandler target = sessions.get(targetUsername);
        if (target == null) {
            return false;
        }
        target.sendAsync(message);
        return true;
    }

    private static class MapEntryHolder {
        final String username;
        final ClientHandler handler;

        MapEntryHolder(String u, ClientHandler h) {
            this.username = u;
            this.handler = h;
        }
    }

    private static List<MapEntryHolder> getSessionSnapshot() {
        List<MapEntryHolder> list = new ArrayList<>();
        sessions.forEach((u, h) -> list.add(new MapEntryHolder(u, h)));
        return list;
    }

    public static void clientDisconnected() {
        int left = connectedClients.decrementAndGet();
        System.out.println("[SERVER] Klien terhubung: " + left + " | Sesi login aktif: " + sessions.size());
    }

    public static void main(String[] args) {
        System.out.println();
        System.out.println("=======================================================");
        System.out.println("       NETAUCTION LIVE SERVER (5-PILLAR ENGINE)");
        System.out.println("=======================================================");

        try {
            InetAddress address = InetAddress.getLocalHost();
            System.out.println("Hostname        : " + address.getHostName());
            System.out.println("IP Address      : " + address.getHostAddress());
            System.out.println("TCP Command Port: " + TCP_PORT + " (FixedThreadPool=" + THREAD_POOL_SIZE + ")");
            System.out.println("TCP Binary Port : " + FILE_PORT + " (Chunking 8KB)");
            System.out.println("UDP Ticker Port : " + UDP_PORT + " (Live Feed 1s & Heartbeat)");
            System.out.println("Log File        : " + AppLogger.getFileName());
            System.out.println("-------------------------------------------------------");

            auctionManager.startScheduler(); // penjadwal otomatis: buka dan tutup lelang sesuai waktu

            // Uji koneksi awal ke Exchange Rate REST API (Pilar 4 - Gson)
            System.out.println("[API] Menguji koneksi ke Exchange Rate API (Gson)...");
            try {
                double idrPerUsd = 1.0 / CurrencyService.getRate("IDR", "USD");
                System.out.println("[API] Sukses. Kurs referensi: 1 USD = Rp " + CurrencyService.plain(idrPerUsd));
            } catch (CurrencyException e) {
                System.out.println("[API] Peringatan: " + e.getMessage());
                System.out.println("[API] Server tetap berjalan; permintaan CONVERT akan mencoba ulang otomatis.");
            }
            System.out.println("-------------------------------------------------------");

            // Jalankan Subsistem UDP Live Ticker & Heartbeat (Pilar 5)
            UdpTickerBroadcaster udpBroadcaster = new UdpTickerBroadcaster(UDP_PORT, auctionManager);
            Thread udpThread = new Thread(udpBroadcaster, "udp-live-ticker");
            udpThread.setDaemon(true);
            udpThread.start();

            // Jalankan Listener Transfer Berkas Biner (Pilar 3)
            Thread fileListenerThread = new Thread(() -> startBinaryFileServer(), "binary-file-listener");
            fileListenerThread.setDaemon(true);
            fileListenerThread.start();

            // Shutdown hook untuk membersihkan Thread Pool dengan rapi
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[SERVER] Mematikan Thread Pool dan subsistem jaringan...");
                udpBroadcaster.stop();
                clientPool.shutdownNow();
                fileTransferPool.shutdownNow();
            }));

            // Jalankan Server Socket TCP Utama (Pilar 1 & 2)
            try (ServerSocket serverSocket = new ServerSocket()) {
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(TCP_PORT));

                System.out.println("[TCP SERVER] Siap menerima koneksi klien pada port " + TCP_PORT + "...");
                AppLogger.log("SERVER STARTED | TCP=" + TCP_PORT + " | FILE=" + FILE_PORT + " | UDP=" + UDP_PORT);

                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    int total = connectedClients.incrementAndGet();
                    String ip = clientSocket.getInetAddress().getHostAddress();

                    // Fault tolerance: tolak koneksi baru bila seluruh thread pool terpakai,
                    // agar klien tidak menggantung tanpa balasan di antrean executor.
                    if (total > THREAD_POOL_SIZE) {
                        rejectBusy(clientSocket, ip);
                        continue;
                    }

                    System.out.println("[CONNECT] Klien baru: " + ip + " | Total aktif: " + total);
                    AppLogger.log("CONNECT | IP=" + ip);

                    clientPool.execute(new ClientHandler(clientSocket));
                }
            }
        } catch (IOException e) {
            System.out.println("[SERVER ERROR] " + e.getMessage());
            AppLogger.log("SERVER ERROR | " + e.getMessage());
        }
    }

    /** Menolak koneksi saat kapasitas thread pool penuh dengan balasan 503 yang jelas. */
    private static void rejectBusy(Socket clientSocket, String ip) {
        try (Socket sock = clientSocket) {
            sock.getOutputStream().write(
                    ("503 ERROR Server penuh (maksimal " + THREAD_POOL_SIZE + " klien). Coba lagi nanti.\r\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            sock.getOutputStream().flush();
        } catch (IOException ignored) {
        } finally {
            connectedClients.decrementAndGet();
            System.out.println("[REJECT] Kapasitas penuh, koneksi " + ip + " ditolak (503)");
            AppLogger.log("REJECT | IP=" + ip + " | Alasan=thread pool penuh");
        }
    }

    private static void startBinaryFileServer() {
        try (ServerSocket fileServerSocket = new ServerSocket()) {
            fileServerSocket.setReuseAddress(true);
            fileServerSocket.bind(new InetSocketAddress(FILE_PORT));
            System.out.println("[FILE SERVER] Saluran transfer biner aktif pada port " + FILE_PORT);

            while (!Thread.currentThread().isInterrupted()) {
                Socket fileClient = fileServerSocket.accept();
                fileTransferPool.execute(new FileTransferHandler(fileClient));
            }
        } catch (IOException e) {
            System.out.println("[FILE SERVER ERROR] " + e.getMessage());
            AppLogger.log("FILE SERVER ERROR | " + e.getMessage());
        }
    }
}
