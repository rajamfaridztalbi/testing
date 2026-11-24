package client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Frontend GUI Java Swing untuk NetAuction Live
 * - Seluruh I/O soket dijalankan di Background Thread & SwingWorker (bukan di Event Handler tombol).
 * - Pembaruan komponen UI dilakukan secara aman via SwingUtilities.invokeLater().
 */
public class AuctionClientGUI extends JFrame {

    private static final int DEFAULT_TCP_PORT = 5000;
    private static final int DEFAULT_FILE_PORT = 5001;
    private static final int DEFAULT_UDP_PORT = 5002;
    private static final int CHUNK_SIZE = 8192;
    private static final String CRLF = "\r\n";
    private static final String NO_FILE = "(tanpa berkas katalog)";
    private static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024; // 5 MB, sama dengan aturan server
    private static final java.util.Set<String> ALLOWED_EXT = java.util.Set.of("png", "jpg", "jpeg", "pdf");

    // State Jaringan
    private Socket tcpSocket;
    private PrintWriter serverOut;
    private InetAddress serverAddr;
    private final BlockingQueue<String> syncQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private String currentUsername;

    // Komponen UI
    private JTextField txtHost, txtUsername, txtBidId, txtBidAmount;
    private JTextField txtConvFrom, txtConvTo, txtConvAmount;
    private JTextField txtChatTarget, txtChatInput;
    private JButton btnConnect, btnRefresh, btnBid, btnConvert, btnSendChat, btnDownload, btnUpload;
    private JComboBox<String> cmbCatalog, cmbSellFile;
    private JTextField txtSellName, txtSellPrice;
    private JButton btnSell;
    private JLabel lblTicker, lblStatusBadge;
    private JTextArea txtChatLog;
    private DefaultTableModel tableModel;
    private JTable tblAuctions;

