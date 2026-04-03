package git.autoupdateservice.security;

import git.autoupdateservice.repo.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class MustChangePasswordFilter extends OncePerRequestFilter {

    private final AppUserRepository appUserRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);

        if (!authenticated) {
            filterChain.doFilter(request, response);
            return;
        }

        // allow specific endpoints
        if (path.equals("/login") || path.equals("/logout") || path.equals("/account/change-password") ||
                path.startsWith("/api/gitlab") || path.endsWith(".css")) {
            filterChain.doFilter(request, response);
            return;
        }

        var userOpt = appUserRepository.findByUsernameAndActiveTrue(auth.getName());
        if (userOpt.isPresent() && userOpt.get().isMustChangePassword()) {
            response.sendRedirect("/account/change-password");
            return;
        }

        filterChain.doFilter(request, response);
    }
}

