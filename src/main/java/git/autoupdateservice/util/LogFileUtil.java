package git.autoupdateservice.util;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LogFileUtil {
    private LogFileUtil() {}

    /**
     * Очищает файл (создаёт директорию и делает размер 0), ошибки игнорирует.
     */
    public static void truncateQuietly(Path file) {
        if (file == null) return;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, "");
        } catch (Exception ignored) {}
    }

    /**
     * Читает хвост файла (максимум maxChars символов), для отображения в веб-интерфейсе.
     */
    public static String readTail(Path file, Charset charset, int maxChars) {
        if (file == null) return null;
        try {
            if (!Files.exists(file)) return null;
            long size = Files.size(file);
            if (size <= 0) return "";

            // Если файл небольшой — читаем целиком.
            if (size <= 256_000) {
                return Files.readString(file, charset);
            }

            // Иначе берём хвост (по байтам), потом режем по символам.
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

    /**
     * Читает файл целиком, но с ограничением по количеству символов (чтобы не раздувать базу).
     * Если файл больше maxChars — сохраняем хвост (самые свежие строки обычно в конце).
     */
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
