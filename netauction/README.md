# NetAuction Live — Real-Time Auction & Financial Trading Hub
**Mata Kuliah:** Pemrograman Jaringan [TIS31263] — Universitas Riau  
**Kelompok:** Kelompok 7 (Skenario: *NetAuction Live*)

---

## 1. Arsitektur 5 Pilar Spesifikasi Teknis
Aplikasi ini telah disesuaikan sepenuhnya dengan **Panduan Spesifikasi Teknis Final Project TIS31263**:

1. **Pilar 1 — Concurrency & Thread-Pool Management (M10)**
   - `AuctionServer` menggunakan `Executors.newFixedThreadPool(32)` untuk melayani banyak klien TCP secara bersamaan dan `Executors.newCachedThreadPool()` untuk saluran transfer berkas biner.
   - Seluruh state bersama (`sessions`, `auctions`, `subscribers`, `connectedClients`) diproteksi menggunakan `ConcurrentHashMap`, `AtomicInteger`/`AtomicLong`, dan blok `synchronized` per objek lelang untuk mencegah *race condition*.
2. **Pilar 2 — Custom Application Protocol & Chat Messaging (M11)**
   - Mendukung perintah protokol berbasis teks UTF-8 (`\r\n`): `LOGIN`, `LIST`, `USERS`, `BID`, `STATUS`, `CONVERT`, `BROADCAST`, `PRIVATE` (pesan antar-klien), `CATALOG`, `DOWNLOAD`, dan `EXIT`.
   - Sisi klien (`AuctionClient`) menjalankan **Background Receiver Thread** (`tcp-async-receiver`) untuk menerima pesan `BROADCAST`, `PRIVATE`, dan peringatan `OUTBID` secara *asynchronous* tanpa memblokir menu utama.
3. **Pilar 3 — Binary File Transfer & Chunking Stream (M12)**
   - Disediakan melalui saluran biner pada **TCP Port 5001** (`FileTransferHandler`) menggunakan `DataInputStream`/`DataOutputStream` dan `BufferedInputStream`/`BufferedOutputStream` dengan ukuran *chunking buffer* **8KB (`8192 bytes`)**.
   - Pengiriman diawali header metadata (`mode`, `nama berkas`, `ukuran berkas` dalam byte) dan diakhiri dengan `socket.shutdownOutput()` sebagai sinyal EOF sebelum soket ditutup.
4. **Pilar 4 — REST API Integration & JSON Parsing (M13)**
   - `CurrencyService` mengakses Exchange Rate API (`https://open.er-api.com/v6/latest/`) melalui `HttpURLConnection` (HTTP GET) dan memetakan respons JSON ke DTO `ExchangeRateResponse` menggunakan **Google Gson** (`fromJson` & anotasi `@SerializedName`).
5. **Pilar 5 — UDP Telemetry & Heartbeat Subsystem (M9)**
   - `UdpTickerBroadcaster` berjalan pada **UDP Port 5002** menggunakan `DatagramSocket` dan `DatagramPacket`.
   - Mengirimkan **Live Ticker Feed** pembaruan harga lelang setiap 1 detik ke seluruh klien aktif serta memproses **Heartbeat PING/PONG** berkala dengan `setSoTimeout` dan retransmisi paket di lapisan aplikasi (`TICK_ACK`).

---

## 2. Struktur Direktori Proyek
```text
NetAuction_Live/
├── .vscode/
│   └── settings.json
└── netauction/
    ├── README.md
    ├── pom.xml
    ├── lib/
    │   └── gson-2.13.1.jar
    ├── catalog_files/               # Berkas katalog & foto barang di server
    │   ├── brosur_lengkap_lelang.pdf
    │   ├── foto_laptop_rog.png
    │   └── katalog_lelang.pdf
    ├── downloads/                   # Folder tujuan unduhan klien
    └── src/main/java/              # Struktur standar Maven
        ├── client/
        │   ├── AuctionClient.java
        │   └── AuctionClientGUI.java
        ├── server/
        │   ├── Auction.java
        │   ├── AuctionInfo.java
        │   ├── AuctionManager.java
        │   ├── AuctionServer.java
        │   ├── ClientHandler.java
        │   ├── FileTransferHandler.java
        │   └── UdpTickerBroadcaster.java
        └── util/
            ├── AppLogger.java
            ├── CurrencyService.java
            └── ExchangeRateResponse.java
```

---

## 3. Petunjuk Kompilasi & Menjalankan (Terminal / CMD / PowerShell)

