package com.winter.airesumeoptimizer.module.ai.usage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("ai_usage_records")
public class AiUsageRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long optimizationTaskId;
    private String operation;
    private String source;
    private String provider;
    private String model;
    private Long credentialRevision;
    private String outcome;
    private String failureCode;
    private Long latencyMs;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer gatewayAttemptCount;
    private Integer providerDispatchCount;
    private LocalDateTime createdAt;
}
