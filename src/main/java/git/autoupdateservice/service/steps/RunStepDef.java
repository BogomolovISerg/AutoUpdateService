package git.autoupdateservice.service.steps;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Описание шага выполнения регламентного задания.
 * Задаётся во внешнем JSON-файле (runner.steps-file).
 *
 * ВАЖНО: здесь хранятся САМИ команды (массив аргументов), а не "типы".
 *
 * Поля:
 *  - order: номер/порядок (можно также num/number/step)
 *  - code: код шага (используется для имён логов, допускает {{...}} токены)
 *  - title: человекочитаемое имя (допускает {{...}} токены)
 *  - command: команда как массив аргументов (каждый аргумент отдельной строкой; допускает {{...}} токены)
 *  - enabled: включён ли шаг (по умолчанию true)
 *  - always: выполнять ли шаг в finally (по умолчанию false)
 *  - foreach: "extensions" — выполнить шаг для каждого расширения
 *  - condition: "needMain" — выполнить только если есть задачи MAIN
 *  - special: "extensionPlans" — выполнить шаги из extension JSON по маске extensionPlanFilePattern
 *
 *  - retry: (опционально) режим "проверка с повторами" — полезно для session closed
 *      - maxAttempts: 0 => брать из Settings.closedMaxAttempts
 *      - sleepSeconds: 0 => брать из Settings.closedSleepSeconds
 *      - checkCommand: команда-проверка (обычно session closed)
 *      - onFailCommand: команда при неудаче (например session kill), затем sleep и повтор
 */
@Data
@NoArgsConstructor
public class RunStepDef {

    @JsonAlias({"num", "number", "step"})
    private int order;

    private String code;
    private String title;

    private boolean enabled = true;
    private boolean always = false;

    private String foreach;     // e.g. "extensions"
    private String condition;   // e.g. "needMain"
    @JsonAlias({"action", "builtin"})
    private String special;     // e.g. "extensionPlans"
    private String phase;       // legacy compatibility, ignored by executor

    private List<String> command;

    private RetryDef retry;

    @Data
    @NoArgsConstructor
    public static class RetryDef {
        private int maxAttempts = 0;
        private int sleepSeconds = 0;
        private List<String> checkCommand;
        private List<String> onFailCommand;
    }
}