Pastikan berada di dalam folder `netauction/`:
```bash
cd netauction
```

### A. Kompilasi Source Code (Windows)
```powershell
mkdir out
dir /s /b src\main\java\*.java > sources.txt
javac -cp "lib/gson-2.13.1.jar" -d out @sources.txt
```

### B. Kompilasi Source Code (Linux / macOS)
```bash
mkdir -p out
javac -cp "lib/gson-2.13.1.jar" -d out $(find src/main/java -name "*.java")
```

### C. Menjalankan Server
*(Jalankan dari folder `netauction/` agar folder `catalog_files/` dan `auction.log` terbaca otomatis)*
* **Windows:**
  ```powershell
  java -cp "out;lib/gson-2.13.1.jar" server.AuctionServer
  ```
* **Linux / macOS:**
  ```bash
  java -cp "out:lib/gson-2.13.1.jar" server.AuctionServer
  ```

### D. Menjalankan Client (Buka 2 atau lebih terminal untuk demo multi-klien)
* **Windows:**
  ```powershell
  java -cp "out;lib/gson-2.13.1.jar" client.AuctionClient
  ```
* **Linux / macOS:**
  ```bash
  java -cp "out:lib/gson-2.13.1.jar" client.AuctionClient
  ```
*(Opsional: tambahkan argumen IP server jika dijalankan antar-komputer, contoh: `java -cp "out;lib/gson-2.13.1.jar" client.AuctionClient 192.168.1.10`)*

---

## 4. Matriks Port Jaringan
| Layanan | Protokol | Port | Fungsi |
| :--- | :---: | :---: | :--- |
| Command & Chat Engine | TCP | `5000` | Login, Bidding, Broadcast, Private Msg, Konversi Valas |
| Binary Catalog Transfer | TCP | `5001` | Unduh & unggah berkas katalog/gambar biner (chunk 8KB) |
| Live Ticker & Heartbeat | UDP | `5002` | Siaran ticker harga lelang per detik & heartbeat ping |


### E. Menjalankan Client Versi GUI Java Swing (Bonus +20 Poin)
* **Windows:**
  ```powershell
  java -cp "out;lib/gson-2.13.1.jar" client.AuctionClientGUI
  ```
* **Linux / macOS:**
  ```bash
  java -cp "out:lib/gson-2.13.1.jar" client.AuctionClientGUI
  ```

---

## Versi Web (HTML) - WebGateway

Versi ini menambahkan antarmuka web tanpa mengubah `AuctionServer`. Browser tidak dapat membuka soket
TCP/UDP mentah, sehingga `web.WebGateway` bertindak sebagai jembatan: satu sesi browser = satu koneksi TCP
(port 5000) + satu soket UDP (port 5002). Pesan asinkron (OUTBID, broadcast, ticker UDP) dikirim ke browser
lewat Server-Sent Events, sedangkan transfer berkas biner dialirkan dari port 5001.

Kompilasi (dari folder `netauction`):

    javac -cp lib/gson-2.13.1.jar -d out $(find src/main/java -name "*.java")

Jalankan (3 terminal, semuanya dari folder `netauction`):

    java -cp "out:lib/gson-2.13.1.jar" server.AuctionServer
    java -cp "out:src/main/resources:lib/gson-2.13.1.jar" web.WebGateway
    # lalu buka http://localhost:8080 di beberapa tab/browser untuk simulasi multi-peserta

Di Windows, ganti pemisah classpath `:` dengan `;`.

**Penting:** halaman web hanya bisa dibuka lewat `http://localhost:8080` (dilayani `WebGateway`). Membuka
`index.html` langsung dari file atau server bawaan IDE akan menghasilkan 404 karena `/api/...` tidak ada di sana.
Halaman (`index.html`, `style.css`, `app.js`) berada di `src/main/resources/web/` dan dimuat dari classpath, jadi tidak bergantung pada working directory.
`catalog_files/` tetap dibaca dari working directory server (folder `netauction`).
Argumen opsional gateway: `web.WebGateway <host_server> <port_http>`.

Catatan kapasitas: AuctionServer melayani maksimal 32 klien bersamaan; koneksi ke-33 ditolak dengan `503`.
Gateway menutup sesi web yang ditinggalkan (tab ditutup tanpa Keluar) setelah 45 detik.

---

## Membuka di IntelliJ IDEA (Maven)

