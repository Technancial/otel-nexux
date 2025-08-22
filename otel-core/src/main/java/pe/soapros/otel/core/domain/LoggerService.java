package pe.soapros.otel.core.domain;

import java.util.Map;

public interface LoggerService {
    void info(String message, Map<String, String> attributes);
    void debug(String message, Map<String, String> attributes);
    void warn(String message, Map<String, String> attributes);
    void error(String message, Map<String, String> attributes);

    void error(String message, Throwable throwable, Map<String, String> attributes);
}
