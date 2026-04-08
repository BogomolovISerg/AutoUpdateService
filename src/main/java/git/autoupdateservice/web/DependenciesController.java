package git.autoupdateservice.web;

import git.autoupdateservice.domain.CodeSourceRoot;
import git.autoupdateservice.domain.DependencyCallerType;
import git.autoupdateservice.domain.SourceKind;
import git.autoupdateservice.repo.CodeSourceRootRepository;
import git.autoupdateservice.service.DependencyGraphStateService;
import git.autoupdateservice.service.DependencyTreeBuildService;
import git.autoupdateservice.service.DependencyTreeSearchService;
import git.autoupdateservice.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class DependenciesController {

    private final DependencyTreeBuildService dependencyTreeBuildService;
    private final DependencyTreeSearchService dependencyTreeSearchService;
    private final CodeSourceRootRepository codeSourceRootRepository;
    private final DependencyGraphStateService dependencyGraphStateService;
    private final SettingsService settingsService;

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    @GetMapping("/dependencies")
    public String page(
            @RequestParam(required = false, defaultValue = "module") String mode,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) DependencyCallerType objectType,
            Model model
    ) {
        var snapshot = dependencyTreeSearchService.latestSnapshot().orElse(null);
        var graphState = dependencyGraphStateService.getState();

        ZoneId zone;
        try {
            zone = ZoneId.of(settingsService.get().getTimezone());
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
        }

        model.addAttribute("snapshotStartedAtFmt",
                formatTs(snapshot == null ? null : snapshot.getStartedAt(), zone));
        model.addAttribute("snapshotFinishedAtFmt",
                formatTs(snapshot == null ? null : snapshot.getFinishedAt(), zone));
        model.addAttribute("graphLastRebuildAtFmt",
                formatTs(graphState == null ? null : graphState.getLastRebuildAt(), zone));
        model.addAttribute("graphLastGitChangeAtFmt",
                formatTs(graphState == null ? null : graphState.getLastGitChangeAt(), zone));
        model.addAttribute("graphStaleSinceFmt",
                formatTs(graphState == null ? null : graphState.getStaleSince(), zone));

        model.addAttribute("snapshotNoteSummary",
                buildSnapshotNoteSummary(snapshot == null ? null : snapshot.getNotes()));
        model.addAttribute("snapshotNoteRows",
                parseSnapshotNotes(snapshot == null ? null : snapshot.getNotes()));

        model.addAttribute("snapshot", snapshot);
        model.addAttribute("treeSnapshotId", snapshot == null ? null : snapshot.getId());
        model.addAttribute("mode", mode);
        model.addAttribute("q", q);
        model.addAttribute("objectType", objectType);
        model.addAttribute("objectTypes", DependencyCallerType.values());
        model.addAttribute("baseSource", codeSourceRootRepository.findFirstBySourceKindOrderByUpdatedAtDesc(SourceKind.BASE).orElse(null));
        model.addAttribute("extensionSources", codeSourceRootRepository.findAllBySourceKindAndEnabledIsTrueOrderByPriorityAscSourceNameAsc(SourceKind.EXTENSION));
        model.addAttribute("graphState", graphState);
        model.addAttribute("dirtyItems", dependencyGraphStateService.latestDirtyItems());
        return "dependencies";
    }

    @PostMapping("/dependencies/rebuild")
    public String rebuild(RedirectAttributes ra) {
        try {
            var snapshot = dependencyTreeBuildService.fullRebuild();
            ra.addFlashAttribute("message", "Сканирование завершено. Статус: " + snapshot.getStatus());
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dependencies";
    }

    @PostMapping("/dependencies/source/save")
    public String saveBaseSource(
            @RequestParam String sourceName,
            @RequestParam String rootPath,
            @RequestParam(defaultValue = "false") boolean enabled,
            RedirectAttributes ra
    ) {
        CodeSourceRoot source = codeSourceRootRepository
                .findFirstBySourceKindOrderByUpdatedAtDesc(SourceKind.BASE)
                .orElseGet(CodeSourceRoot::new);

        source.setSourceKind(SourceKind.BASE);
        source.setSourceName(sourceName == null || sourceName.isBlank() ? "Основная конфигурация" : sourceName.trim());
        source.setRootPath(rootPath == null ? "" : rootPath.trim());
        source.setEnabled(enabled);
        source.setPriority(0);
        source.setUpdatedAt(OffsetDateTime.now());
        codeSourceRootRepository.save(source);

        ra.addFlashAttribute("message", "Источник основной конфигурации сохранён");
        return "redirect:/dependencies";
    }

    @GetMapping("/dependencies/tree/modules")
    @ResponseBody
    public List<DependencyTreeSearchService.ModuleNode> treeModules(
            @RequestParam UUID snapshotId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) DependencyCallerType objectType,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        return dependencyTreeSearchService.findModules(snapshotId, q, objectType, limit, offset);
    }

    @GetMapping("/dependencies/tree/modules/count")
    @ResponseBody
    public Map<String, Long> treeModuleCount(
            @RequestParam UUID snapshotId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) DependencyCallerType objectType
    ) {
        return Map.of("total", dependencyTreeSearchService.countModules(snapshotId, q, objectType));
    }

    @GetMapping("/dependencies/tree/methods")
    @ResponseBody
    public List<DependencyTreeSearchService.MethodNode> treeMethods(
            @RequestParam UUID snapshotId,
            @RequestParam String moduleName,
            @RequestParam SourceKind sourceKind,
            @RequestParam String sourceName,
            @RequestParam(required = false) DependencyCallerType objectType
    ) {
        return dependencyTreeSearchService.findMethods(snapshotId, moduleName, sourceKind, sourceName, objectType);
    }

    @GetMapping("/dependencies/tree/objects")
    @ResponseBody
    public List<DependencyTreeSearchService.ObjectNode> treeObjects(
            @RequestParam UUID snapshotId,
            @RequestParam String moduleName,
            @RequestParam String methodName,
            @RequestParam SourceKind sourceKind,
            @RequestParam String sourceName,
            @RequestParam(required = false) DependencyCallerType objectType
    ) {
        return dependencyTreeSearchService.findObjects(snapshotId, moduleName, methodName, sourceKind, sourceName, objectType);
    }

    @lombok.Value
    @lombok.Builder
    public static class SnapshotNoteRow {
        String kind;
        String file;
        String rel;
        String decodedRel;
        String error;
    }

    private String formatTs(OffsetDateTime value, ZoneId zone) {
        if (value == null) {
            return null;
        }
        return value.atZoneSameInstant(zone).format(TS_FMT);
    }

    private String buildSnapshotNoteSummary(String notes) {
        if (notes == null || notes.isBlank()) {
            return null;
        }
        int idx = notes.indexOf(". Первые ошибки:");
        if (idx < 0) {
            return notes;
        }
        return notes.substring(0, idx);
    }

    private List<SnapshotNoteRow> parseSnapshotNotes(String notes) {
        if (notes == null || notes.isBlank()) {
            return List.of();
        }

        int idx = notes.indexOf("Первые ошибки:");
        if (idx < 0) {
            return List.of();
        }

        String tail = notes.substring(idx + "Первые ошибки:".length()).trim();
        String[] parts = tail.split("\\s*;\\s*");

        Pattern p = Pattern.compile(
                "Пропущен\\s+(общий модуль|объектный модуль)(?:\\s+\\[(.*?)] )?:\\s+(.*?)\\s+\\|\\s+rel=(.*?)\\s+\\|\\s+decodedRel=(.*?)\\s+\\|\\s+error=(.*)"
        );

        List<SnapshotNoteRow> rows = new ArrayList<>();
        for (String part : parts) {
            Matcher m = p.matcher(part);
            if (m.matches()) {
                String kind = m.group(1);
                String sourceName = m.group(2);
                if (sourceName != null && !sourceName.isBlank()) {
                    kind = kind + " [" + sourceName + "]";
                }
                rows.add(SnapshotNoteRow.builder()
                        .kind(kind)
                        .file(m.group(3))
                        .rel(m.group(4))
                        .decodedRel(m.group(5))
                        .error(m.group(6))
                        .build());
            } else {
                rows.add(SnapshotNoteRow.builder()
                        .kind("прочее")
                        .file(part)
                        .rel("")
                        .decodedRel("")
                        .error("")
                        .build());
            }
        }
        return rows;
    }
}