    public AuctionClientGUI() {
        setTitle("NetAuction Live — Real-Time Auction & Financial Trading Hub");
        setSize(1020, 680);
        setMinimumSize(new Dimension(920, 600));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLocationRelativeTo(null);

        initComponents();
        setControlsEnabled(false);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                disconnectAndExit();
            }
        });
    }

    private void initComponents() {
        setLayout(new BorderLayout(8, 8));

        // ==================== PANEL ATAS: KONEKSI & LIVE TICKER ====================
        JPanel topWrapper = new JPanel(new BorderLayout(4, 4));
        topWrapper.setBorder(new EmptyBorder(8, 10, 0, 10));

        JPanel connPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        connPanel.setBorder(new TitledBorder("Koneksi Server (TCP 5000 | File 5001 | UDP 5002)"));

        txtHost = new JTextField("localhost", 12);
        txtUsername = new JTextField("raja_01", 12);
        btnConnect = new JButton("Hubungkan & Login");
        lblStatusBadge = new JLabel("  OFFLINE  ");
        lblStatusBadge.setOpaque(true);
        lblStatusBadge.setBackground(new Color(220, 53, 69));
        lblStatusBadge.setForeground(Color.WHITE);
        lblStatusBadge.setFont(new Font("SansSerif", Font.BOLD, 11));

        connPanel.add(new JLabel("Host:"));
        connPanel.add(txtHost);
        connPanel.add(new JLabel("Username:"));
        connPanel.add(txtUsername);
        connPanel.add(btnConnect);
        connPanel.add(lblStatusBadge);

        // Banner UDP Live Ticker Feed
        JPanel tickerPanel = new JPanel(new BorderLayout());
        tickerPanel.setBackground(new Color(24, 34, 48));
        tickerPanel.setBorder(new EmptyBorder(6, 10, 6, 10));
        lblTicker = new JLabel("UDP LIVE TICKER: Belum terhubung ke feed harga real-time...");
        lblTicker.setForeground(new Color(102, 252, 241));
        lblTicker.setFont(new Font("Monospaced", Font.BOLD, 12));
        tickerPanel.add(lblTicker, BorderLayout.CENTER);

        topWrapper.add(connPanel, BorderLayout.NORTH);
        topWrapper.add(tickerPanel, BorderLayout.SOUTH);
        add(topWrapper, BorderLayout.NORTH);

        // ==================== PANEL TENGAH: TABEL LELANG & PANEL AKSI ====================
        JSplitPane centerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        centerSplit.setResizeWeight(0.62);
        centerSplit.setBorder(new EmptyBorder(0, 10, 0, 10));

        // Kiri: Tabel Barang Lelang + Panel Bid & File Catalog
        JPanel leftPanel = new JPanel(new BorderLayout(6, 6));

        String[] cols = {"ID", "Nama Barang", "Tawaran Tertinggi (IDR)", "Penawar", "Penjual", "Setara USD", "Katalog"};
        tableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        tblAuctions = new JTable(tableModel);
        tblAuctions.setRowHeight(24);
        tblAuctions.getSelectionModel().addListSelectionListener(e -> {
            int row = tblAuctions.getSelectedRow();
            if (row >= 0) {
                txtBidId.setText(tableModel.getValueAt(row, 0).toString());
            }
        });

        JScrollPane tableScroll = new JScrollPane(tblAuctions);
        tableScroll.setBorder(new TitledBorder("Daftar Barang Lelang Real-Time"));
        leftPanel.add(tableScroll, BorderLayout.CENTER);

        // Sub-panel Kontrol Bid & Katalog Biner (Pilar 3)
        JPanel bottomLeftPanel = new JPanel(new GridLayout(3, 1, 4, 4));

        JPanel bidPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bidPanel.setBorder(new TitledBorder("Ajukan Tawaran (Synchronized Bidding)"));
        txtBidId = new JTextField(4);
        txtBidAmount = new JTextField(11);
        btnBid = new JButton("Kirim Bid (IDR)");
        btnRefresh = new JButton("Refresh Data");
        bidPanel.add(new JLabel("ID:"));
        bidPanel.add(txtBidId);
        bidPanel.add(new JLabel("Nominal Rp:"));
        bidPanel.add(txtBidAmount);
        bidPanel.add(btnBid);
        bidPanel.add(btnRefresh);

        JPanel filePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        filePanel.setBorder(new TitledBorder("Katalog Biner (Chunking 8KB - Port 5001)"));
        cmbCatalog = new JComboBox<>(new String[]{"katalog_lelang.pdf", "brosur_lengkap_lelang.pdf", "foto_laptop_rog.png"});
        btnDownload = new JButton("Unduh Katalog");
        btnUpload = new JButton("Unggah Berkas");
        filePanel.add(cmbCatalog);
        filePanel.add(btnDownload);
        filePanel.add(btnUpload);

        JPanel sellPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        sellPanel.setBorder(new TitledBorder("Jual Barang Anda (SELL)"));
        txtSellName = new JTextField(12);
        txtSellPrice = new JTextField(8);
        cmbSellFile = new JComboBox<>(new String[]{NO_FILE});
        btnSell = new JButton("Lelang Barang");
        sellPanel.add(new JLabel("Nama:"));
        sellPanel.add(txtSellName);
        sellPanel.add(new JLabel("Harga awal Rp:"));
        sellPanel.add(txtSellPrice);
        sellPanel.add(cmbSellFile);
        sellPanel.add(btnSell);

        bottomLeftPanel.add(bidPanel);
        bottomLeftPanel.add(sellPanel);
        bottomLeftPanel.add(filePanel);
        leftPanel.add(bottomLeftPanel, BorderLayout.SOUTH);

        // Kanan: Konversi Valas (Gson API) & Ruang Chat / Notifikasi Outbid
        JPanel rightPanel = new JPanel(new BorderLayout(6, 6));

        JPanel convPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 4));
        convPanel.setBorder(new TitledBorder("Konversi Valas (REST API + Gson)"));
        txtConvFrom = new JTextField("USD", 4);
        txtConvTo = new JTextField("IDR", 4);
        txtConvAmount = new JTextField("100", 7);
        btnConvert = new JButton("Hitung");
        convPanel.add(txtConvFrom);
        convPanel.add(new JLabel("ke"));
        convPanel.add(txtConvTo);
        convPanel.add(txtConvAmount);
        convPanel.add(btnConvert);
        rightPanel.add(convPanel, BorderLayout.NORTH);

        JPanel chatPanel = new JPanel(new BorderLayout(4, 4));
        chatPanel.setBorder(new TitledBorder("Live Chat, Notifikasi Outbid & Log Sistem"));
        txtChatLog = new JTextArea();
        txtChatLog.setEditable(false);
        txtChatLog.setLineWrap(true);
        txtChatLog.setWrapStyleWord(true);
        txtChatLog.setFont(new Font("Monospaced", Font.PLAIN, 12));
        chatPanel.add(new JScrollPane(txtChatLog), BorderLayout.CENTER);

        JPanel chatInputPanel = new JPanel(new BorderLayout(4, 4));
        txtChatTarget = new JTextField("ALL", 7);
        txtChatTarget.setToolTipText("Ketik 'ALL' untuk Broadcast, atau username tujuan untuk Private Message");
        txtChatInput = new JTextField();
        btnSendChat = new JButton("Kirim");

        JPanel targetWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        targetWrap.add(new JLabel("Tujuan:"));
        targetWrap.add(txtChatTarget);

        chatInputPanel.add(targetWrap, BorderLayout.WEST);
        chatInputPanel.add(txtChatInput, BorderLayout.CENTER);
        chatInputPanel.add(btnSendChat, BorderLayout.EAST);
        chatPanel.add(chatInputPanel, BorderLayout.SOUTH);

        rightPanel.add(chatPanel, BorderLayout.CENTER);

        centerSplit.setLeftComponent(leftPanel);
        centerSplit.setRightComponent(rightPanel);
        add(centerSplit, BorderLayout.CENTER);

        // ==================== EVENT LISTENERS (DELEGASI KE SWINGWORKER) ====================
        btnConnect.addActionListener(e -> connectAsync());
        btnRefresh.addActionListener(e -> executeCommandAsync("LIST", this::handleListResponse));
        btnBid.addActionListener(e -> {
            String id = txtBidId.getText().trim();
            String amt = txtBidAmount.getText().trim();
            if (!id.matches("\\d+") || !amt.matches("\\d+")) {
                appendLog("[VALIDASI] ID dan nominal bid harus berupa angka bulat.");
                return;
            }
            executeCommandAsync("BID " + id + " " + amt, resp -> {
                appendLog("[BID] " + resp);
                executeCommandAsync("LIST", this::handleListResponse);
            });
        });

        btnConvert.addActionListener(e -> {
            String from = txtConvFrom.getText().trim().toUpperCase(Locale.ROOT);
            String to = txtConvTo.getText().trim().toUpperCase(Locale.ROOT);
            String amt = txtConvAmount.getText().trim();
            executeCommandAsync("CONVERT " + from + " " + to + " " + amt,
                    resp -> appendLog("[VALAS GSON] " + resp));
        });

        btnSendChat.addActionListener(e -> sendChatAction());
        txtChatInput.addActionListener(e -> sendChatAction());

        btnDownload.addActionListener(e -> {
            Object selected = cmbCatalog.getSelectedItem();
            if (selected != null) {
                downloadBinaryAsync(selected.toString());
            }
        });

        btnUpload.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.setFileFilter(new FileNameExtensionFilter("PNG, JPG, atau PDF (maks. 5 MB)", "png", "jpg", "jpeg", "pdf"));
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File chosen = chooser.getSelectedFile();
                String problem = validateUploadFile(chosen);
                if (problem != null) {
                    appendLog("[VALIDASI UNGGAH] " + problem);
                    JOptionPane.showMessageDialog(this, problem, "Berkas ditolak", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                uploadBinaryAsync(chosen);
            }
        });

        btnSell.addActionListener(e -> {
            String name = txtSellName.getText().trim().replaceAll("\\s+", " ");
            String price = txtSellPrice.getText().trim();
            if (!name.matches("[A-Za-z0-9 ().,'-]{3,40}")) {
                appendLog("[VALIDASI] Nama barang 3-40 karakter (huruf, angka, spasi, dan tanda ().,'-).");
                return;
            }
            if (!price.matches("\\d{4,12}") || Long.parseLong(price) < 1000) {
                appendLog("[VALIDASI] Harga awal harus angka bulat minimal 1000 (tanpa titik/koma).");
                return;
            }
            Object sel = cmbSellFile.getSelectedItem();
            String file = (sel == null || NO_FILE.equals(sel.toString())) ? "" : " " + sel;
            executeCommandAsync("SELL " + name + " " + price + file, resp -> {
                appendLog("[SELL] " + resp);
                if (resp.startsWith("200")) {
                    txtSellName.setText("");
                    txtSellPrice.setText("");
                    executeCommandAsync("LIST", this::handleListResponse);
                }
            });
        });
    }

    private void sendChatAction() {
        String target = txtChatTarget.getText().trim();
        String msg = txtChatInput.getText().trim();
        if (msg.isEmpty()) return;
        txtChatInput.setText("");

        if (target.isEmpty() || "ALL".equalsIgnoreCase(target)) {
            executeCommandAsync("BROADCAST " + msg, resp -> appendLog("[ME -> ALL] " + msg));
        } else {
            executeCommandAsync("PRIVATE " + target + " " + msg,
                    resp -> appendLog("[ME -> " + target + "] " + msg + " (" + resp + ")"));
        }
    }

    // ==================== BACKGROUND NETWORK WORKERS ====================

    private void connectAsync() {
        if (connected.get()) {
            disconnectAndExit();
            return;
        }
        String host = txtHost.getText().trim();
        String user = txtUsername.getText().trim();
        btnConnect.setEnabled(false);

        new SwingWorker<Boolean, Void>() {
            private String statusMsg = "";

            @Override
            protected Boolean doInBackground() {
                try {
                    serverAddr = InetAddress.getByName(host);
                    tcpSocket = new Socket();
                    tcpSocket.connect(new InetSocketAddress(serverAddr, DEFAULT_TCP_PORT), 5000);

                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(tcpSocket.getInputStream(), StandardCharsets.UTF_8));
                    serverOut = new PrintWriter(
                            new OutputStreamWriter(tcpSocket.getOutputStream(), StandardCharsets.UTF_8), false);
                    connected.set(true);

                    // Background Receiver Thread (Pilar 2)
                    Thread rxThread = new Thread(() -> receiverLoop(in), "gui-tcp-receiver");
                    rxThread.setDaemon(true);
                    rxThread.start();

                    String loginResp = sendSyncCommand("LOGIN " + user);
                    statusMsg = loginResp;
                    if (loginResp.startsWith("200 ")) {
                        currentUsername = user;
                        // Background UDP Live Ticker & Heartbeat Thread (Pilar 5)
                        Thread udpThread = new Thread(AuctionClientGUI.this::udpTickerLoop, "gui-udp-ticker");
                        udpThread.setDaemon(true);
                        udpThread.start();
                        return true;
                    }
                    connected.set(false);
                    tcpSocket.close();
                    return false;
                } catch (Exception ex) {
                    statusMsg = "Gagal terhubung: " + ex.getMessage();
                    connected.set(false);
                    return false;
                }
            }

            @Override
            protected void done() {
                btnConnect.setEnabled(true);
                try {
                    if (get()) {
                        lblStatusBadge.setText("  ONLINE: " + currentUsername + "  ");
                        lblStatusBadge.setBackground(new Color(40, 167, 69));
                        btnConnect.setText("Putuskan");
                        setControlsEnabled(true);
                        appendLog("[SYSTEM] " + statusMsg);
                        executeCommandAsync("LIST", AuctionClientGUI.this::handleListResponse);
                        refreshCatalogListAsync();
                    } else {
                        appendLog("[ERROR] " + statusMsg);
                    }
                } catch (Exception ex) {
                    appendLog("[ERROR] " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void receiverLoop(BufferedReader in) {
        try {
            String line;
            while (connected.get() && (line = in.readLine()) != null) {
                if (line.startsWith("ASYNC ")) {
                    String event = line.substring(6);
                    appendLog(">>> " + event);
                    if (event.startsWith("BID_UPDATE") || event.startsWith("OUTBID") || event.startsWith("NEW_LOT")) {
                        executeCommandAsync("LIST", this::handleListResponse);
                    }
                } else {
                    syncQueue.offer(line);
                }
            }
        } catch (IOException e) {
            if (connected.get()) {
                connected.set(false);
                SwingUtilities.invokeLater(() -> {
                    lblStatusBadge.setText("  DISCONNECTED  ");
                    lblStatusBadge.setBackground(new Color(220, 53, 69));
                    setControlsEnabled(false);
                    appendLog("[KONEKSI TERPUTUS] " + e.getMessage());
                });
            }
        }
    }

    private void udpTickerLoop() {
        AtomicLong pingId = new AtomicLong(1);
        long lastPing = 0;

        try (DatagramSocket udpSock = new DatagramSocket()) {
            udpSock.setSoTimeout(1500);
            byte[] buf = new byte[1024];
            sendUdpPacket(udpSock, "UDP_SUB " + currentUsername);

            while (connected.get()) {
                long now = System.currentTimeMillis();
                if (now - lastPing >= 5000) {
                    sendUdpPacket(udpSock, "PING " + currentUsername + " " + pingId.getAndIncrement());
                    lastPing = now;
                }
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    udpSock.receive(pkt);
                    String msg = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8).trim();
                    if (msg.startsWith("TICKER|")) {
                        String[] parts = msg.split("\\|", 3);
                        if (parts.length == 3) {
                            sendUdpPacket(udpSock, "TICK_ACK " + parts[1]);
                            String tickerText = "UDP LIVE TICKER [#" + parts[1] + "]: " + parts[2];
                            SwingUtilities.invokeLater(() -> lblTicker.setText(tickerText));
                        }
                    }
                } catch (SocketTimeoutException timeout) {
                    sendUdpPacket(udpSock, "PING " + currentUsername + " " + pingId.get());
                }
            }
            sendUdpPacket(udpSock, "UDP_UNSUB " + currentUsername);
        } catch (IOException ignored) {
        }
    }

    private void sendUdpPacket(DatagramSocket sock, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        sock.send(new DatagramPacket(data, data.length, serverAddr, DEFAULT_UDP_PORT));
    }

    private synchronized String sendSyncCommand(String cmd) throws IOException, InterruptedException {
        syncQueue.clear();
        serverOut.print(cmd + CRLF);
        serverOut.flush();
        String resp = syncQueue.poll(12, TimeUnit.SECONDS);
        if (resp == null) {
            throw new SocketTimeoutException("Server timeout.");
        }
        return resp;
    }

    private void executeCommandAsync(String cmd, java.util.function.Consumer<String> onSuccess) {
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return sendSyncCommand(cmd);
            }

            @Override
            protected void done() {
                try {
                    onSuccess.accept(get());
                } catch (Exception ex) {
                    appendLog("[ERROR] " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void handleListResponse(String rawResp) {
        if (!rawResp.startsWith("200 SUCCESS ")) {
            appendLog("[LIST ERROR] " + rawResp);
            return;
        }
        String payload = rawResp.substring("200 SUCCESS ".length());
        tableModel.setRowCount(0);
        for (String item : payload.split("#")) {
            String[] f = item.split(";");
            if (f.length >= 6) {
                String rupiah = String.format(Locale.forLanguageTag("id-ID"), "Rp %,d", Long.parseLong(f[2]));
                String seller = f.length >= 7 ? f[6] : "-";
                if (seller.equals(currentUsername)) seller += " (Anda)";
                tableModel.addRow(new Object[]{f[0], f[1], rupiah, f[3], seller, f[4], f[5]});
            }
        }
    }

    private void refreshCatalogListAsync() {
        executeCommandAsync("CATALOG", resp -> {
            if (resp.startsWith("200 SUCCESS ")) {
                cmbCatalog.removeAllItems();
                Object keepSell = cmbSellFile.getSelectedItem();
                cmbSellFile.removeAllItems();
                cmbSellFile.addItem(NO_FILE);
                String payload = resp.substring("200 SUCCESS ".length());
                for (String entry : payload.split("#")) {
                    String[] parts = entry.split(";");
                    if (parts.length >= 1) {
                        cmbCatalog.addItem(parts[0]);
                        if (parts[0].toLowerCase(Locale.ROOT).matches(".+\\.(png|jpe?g|pdf)")) {
                            cmbSellFile.addItem(parts[0]);
                        }
                    }
                }
                if (keepSell != null) {
                    cmbSellFile.setSelectedItem(keepSell);
                }
            }
        });
    }

    private void downloadBinaryAsync(String fileName) {
        appendLog("[FILE TRANSFER] Mengunduh '" + fileName + "' (Chunking 8KB)...");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                File dir = new File("downloads");
                if (!dir.exists()) dir.mkdirs();
                File outFile = new File(dir, fileName);

                try (
                        Socket fileSock = new Socket(serverAddr, DEFAULT_FILE_PORT);
                        DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(fileSock.getOutputStream()));
                        DataInputStream dis = new DataInputStream(new BufferedInputStream(fileSock.getInputStream()))
                ) {
                    dos.writeUTF("DOWNLOAD");
                    dos.writeUTF(fileName);
                    dos.flush();
                    fileSock.shutdownOutput();

                    String status = dis.readUTF();
                    if (!"OK".equals(status)) {
                        return "Gagal: " + dis.readUTF();
                    }
                    String confirmedName = dis.readUTF();
                    long fileSize = dis.readLong();

                    byte[] buf = new byte[CHUNK_SIZE];
                    long rem = fileSize;
                    try (BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(outFile))) {
                        while (rem > 0) {
                            int r = dis.read(buf, 0, (int) Math.min(buf.length, rem));
                            if (r == -1) throw new EOFException("Stream terputus.");
                            fos.write(buf, 0, r);
                            rem -= r;
                        }
                    }
                    return "Berhasil mengunduh " + confirmedName + " (" + fileSize + " bytes) ke " + outFile.getPath();
                }
            }

            @Override
            protected void done() {
                try {
                    appendLog("[DOWNLOAD] " + get());
                } catch (Exception ex) {
                    appendLog("[DOWNLOAD ERROR] " + ex.getMessage());
                }
            }
        }.execute();
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

    private void uploadBinaryAsync(File file) {
        appendLog("[FILE TRANSFER] Mengunggah '" + file.getName() + "' (" + file.length() + " bytes)...");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                try (
                        Socket fileSock = new Socket(serverAddr, DEFAULT_FILE_PORT);
                        DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(fileSock.getOutputStream()));
                        DataInputStream dis = new DataInputStream(new BufferedInputStream(fileSock.getInputStream()))
                ) {
                    dos.writeUTF("UPLOAD");
                    dos.writeUTF(file.getName());
                    dos.writeLong(file.length());
                    dos.flush();

                    byte[] buf = new byte[CHUNK_SIZE];
                    try (BufferedInputStream fis = new BufferedInputStream(new FileInputStream(file))) {
                        int r;
                        while ((r = fis.read(buf)) != -1) {
                            dos.write(buf, 0, r);
                        }
                    }
                    dos.flush();
                    fileSock.shutdownOutput();

                    String status = dis.readUTF();
                    return "[" + status + "] " + dis.readUTF();
                }
            }

            @Override
            protected void done() {
                try {
                    appendLog("[UPLOAD] " + get());
                    refreshCatalogListAsync();
                } catch (Exception ex) {
                    appendLog("[UPLOAD ERROR] " + ex.getMessage() + " (server mungkin menolak berkas: nama sudah ada atau melanggar batas)");
                }
            }
        }.execute();
    }

    private void setControlsEnabled(boolean enabled) {
        btnRefresh.setEnabled(enabled);
        btnBid.setEnabled(enabled);
        btnConvert.setEnabled(enabled);
        btnSendChat.setEnabled(enabled);
        btnDownload.setEnabled(enabled);
        btnUpload.setEnabled(enabled);
        btnSell.setEnabled(enabled);
    }

    private void appendLog(String text) {
        SwingUtilities.invokeLater(() -> {
            txtChatLog.append(text + "\n");
            txtChatLog.setCaretPosition(txtChatLog.getDocument().getLength());
        });
    }

    private void disconnectAndExit() {
        if (connected.get()) {
            connected.set(false);
            try {
                if (serverOut != null) {
                    serverOut.print("EXIT" + CRLF);
                    serverOut.flush();
                }
                if (tcpSocket != null && !tcpSocket.isClosed()) {
                    tcpSocket.close();
                }
            } catch (IOException ignored) {
            }
        }
        dispose();
        System.exit(0);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new AuctionClientGUI().setVisible(true));
    }
}
