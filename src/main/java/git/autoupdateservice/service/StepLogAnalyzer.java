package git.autoupdateservice.service;

/**
 * Анализатор логов шага.
 *
 * На вход подаём полный текст stdout/stderr/debug (уже прочитанный с корректной кодировкой).
 * Возвращаем:
 *  - hasErrors=true если по логам видно, что шаг НЕ выполнился, даже если exitCode==0
 *  - summary: краткий результат выполнения для web-лога (или краткое сообщение об ошибке)
 */
public interface StepLogAnalyzer {

    record Analysis(boolean hasErrors, String summary) {
        public static Analysis ok() { return new Analysis(false, null); }
        public static Analysis error(String summary) { return new Analysis(true, summary); }
    }

    Analysis analyze(String stepCode, String stdoutText, String stderrText, String debugText);
}