1. **File > Open**, pilih folder `netauction` (yang berisi `pom.xml`), lalu **Open as Project**. Tunggu Maven selesai memuat dependency Gson.
2. Source code berada di `src/main/java`, sesuai standar Maven, jadi `mvn compile` dan IntelliJ langsung mengenalinya.
3. Buka **Run > Edit Configurations**, buat konfigurasi Application untuk `server.AuctionServer` dan `web.WebGateway` dan klien (`client.AuctionClient` atau `client.AuctionClientGUI`).
4. **Working directory wajib folder `netauction`** (isi kolom Working directory dengan `$MODULE_WORKING_DIR$`) agar folder `catalog_files/` terbaca oleh server (halaman web sendiri dimuat dari classpath).
5. Untuk menjalankan beberapa klien sekaligus, aktifkan **Allow multiple instances** pada konfigurasi klien.

Library Gson diambil Maven dari `pom.xml`; berkas `lib/gson-2.13.1.jar` tetap disertakan untuk kompilasi manual dengan `javac`.

---

## Fitur Jual Barang (khusus versi Web)

Peserta dapat mengajukan barang untuk dilelang. Fitur ini **hanya ada di proyek Web ini**; versi Swing/CLI
terpisah (`FinalProject-v4-maven`) tidak mengubah perilaku lelangnya.

**Perintah protokol baru:** `SELL <nama_barang> <harga_awal> [nama_berkas]`

    SELL Jam Tangan Seiko 750000 jam_seiko.png      -> 200 SUCCESS Barang #4 'Jam Tangan Seiko' resmi dilelang ...

| Aturan | Nilai |
|---|---|
| Nama barang | 3-40 karakter (huruf, angka, spasi, `().,'-`) |
| Harga awal | 1.000 sampai 12 digit rupiah |
| Berkas katalog | opsional; harus sudah diunggah dan bertipe PNG/JPG/PDF |
| Kuota | maks. 20 barang di ruang lelang, maks. 3 barang aktif per penjual (respons 409) |
| Penjual menawar barang sendiri | ditolak `403` |
| Pengumuman | server menyiarkan `ASYNC NEW_LOT ...` ke peserta lain; ID barang dibuat atomik (`AtomicInteger`) |

Kolom ke-7 pada balasan `LIST`/`STATUS` kini berisi nama penjual (barang bawaan: `Balai Lelang`).

**Pembatasan unggahan berkas** (ditegakkan di `FileTransferHandler`, dan diperiksa lebih dulu di `WebGateway`):

- Tipe: hanya `.png`, `.jpg`, `.jpeg`, `.pdf`.
- Ukuran: maksimal 5 MB, berkas kosong ditolak.
- Isi berkas harus cocok dengan tipenya (signature PNG / JPEG / `%PDF-`), sehingga ekstensi palsu ditolak.
- Nama berkas: huruf, angka, titik, garis bawah, strip (maks. 60 karakter).
- Berkas dengan nama yang sudah ada tidak ditimpa (`CREATE_NEW`); unggahan yang terputus dihapus otomatis.

---

## Kategori, Hapus Barang, dan Hapus Berkas (versi Web)

**Kategori barang:** `smartphone`, `pclaptop` (PC & Laptop), `aksesoris`. Barang yang dijual tanpa kategori
(klien CLI/Swing lama) masuk kategori `lainnya` dan hanya tampil di tab "Semua". Halaman web menyediakan
tab filter per kategori (penyaringan dilakukan di browser dari hasil `LIST`).

| Perintah / format | Keterangan |
|---|---|
| `SELL <nama> <harga> [berkas] [@kategori]` | Contoh: `SELL Samsung S23 3000000 foto.png @smartphone`. Kategori tidak dikenal ditolak `400`. |
| `DELLOT <auction_id>` | Hanya penjual barang tersebut. Ditolak `403` bila bukan penjual (termasuk barang bawaan), `409` bila sudah ada penawaran. Pengecekan dan penghapusan atomik terhadap `BID` (tawaran ke barang yang baru dihapus dijawab `404`). Peserta lain menerima `ASYNC LOT_REMOVED ...`. |
| `DELFILE <nama_berkas>` | Hanya pengunggah berkas. `403` untuk berkas bawaan/tanpa pemilik atau milik orang lain, `409` bila berkas masih dipakai barang lelang aktif. Peserta lain menerima `ASYNC FILE_REMOVED ...`. |
| `UPTICKET` | Meminta tiket unggah sekali pakai (berlaku 60 detik). Dipakai gateway agar port 5001 tahu siapa pengunggahnya. |
| Port 5001 `UPLOAD_AUTH` | Header: mode, tiket, nama berkas, ukuran (long). Sama seperti `UPLOAD` (chunk 8KB, `shutdownOutput()`), tetapi pengunggah dicatat sebagai pemilik berkas. Mode `UPLOAD` lama tetap diterima; berkasnya tidak memiliki pemilik sehingga tidak dapat dihapus peserta. |

