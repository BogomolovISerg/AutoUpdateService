package git.autoupdateservice.web;

import git.autoupdateservice.domain.LogEvent;
import git.autoupdateservice.repo.LogEventRepository;
import git.autoupdateservice.repo.StepLogBlobRepository;
import git.autoupdateservice.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class LogsController {

    private static final LocalDate NO_DATE = LocalDate.of(1970, 1, 1);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    private final LogEventRepository logEventRepository;
    private final SettingsService settingsService;
    private final StepLogBlobRepository stepLogBlobRepository;

    @GetMapping("/logs")
    public String logs(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false, defaultValue = "1") int page,
                       Model model) {

        if (page < 1) page = 1;

        var s = settingsService.get();
        int pageSize = SettingsService.normalizePageSize(s.getLogsPageSize(), 50);

        ZoneId zone = resolveZone();

        // если пользователь перепутал даты местами — чиняем
        if (from != null && to != null && from.isAfter(to)) {
            LocalDate tmp = from; from = to; to = tmp;
        }

        OffsetDateTime fromTs = null;
        OffsetDateTime toTs = null;

        if (from != null) {
            fromTs = from.atStartOfDay(zone).toOffsetDateTime();
        }
        if (to != null) {
            toTs = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime().minusNanos(1);
        }

        var pageable = PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "ts"));
        var p = logEventRepository.search(fromTs, toTs, pageable);
        List<LogEvent> list = p.getContent();

        // Группировка по дате (защита от ts == null)
        Map<LocalDate, List<LogEvent>> grouped = list.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getTs() == null
                                ? NO_DATE
                                : e.getTs().atZoneSameInstant(zone).toLocalDate(),
                        () -> new TreeMap<LocalDate, List<LogEvent>>(Comparator.<LocalDate>reverseOrder()),
                        Collectors.toList()
                ));

        // Форматирование ts в читаемый вид (ключи UUID, чтобы совпадали с e.id в шаблоне)
        var tsFmt = new HashMap<UUID, String>();
        for (var e : list) {
            if (e.getId() != null && e.getTs() != null) {
                tsFmt.put(e.getId(), e.getTs().atZoneSameInstant(zone).format(TS_FMT));
            }
        }

        // Чтобы в списке показать ссылку "подробнее" только там, где есть сохранённые step-логи.
        Set<UUID> withDetails;
        try {
            List<UUID> ids = list.stream()
                    .map(LogEvent::getId)
                    .filter(Objects::nonNull)
                    .toList();
            withDetails = ids.isEmpty()
                    ? Set.of()
                    : Set.copyOf(stepLogBlobRepository.findExistingEventIds(ids));
        } catch (Exception ex) {
            withDetails = Set.of();
        }

        model.addAttribute("grouped", grouped.entrySet());
        model.addAttribute("withDetails", withDetails);
        model.addAttribute("tsFmt", tsFmt);
        model.addAttribute("noDate", NO_DATE);

        model.addAttribute("from", from);
        model.addAttribute("to", to);

        model.addAttribute("page", page);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("totalPages", Math.max(1, p.getTotalPages()));
        model.addAttribute("totalElements", p.getTotalElements());

        return "logs";
    }

    @GetMapping("/logs/view")
    public String logView(@RequestParam UUID id, Model model) {
        var e = logEventRepository.findById(id).orElse(null);
        if (e == null) {
            model.addAttribute("message", "Запись лога не найдена");
            return "error";
        }

        var blobs = stepLogBlobRepository.findByEventIdOrderByKindAsc(id);
        model.addAttribute("event", e);
        model.addAttribute("blobs", blobs);
        return "logs-view";
    }

    private ZoneId resolveZone() {
        try {
            var s = settingsService.get();
            if (s != null && s.getTimezone() != null && !s.getTimezone().isBlank()) {
                return ZoneId.of(s.getTimezone());
            }
        } catch (Exception ignored) { }
        return ZoneId.systemDefault();
    }
}