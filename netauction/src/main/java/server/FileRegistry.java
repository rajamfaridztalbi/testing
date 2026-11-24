package server;

import util.AppLogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FileRegistry {

    private static final Set<String> SEED_FILES =
            Set.of("foto_laptop_rog.png", "katalog_lelang.pdf", "brosur_lengkap_lelang.pdf");
    private static final File STORE = new File("file_owners.properties");
    private static final long TICKET_TTL_MS = 60_000L;

    private static final Properties owners = new Properties();
    private static final ConcurrentHashMap<String, long[]> expiry = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> ticketUser = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    static {
        if (STORE.isFile()) {
            try (FileInputStream in = new FileInputStream(STORE)) {
                owners.load(in);
            } catch (IOException e) {
                AppLogger.log("FILE REGISTRY | Gagal memuat " + STORE.getName() + " : " + e.getMessage());
            }
        }
    }

    private FileRegistry() {
    }

    /** Pemilik berkas: nama peserta, "Balai Lelang" untuk berkas bawaan, atau "-" bila tidak diketahui. */
    public static synchronized String ownerOf(String fileName) {
        if (SEED_FILES.contains(fileName)) {
            return AuctionManager.HOUSE;
        }
        return owners.getProperty(fileName, "-");
    }

    public static synchronized void setOwner(String fileName, String user) {
        owners.setProperty(fileName, user);
        save();
    }

    public static synchronized void removeOwner(String fileName) {
        if (owners.remove(fileName) != null) {
            save();
        }
    }

    private static void save() {
        try (FileOutputStream out = new FileOutputStream(STORE)) {
            owners.store(out, "Pemilik berkas katalog NetAuction Live");
        } catch (IOException e) {
            AppLogger.log("FILE REGISTRY | Gagal menyimpan " + STORE.getName() + " : " + e.getMessage());
        }
    }

    /** Menerbitkan tiket unggah sekali pakai untuk peserta yang sedang login. */
    public static String issueTicket(String user) {
        long now = System.currentTimeMillis();
        expiry.entrySet().removeIf(e -> e.getValue()[0] < now); // bersihkan tiket kedaluwarsa
        ticketUser.keySet().removeIf(t -> !expiry.containsKey(t));
        byte[] raw = new byte[12];
        RANDOM.nextBytes(raw);
        StringBuilder sb = new StringBuilder();
        for (byte b : raw) {
            sb.append(String.format("%02x", b));
        }
        String token = sb.toString();
        expiry.put(token, new long[]{now + TICKET_TTL_MS});
        ticketUser.put(token, user);
        return token;
    }

    /** Memakai tiket: mengembalikan nama peserta, atau null bila tidak valid / kedaluwarsa / sudah dipakai. */
    public static String consumeTicket(String token) {
        long[] exp = expiry.remove(token);
        String user = ticketUser.remove(token);
        if (exp == null || user == null || exp[0] < System.currentTimeMillis()) {
            return null;
        }
        return user;
    }
}
