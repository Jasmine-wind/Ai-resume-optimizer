package com.winter.airesumeoptimizer.module.ai.usage.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.winter.airesumeoptimizer.infra.ai.AiFailureCode;
import com.winter.airesumeoptimizer.infra.ai.AiInvocationContext;
import com.winter.airesumeoptimizer.infra.ai.AiSelectionSnapshot;
import com.winter.airesumeoptimizer.infra.ai.AiSource;
import com.winter.airesumeoptimizer.infra.ai.AiUsageMetrics;
import com.winter.airesumeoptimizer.module.ai.usage.entity.AiUsageRecord;
import com.winter.airesumeoptimizer.module.ai.usage.service.AiUsageRecordPersistence;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiUsageRecorderImplTest {

    private final AiUsageRecordPersistence usageRecordPersistence = mock(AiUsageRecordPersistence.class);
    private final AiUsageRecorderImpl recorder = new AiUsageRecorderImpl(usageRecordPersistence);

    @Test
    void recordSuccessShouldOnlyPersistLedgerFieldsWithoutContentOrSecrets() {
        AiSelectionSnapshot selection = new AiSelectionSnapshot(
                AiSource.USER_BYOK,
                AiSelectionSnapshot.OPENAI_COMPATIBLE,
                77L,
                4L,
                "https://byok.example.com/v1",
                "byok-model",
                "{\"temperature\":0.2,\"maxOutputTokens\":100}",
                null);
        AiInvocationContext context = AiInvocationContext.task(42L, 55L, "BULLET_REWRITE", selection);

        recorder.recordSuccess(context, selection, new AiUsageMetrics(11L, 22L, 123L, 2, 5));

        AiUsageRecord record = captureInsertedRecord();
        assertThat(record.getUserId()).isEqualTo(42L);
        assertThat(record.getOptimizationTaskId()).isEqualTo(55L);
        assertThat(record.getOperation()).isEqualTo("BULLET_REWRITE");
        assertThat(record.getSource()).isEqualTo("USER_BYOK");
        assertThat(record.getProvider()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(record.getModel()).isEqualTo("byok-model");
        assertThat(record.getCredentialRevision()).isEqualTo(4L);
        assertThat(record.getOutcome()).isEqualTo("SUCCESS");
        assertThat(record.getFailureCode()).isNull();
        assertThat(record.getLatencyMs()).isEqualTo(123L);
        assertThat(record.getPromptTokens()).isEqualTo(11);
        assertThat(record.getCompletionTokens()).isEqualTo(22);
        assertThat(record.getTotalTokens()).isEqualTo(33);
        assertThat(record.getGatewayAttemptCount()).isEqualTo(2);
        assertThat(record.getProviderDispatchCount()).isEqualTo(5);
    }

    @Test
    void recordFailureShouldPersistStableFailureCodeWithoutProviderDetails() {
        AiSelectionSnapshot selection = new AiSelectionSnapshot(
                AiSource.SYSTEM_DEFAULT,
                AiSelectionSnapshot.OPENAI_COMPATIBLE,
                null,
                null,
                "",
                "system-model",
                "{}",
                null);
        AiInvocationContext context = AiInvocationContext.user(42L, "EVIDENCE_MATCH", selection);

        recorder.recordFailure(context, selection, AiFailureCode.PROVIDER_UNAUTHORIZED, 456L, 1);

        AiUsageRecord record = captureInsertedRecord();
        assertThat(record.getOutcome()).isEqualTo("FAILURE");
        assertThat(record.getFailureCode()).isEqualTo("PROVIDER_UNAUTHORIZED");
        assertThat(record.getLatencyMs()).isEqualTo(456L);
        assertThat(record.getPromptTokens()).isNull();
        assertThat(record.getCompletionTokens()).isNull();
        assertThat(record.getTotalTokens()).isNull();
        assertThat(record.getSource()).isEqualTo("SYSTEM_DEFAULT");
        assertThat(record.getCredentialRevision()).isNull();
        assertThat(record.getGatewayAttemptCount()).isEqualTo(1);
        assertThat(record.getProviderDispatchCount()).isEqualTo(1);
    }

    @Test
    void recordShouldClampNegativeLatencyAndOutOfBoundsTokens() {
        AiSelectionSnapshot selection = new AiSelectionSnapshot(
                AiSource.SYSTEM_DEFAULT,
                AiSelectionSnapshot.OPENAI_COMPATIBLE,
                null,
                null,
                "",
                "system-model",
                "{}",
                null);
        AiInvocationContext context = AiInvocationContext.user(42L, "JOB_PARSE", selection);

        recorder.recordSuccess(
                context,
                selection,
                new AiUsageMetrics((long) Integer.MAX_VALUE + 1L, -5L, -10L, 1));

        AiUsageRecord record = captureInsertedRecord();
        assertThat(record.getLatencyMs()).isZero();
        assertThat(record.getPromptTokens()).isNull();
        assertThat(record.getCompletionTokens()).isNull();
        assertThat(record.getTotalTokens()).isNull();
    }

    private AiUsageRecord captureInsertedRecord() {
        ArgumentCaptor<AiUsageRecord> captor = ArgumentCaptor.forClass(AiUsageRecord.class);
        verify(usageRecordPersistence).persist(captor.capture());
        return captor.getValue();
    }
}
