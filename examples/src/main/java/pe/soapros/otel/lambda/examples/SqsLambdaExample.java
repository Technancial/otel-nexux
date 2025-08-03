package pe.soapros.otel.lambda.examples;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import pe.soapros.otel.lambda.infrastructure.SqsTracingLambdaWrapper;
import io.opentelemetry.api.GlobalOpenTelemetry;

/**
 * Ejemplo simple de función Lambda SQS usando SqsTracingLambdaWrapper.
 */
public class SqsLambdaExample extends SqsTracingLambdaWrapper {

    public SqsLambdaExample() {
        super(GlobalOpenTelemetry.get());
    }

    @Override
    public void handle(SQSEvent.SQSMessage message, Context context) {
        // El wrapper ya ha creado el span y configurado las métricas automáticamente
        System.out.println("Procesando mensaje SQS: " + message.getMessageId());
        System.out.println("Body: " + message.getBody());
        
        // Tu lógica de negocio aquí
        processMessage(message);
        
        System.out.println("Mensaje SQS procesado exitosamente");
    }

    private void processMessage(SQSEvent.SQSMessage message) {
        // Lógica específica del mensaje
        String body = message.getBody();
        String messageId = message.getMessageId();
        
        // Simular procesamiento
        try {
            Thread.sleep(100); // Simular procesamiento
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Processing interrupted");
        }
        
        System.out.println("Procesando mensaje: " + messageId + " con body: " + body);
    }
}