package com.winter.airesumeoptimizer.module.ai.credential.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.winter.airesumeoptimizer.infra.ai.AiFailureCode;
import com.winter.airesumeoptimizer.infra.ai.AiGatewayException;
import com.winter.airesumeoptimizer.infra.ai.AiSelectionSnapshot;
import com.winter.airesumeoptimizer.infra.ai.AiSource;
import com.winter.airesumeoptimizer.infra.ai.transport.BaseUrlPolicy;
import com.winter.airesumeoptimizer.module.ai.credential.dto.AiCredentialUpsertRequestDTO;
import com.winter.airesumeoptimizer.module.ai.credential.entity.AiProviderCredential;
import com.winter.airesumeoptimizer.module.ai.credential.mapper.AiProviderCredentialMapper;
import com.winter.airesumeoptimizer.module.ai.credential.service.CredentialCipher;
import com.winter.airesumeoptimizer.module.ai.credential.service.DecryptedCredentialMaterial;
import com.winter.airesumeoptimizer.module.ai.credential.vo.AiCredentialVO;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiCredentialServiceImplTest {

    private static final Long USER_ID = 42L;
    private static final String NORMALIZED_BASE_URL = "https://byok.example.com:443/v1";
    private static final String NORMALIZED_CONFIG = "{\"temperature\":0.2,\"maxOutputTokens\":16000}";

    private final AiProviderCredentialMapper credentialMapper = mock(AiProviderCredentialMapper.class);
    private final CredentialCipher credentialCipher = mock(CredentialCipher.class);
    private final AiCredentialServiceImpl service = new AiCredentialServiceImpl(
            credentialMapper,
            credentialCipher,
            new ObjectMapper(),
            new BaseUrlPolicy(host -> new InetAddress[]{address("93.184.216.34")}));

    @BeforeEach
    void enableCredentialFeature() {
        when(credentialCipher.isEnabled()).thenReturn(true);
    }

    @Test
    void getShouldReturnUnconfiguredStateWhenNoCredentialExists() {
        when(credentialMapper.selectOne(any())).thenReturn(null);

        AiCredentialVO vo = service.get(USER_ID);

        assertThat(vo.isConfigured()).isFalse();
        assertThat(vo.isApiKeyConfigured()).isFalse();
        assertThat(vo.getStatus()).isEqualTo("DISABLED");
        assertThat(vo.getMaskedApiKey()).isEmpty();
        assertThat(vo.getCredentialRevision()).isNull();
    }

    @Test
    void saveShouldCreateDisabledCredentialWithFirstRevisionAndMaskedVo() {
        when(credentialMapper.selectOne(any())).thenReturn(null);
        when(credentialMapper.insert(any(AiProviderCredential.class))).thenAnswer(invocation -> {
            AiProviderCredential created = invocation.getArgument(0);
            created.setId(77L);
            return 1;
        });
        when(credentialCipher.encrypt("user-secret-key", USER_ID, 77L))
                .thenReturn(new CredentialCipher.EncryptedValue("enc:v1:v1:nonce:cipher", "v1"));

        AiCredentialVO vo = service.saveOrReplace(USER_ID, request("user-secret-key"));

        assertThat(vo.getStatus()).isEqualTo("DISABLED");
        assertThat(vo.getCredentialRevision()).isEqualTo(1L);
        assertThat(vo.isConfigured()).isTrue();
        assertThat(vo.isApiKeyConfigured()).isTrue();
        assertThat(vo.getMaskedApiKey()).doesNotContain("user-secret-key");
        assertThat(vo.getBaseUrl()).isEqualTo(NORMALIZED_BASE_URL);

        ArgumentCaptor<AiProviderCredential> persisted = ArgumentCaptor.forClass(AiProviderCredential.class);
        verify(credentialMapper).updateById(persisted.capture());
        AiProviderCredential entity = persisted.getValue();
        assertThat(entity.getEncryptedApiKey()).isEqualTo("enc:v1:v1:nonce:cipher");
        assertThat(entity.getEncryptedApiKey()).doesNotContain("user-secret-key");
        assertThat(entity.getEncryptionKeyVersion()).isEqualTo("v1");
        assertThat(entity.getStatus()).isEqualTo("DISABLED");
    }

    @Test
    void replaceShouldIncrementRevisionAndResetStatusToDisabled() {
        AiProviderCredential existing = activeCredential(3L);
        when(credentialMapper.selectOne(any())).thenReturn(existing);
        when(credentialMapper.update(any(), any())).thenReturn(1);
        when(credentialCipher.encrypt("replacement-key", USER_ID, 77L))
                .thenReturn(new CredentialCipher.EncryptedValue("enc:v1:v1:n2:c2", "v1"));

        AiCredentialVO vo = service.saveOrReplace(USER_ID, request("replacement-key"));

        assertThat(vo.getCredentialRevision()).isEqualTo(4L);
        assertThat(vo.getStatus()).isEqualTo("DISABLED");
        verify(credentialMapper, never()).insert(any(AiProviderCredential.class));
    }

    @Test
    void replaceShouldFailClosedWhenConcurrentRevisionWins() {
        when(credentialMapper.selectOne(any())).thenReturn(activeCredential(3L));
        when(credentialMapper.update(any(), any())).thenReturn(0);
        when(credentialCipher.encrypt(any(), anyLong(), anyLong()))
                .thenReturn(new CredentialCipher.EncryptedValue("enc:v1:v1:n:c", "v1"));

        assertThatThrownBy(() -> service.saveOrReplace(USER_ID, request("replacement-key")))
                .isInstanceOf(AiGatewayException.class)
                .extracting(exception -> ((AiGatewayException) exception).getFailureCode())
                .isEqualTo(AiFailureCode.CREDENTIAL_CHANGED);
    }

    @Test
    void saveShouldValidateStructureWithoutResolvingDns() {
        AiCredentialServiceImpl serviceWithUnavailableDns = new AiCredentialServiceImpl(
                credentialMapper,
                credentialCipher,
                new ObjectMapper(),
                new BaseUrlPolicy(host -> {
                    throw new AssertionError("save must not resolve DNS");
                }));
        when(credentialMapper.selectOne(any())).thenReturn(null);
        when(credentialMapper.insert(any(AiProviderCredential.class))).thenAnswer(invocation -> {
            AiProviderCredential created = invocation.getArgument(0);
            created.setId(77L);
            return 1;
        });
        when(credentialCipher.encrypt(any(), anyLong(), anyLong()))
                .thenReturn(new CredentialCipher.EncryptedValue("enc:v1:v1:nonce:cipher", "v1"));

        AiCredentialVO saved = serviceWithUnavailableDns.saveOrReplace(USER_ID, request("valid-key"));

        assertThat(saved.isConfigured()).isTrue();
        verify(credentialMapper).insert(any(AiProviderCredential.class));
    }

    @Test
    void saveShouldRejectUnsafeBaseUrlWithStableFailureCode() {
        AiCredentialUpsertRequestDTO unsafe = request("valid-key");
        unsafe.setBaseUrl("http://byok.example.com/v1");

        assertThatThrownBy(() -> service.saveOrReplace(USER_ID, unsafe))
                .isInstanceOf(AiGatewayException.class)
                .extracting(exception -> ((AiGatewayException) exception).getFailureCode())
                .isEqualTo(AiFailureCode.UNSAFE_BASE_URL);
        verify(credentialMapper, never()).insert(any(AiProviderCredential.class));
    }

    @Test
    void saveShouldRejectBlankApiKeyAndModel() {
        AiCredentialUpsertRequestDTO blankKey = request("  ");
        assertThatThrownBy(() -> service.saveOrReplace(USER_ID, blankKey))
                .isInstanceOf(AiGatewayException.class)
                .extracting(exception -> ((AiGatewayException) exception).getFailureCode())
                .isEqualTo(AiFailureCode.INVALID_CREDENTIAL);

        AiCredentialUpsertRequestDTO blankModel = request("valid-key");
        blankModel.setModel(" ");
        assertThatThrownBy(() -> service.saveOrReplace(USER_ID, blankModel))
                .isInstanceOf(AiGatewayException.class)
                .extracting(exception -> ((AiGatewayException) exception).getFailureCode())
                .isEqualTo(AiFailureCode.CONFIGURATION_INVALID);
    }

    @Test
    void enableAndDisableShouldToggleStatusWithoutChangingRevision() {
        AiProviderCredential credential = activeCredential(2L);
        when(credentialMapper.selectOne(any())).thenReturn(credential);
        when(credentialMapper.update(any(), any())).thenReturn(1);

        AiCredentialVO disabled = service.disable(USER_ID);
        assertThat(disabled.getStatus()).isEqualTo("DISABLED");
        assertThat(disabled.getCredentialRevision()).isEqualTo(2L);

        credential.setStatus("DISABLED");
        AiCredentialVO enabled = service.enable(USER_ID);
        assertThat(enabled.getStatus()).isEqualTo("ACTIVE");
        assertThat(enabled.getCredentialRevision()).isEqualTo(2L);
    }

    @Test
    void enableShouldRejectLostConcurrentReplaceWithoutOverwritingCredentialRow() {
        AiProviderCredential credential = disabledCredential(2L);
        when(credentialMapper.selectOne(any())).thenReturn(credential);
        when(credentialMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.enable(USER_ID))
                .isInstanceOf(AiGatewayException.class)
                .extracting(exception -> ((AiGatewayException) exception).getFailureCode())
                .isEqualTo(AiFailureCode.CREDENTIAL_CHANGED);
        verify(credentialMapper, never()).updateById(any(AiProviderCredential.class));
    }

    @Test
    void enableShouldFailClosedWhenNoCredentialConfigured() {
        when(credentialMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.enable(USER_ID))
                .isInstanceOf(AiGatewayException.class)
                .extracting(exception -> ((AiGatewayException) exception).getFailureCode())
                .isEqualTo(AiFailureCode.INVALID_CREDENTIAL);
    }

    @Test
    void deleteShouldRemoveOwnedCredential() {
        AiProviderCredential credential = activeCredential(1L);
        when(credentialMapper.selectOne(any())).thenReturn(credential);

        service.delete(USER_ID);

        verify(credentialMapper).deleteById(77L);
    }

    @Test
    void disabledByokFeatureShouldIgnoreActiveCredentialForNewSelections() {
        when(credentialCipher.isEnabled()).thenReturn(false);
        when(credentialMapper.selectOne(any())).thenReturn(activeCredential(5L));

        assertThat(service.resolveCurrentSelection(USER_ID)).isEmpty();
        verify(credentialMapper, never()).selectOne(any());
    }

    @Test
    void enableShouldFailClosedWhenStoredEnvelopeCannotBeDecrypted() {
        AiProviderCredential credential = disabledCredential(2L);
        when(credentialMapper.selectOne(any())).thenReturn(credential);
        when(credentialCipher.decrypt(credential.getEncryptedApiKey(), USER_ID, 77L))
                .thenThrow(new AiGatewayException(AiFailureCode.CONFIGURATION_INVALID, "unavailable"));

        assertThatThrownBy(() -> service.enable(USER_ID))
                .isInstanceOf(AiGatewayException.class)
                .extracting(exception -> ((AiGatewayException) exception).getFailureCode())
                .isEqualTo(AiFailureCode.CONFIGURATION_INVALID);
        verify(credentialMapper, never()).update(any(), any());
    }

    @Test
    void resolveMaterialShouldRotateEnvelopeWithoutChangingCredentialRevision() {
        AiProviderCredential credential = activeCredential(5L);
        when(credentialMapper.selectOne(any())).thenReturn(credential);
        when(credentialCipher.decrypt(credential.getEncryptedApiKey(), USER_ID, 77L))
                .thenReturn("decrypted-byok-key");
        when(credentialCipher.needsRotation("v1")).thenReturn(true);
        when(credentialCipher.rotate(credential.getEncryptedApiKey(), USER_ID, 77L))
                .thenReturn(new CredentialCipher.EncryptedValue("enc:v1:v2:new:cipher", "v2"));
        when(credentialMapper.update(any(), any())).thenReturn(1);

        DecryptedCredentialMaterial material = service.resolveMaterial(USER_ID, snapshot(5L));

        assertThat(material.apiKey()).isEqualTo("decrypted-byok-key");
        assertThat(material.credentialRevision()).isEqualTo(5L);
        assertThat(credential.getCredentialRevision()).isEqualTo(5L);
        assertThat(credential.getEncryptionKeyVersion()).isEqualTo("v2");
        verify(credentialMapper).update(any(), any());
    }

    @Test
    void resolveCurrentSelectionShouldBeEmptyUnlessCredentialIsActive() {
        when(credentialMapper.selectOne(any())).thenReturn(null);
        assertThat(service.resolveCurrentSelection(USER_ID)).isEmpty();

        when(credentialMapper.selectOne(any())).thenReturn(disabledCredential(5L));
        assertThat(service.resolveCurrentSelection(USER_ID)).isEmpty();

        when(credentialMapper.selectOne(any())).thenReturn(activeCredential(5L));
        Optional<AiSelectionSnapshot> selection = service.resolveCurrentSelection(USER_ID);
        assertThat(selection).isPresent();
        assertThat(selection.get().source()).isEqualTo(AiSource.USER_BYOK);
        assertThat(selection.get().credentialId()).isEqualTo(77L);
        assertThat(selection.get().credentialRevision()).isEqualTo(5L);
        assertThat(selection.get().baseUrl()).isEqualTo(NORMALIZED_BASE_URL);
        assertThat(selection.get().model()).isEqualTo("byok-model");
    }

    @Test
    void resolveMaterialShouldDecryptOnlyMatchingActiveCredential() {
        AiProviderCredential credential = activeCredential(5L);
        when(credentialMapper.selectOne(any())).thenReturn(credential);
        when(credentialCipher.decrypt(credential.getEncryptedApiKey(), USER_ID, 77L))
                .thenReturn("decrypted-byok-key");

        DecryptedCredentialMaterial material = service.resolveMaterial(USER_ID, snapshot(5L));

        assertThat(material.apiKey()).isEqualTo("decrypted-byok-key");
        assertThat(material.credentialId()).isEqualTo(77L);
        assertThat(material.credentialRevision()).isEqualTo(5L);
    }

    @Test
    void resolveMaterialShouldFailClosedOnRevisionStatusOrOwnershipMismatch() {
        AiProviderCredential credential = activeCredential(6L);
        when(credentialMapper.selectOne(any())).thenReturn(credential);

        assertThatThrownBy(() -> service.resolveMaterial(USER_ID, snapshot(5L)))
                .isInstanceOf(AiGatewayException.class)
                .extracting(exception -> ((AiGatewayException) exception).getFailureCode())
                .isEqualTo(AiFailureCode.CREDENTIAL_CHANGED);

        credential.setStatus("DISABLED");
        assertThatThrownBy(() -> service.resolveMaterial(USER_ID, snapshot(6L)))
                .isInstanceOf(AiGatewayException.class)
                .extracting(exception -> ((AiGatewayException) exception).getFailureCode())
                .isEqualTo(AiFailureCode.CREDENTIAL_CHANGED);

        when(credentialMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.resolveMaterial(USER_ID + 1, snapshot(6L)))
                .isInstanceOf(AiGatewayException.class)
                .extracting(exception -> ((AiGatewayException) exception).getFailureCode())
                .isEqualTo(AiFailureCode.CREDENTIAL_CHANGED);
        verify(credentialCipher, never()).decrypt(any(), any(), any());
    }

    private AiCredentialUpsertRequestDTO request(String apiKey) {
        AiCredentialUpsertRequestDTO request = new AiCredentialUpsertRequestDTO();
        request.setBaseUrl("https://byok.example.com/v1");
        request.setApiKey(apiKey);
        request.setModel("byok-model");
        return request;
    }

    private AiSelectionSnapshot snapshot(Long revision) {
        return new AiSelectionSnapshot(
                AiSource.USER_BYOK,
                AiSelectionSnapshot.OPENAI_COMPATIBLE,
                77L,
                revision,
                NORMALIZED_BASE_URL,
                "byok-model",
                NORMALIZED_CONFIG,
                null);
    }

    private AiProviderCredential activeCredential(Long revision) {
        return credential(revision, "ACTIVE");
    }

    private AiProviderCredential disabledCredential(Long revision) {
        return credential(revision, "DISABLED");
    }

    private AiProviderCredential credential(Long revision, String status) {
        AiProviderCredential credential = new AiProviderCredential();
        credential.setId(77L);
        credential.setUserId(USER_ID);
        credential.setProviderType(AiSelectionSnapshot.OPENAI_COMPATIBLE);
        credential.setBaseUrl(NORMALIZED_BASE_URL);
        credential.setEncryptedApiKey("enc:v1:v1:nonce:cipher");
        credential.setEncryptionKeyVersion("v1");
        credential.setModel("byok-model");
        credential.setConfigJson(NORMALIZED_CONFIG);
        credential.setStatus(status);
        credential.setCredentialRevision(revision);
        credential.setCreatedAt(LocalDateTime.now());
        credential.setUpdatedAt(LocalDateTime.now());
        return credential;
    }

    private InetAddress address(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
