package pe.soapros.otel.lambda.examples;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.KafkaEvent;
import pe.soapros.otel.lambda.infrastructure.KafkaTracingLambdaWrapper;
import io.opentelemetry.api.GlobalOpenTelemetry;

/**
 * Ejemplo simple de función Lambda Kafka usando KafkaTracingLambdaWrapper.
 */
public class KafkaLambdaExample extends KafkaTracingLambdaWrapper {

    public KafkaLambdaExample() {
        super(GlobalOpenTelemetry.get());
    }

    @Override
    protected void handleRecord(KafkaEvent.KafkaEventRecord record, Context context) {
        // El wrapper ya ha creado el span y configurado las métricas automáticamente
        System.out.println("Procesando mensaje Kafka del topic: " + record.getTopic());
        System.out.println("Partition: " + record.getPartition() + ", Offset: " + record.getOffset());
        
        // Tu lógica de negocio aquí
        processKafkaMessage(record);
        
        System.out.println("Mensaje Kafka procesado exitosamente");
    }

    private void processKafkaMessage(KafkaEvent.KafkaEventRecord record) {
        // Obtener información del mensaje
        String topic = record.getTopic();
        String value = record.getValue();
        long offset = record.getOffset();
        int partition = record.getPartition();
        
        // Simular procesamiento
        try {
            Thread.sleep(150); // Simular procesamiento
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Processing interrupted");
        }
        
        System.out.printf("Procesando mensaje de topic '%s', partition %d, offset %d: %s%n", 
                         topic, partition, offset, value);
    }
}