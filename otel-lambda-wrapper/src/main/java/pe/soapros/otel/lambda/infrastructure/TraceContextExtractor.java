package pe.soapros.otel.lambda.infrastructure;

import com.amazonaws.services.lambda.runtime.events.KafkaEvent;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TraceContextExtractor {
    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier != null ? carrier.get(key) : null;
        }

        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier != null ? carrier.keySet() : Collections.emptySet();
        }
    };

    public static Context extractFromHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Context.current();
        }

        try {
            return GlobalOpenTelemetry.getPropagators()
                    .getTextMapPropagator()
                    .extract(Context.current(), headers, MAP_GETTER);
        } catch (Exception e) {
            // Log pero no fallar - continuar sin contexto de traza
            System.err.println("Failed to extract trace context from headers: " + e.getMessage());
            return Context.current();
        }
    }

    public static Context extractFromSqsMessage(SQSEvent.SQSMessage message) {
        if (message == null) {
            return Context.current();
        }

        Map<String, SQSEvent.MessageAttribute> attributes = message.getMessageAttributes();
        if (attributes == null || attributes.isEmpty()) {
            return Context.current();
        }

        try {
            Map<String, String> headerMap = attributes.entrySet().stream()
                    .filter(e -> e.getValue() != null && e.getValue().getStringValue() != null)
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            e -> e.getValue().getStringValue()
                    ));

            return extractFromHeaders(headerMap);
        } catch (Exception e) {
            System.err.println("Failed to extract trace context from SQS message: " + e.getMessage());
            return Context.current();
        }
    }

    public static Context extractFromKafkaRecord(KafkaEvent.KafkaEventRecord record) {
        if (record == null) {
            return Context.current();
        }

        Map<String, String> headersMap = new HashMap<>();

        try {
            List<Map<String, byte[]>> headers = record.getHeaders();
            if (headers != null) {
                for (Map<String, byte[]> header : headers) {
                    if (header != null) {
                        for (Map.Entry<String, byte[]> entry : header.entrySet()) {
                            if (entry.getKey() != null && entry.getValue() != null) {
                                headersMap.put(entry.getKey(),
                                        new String(entry.getValue(), StandardCharsets.UTF_8));
                            }
                        }
                    }
                }
            }

            return extractFromHeaders(headersMap);
        } catch (Exception e) {
            System.err.println("Failed to extract trace context from Kafka record: " + e.getMessage());
            return Context.current();
        }
    }

    public static void injectIntoHeaders(Context context, Map<String, String> headers) {
        if (context == null || headers == null) return;

        try {
            GlobalOpenTelemetry.getPropagators()
                    .getTextMapPropagator()
                    .inject(context, headers, (carrier, key, value) -> {
                        if (carrier != null && key != null && value != null) {
                            carrier.put(key, value);
                        }
                    });
        } catch (Exception e) {
            System.err.println("Failed to inject trace context: " + e.getMessage());
        }
    }

    /**
     * Verifica si hay contexto de traza válido
     */
    public static boolean hasValidTraceContext(Context context) {
        if (context == null) return false;

        try {
            return io.opentelemetry.api.trace.Span.fromContext(context)
                    .getSpanContext()
                    .isValid();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extrae trace ID del contexto (útil para logging correlacionado)
     */
    public static String getTraceId(Context context) {
        if (context == null) return null;

        try {
            return io.opentelemetry.api.trace.Span.fromContext(context)
                    .getSpanContext()
                    .getTraceId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extrae span ID del contexto
     */
    public static String getSpanId(Context context) {
        if (context == null) return null;

        try {
            return io.opentelemetry.api.trace.Span.fromContext(context)
                    .getSpanContext()
                    .getSpanId();
        } catch (Exception e) {
            return null;
        }
    }
}
