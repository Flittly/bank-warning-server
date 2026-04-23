package com.yangtze.bankwarning.config;

import com.yangtze.bankwarning.dto.kafka.ModelResult;
import com.yangtze.bankwarning.dto.kafka.ModelTask;  // ← 添加导入
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;  // ← 添加导入
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaConfig {

    // ==================== ModelTask 生产者（新增）====================

    @Bean
    public ProducerFactory<String, ModelTask> modelTaskProducerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:9092");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);

        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    @Primary  // ← 标记为主要 Bean，优先注入
    public KafkaTemplate<String, ModelTask> modelTaskKafkaTemplate(
            ProducerFactory<String, ModelTask> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    // ==================== ModelResult 生产者（如果您还需要它）====================

    @Bean
    public ProducerFactory<String, ModelResult> modelResultProducerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:9092");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);

        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, ModelResult> modelResultKafkaTemplate(
            ProducerFactory<String, ModelResult> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    // ==================== ModelResult 消费者（如果您需要监听 ModelResult）====================

    @Bean
    public ConsumerFactory<String, ModelResult> modelResultConsumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:9092");
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "bank-warning-group");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JacksonJsonDeserializer.class);
        config.put(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, ModelResult.class.getName());
        config.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, "com.yangtze.bankwarning.dto.kafka");

        return new DefaultKafkaConsumerFactory<>(
            config,
            new StringDeserializer(),
            new JacksonJsonDeserializer<>(ModelResult.class)
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ModelResult> kafkaListenerContainerFactory(
            ConsumerFactory<String, ModelResult> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, ModelResult> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(
                new DefaultErrorHandler(new FixedBackOff(3000L, 3L)));

        return factory;
    }
}
