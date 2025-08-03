package pe.soapros.otel.core.domain;

public interface TraceSpan extends AutoCloseable{
    void addEvent(String event);
    void recordException(Throwable throwable);
    void end();
}
