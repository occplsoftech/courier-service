package com.courier.modules.company.application;

import com.courier.modules.company.domain.CompanyRepository;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyLogoServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    /** The smallest valid PNG: signature + IHDR start is enough for the type check. */
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};

    private final CompanyRepository repository = mock(CompanyRepository.class);
    private final CompanyLogoService service = new CompanyLogoService(repository, mock(AuditService.class));

    @BeforeEach
    void setUp() {
        CompanyContext.setCompanyId(COMPANY);
        when(repository.setLogoDataUrl(eq(COMPANY), any())).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
    }

    @Test
    @DisplayName("an uploaded PNG is stored on the caller's own company as a data URL")
    void uploadStoresDataUrl() {
        service.upload(PNG);
        verify(repository).setLogoDataUrl(eq(COMPANY), startsWith("data:image/png;base64,"));
    }

    @Test
    @DisplayName("remove clears it")
    void removeClears() {
        service.remove();
        verify(repository).setLogoDataUrl(eq(COMPANY), isNull());
    }

    @Test
    @DisplayName("a file that is not PNG, JPEG or WebP is refused and nothing is stored")
    void notAnImageRefused() {
        assertThatThrownBy(() -> service.upload("<svg onload=alert(1)>".getBytes()))
                .isInstanceOf(BusinessRuleException.class);
        verify(repository, never()).setLogoDataUrl(any(), any());
    }
}
