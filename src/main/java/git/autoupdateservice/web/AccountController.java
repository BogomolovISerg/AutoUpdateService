package git.autoupdateservice.web;

import git.autoupdateservice.domain.LogType;
import git.autoupdateservice.repo.AppUserRepository;
import git.autoupdateservice.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;

@Controller
@RequiredArgsConstructor
@RequestMapping("/account")
public class AccountController {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    @GetMapping("/change-password")
    public String page(Model model) {
        model.addAttribute("form", new ChangePasswordForm());
        return "change-password";
    }

    @PostMapping("/change-password")
    public String change(@Valid @ModelAttribute("form") ChangePasswordForm form,
                         BindingResult br,
                         Authentication auth,
                         HttpServletRequest request,
                         Model model) {
        if (br.hasErrors()) return "change-password";
        if (form.newPassword == null || form.newPassword.isBlank()) {
            model.addAttribute("error", "Пароль не может быть пустым");
            return "change-password";
        }
        if (!form.newPassword.equals(form.confirmPassword)) {
            model.addAttribute("error", "Пароли не совпадают");
            return "change-password";
        }
        if (auth == null) return "redirect:/login";

        var u = appUserRepository.findByUsernameAndActiveTrue(auth.getName()).orElseThrow();
        u.setPasswordHash(passwordEncoder.encode(form.newPassword));
        u.setMustChangePassword(false);
        u.setUpdatedAt(OffsetDateTime.now());
        appUserRepository.save(u);

        auditLogService.info(LogType.PASSWORD_CHANGED, "Password changed", "{}",
                IpUtil.clientIp(request), auth.getName(), null);

        return "redirect:/";
    }

    @Data
    public static class ChangePasswordForm {
        public String newPassword;
        public String confirmPassword;
    }
}

