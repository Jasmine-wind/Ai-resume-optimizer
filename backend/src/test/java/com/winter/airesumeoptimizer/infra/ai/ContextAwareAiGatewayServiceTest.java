package com.winter.airesumeoptimizer.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.winter.airesumeoptimizer.infra.ai.transport.BaseUrlPolicy;
import com.winter.airesumeoptimizer.module.ai.credential.service.AiCredentialService;
import com.winter.airesumeoptimizer.module.ai.credential.service.DecryptedCredentialMaterial;
import com.winter.airesumeoptimizer.module.ai.usage.service.AiUsageRecorder;
import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ContextAwareAiGatewayServiceTest {

    @Test
    void selectionForNewTaskRequiresActiveByokAndNeverCreatesSystemDefault() {
        AiCredentialService credentialService = mock(AiCredentialService.class);
        ContextAwareAiGatewayService gateway = gateway(
                credentialService,
                mock(AiProviderAdapter.class),
                mock(AiUsageRecorder.class),
                systemProperties());
        when(credentialService.resolveCurrentSelection(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gateway.selectionForNewTask(42L))
                .isInstanceOf(AiGatewayException.class)
                .extracting(exception -> ((AiGatewayException) exception).getFailureCode())
                .isEqualTo(AiFailureCode.AI_CONFIGURATION_REQUIRED);
    }

    @Test
    void shouldFailClosedWhenFrozenByokCredentialChangesAndNeverFallback() {
        AiCredentialService credentialService = mock(AiCredentialService.class);
        AiProviderAdapter adapter = mock(AiProviderAdapter.class);
        AiUsageRecorder usageRecorder = mock(AiUsageRecorder.class);
        ContextAwareAiGatewayService gateway = gateway(
                credentialService,
                adapter,
                usageRecorder,
                systemProperties());
        AiSelectionSnapshot selection = new AiSelectionSnapshot(
                AiSource.USER_BYOK,
                AiSelectionSnapshot.OPENAI_COMPATIBLE,
                99L,
                4L,
                "https://byok.example.com/v1",
                "byok-model",
                "{\"temperature\":0.2,\"maxOutputTokens\":100}",
                null);
        when(credentialService.resolveMaterial(42L, selection))
                .thenThrow(new AiGatewayException(AiFailureCode.CREDENTIAL_CHANGED, "已变更"));

        assertThatThrownBy(() -> gateway.complete(
                AiInvocationContext.task(42L, 77L, "TASK_OPERATION", selection),
                request()))
                .isInstanceOf(AiGatewayException.class)
                .extracting(exception -> ((AiGatewayException) exception).getFailureCode())
                .isEqualTo(AiFailureCode.CREDENTIAL_CHANGED);
        verify(adapter, never()).complete(any());
        verify(credentialService, never()).resolveCurrentSelection(42L);
        verify(usageRecorder, never()).recordFailure(any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void shouldUseActiveByokSelectionAndNeverFallBackToSystemDefault() {
        AiCredentialService credentialService = mock(AiCredentialService.class);
        AiProviderAdapter adapter = mock(AiProviderAdapter.class);
        AiUsageRecorder usageRecorder = mock(AiUsageRecorder.class);
        ContextAwareAiGatewayService gateway = gateway(
                credentialService,
                adapter,
                usageRecorder,
                systemProperties());
        AiSelectionSnapshot byokSelection = byokSelection();
        when(credentialService.resolveCurrentSelection(42L)).thenReturn(Optional.of(byokSelection));
        when(credentialService.resolveMaterial(42L, byokSelection))
                .thenReturn(new DecryptedCredentialMaterial(
                        "byok-decrypted-key",
                        byokSelection.baseUrl(),
                        byokSelection.model(),
                        byokSelection.configJson(),
                        byokSelection.credentialId(),
                        byokSelection.credentialRevision()));
        when(adapter.complete(any(AiProviderRequest.class)))
                .thenReturn(new AiProviderResponse("byok ok", 1L, 2L));

        AiCompletionResult result = gateway.complete(
                AiInvocationContext.user(42L, "TEST_OPERATION", null),
                request());

        assertThat(result.text()).isEqualTo("byok ok");
        assertThat(result.source()).isEqualTo(AiSource.USER_BYOK);
        assertThat(result.credentialId()).isEqualTo(99L);
        assertThat(result.credentialRevision()).isEqualTo(4L);
        ArgumentCaptor<AiProviderRequest> requests = ArgumentCaptor.forClass(AiProviderRequest.class);
        verify(adapter).complete(requests.capture());
        assertThat(requests.getValue().baseUrl()).isEqualTo("https://byok.example.com:443/v1");
        assertThat(requests.getValue().model()).isEqualTo("byok-model");
        assertThat(requests.getValue().apiKey()).isEqualTo("byok-decrypted-key");
    }

    @Test
    void shouldNotRetryNonTransientByokFailureAndNeverFallBackToSystemDefault() {
        AiCredentialService credentialService = mock(AiCredentialService.class);
        AiProviderAdapter adapter = mock(AiProviderAdapter.class);
        AiUsageRecorder usageRecorder = mock(AiUsageRecorder.class);
        ContextAwareAiGatewayService gateway = gateway(
                credentialService,
                adapter,
                usageRecorder,
                systemProperties());
        AiSelectionSnapshot byokSelection = byokSelection();
        when(credentialService.resolveMaterial(42L, byokSelection))
                .thenReturn(new DecryptedCredentialMaterial(
                        "byok-decrypted-key",
                        byokSelection.baseUrl(),
                        byokSelection.model(),
                        byokSelection.configJson(),
                        byokSelection.credentialId(),
                        byokSelection.credentialRevision()));
        when(adapter.complete(any(AiProviderRequest.class)))
                .thenThrow(new AiGatewayException(AiFailureCode.PROVIDER_UNAUTHORIZED, "AI Provider 认证失败"));

        assertThatThrownBy(() -> gateway.complete(
                AiInvocationContext.task(42L, 77L, "TASK_OPERATION", byokSelection),
                request()))
                .isInstanceOf(AiGatewayException.class)
                .extracting(exception -> ((AiGatewayException) exception).getFailureCode())
                .isEqualTo(AiFailureCode.PROVIDER_UNAUTHORIZED);
        ArgumentCaptor<AiProviderRequest> requests = ArgumentCaptor.forClass(AiProviderRequest.class);
        verify(adapter, org.mockito.Mockito.times(1)).complete(requests.capture());
        // No silent fallback: the system default endpoint must never be used.
        assertThat(requests.getAllValues()).extracting(AiProviderRequest::baseUrl)
                .containsOnly("https://byok.example.com:443/v1");
        assertThat(requests.getAllValues()).extracting(AiProviderRequest::apiKey)
                .containsOnly("byok-decrypted-key");
    }

    @Test
    void shouldRetryRateLimitedByokCallWithSameCredential() {
        AiCredentialService credentialService = mock(AiCredentialService.class);
        AiProviderAdapter adapter = mock(AiProviderAdapter.class);
        AiUsageRecorder usageRecorder = mock(AiUsageRecorder.class);
        ContextAwareAiGatewayService gateway = gateway(
                credentialService,
                adapter,
                usageRecorder,
                systemProperties());
        AiSelectionSnapshot byokSelection = byokSelection();
        when(credentialService.resolveMaterial(42L, byokSelection))
                .thenReturn(new DecryptedCredentialMaterial(
                        "byok-decrypted-key",
                        byokSelection.baseUrl(),
                        byokSelection.model(),
                        byokSelection.configJson(),
                        byokSelection.credentialId(),
                        byokSelection.credentialRevision()));
        when(adapter.complete(any(AiProviderRequest.class)))
                .thenThrow(new AiGatewayException(AiFailureCode.RATE_LIMITED, "过于频繁", true, 0L))
                .thenReturn(new AiProviderResponse("byok ok", null, null));

        AiCompletionResult result = gateway.complete(
                AiInvocationContext.task(42L, 77L, "TASK_OPERATION", byokSelection),
                request());

        assertThat(result.text()).isEqualTo("byok ok");
        assertThat(result.usage().attempts()).isEqualTo(2);
        ArgumentCaptor<AiProviderRequest> requests = ArgumentCaptor.forClass(AiProviderRequest.class);
        verify(adapter, org.mockito.Mockito.times(2)).complete(requests.capture());
        assertThat(requests.getAllValues()).extracting(AiProviderRequest::apiKey)
                .containsOnly("byok-decrypted-key");
    }

    @Test
    void shouldFailClosedWhenByokKeyCannotBeDecryptedWithoutSystemFallback() {
        AiCredentialService credentialService = mock(AiCredentialService.class);
        AiProviderAdapter adapter = mock(AiProviderAdapter.class);
        AiUsageRecorder usageRecorder = mock(AiUsageRecorder.class);
        ContextAwareAiGatewayService gateway = gateway(
                credentialService,
                adapter,
                usageRecorder,
                systemProperties());
        AiSelectionSnapshot byokSelection = byokSelection();
        when(credentialService.resolveMaterial(42L, byokSelection))
                .thenThrow(new AiGatewayException(AiFailureCode.CONFIGURATION_INVALID, "AI Credential 加密配置不可用"));

        assertThatThrownBy(() -> gateway.complete(
                AiInvocationContext.task(42L, 77L, "TASK_OPERATION", byokSelection),
                request()))
                .isInstanceOf(AiGatewayException.class)
                .extracting(exception -> ((AiGatewayException) exception).getFailureCode())
                .isEqualTo(AiFailureCode.CONFIGURATION_INVALID);
        verify(adapter, never()).complete(any());
        verify(credentialService, never()).resolveCurrentSelection(anyLong());
        verify(usageRecorder, never()).recordFailure(any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void credentialTestShouldFailClosedOnBoundedDnsTimeoutWithoutProviderDispatch() {
        AiCredentialService credentialService = mock(AiCredentialService.class);
        AiProviderAdapter adapter = mock(AiProviderAdapter.class);
        AiUsageRecorder usageRecorder = mock(AiUsageRecorder.class);
        ContextAwareAiGatewayService gateway = new ContextAwareAiGatewayService(
                credentialService,
                adapter,
                usageRecorder,
                systemProperties(),
                new ObjectMapper(),
                new BaseUrlPolicy(host -> {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    return new InetAddress[]{address("8.8.8.8")};
                }, java.time.Duration.ofMillis(100)));
        long startedAt = System.nanoTime();

        AiCredentialTestResult result = gateway.test(
                42L,
                "candidate-key",
                "https://provider.example.com/v1",
                "candidate-model",
                java.util.Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo(AiFailureCode.TIMEOUT);
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - startedAt))
                .isLessThan(java.time.Duration.ofSeconds(1));
        verify(adapter, never()).complete(any());
        verify(usageRecorder, never()).recordFailure(any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void usageLedgerFailureMustNotChangeCompletedAiResult() {
        AiCredentialService credentialService = mock(AiCredentialService.class);
        AiProviderAdapter adapter = mock(AiProviderAdapter.class);
        AiUsageRecorder usageRecorder = mock(AiUsageRecorder.class);
        org.mockito.Mockito.doThrow(new RuntimeException("ledger down"))
                .when(usageRecorder).recordSuccess(any(), any(), any());
        ContextAwareAiGatewayService gateway = gateway(
                credentialService,
                adapter,
                usageRecorder,
                systemProperties());
        AiSelectionSnapshot selection = byokSelection();
        when(credentialService.resolveCurrentSelection(42L)).thenReturn(Optional.of(selection));
        when(credentialService.resolveMaterial(42L, selection))
                .thenReturn(new DecryptedCredentialMaterial(
                        "byok-decrypted-key", selection.baseUrl(), selection.model(), selection.configJson(),
                        selection.credentialId(), selection.credentialRevision()));
        when(adapter.complete(any(AiProviderRequest.class)))
                .thenReturn(new AiProviderResponse("ok", 1L, 1L));

        AiCompletionResult result = gateway.complete(
                AiInvocationContext.user(42L, "TEST_OPERATION", null),
                request());

        assertThat(result.text()).isEqualTo("ok");
        assertThat(result.source()).isEqualTo(AiSource.USER_BYOK);
    }

    private AiGatewayRequest request() {
        return new AiGatewayRequest("TEST_POLICY", "policy", "data");
    }

    private AiSelectionSnapshot byokSelection() {
        return new AiSelectionSnapshot(
                AiSource.USER_BYOK,
                AiSelectionSnapshot.OPENAI_COMPATIBLE,
                99L,
                4L,
                "https://byok.example.com:443/v1",
                "byok-model",
                "{\"temperature\":0.2,\"maxOutputTokens\":100}",
                null);
    }

    private ContextAwareAiGatewayService gateway(
            AiCredentialService credentialService,
            AiProviderAdapter adapter,
            AiUsageRecorder usageRecorder,
            AiClientProperties properties) {
        return new ContextAwareAiGatewayService(
                credentialService,
                adapter,
                usageRecorder,
                properties,
                new ObjectMapper(),
                new BaseUrlPolicy(host -> new InetAddress[]{address("8.8.8.8")}));
    }

    private AiClientProperties systemProperties() {
        AiClientProperties properties = new AiClientProperties();
        properties.setApiKey("system-synthetic-key");
        properties.setBaseUrl("https://provider.example.com/v1");
        properties.setModel("system-model");
        properties.setTemperature(0.2d);
        properties.setMaxTokens(100);
        properties.setTimeoutSeconds(5);
        return properties;
    }

    private InetAddress address(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
