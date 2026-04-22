package git.autoupdateservice.web;

import git.autoupdateservice.domain.Settings;
import git.autoupdateservice.service.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;

@Controller
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;

    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("form", SettingsForm.from(settingsService.get()));
        model.addAttribute("pendingNewCount", settingsService.pendingNewCount());
        return "settings";
    }

    @PostMapping("/settings")
    public String save(@Valid @ModelAttribute("form") SettingsForm form,
                       BindingResult br,
                       HttpServletRequest request,
                       Authentication auth,
                       Model model) {
        if (br.hasErrors()) {
            model.addAttribute("pendingNewCount", settingsService.pendingNewCount());
            return "settings";
        }

        String ip = IpUtil.clientIp(request);
        String actor = auth != null ? auth.getName() : "anonymous";

        long pending = settingsService.pendingNewCount();
        if (!form.autoUpdateEnabled && pending > 0 && form.cancelPending == null) {
            model.addAttribute("pendingNewCount", pending);
            model.addAttribute("form", form);
            return "confirm-disable";
        }

        if (form.cancelPending != null) {
            settingsService.update(form.toSettings(), ip, actor);
            if (!form.autoUpdateEnabled && Boolean.TRUE.equals(form.cancelPending)) {
                settingsService.cancelPendingNewTasks(ip, actor);
            }
        } else {
            settingsService.update(form.toSettings(), ip, actor);
        }

        return "redirect:/";
    }

    @Data
    public static class SettingsForm {
        public boolean autoUpdateEnabled;
        public boolean dependencyGraphRebuildEnabled;
        public LocalTime testRunTime;
        public LocalDate nextTestRunDate;
        public LocalTime productionRunTime;
        public LocalDate nextProductionRunDate;
        public String timezone;
        public String lockMessage;
        public String uccode;
        public int closedMaxAttempts;
        public int closedSleepSeconds;

        public int queuePageSize;
        public int logsPageSize;

        public Boolean cancelPending;

        public boolean isDependencyGraphRebuildEnabled() {
            return dependencyGraphRebuildEnabled;
        }

        public void setDependencyGraphRebuildEnabled(boolean dependencyGraphRebuildEnabled) {
            this.dependencyGraphRebuildEnabled = dependencyGraphRebuildEnabled;
        }

        static SettingsForm from(Settings s) {
            SettingsForm f = new SettingsForm();
            f.autoUpdateEnabled = s.isAutoUpdateEnabled();
            f.dependencyGraphRebuildEnabled = s.isDependencyGraphRebuildEnabled();
            f.testRunTime = s.getTestRunTime();
            f.nextTestRunDate = s.getNextTestRunDate();
            f.productionRunTime = s.getProductionRunTime();
            f.nextProductionRunDate = s.getNextProductionRunDate();
            f.timezone = s.getTimezone();
            f.lockMessage = s.getLockMessage();
            f.uccode = s.getUccode();
            f.closedMaxAttempts = s.getClosedMaxAttempts();
            f.closedSleepSeconds = s.getClosedSleepSeconds();

            f.queuePageSize = s.getQueuePageSize();
            f.logsPageSize = s.getLogsPageSize();
            return f;
        }

        Settings toSettings() {
            Settings s = new Settings();
            s.setId(1L);
            s.setAutoUpdateEnabled(autoUpdateEnabled);
            s.setDependencyGraphRebuildEnabled(dependencyGraphRebuildEnabled);
            s.setTestRunTime(testRunTime);
            s.setNextTestRunDate(nextTestRunDate);
            s.setProductionRunTime(productionRunTime);
            s.setNextProductionRunDate(nextProductionRunDate);
            s.setTimezone(timezone);
            s.setLockMessage(lockMessage);
            s.setUccode(uccode);
            s.setClosedMaxAttempts(closedMaxAttempts);
            s.setClosedSleepSeconds(closedSleepSeconds);

            s.setQueuePageSize(queuePageSize);
            s.setLogsPageSize(logsPageSize);
            return s;
        }
    }
}

