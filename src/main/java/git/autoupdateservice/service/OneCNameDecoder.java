package git.autoupdateservice.service;

import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OneCNameDecoder {

    private static final Pattern ENCODED_PART = Pattern.compile("#U([0-9A-Fa-f]{4})");

    public String decodePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return rawPath;
        }

        String normalized = rawPath.replace('\\', '/');
        String[] parts = normalized.split("/");
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(decodePathSegment(parts[i]));
        }

        return sb.toString();
    }

    public String decodePathSegment(String raw) {
        if (raw == null || raw.isBlank() || raw.indexOf("#U") < 0) {
            return raw;
        }

        try {
            Matcher matcher = ENCODED_PART.matcher(raw);
            StringBuffer sb = new StringBuffer();
            boolean found = false;

            while (matcher.find()) {
                found = true;
                int codePoint = Integer.parseInt(matcher.group(1), 16);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) codePoint)));
            }

            if (!found) {
                return raw;
            }

            matcher.appendTail(sb);
            return sb.toString();
        } catch (Exception e) {
            return raw;
        }
    }
}