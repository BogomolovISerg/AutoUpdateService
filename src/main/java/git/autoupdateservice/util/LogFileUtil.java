package git.autoupdateservice.util;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LogFileUtil {
    private LogFileUtil() {}

    public static void truncateQuietly(Path file) {
        if (file == null) return;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, "");
        } catch (Exception ignored) {}
    }

    public static String readTail(Path file, Charset charset, int maxChars) {
        if (file == null) return null;
        try {
            if (!Files.exists(file)) return null;
            long size = Files.size(file);
            if (size <= 0) return "";

            if (size <= 256_000) {
                return Files.readString(file, charset);
            }

            int tailBytes = 256_000;
            long start = Math.max(0, size - tailBytes);

            byte[] buf;
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(start);
                buf = new byte[(int) (size - start)];
                raf.readFully(buf);
            }

            String s = new String(buf, charset);
            if (s.length() > maxChars) {
                s = s.substring(s.length() - maxChars);
                s = "... (tail) ...\n" + s;
            }
            return s;
        } catch (IOException e) {
            return "<cannot read log: " + e.getMessage() + ">";
        }
    }

    public static String readAllLimited(Path file, Charset charset, int maxChars) {
        if (file == null) return null;
        try {
            if (!Files.exists(file)) return null;
            String s = Files.readString(file, charset);
            if (s.length() <= maxChars) return s;
            return "... (tail, truncated) ...\n" + s.substring(s.length() - maxChars);
        } catch (IOException e) {
            return "<cannot read log: " + e.getMessage() + ">";
        }
    }
}
