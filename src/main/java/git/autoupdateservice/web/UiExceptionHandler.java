package git.autoupdateservice.web;

import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class UiExceptionHandler {
    @ExceptionHandler(IllegalStateException.class)
    public String handle(IllegalStateException e, Model model) {
        model.addAttribute("message", e.getMessage());
        return "error";
    }
}
