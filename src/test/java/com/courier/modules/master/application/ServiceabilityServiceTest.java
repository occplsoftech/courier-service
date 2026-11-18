package com.courier.modules.master.application;

import com.courier.modules.master.domain.Area;
import com.courier.modules.master.domain.AreaRepository;
import com.courier.modules.master.domain.CityRepository;
import com.courier.modules.master.domain.GlobalMasters;
import com.courier.modules.master.domain.Pincode;
import com.courier.modules.master.domain.PincodeArea;
import com.courier.modules.master.domain.PincodeAreaRepository;
import com.courier.modules.master.domain.PincodeRepository;
import com.courier.modules.master.domain.UnserviceableRequest;
import com.courier.modules.master.domain.UnserviceableRequestRepository;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceabilityServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID P = GlobalMasters.PLATFORM_COMPANY_ID;

    private final PincodeRepository pincodes = mock(PincodeRepository.class);
    private final PincodeAreaRepository links = mock(PincodeAreaRepository.class);
    private final AreaRepository areas = mock(AreaRepository.class);
    private final CityRepository cities = mock(CityRepository.class);
    private final UnserviceableRequestRepository requests = mock(UnserviceableRequestRepository.class);
    private ServiceabilityService service;

    private final UUID pincodeId = UUID.randomUUID();
    private final UUID areaId = UUID.randomUUID();
    private Pincode pincode;
    private PincodeArea link;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new ServiceabilityService(pincodes, links, areas, cities, requests, mock(AuditService.class), tx);
        CompanyContext.setCompanyId(COMPANY);
        AuthenticatedUser u = new AuthenticatedUser(UUID.randomUUID(), COMPANY, "a@x", Set.of(Roles.COMPANY_ADMIN), "j");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.authorities()));

        pincode = new Pincode();
        pincode.setId(pincodeId);
        pincode.setCode("411001");
        pincode.setName("Pune GPO");
        pincode.setAreaId(areaId);
        pincode.setServiceable(true);
        link = new PincodeArea();
        link.setPincodeId(pincodeId);
        link.setAreaId(areaId);
        Area area = new Area();
        area.setName("Camp");
        when(pincodes.findByCodeWithinCompany("411001", P)).thenReturn(Optional.of(pincode));
        when(links.findByCompanyIdAndPincodeIdAndAreaId(P, pincodeId, areaId)).thenReturn(Optional.of(link));
        when(areas.findByIdWithinCompany(areaId, P)).thenReturn(Optional.of(area));
        when(requests.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void serviceableAreaIsNotCounted() {
        ServiceabilityService.Check c = service.check("411001", areaId, null, UnserviceableRequest.Source.STAFF);
        assertThat(c.serviceable()).isTrue();
        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void areaSwitchedOffIsRefusedAndCountedEachTime() {
        link.setServiceable(false);
        UUID branch = UUID.randomUUID();
        when(requests.findKey(COMPANY, "411001", areaId.toString())).thenReturn(Optional.empty());
        ServiceabilityService.Check c = service.check("411001", areaId, branch, UnserviceableRequest.Source.STAFF);
        assertThat(c.serviceable()).isFalse();
        assertThat(c.reason()).isEqualTo(UnserviceableRequest.Reason.AREA_OFF);
        assertThat(c.message()).isEqualTo("Service unavailable for Camp (411001).");

        ArgumentCaptor<UnserviceableRequest> saved = ArgumentCaptor.forClass(UnserviceableRequest.class);
        verify(requests).saveAndFlush(saved.capture());
        UnserviceableRequest row = saved.getValue();
        assertThat(row.getStaffCount()).isEqualTo(1);
        assertThat(row.getAreaName()).isEqualTo("Camp");
        assertThat(row.getLastBranchId()).isEqualTo(branch);

        when(requests.findKey(COMPANY, "411001", areaId.toString())).thenReturn(Optional.of(row));
        service.check("411001", areaId, branch, UnserviceableRequest.Source.STAFF);
        service.check("411001", null, null, UnserviceableRequest.Source.PORTAL); // whole pincode is fine for the portal
        assertThat(row.getStaffCount()).isEqualTo(2);
        verify(requests, times(2)).saveAndFlush(any());
    }

    @Test
    void pincodeOffAndNotOnFile() {
        pincode.setServiceable(false);
        assertThat(service.check("411001", areaId, null, UnserviceableRequest.Source.STAFF).reason())
                .isEqualTo(UnserviceableRequest.Reason.PINCODE_OFF);
        when(pincodes.findByCodeWithinCompany("999999", P)).thenReturn(Optional.empty());
        ServiceabilityService.Check c = service.check("999999", null, null, UnserviceableRequest.Source.PORTAL);
        assertThat(c.reason()).isEqualTo(UnserviceableRequest.Reason.NOT_ON_FILE);
        assertThatThrownBy(() -> service.check("12", null, null, UnserviceableRequest.Source.STAFF)).hasMessageContaining("6-digit");
    }

    @Test
    void turnOnSwitchesThePincodeAndAreaOn() {
        pincode.setServiceable(false);
        link.setServiceable(false);
        UnserviceableRequest r = new UnserviceableRequest();
        r.setId(UUID.randomUUID());
        r.setPincodeCode("411001");
        r.setAreaKey(areaId.toString());
        r.setAreaId(areaId);
        r.setReason(UnserviceableRequest.Reason.AREA_OFF);
        r.setStaffCount(3);
        when(requests.findOwned(r.getId(), COMPANY)).thenReturn(Optional.of(r));

        ServiceabilityService.ReportRow row = service.turnOn(r.getId());

        assertThat(pincode.isServiceable()).isTrue();
        assertThat(link.isServiceable()).isTrue();
        assertThat(r.getTurnedOnAt()).isNotNull();
        assertThat(row.serviceableNow()).isTrue();
        assertThat(row.total()).isEqualTo(3);
        verify(pincodes).saveAndFlush(eq(pincode));
    }
}
