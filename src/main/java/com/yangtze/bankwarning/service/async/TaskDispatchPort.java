package com.yangtze.bankwarning.service.async;

import com.yangtze.bankwarning.dto.kafka.ModelTask;

public interface TaskDispatchPort {
    void send(ModelTask task);
}
