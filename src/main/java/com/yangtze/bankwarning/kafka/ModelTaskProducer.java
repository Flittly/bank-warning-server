package com.yangtze.bankwarning.kafka;

import com.yangtze.bankwarning.dto.kafka.ModelTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

// Kafka 任务生产者：负责把断面计算任务发送到 Kafka
@Component
public class ModelTaskProducer {

    // 日志记录器
    private static final Logger log = LoggerFactory.getLogger(ModelTaskProducer.class);

    // Spring Kafka 模板
    private final KafkaTemplate<String, ModelTask> kafkaTemplate;

    // 任务 Topic 名称（从配置文件读取）
    @Value("${app.kafka.task-topic}")
    private String taskTopic;

    // 构造函数注入 KafkaTemplate
    public ModelTaskProducer(KafkaTemplate<String, ModelTask> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    // 发送一个任务到 Kafka
    public void send(ModelTask task) {
        // 构造消息 Key：runId + ":" + sectionId
        // 这样同一个 runId 的不同 section 会均匀分散到不同 partition，能并行处理
        String key = task.getRunId() + ":" + task.getSectionId();

        // 异步发送消息到 Kafka
        CompletableFuture<SendResult<String, ModelTask>> future =
                kafkaTemplate.send(taskTopic, key, task);

        // 监听发送结果
        future.whenComplete((result, throwable) -> {
            // 如果发送失败
            if (throwable != null) {
                log.error("[kafka-task-send] 发送任务失败 runId={} taskId={} sectionId={} modelType={} error={}",
                        task.getRunId(),
                        task.getTaskId(),
                        task.getSectionId(),
                        task.getModelType(),
                        throwable.getMessage(),
                        throwable);
                return;
            }

            // 发送成功，打印日志
            log.info("[kafka-task-send] 发送任务成功 runId={} taskId={} sectionId={} partition={} offset={}",
                    task.getRunId(),
                    task.getTaskId(),
                    task.getSectionId(),
                    result.getRecordMetadata().partition(),  // 发送到哪个 partition
                    result.getRecordMetadata().offset());     // 在该 partition 的偏移量
        });
    }
}