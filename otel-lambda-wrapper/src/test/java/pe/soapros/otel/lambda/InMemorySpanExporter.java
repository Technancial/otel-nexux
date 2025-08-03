package pe.soapros.otel.lambda;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class InMemorySpanExporter implements SpanExporter {
    private final List<SpanData> finishedSpanItems = Collections.synchronizedList(new ArrayList<>());

    private InMemorySpanExporter() {}

    public static InMemorySpanExporter create() {
        return new InMemorySpanExporter();
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        finishedSpanItems.addAll(spans);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        finishedSpanItems.clear();
        return CompletableResultCode.ofSuccess();
    }

    public List<SpanData> getFinishedSpanItems() {
        return new ArrayList<>(finishedSpanItems);
    }

    public void reset() {
        finishedSpanItems.clear();
    }
}
