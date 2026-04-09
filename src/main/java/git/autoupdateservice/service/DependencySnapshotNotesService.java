package git.autoupdateservice.service;

import lombok.Builder;
import lombok.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DependencySnapshotNotesService {

    private static final String ERRORS_PREFIX = "Первые ошибки:";
    private static final Pattern NOTE_PATTERN = Pattern.compile(
            "Пропущен\\s+(общий модуль|объектный модуль)(?:\\s+\\[(.*?)] )?:\\s+(.*?)\\s+\\|\\s+rel=(.*?)\\s+\\|\\s+decodedRel=(.*?)\\s+\\|\\s+error=(.*)"
    );

    public String buildSummary(String notes) {
        if (notes == null || notes.isBlank()) {
            return null;
        }
        int index = notes.indexOf(". " + ERRORS_PREFIX);
        if (index < 0) {
            return notes;
        }
        return notes.substring(0, index);
    }

    public List<SnapshotNoteRow> parseRows(String notes) {
        if (notes == null || notes.isBlank()) {
            return List.of();
        }

        int index = notes.indexOf(ERRORS_PREFIX);
        if (index < 0) {
            return List.of();
        }

        String[] parts = notes.substring(index + ERRORS_PREFIX.length()).trim().split("\\s*;\\s*");
        List<SnapshotNoteRow> rows = new ArrayList<>();
        for (String part : parts) {
            Matcher matcher = NOTE_PATTERN.matcher(part);
            if (matcher.matches()) {
                String kind = matcher.group(1);
                String sourceName = matcher.group(2);
                if (sourceName != null && !sourceName.isBlank()) {
                    kind = kind + " [" + sourceName + "]";
                }
                rows.add(SnapshotNoteRow.builder()
                        .kind(kind)
                        .file(matcher.group(3))
                        .rel(matcher.group(4))
                        .decodedRel(matcher.group(5))
                        .error(matcher.group(6))
                        .build());
                continue;
            }

            rows.add(SnapshotNoteRow.builder()
                    .kind("прочее")
                    .file(part)
                    .rel("")
                    .decodedRel("")
                    .error("")
                    .build());
        }
        return rows;
    }

    @Value
    @Builder
    public static class SnapshotNoteRow {
        String kind;
        String file;
        String rel;
        String decodedRel;
        String error;
    }
}