Format balasan: `LIST` memiliki kolom ke-8 (kategori), `CATALOG` memiliki kolom ke-3 (pemilik).
Kepemilikan berkas disimpan di `file_owners.properties` (dibuat otomatis di working directory server) sehingga bertahan
saat server dimulai ulang; berkas ini tidak perlu dikumpulkan.

---

## Lelang Terjadwal, Pemenang, dan Halaman Penawaran (versi Web)

**Siklus barang:** `menunggu` (sudah diajukan, belum mulai) -> `berjalan` (bisa ditawar) -> `selesai`.
Penjadwal server (thread `auction-scheduler`, tiap 0,5 detik) membuka dan menutup barang otomatis. Pergantian status
atomik terhadap `BID` (pemeriksaan waktu dilakukan di dalam kunci barang), sehingga tawaran yang datang setelah waktu habis
selalu ditolak `409`. Barang bawaan langsung berjalan selama 30 menit sejak server menyala.

| Perintah / aturan | Keterangan |
|---|---|
| `SELL <nama> <harga> [berkas] [@kategori] [+S<menit>] [+D<menit>]` | `+S` = mulai berapa menit lagi (0, 1, 5, 15, 30, 60; bawaan 0). `+D` = durasi (1, 5, 15, 30, 60; bawaan 15). Contoh: `SELL Kamera 450000 foto.png @aksesoris +S5 +D30`. |
| `BID <id> <nominal>` | Ditolak `409` bila barang belum mulai, sudah berakhir, atau nominal di bawah minimal. Tawaran pertama minimal sama dengan harga awal; tawaran berikutnya minimal tawaran tertinggi + kelipatan (Rp 10.000 bila harga di bawah Rp 1 juta, Rp 50.000 bila di atasnya). |
| Anti-sniping | Tawaran pada 30 detik terakhir memperpanjang waktu menjadi 30 detik lagi (maksimal 10 kali per barang). |
| `HISTORY <id>` | Riwayat tawaran (terbaru dulu): `penawar;nominal;detik_lalu#...` (maks. 30). |
| Pemenang | Penawar tertinggi saat waktu habis. Tanpa penawaran = "tidak terjual". |

**Pesan asinkron baru:** `AUCTION_START` dan `AUCTION_END` (siaran ke semua peserta), `WINNER` (ke pemenang), `SOLD` / `UNSOLD` (ke penjual).
Barang yang sudah selesai tidak dihitung ke kuota (maks. 20 barang aktif, 3 per penjual) dan disimpan sebagai riwayat (maks. 20 barang selesai).

**Kolom balasan `LIST` / `STATUS`:** `id;nama;tawaran;penawar;usd;berkas;penjual;kategori;status;detik_sampai_mulai;detik_sampai_berakhir;kelipatan;harga_awal;jumlah_tawaran;tawaran_minimal`.
Sisa waktu dikirim sebagai detik relatif jam server, sehingga hitung mundur di browser tidak bergantung jam komputer pengguna.
Ticker UDP memuat status tiap barang (`[sisa mm:ss]`, `[mulai mm:ss]`, atau `[SELESAI, pemenang ...]`).

**Halaman web (routing `#hash`, satu aplikasi sehingga koneksi tidak terputus):**

| Alamat | Isi |
|---|---|
| `#/` | Daftar barang dengan filter kategori dan status, hitung mundur per barang |
| `#/barang/<id>` | Halaman penawaran: kartu penawaran tertinggi, kolom "Penawaran Anda" dengan tombol `-10x`, `-`, `+`, `+10x`, hitung mundur, riwayat tawaran, unduh katalog, hapus barang (penjual, bila belum ada tawaran) |
| `#/jual` | Halaman ajukan barang: nama, kategori, harga, berkas, waktu mulai, durasi, dan ringkasan jadwal |

**Pengujian dengan waktu dipercepat:** jalankan server dengan `-Dnetauction.minuteMs=4000` agar 1 "menit" lelang = 4 detik
(hanya untuk uji; jangan dipakai saat demo biasa).
