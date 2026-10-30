package com.courier.modules.shipment.application.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SignedLinksTest {

    private final FileStoragePort storage = mock(FileStoragePort.class);
    private final SignedLinks links = new SignedLinks(storage);

    @Test
    @DisplayName("a stored address becomes a one-hour signed link")
    void signs() {
        when(storage.presignGet("https://b.s3.ap-south-1.amazonaws.com/pod/x.png", Duration.ofHours(1))).thenReturn("https://signed");

        assertThat(links.of("https://b.s3.ap-south-1.amazonaws.com/pod/x.png")).isEqualTo("https://signed");
    }

    @Test
    @DisplayName("nothing or a blank value is passed on untouched, and storage is not asked")
    void blank() {
        assertThat(links.of(null)).isNull();
        assertThat(links.of(" ")).isEqualTo(" ");
        verify(storage, never()).presignGet(any(), any());
    }
}
