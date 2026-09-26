package com.payrelay.api.bank;

import com.payrelay.api.bank.BankConfigService.UpdateRequest;
import com.payrelay.api.service.ApiException;
import com.payrelay.api.worker.PaymentWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "payrelay.worker.poll-interval-ms=3600000")
@ActiveProfiles("dev")
class BankConfigServiceTest {

    @MockitoBean
    PaymentWorker worker;

    @Autowired BankConfigService banks;

    @Test
    void everySeededBankIsValidAndPreviewsCleanly() {
        for (String code : banks.codes()) {
            var preview = banks.preview(code, banks.get(code).spec(), null);
            assertThat(preview.errors()).as(code).isEmpty();
            assertThat(preview.body()).as(code).isNotNull();
            assertThat(preview.path()).as(code).startsWith("/banks/");
        }
    }

    @Test
    void previewShowsTheBankSpecificShape() {
        var preview = banks.preview("ZENITH", banks.get("ZENITH").spec(), null);
        @SuppressWarnings("unchecked")
        var payment = (Map<String, Object>) ((Map<String, Object>) preview.body()).get("payment");
        assertThat(payment).containsEntry("amountInPaise", 12500050L);
        assertThat(preview.headers().get("Authorization")).startsWith("Bearer {token");
    }

    @Test
    void savingCreatesANewVersionAndKeepsHistory() {
        var before = banks.get("ORBIT");
        try {
            var after = banks.update("ORBIT", new UpdateRequest("Orbit Bank (renamed)", true, before.version(), before.spec()));
            assertThat(after.version()).isEqualTo(before.version() + 1);
            assertThat(banks.history("ORBIT").getFirst().version()).isEqualTo(after.version());
            assertThat(banks.history("ORBIT")).extracting(BankConfigService.HistoryEntry::version).contains(before.version());
        } finally {
            var current = banks.get("ORBIT");
            banks.update("ORBIT", new UpdateRequest(before.displayName(), before.enabled(), current.version(), before.spec()));
        }
    }

    @Test
    void staleVersionIsRefusedInsteadOfOverwriting() {
        var current = banks.get("NOVA");
        assertThatThrownBy(() -> banks.update("NOVA", new UpdateRequest("x", true, current.version() - 1, current.spec())))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void invalidSpecIsRefusedWithEveryProblemListed() {
        var current = banks.get("NOVA");
        var spec = current.spec();
        var broken = new BankSpec(spec.endpoint(), spec.auth(),
                new BankSpec.Request(List.of(), List.of(new BankSpec.FieldRule("amount", "wrongField", null, null, null, null))),
                spec.response(), spec.lookups());
        assertThatThrownBy(() -> banks.update("NOVA", new UpdateRequest("Nova Bank", true, current.version(), broken)))
                .isInstanceOf(BankConfigService.SpecValidationException.class)
                .satisfies(e -> assertThat(((BankConfigService.SpecValidationException) e).errors())
                        .anyMatch(m -> m.contains("wrongField"))
                        .anyMatch(m -> m.contains("transferKey")));
        assertThat(banks.get("NOVA").version()).isEqualTo(current.version()); // nothing saved
    }
}
