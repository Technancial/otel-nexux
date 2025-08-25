package pe.soapros.otel.core.infrastructure;

import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import pe.soapros.otel.core.domain.LoggerService;

import java.time.Instant;
import java.util.Map;

public class OpenTelemetryLoggerService implements LoggerService {
    
    private final Logger logger;
    private final String instrumentationName;
    private final boolean enableTraceCorrelation;
    
    public OpenTelemetryLoggerService(Logger logger) {
        this(logger, "otel-core", true);
    }
    
    public OpenTelemetryLoggerService(Logger logger, String instrumentationName, boolean enableTraceCorrelation) {
        this.logger = logger;
        this.instrumentationName = instrumentationName;
        this.enableTraceCorrelation = enableTraceCorrelation;
    }
    
    @Override
    public void info(String message, Map<String, String> attributes) {
        log(Severity.INFO, message, attributes, null);
    }
    
    @Override
    public void debug(String message, Map<String, String> attributes) {
        log(Severity.DEBUG, message, attributes, null);
    }
    
    @Override
    public void warn(String message, Map<String, String> attributes) {
        log(Severity.WARN, message, attributes, null);
    }
    
    @Override
    public void error(String message, Map<String, String> attributes) {
        log(Severity.ERROR, message, attributes, null);
    }
    
    @Override
    public void error(String message, Throwable throwable, Map<String, String> attributes) {
        log(Severity.ERROR, message, attributes, throwable);
    }
    
    private void log(Severity severity, String message, Map<String, String> attributes, Throwable throwable) {
        try {
            // 🔧 PUNTO CLAVE: Obtener el contexto actual CORRECTAMENTE
            Context currentContext = Context.current();
            System.out.println("currentContext.toString() = " + currentContext.toString());

            var logRecordBuilder = logger.logRecordBuilder()
                    .setTimestamp(Instant.now())
                    .setSeverity(severity)
                    .setBody(message)
                    .setContext(currentContext); // ← CRÍTICO: Usar el contexto actual

            // 🚀 CORRELACIÓN AUTOMÁTICA DE TRACE
            if (enableTraceCorrelation) {
                //addTraceCorrelation(logRecordBuilder);
                addTraceCorrelationExplicit(logRecordBuilder, currentContext);
            }


            // Add user attributes
            if (attributes != null) {
                attributes.forEach(logRecordBuilder::setAttribute);
            }

            // Add exception details if present
            if (throwable != null) {
                addExceptionDetails(logRecordBuilder, throwable);
            }

            // Add contextual information
            addContextualInformation(logRecordBuilder);

            logRecordBuilder.emit();

        } catch (Exception e) {
            // Fallback: al menos intentar hacer el log sin correlación
            System.err.println("ERROR in OpenTelemetryLoggerService: " + e.getMessage());
            System.err.println("Original message: " + message);
        }
    }
    
    private void addTraceCorrelation(io.opentelemetry.api.logs.LogRecordBuilder logRecordBuilder) {
        try {
            Context currentContext = Context.current();
            Span currentSpan = Span.fromContext(currentContext);
            SpanContext spanContext = currentSpan.getSpanContext();
            
            if (spanContext.isValid()) {
                logRecordBuilder.setAttribute("trace_id", spanContext.getTraceId());
                logRecordBuilder.setAttribute("span_id", spanContext.getSpanId());
                logRecordBuilder.setAttribute("trace_flags", String.format("0%d", spanContext.getTraceFlags().asByte()));
                
                if (spanContext.isSampled()) {
                    logRecordBuilder.setAttribute("trace_sampled", true);
                }
            }
        } catch (Exception e) {
            // Silently ignore - don't let logging correlation break actual logging
        }
    }

    /**
     * 🔧 MÉTODO CRÍTICO: Agregar correlación de trace EXPLÍCITAMENTE
     */
    private void addTraceCorrelationExplicit(LogRecordBuilder logRecordBuilder, Context context) {
        try {
            // Obtener el span actual del contexto
            Span currentSpan = Span.fromContext(context);
            SpanContext spanContext = currentSpan.getSpanContext();

            if (spanContext.isValid()) {
                // ✅ ESTABLECER EXPLÍCITAMENTE trace_id y span_id
                logRecordBuilder.setAttribute("trace_id", spanContext.getTraceId());
                logRecordBuilder.setAttribute("span_id", spanContext.getSpanId());
                logRecordBuilder.setAttribute("trace_flags", spanContext.getTraceFlags().asByte());

                if (spanContext.isSampled()) {
                    logRecordBuilder.setAttribute("trace_sampled", true);
                }

                System.out.println("🎯 TRACE CORRELATION ADDED: traceId=" + spanContext.getTraceId() +
                        ", spanId=" + spanContext.getSpanId());
            } else {
                System.out.println("⚠️ NO VALID SPAN CONTEXT FOUND");
            }
        } catch (Exception e) {
            System.err.println("🔥 ERROR adding trace correlation: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void addExceptionDetails(io.opentelemetry.api.logs.LogRecordBuilder logRecordBuilder, Throwable throwable) {
        logRecordBuilder.setAttribute("exception.type", throwable.getClass().getName());
        logRecordBuilder.setAttribute("exception.message", throwable.getMessage() != null ? throwable.getMessage() : "");
        logRecordBuilder.setAttribute("exception.stacktrace", getStackTrace(throwable));
    }
    
    private void addContextualInformation(io.opentelemetry.api.logs.LogRecordBuilder logRecordBuilder) {
        Thread currentThread = Thread.currentThread();
        logRecordBuilder.setAttribute("thread.name", currentThread.getName());
        logRecordBuilder.setAttribute("thread.id", currentThread.getId());
        logRecordBuilder.setAttribute("service.name", instrumentationName);
        
        // Environment information
        String environment = System.getenv("ENVIRONMENT");
        if (environment != null) {
            logRecordBuilder.setAttribute("environment", environment);
        }
        
        // Lambda context if available
        String functionName = System.getenv("AWS_LAMBDA_FUNCTION_NAME");
        if (functionName != null) {
            logRecordBuilder.setAttribute("faas.name", functionName);
            
            String functionVersion = System.getenv("AWS_LAMBDA_FUNCTION_VERSION");
            if (functionVersion != null) {
                logRecordBuilder.setAttribute("faas.version", functionVersion);
            }
        }
    }
    
    private String getStackTrace(Throwable throwable) {
        var writer = new java.io.StringWriter();
        var printWriter = new java.io.PrintWriter(writer);
        throwable.printStackTrace(printWriter);
        return writer.toString();
    }
    
    public Logger getLogger() {
        return logger;
    }
    
    public boolean isTraceCorrelationEnabled() {
        return enableTraceCorrelation;
    }
}