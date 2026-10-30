package com.courier.modules.finance.api;

import com.courier.modules.finance.domain.WalletTopupRequest;
import com.courier.modules.shipment.application.storage.FileStoragePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TopupRequestMapperTest {

    private final FileStoragePort storage = mock(FileStoragePort.class);
    private final TopupRequestMapper mapper = new TopupRequestMapper(storage);

    @Test
    @DisplayName("the proof image goes out as a short-lived signed link, so the staff screen can open a private bucket's file")
    void proofIsSigned() {
        WalletTopupRequest r = WalletTopupRequest.builder()
                .proofImageUrl("https://bucket.s3.ap-south-1.amazonaws.com/topup-proof/c/photo.png").build();
        when(storage.presignGet(eq(r.getProofImageUrl()), eq(Duration.ofHours(1)))).thenReturn("https://signed/link");

        assertThat(mapper.toResponse(r).proofImageUrl()).isEqualTo("https://signed/link");
    }

    @Test
    @DisplayName("a request with no proof stays empty")
    void noProof() {
        assertThat(mapper.toResponse(WalletTopupRequest.builder().build()).proofImageUrl()).isNull();
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.never()).presignGet(any(), any());
    }
}
