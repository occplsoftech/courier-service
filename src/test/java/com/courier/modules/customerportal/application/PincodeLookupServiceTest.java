package com.courier.modules.customerportal.application;

import com.courier.modules.master.application.PincodeGeography;
import com.courier.modules.master.application.PincodeService;
import com.courier.modules.master.domain.Pincode;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PincodeLookupServiceTest {

    @Mock private CurrentCustomerResolver resolver;
    @Mock private PincodeService pincodes;
    @InjectMocks private PincodeLookupService service;

    @ParameterizedTest
    @ValueSource(strings = {"", "41101", "4110160", "011016", "41A016"})
    @DisplayName("refuses malformed pincodes before any lookup")
    void malformed(String code) {
        assertThatThrownBy(() -> service.lookup(code)).isInstanceOf(BusinessRuleException.class);
        verifyNoInteractions(pincodes);
    }

    @Test
    @DisplayName("an unknown pincode is reported as not served, not as an error")
    void unknown() {
        when(pincodes.findByCode("411016")).thenThrow(new ResourceNotFoundException("Pincode", "411016"));

        PincodeLookupService.PincodeInfo info = service.lookup("411016");

        assertThat(info.known()).isFalse();
        assertThat(info.serviceable()).isFalse();
    }

    @Test
    @DisplayName("a known pincode carries its place names and the network's flags")
    void known() {
        Pincode p = mock(Pincode.class);
        when(p.isServiceable()).thenReturn(true);
        when(p.isCodAvailable()).thenReturn(false);
        when(pincodes.findByCode("411016")).thenReturn(p);
        when(pincodes.geography("411016"))
                .thenReturn(Optional.of(new PincodeGeography("411016", "Model Colony", "Pune", "Pune", "Maharashtra")));

        PincodeLookupService.PincodeInfo info = service.lookup(" 411016 ");

        assertThat(info.known()).isTrue();
        assertThat(info.city()).isEqualTo("Pune");
        assertThat(info.serviceable()).isTrue();
        assertThat(info.codAvailable()).isFalse();
    }
}
