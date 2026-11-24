package util;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class AppLogger {

    private static final String FILE_NAME = "auction.log";
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static synchronized void log(String message) {
        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(FILE_NAME, true)))) {
            writer.println("[" + LocalDateTime.now().format(FORMAT) + "] " + message);
        } catch (IOException e) {
            System.out.println("[LOGGER ERROR] " + e.getMessage());
        }
    }

    public static String getFileName() {
        return FILE_NAME;
    }
}
