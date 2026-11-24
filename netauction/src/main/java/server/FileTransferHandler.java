package server;

import util.AppLogger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FileTransferHandler implements Runnable {

    public static final int CHUNK_SIZE = 8192; // 8KB chunking buffer
    private static final String CATALOG_DIR = "catalog_files";

    private final Socket fileSocket;

    public FileTransferHandler(Socket fileSocket) {
        this.fileSocket = fileSocket;
    }

    @Override
    public void run() {
        try (
                Socket sock = this.fileSocket;
                DataInputStream dis = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
                DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream()))
        ) {
            String mode = dis.readUTF();

            if ("DOWNLOAD".equalsIgnoreCase(mode)) {
                String requestedName = dis.readUTF();
                handleDownload(sock, dos, requestedName);
            } else if ("UPLOAD".equalsIgnoreCase(mode)) {
                // Mode lama (tanpa identitas): berkas tidak memiliki pemilik sehingga tidak dapat dihapus peserta
                String fileName = dis.readUTF();
                long fileSize = dis.readLong();
                handleUpload(sock, dis, dos, fileName, fileSize, null);
            } else if ("UPLOAD_AUTH".equalsIgnoreCase(mode)) {
                // Mode dengan tiket (UPTICKET): pengunggah tercatat sebagai pemilik berkas
                String token = dis.readUTF();
                String fileName = dis.readUTF();
                long fileSize = dis.readLong();
                String owner = FileRegistry.consumeTicket(token);
                if (owner == null) {
                    rejectUpload(sock, dos, fileName, "Tiket unggah tidak valid atau kedaluwarsa. Coba lagi.");
                } else {
                    handleUpload(sock, dis, dos, fileName, fileSize, owner);
                }
            } else {
                dos.writeUTF("ERROR");
                dos.writeUTF("Mode transfer biner tidak dikenal: " + mode);
                dos.flush();
                sock.shutdownOutput();
            }
        } catch (IOException e) {
            System.out.println("[FILE TRANSFER ERROR] " + e.getMessage());
            AppLogger.log("FILE TRANSFER ERROR | " + e.getMessage());
        }
    }

    private void handleDownload(Socket sock, DataOutputStream dos, String requestedName) throws IOException {
        // Cegah path traversal (ambil nama file saja)
        String safeName = new File(requestedName).getName();
        Path filePath = Paths.get(CATALOG_DIR, safeName);
        File file = filePath.toFile();

        if (!file.exists() || !file.isFile()) {
            dos.writeUTF("NOT_FOUND");
            dos.writeUTF("Berkas katalog '" + safeName + "' tidak ditemukan di server.");
            dos.flush();
            sock.shutdownOutput();
            AppLogger.log("FILE DOWNLOAD GAGAL | File=" + safeName + " tidak ditemukan");
            return;
        }

        long fileSize = file.length();
        // 1. Kirim header metadata (status, nama berkas, ukuran berkas)
        dos.writeUTF("OK");
        dos.writeUTF(file.getName());
        dos.writeLong(fileSize);
        dos.flush();

        // 2. Kirim isi berkas biner dengan chunking buffer 8KB
        byte[] buffer = new byte[CHUNK_SIZE];
        long totalSent = 0;
        try (BufferedInputStream fis = new BufferedInputStream(new FileInputStream(file))) {
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                dos.write(buffer, 0, bytesRead);
                totalSent += bytesRead;
            }
        }
        dos.flush();

        // 3. Akhiri dengan sinyal shutdownOutput()
        sock.shutdownOutput();

        System.out.println("[FILE TRANSFER] Terkirim: " + file.getName() + " (" + totalSent + " bytes, chunk 8KB)");
        AppLogger.log("FILE DOWNLOAD | File=" + file.getName() + " | Size=" + totalSent + " bytes");
    }

    // ---------------- Pembatasan unggahan ----------------
    public static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024; // 5 MB
    private static final java.util.Set<String> ALLOWED_EXT = java.util.Set.of("png", "jpg", "jpeg", "pdf");

    /** Mengembalikan pesan alasan jika berkas tidak boleh diunggah, atau null bila lolos. */
    public static String validateUpload(String name, long size) {
        if (!name.matches("[A-Za-z0-9_.-]{1,60}")) {
            return "Nama berkas hanya boleh huruf, angka, titik, garis bawah, dan strip (maksimal 60 karakter).";
        }
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        if (!ALLOWED_EXT.contains(ext)) {
            return "Tipe berkas tidak diizinkan. Hanya PNG, JPG, atau PDF.";
        }
        if (size <= 0) {
            return "Berkas kosong.";
        }
        if (size > MAX_UPLOAD_BYTES) {
            return "Ukuran berkas melebihi batas 5 MB.";
        }
        if (new File(CATALOG_DIR, name).exists()) {
            return "Berkas '" + name + "' sudah ada di katalog. Ganti nama berkas.";
        }
        return null;
    }

    /** Memeriksa byte awal isi berkas agar sesuai tipe yang diklaim ekstensinya (cegah ekstensi palsu). */
    public static boolean validSignature(String name, byte[] b, int len) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".png")) {
            return len >= 4 && b[0] == (byte) 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return len >= 3 && b[0] == (byte) 0xFF && b[1] == (byte) 0xD8 && b[2] == (byte) 0xFF;
        }
        if (lower.endsWith(".pdf")) {
            return len >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-';
        }
        return false;
    }

    private void rejectUpload(Socket sock, DataOutputStream dos, String name, String reason) throws IOException {
        dos.writeUTF("ERROR");
        dos.writeUTF(reason);
        dos.flush();
        sock.shutdownOutput();
        System.out.println("[FILE UPLOAD DITOLAK] " + name + " : " + reason);
        AppLogger.log("FILE UPLOAD DITOLAK | File=" + name + " | Alasan=" + reason);
    }

    private void handleUpload(Socket sock, DataInputStream dis, DataOutputStream dos,
                              String rawName, long fileSize, String owner) throws IOException {
        String safeName = new File(rawName).getName();
        String error = validateUpload(safeName, fileSize);
        if (error != null) {
            rejectUpload(sock, dos, safeName, error);
            return;
        }
        File dir = new File(CATALOG_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File outFile = new File(dir, safeName);

        java.io.OutputStream raw;
        try {
            // CREATE_NEW: gagal bila berkas sudah ada, sehingga tidak ada penimpaan walau 2 unggahan bersamaan
            raw = java.nio.file.Files.newOutputStream(outFile.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            rejectUpload(sock, dos, safeName, "Berkas '" + safeName + "' sudah ada di katalog. Ganti nama berkas.");
            return;
        }

        byte[] buffer = new byte[CHUNK_SIZE];
        long remaining = fileSize;
        long totalReceived = 0;
        boolean first = true;
        boolean badSignature = false;

        try (BufferedOutputStream fos = new BufferedOutputStream(raw)) {
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int bytesRead = first ? dis.readNBytes(buffer, 0, toRead) : dis.read(buffer, 0, toRead);
                if (bytesRead <= 0) {
                    throw new IOException("Stream terputus sebelum seluruh byte berkas diterima.");
                }
                if (first) {
                    first = false;
                    if (!validSignature(safeName, buffer, bytesRead)) {
                        badSignature = true;
                        break;
                    }
                }
                fos.write(buffer, 0, bytesRead);
                remaining -= bytesRead;
                totalReceived += bytesRead;
            }
        } catch (IOException e) {
            outFile.delete(); // jangan sisakan berkas setengah jadi
            throw e;
        }

        if (badSignature) {
            outFile.delete();
            rejectUpload(sock, dos, safeName, "Isi berkas tidak cocok dengan tipenya (" + safeName + ").");
            return;
        }

        if (owner != null) {
            FileRegistry.setOwner(safeName, owner);
        }
        dos.writeUTF("OK");
        dos.writeUTF("Berkas '" + safeName + "' (" + totalReceived + " bytes) berhasil diunggah ke katalog server.");
        dos.flush();
        sock.shutdownOutput();

        System.out.println("[FILE UPLOAD] Diterima: " + safeName + " (" + totalReceived + " bytes)");
        AppLogger.log("FILE UPLOAD | File=" + safeName + " | Size=" + totalReceived + " bytes");
    }
}
