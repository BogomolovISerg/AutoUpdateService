package git.autoupdateservice.service;

import git.autoupdateservice.domain.CodeSourceRoot;
import git.autoupdateservice.domain.DependencyCallerType;
import git.autoupdateservice.domain.DependencyGraphDirtyItem;
import git.autoupdateservice.domain.DependencyGraphState;
import git.autoupdateservice.domain.DependencySnapshot;
import git.autoupdateservice.domain.SourceKind;
import git.autoupdateservice.repo.CodeSourceRootRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DependenciesPageService {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    private final DependencySnapshotService dependencySnapshotService;
    private final DependencyGraphStateService dependencyGraphStateService;
    private final SettingsService settingsService;
    private final CodeSourceRootRepository codeSourceRootRepository;
    private final DependencySnapshotNotesService dependencySnapshotNotesService;

    public PageData buildPageData(String mode, String query, DependencyCallerType objectType) {
        DependencySnapshot snapshot = dependencySnapshotService.latestReadySnapshot().orElse(null);
        DependencyGraphState graphState = dependencyGraphStateService.getState();
        ZoneId zone = resolveZoneId();

        return new PageData(
                snapshot,
                snapshot == null ? null : snapshot.getId(),
                graphState,
                formatTs(snapshot == null ? null : snapshot.getStartedAt(), zone),
                formatTs(snapshot == null ? null : snapshot.getFinishedAt(), zone),
                formatTs(graphState == null ? null : graphState.getLastRebuildAt(), zone),
                formatTs(graphState == null ? null : graphState.getLastGitChangeAt(), zone),
                formatTs(graphState == null ? null : graphState.getStaleSince(), zone),
                dependencySnapshotNotesService.buildSummary(snapshot == null ? null : snapshot.getNotes()),
                dependencySnapshotNotesService.parseRows(snapshot == null ? null : snapshot.getNotes()),
                mode,
                query,
                objectType,
                DependencyCallerType.values(),
                codeSourceRootRepository.findFirstBySourceKindOrderByUpdatedAtDesc(SourceKind.BASE).orElse(null),
                codeSourceRootRepository.findAllBySourceKindAndEnabledIsTrueOrderByPriorityAscSourceNameAsc(SourceKind.EXTENSION),
                dependencyGraphStateService.latestDirtyItems()
        );
    }

    private ZoneId resolveZoneId() {
        try {
            return ZoneId.of(settingsService.get().getTimezone());
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    private String formatTs(OffsetDateTime value, ZoneId zone) {
        if (value == null) {
            return null;
        }
        return value.atZoneSameInstant(zone).format(TS_FMT);
    }

    public record PageData(
            DependencySnapshot snapshot,
            java.util.UUID treeSnapshotId,
            DependencyGraphState graphState,
            String snapshotStartedAtFmt,
            String snapshotFinishedAtFmt,
            String graphLastRebuildAtFmt,
            String graphLastGitChangeAtFmt,
            String graphStaleSinceFmt,
            String snapshotNoteSummary,
            List<DependencySnapshotNotesService.SnapshotNoteRow> snapshotNoteRows,
            String mode,
            String query,
            DependencyCallerType objectType,
            DependencyCallerType[] objectTypes,
            CodeSourceRoot baseSource,
            List<CodeSourceRoot> extensionSources,
            List<DependencyGraphDirtyItem> dirtyItems
    ) {
        public void applyTo(Model model) {
            model.addAttribute("snapshotStartedAtFmt", snapshotStartedAtFmt);
            model.addAttribute("snapshotFinishedAtFmt", snapshotFinishedAtFmt);
            model.addAttribute("graphLastRebuildAtFmt", graphLastRebuildAtFmt);
            model.addAttribute("graphLastGitChangeAtFmt", graphLastGitChangeAtFmt);
            model.addAttribute("graphStaleSinceFmt", graphStaleSinceFmt);
            model.addAttribute("snapshotNoteSummary", snapshotNoteSummary);
            model.addAttribute("snapshotNoteRows", snapshotNoteRows);
            model.addAttribute("snapshot", snapshot);
            model.addAttribute("treeSnapshotId", treeSnapshotId);
            model.addAttribute("mode", mode);
            model.addAttribute("q", query);
            model.addAttribute("objectType", objectType);
            model.addAttribute("objectTypes", objectTypes);
            model.addAttribute("baseSource", baseSource);
            model.addAttribute("extensionSources", extensionSources);
            model.addAttribute("graphState", graphState);
            model.addAttribute("dirtyItems", dirtyItems);
        }
    }
}
