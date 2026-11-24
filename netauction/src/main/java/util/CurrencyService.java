package util;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CurrencyService {

    private static final String API_BASE =
            System.getProperty("netauction.rateUrl", "https://open.er-api.com/v6/latest/");

    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 5000;
    private static final long CACHE_MS = 5 * 60 * 1000L;   // cache kurs 5 menit
    private static final long RETRY_MS = 30 * 1000L;       // jeda retry jika API gagal

    public static class CurrencyException extends Exception {
        private static final long serialVersionUID = 1L;
        public final boolean unsupported;

        public CurrencyException(String message, boolean unsupported) {
            super(message);
            this.unsupported = unsupported;
        }
    }

    private static class Entry {
        String body;
        long fetchedAt;
        long lastAttempt;
    }

    private static final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private static final Gson GSON = new Gson();

    /** Mengambil nilai 1 satuan mata uang 'from' dalam mata uang 'to'. */
    public static synchronized double getRate(String from, String to) throws CurrencyException {
        from = from.toUpperCase(Locale.ROOT);
        to = to.toUpperCase(Locale.ROOT);
        if (from.equals(to)) {
            return 1.0;
        }

        Entry entry = cache.computeIfAbsent(from, k -> new Entry());
        long now = System.currentTimeMillis();
        boolean fresh = entry.body != null && (now - entry.fetchedAt) < CACHE_MS;

        if (!fresh) {
            boolean mayRetry = entry.lastAttempt == 0 || (now - entry.lastAttempt) >= RETRY_MS;
            if (mayRetry) {
                entry.lastAttempt = now;
                try {
                    entry.body = fetchBody(from);
                    entry.fetchedAt = now;
                    AppLogger.log("API | Kurs dasar " + from + " berhasil diambil via Gson");
                } catch (CurrencyException e) {
                    cache.remove(from);
                    throw e;
                } catch (IOException e) {
                    AppLogger.log("API ERROR | " + e.getMessage()
                            + (entry.body != null ? " | memakai kurs lama (cache)" : " | kurs belum tersedia"));
                    if (entry.body == null) {
                        throw new CurrencyException("Exchange Rate API tidak dapat dihubungi saat ini.", false);
                    }
                }
            } else if (entry.body == null) {
                throw new CurrencyException("Exchange Rate API sedang bermasalah. Coba lagi dalam beberapa detik.", false);
            }
        }

        ExchangeRateResponse data = parseResponse(entry.body);
        if (data.rates == null || !data.rates.containsKey(to)) {
            throw new CurrencyException("Kode mata uang \"" + to + "\" tidak didukung.", true);
        }
        Double rate = data.rates.get(to);
        if (rate == null || rate <= 0) {
            throw new CurrencyException("Nilai kurs untuk " + to + " tidak valid.", false);
        }
        return rate;
    }

    private static String fetchBody(String base) throws IOException, CurrencyException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(API_BASE + base).toURL().openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "NetAuctionLive/2.0");

            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new CurrencyException("Kode mata uang \"" + base + "\" tidak didukung.", true);
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Exchange Rate API membalas HTTP " + status);
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }

            ExchangeRateResponse data = parseResponse(body.toString());
            if (!"success".equalsIgnoreCase(data.result) || data.rates == null) {
                throw new CurrencyException("Respons Exchange Rate API tidak valid untuk " + base + ".", false);
            }
            if (data.baseCode != null && !base.equalsIgnoreCase(data.baseCode)) {
                throw new CurrencyException("Mata uang dasar pada respons API tidak sesuai.", false);
            }
            return body.toString();
        } finally {
            conn.disconnect();
        }
    }

    public static String toJson(ExchangeRateResponse response) {
        return GSON.toJson(response);
    }

    private static ExchangeRateResponse parseResponse(String json) throws CurrencyException {
        try {
            ExchangeRateResponse data = GSON.fromJson(json, ExchangeRateResponse.class);
            if (data == null) {
                throw new CurrencyException("Respons JSON dari Exchange Rate API kosong.", false);
            }
            return data;
        } catch (JsonParseException e) {
            throw new CurrencyException("Format JSON dari Exchange Rate API tidak valid.", false);
        }
    }

    public static String formatIdr(long idr) {
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.forLanguageTag("id-ID"));
        nf.setMaximumFractionDigits(0);
        return "Rp " + nf.format(idr);
    }

    public static String plain(double value) {
        return new BigDecimal(value).setScale(8, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }
}
