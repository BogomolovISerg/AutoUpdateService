package git.autoupdateservice.util;
import java.util.regex.Pattern;
public final class PasswordMasker {
        private PasswordMasker() {}
        private static final String MASK = "*****";
        private static final Pattern JSON_QUOTED_KEYS = Pattern.compile(
                "(?i)(\\\"(?:db-password|repo-password)\\\"\\s*:\\s*\\\")([^\\\"]*)(\\\")"
        );
        private static final Pattern PLAIN_KEYS = Pattern.compile(
                "(?i)(\\b(?:db-password|repo-password)\\b\\s*[:=]\\s*)([^,;\\s\\]\\}\"]+)"
        );
        private static final Pattern INLINE_FLAGS = Pattern.compile(
                "(?i)(\\b(?:--db-pwd|--db-password|--repo-pwd|--repo-password|--storage-pwd|--storage-password)\\b\\s+)(\"[^\"]*\"|'[^']*'|\\S+)"
        );

       private static final Pattern EQ_FLAGS = Pattern.compile(
                "(?i)(\\b(?:--db-pwd|--db-password|--repo-pwd|--repo-password|--storage-pwd|--storage-password)=)(\"[^\"]*\"|'[^']*'|\\S+)"
       );
        public static String maskText(String text) {
                if (text == null || text.isEmpty()) return text;
                        String masked = text;
                        masked = JSON_QUOTED_KEYS.matcher(masked).replaceAll("$1" + MASK + "$3");
                        masked = PLAIN_KEYS.matcher(masked).replaceAll("$1" + MASK);
                        masked = INLINE_FLAGS.matcher(masked).replaceAll("$1" + MASK);
                        masked = EQ_FLAGS.matcher(masked).replaceAll("$1" + MASK);
                        return masked;
        }
}