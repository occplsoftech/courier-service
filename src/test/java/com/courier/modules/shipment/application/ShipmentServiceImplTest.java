package com.courier.modules.shipment.application;

import com.courier.modules.districtfreight.application.FreightCalculationResult;
import com.courier.modules.districtfreight.application.FreightCalculationService;
import com.courier.modules.finance.application.WalletService;
import com.courier.modules.finance.domain.Wallet;
import com.courier.modules.master.application.PackageTypeService;
import com.courier.modules.master.application.PaymentModeService;
import com.courier.modules.master.application.RouteService;
import com.courier.modules.master.application.ServiceTypeService;
import com.courier.modules.master.domain.PackageType;
import com.courier.modules.master.domain.PaymentMode;
import com.courier.modules.master.domain.Route;
import com.courier.modules.master.domain.ServiceType;
import com.courier.modules.pricing.application.PricingEngine;
import com.courier.modules.pricing.application.PricingProperties;
import com.courier.modules.pricing.application.PricingResult;
import com.courier.modules.rate.application.RateService;
import com.courier.modules.rate.domain.Rate;
import com.courier.modules.shipment.application.command.CreateShipmentCommand;
import com.courier.modules.shipment.application.command.ShipmentItemCommand;
import com.courier.modules.shipment.application.command.UpdateShipmentCommand;
import com.courier.modules.company.domain.Branch;
import com.courier.modules.shipment.application.event.ShipmentEvent;
import com.courier.modules.shipment.application.storage.FileStoragePort;
import com.courier.modules.shipment.domain.BranchShipmentSequenceRepository;
import com.courier.modules.shipment.domain.ReleasedShipmentNumberRepository;
import com.courier.modules.shipment.domain.CompanyShipmentSequenceRepository;
import com.courier.modules.shipment.domain.DeliveryAssignmentRepository;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentChargeRepository;
import com.courier.modules.shipment.domain.ShipmentAssetRepository;
import com.courier.modules.shipment.domain.ShipmentDocumentRepository;
import com.courier.modules.shipment.domain.ShipmentItemRepository;
import com.courier.modules.shipment.domain.ShipmentRepository;
import com.courier.modules.shipment.domain.ShipmentStatus;
import com.courier.modules.shipment.domain.ShipmentStatusHistoryRepository;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Shipment Booking rules, with every collaborator mocked — mirrors
 * {@code rate.application.RateServiceImplTest} / {@code customer.application
 * .CustomerServiceImplTest}'s shape. This module orchestrates other modules rather than
 * re-deciding their own rules, so most fixtures here are stand-ins the Pricing Engine (or
 * Master) is trusted to have already validated in its own test suite. Sender/receiver are
 * plain text (V18) — no Customer module mock is wired in here any more. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShipmentServiceImplTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CALLER = UUID.randomUUID();
    private static final UUID BOOKING_BRANCH = UUID.randomUUID();
    private static final UUID DELIVERY_BRANCH = UUID.randomUUID();
    private static final UUID CROSSING_BRANCH = UUID.randomUUID();
    private static final UUID SERVICE_TYPE = UUID.randomUUID();
    private static final UUID PACKAGE_TYPE = UUID.randomUUID();
    private static final UUID PAYMENT_MODE = UUID.randomUUID();
    private static final String PICKUP_PINCODE = "411001";
    private static final String DELIVERY_PINCODE = "400008";

    @Mock private ShipmentRepository shipmentRepository;
    @Mock private ShipmentItemRepository itemRepository;
    @Mock private ShipmentChargeRepository chargeRepository;
    @Mock private ShipmentStatusHistoryRepository historyRepository;
    @Mock private ShipmentDocumentRepository documentRepository;
    @Mock private DeliveryAssignmentRepository deliveryAssignmentRepository;
    @Mock private BranchShipmentSequenceRepository branchShipmentSequenceRepository;
    @Mock private ReleasedShipmentNumberRepository releasedShipmentNumberRepository;
    @Mock private CompanyShipmentSequenceRepository companyShipmentSequenceRepository;
    @Mock private com.courier.modules.shipment.domain.CompanyDrsSequenceRepository companyDrsSequenceRepository;
    @Mock private com.courier.modules.company.application.UserService userService;
    @Mock private com.courier.modules.company.application.BranchService branchService;
    @Mock private com.courier.modules.shipment.domain.BranchLookupPort branchLookup;
    @Mock private com.courier.modules.customer.application.CustomerService customerService;
    @Mock private com.courier.modules.crossing.application.CrossingService crossingService;
    @Mock private com.courier.modules.support.application.TicketService ticketService;
    @Mock private com.courier.modules.support.application.TicketCategoryService ticketCategoryService;
    @Mock private com.courier.modules.ewaybill.application.EwayBillService ewayBillService;
    @Mock private FreightCalculationService freightCalculationService;
    @Mock private com.courier.modules.pricing.application.calculator.ApplicableChargesCalculator applicableChargesCalculator;
    @Mock private ServiceTypeService serviceTypeService;
    @Mock private PackageTypeService packageTypeService;
    @Mock private PaymentModeService paymentModeService;
    @Mock private com.courier.modules.master.domain.PaymentModeRepository paymentModeRepository;
    @Mock private com.courier.modules.master.domain.ServiceTypeRepository serviceTypeRepository;
    @Mock private com.courier.modules.master.domain.PackageTypeRepository packageTypeRepository;
    @Mock private RateService rateService;
    @Mock private RouteService routeService;
    @Mock private PricingEngine pricingEngine;
    @Mock private WalletService walletService;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private FileStoragePort fileStoragePort;
    @Mock private ShipmentAssetRepository shipmentAssetRepository;
    @Mock private com.courier.modules.shipment.domain.DeliveryDispatchOtpRepository deliveryDispatchOtpRepository;
    @Mock private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Mock private com.courier.modules.company.application.CompanySettingsService companySettingsService;
    @Mock private com.courier.modules.communication.application.CommunicationSettingService communicationSettingService;
    @Mock private com.courier.modules.communication.application.provider.SmsProvider smsProvider;
    @Mock private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private ShipmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ShipmentServiceImpl(shipmentRepository, itemRepository, chargeRepository,
                historyRepository, documentRepository, deliveryAssignmentRepository,
                branchShipmentSequenceRepository, releasedShipmentNumberRepository, companyShipmentSequenceRepository, companyDrsSequenceRepository,
                serviceTypeService, packageTypeService, paymentModeService,
                rateService, routeService, pricingEngine, new PricingProperties(), walletService,
                userService, branchService, branchLookup, paymentModeRepository, serviceTypeRepository, packageTypeRepository, customerService, crossingService, ticketService, ticketCategoryService,
                ewayBillService, freightCalculationService,
                applicableChargesCalculator, auditService, eventPublisher, fileStoragePort, shipmentAssetRepository,
                deliveryDispatchOtpRepository, passwordEncoder, companySettingsService, communicationSettingService,
                smsProvider, objectMapper);

        CompanyContext.setCompanyId(COMPANY);
        signedIn(Roles.COMPANY_ADMIN);

        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(i -> i.getArgument(0));
        when(branchShipmentSequenceRepository.nextValue()).thenReturn(1L);
        when(companyShipmentSequenceRepository.nextValue()).thenReturn(1L);
        when(branchService.getById(any())).thenReturn(Branch.builder().branchCode("PUNE").build());
        when(itemRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(chargeRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(historyRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        when(serviceTypeService.getById(SERVICE_TYPE)).thenReturn(serviceType(2));
        when(packageTypeService.getById(PACKAGE_TYPE)).thenReturn(packageType(null));
        when(paymentModeService.getById(PAYMENT_MODE)).thenReturn(paymentMode(false));

        PricingResult defaultPricing = pricingResult(new BigDecimal("136.00"));
        when(pricingEngine.calculate(any())).thenReturn(defaultPricing);

        // Matches defaultPricing's own freight (100.00) / odaCharge (ZERO) so the freight
        // and ODA deltas net to zero — every pre-existing net-amount/commission assertion
        // below stays valid without needing to know about District Level Freight at all.
        when(freightCalculationService.calculate(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(freightCalculationResult(new BigDecimal("100.00"), BigDecimal.ZERO));

        // To-Pay charge zeroed here so the many TO_PAY-mode tests below keep their own,
        // charge-unrelated expectations; the To-Pay charge tests set it explicitly.
        when(companySettingsService.get())
                .thenReturn(com.courier.modules.company.domain.CompanySettings.builder()
                        .toPayCharge(BigDecimal.ZERO).build());
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------- create

    @Test
    @DisplayName("a TO_PAY booking computes weight from the item grid, prices through the "
            + "engine, persists items/charges/history, and never touches the wallet")
    void bookingSucceeds() {
        // Zeroed explicitly: this test is about the item-grid-to-engine wiring, not the
        // minimum chargeable weight floor (covered by its own test below) — otherwise the
        // booking's 5.000kg would floor to the @BeforeEach stub's entity default (15.000).
        when(companySettingsService.get()).thenReturn(
                com.courier.modules.company.domain.CompanySettings.builder()
                        .defaultChargeableWeightKg(BigDecimal.ZERO).build());

        Shipment created = service.create(command());

        assertThat(created.getShipmentNumber()).isEqualTo("PUNE-000001");
        assertThat(created.getTrackingNumber()).matches("\\d{11}");
        assertThat(created.getStatus()).isEqualTo(ShipmentStatus.BOOKED);
        assertThat(created.getActualWeight()).isEqualByComparingTo("5.000");
        assertThat(created.getChargeableWeight()).isEqualByComparingTo("5.000");
        assertThat(created.getExpectedDeliveryDate())
                .isEqualTo(created.getBookingDate().plusDays(2));
        assertThat(created.getSenderName()).isEqualTo("Asha Shah");
        assertThat(created.getReceiverName()).isEqualTo("Rahul Verma");
        assertThat(created.getPickupPincode()).isEqualTo(PICKUP_PINCODE);
        assertThat(created.getDeliveryPincode()).isEqualTo(DELIVERY_PINCODE);
        assertThat(created.getCurrentLocationId()).isEqualTo(BOOKING_BRANCH);
        // Delivery Branch is resolved (see priceIt/freight calc, unchanged) purely to price
        // this booking — never persisted any more. Load Sheet assigns it for real later.
        assertThat(created.getDeliveryBranchId()).isNull();
        assertThat(created.getNextLocationId()).isNull();

        verify(itemRepository).save(any());
        verify(chargeRepository).save(any());
        verify(historyRepository).save(any());
        verify(walletService, never()).getForBranch(any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(customerService).findOrCreateForBooking("Asha Shah", "9876543210");
        verify(customerService).findOrCreateForBooking("Rahul Verma", "9876500000");
        verify(crossingService, never()).createLegs(any(), any(), any());
    }

    @Test
    @DisplayName("a booking below the company's minimum chargeable weight prices at the "
            + "minimum, not its own lighter actual/volumetric weight; one above it prices "
            + "at its own weight, unfloored")
    void chargeableWeightNeverPricesBelowTheCompanyMinimum() {
        // @BeforeEach's stub already carries the entity default (15.000kg) — command()'s
        // 5.000kg item has no dimensions, so actual == volumetric-less chargeable == 5.000
        // before the floor.
        Shipment belowMinimum = service.create(command());
        assertThat(belowMinimum.getActualWeight()).isEqualByComparingTo("5.000");
        assertThat(belowMinimum.getChargeableWeight()).isEqualByComparingTo("15.000");

        // Same @BeforeEach stub (15.000kg minimum) — a 16.000kg item is above it, so it
        // prices at its own weight instead.
        CreateShipmentCommand aboveMinimum = new CreateShipmentCommand(
                BOOKING_BRANCH, null, PICKUP_PINCODE, DELIVERY_PINCODE,
                "Asha Shah", "221B Baker Street, Pune", "9876543210",
                "Rahul Verma", "12 MG Road, Mumbai", "9876500000",
                SERVICE_TYPE, PACKAGE_TYPE, PAYMENT_MODE,
                null, LocalDate.of(2026, 7, 30), new BigDecimal("1000"), 1, "handle with care", null, null, null,
                List.of(new ShipmentItemCommand("Box", 1, new BigDecimal("16.000"),
                        null, null, null, null, false, false)),
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
        Shipment aboveMinimumShipment = service.create(aboveMinimum);
        assertThat(aboveMinimumShipment.getActualWeight()).isEqualByComparingTo("16.000");
        assertThat(aboveMinimumShipment.getChargeableWeight()).isEqualByComparingTo("16.000");
    }

    @Test
    @DisplayName("a crossing booking sets nextLocationId to the crossing branch (not the "
            + "delivery branch) and creates a CrossingDetail for it")
    void crossingBookingCreatesCrossingDetail() {
        Shipment created = service.create(
                command("Asha Shah", "221B Baker Street, Pune", "9876543210", true, CROSSING_BRANCH));

        assertThat(created.getCurrentLocationId()).isEqualTo(BOOKING_BRANCH);
        assertThat(created.getNextLocationId()).isEqualTo(CROSSING_BRANCH);

        verify(crossingService).createLegs(created.getId(), List.of(CROSSING_BRANCH), null);
    }

    @Test
    @DisplayName("crossing without a crossing branch is refused before anything is persisted")
    void crossingWithoutBranchRejected() {
        assertThatThrownBy(() -> service.create(
                command("Asha Shah", "221B Baker Street, Pune", "9876543210", true, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("crossing");

        verify(shipmentRepository, never()).save(any());
        verify(crossingService, never()).createLegs(any(), any(), any());
    }

    // ------------------------------------------------------------- District Level Freight

    @Test
    @DisplayName("booking is blocked before anything is persisted when no District Level "
            + "Freight configuration exists for this From Station + District")
    void bookingBlockedWithoutFreightConfiguration() {
        when(freightCalculationService.calculate(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessRuleException(
                        "No District Level Freight configuration exists for ICHALKARANJI -> Pune."));

        assertThatThrownBy(() -> service.create(command()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("No District Level Freight configuration exists");

        verify(shipmentRepository, never()).save(any());
        verify(chargeRepository, never()).save(any());
        verify(itemRepository, never()).save(any());
        verify(walletService, never()).getForBranch(any());
    }

    @Test
    @DisplayName("the freight amount persisted on the charge row is District Level Freight's "
            + "own figure, not the Pricing Engine's — the Pricing Engine is still consulted "
            + "for fuel/handling/insurance/discount/round-off only")
    void persistedFreightComesFromDistrictLevelFreight() {
        when(freightCalculationService.calculate(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(freightCalculationResult(new BigDecimal("170.00"), BigDecimal.ZERO));

        service.create(command());

        org.mockito.ArgumentCaptor<com.courier.modules.shipment.domain.ShipmentCharge> captor =
                org.mockito.ArgumentCaptor.forClass(com.courier.modules.shipment.domain.ShipmentCharge.class);
        verify(chargeRepository).save(captor.capture());

        assertThat(captor.getValue().getFreight()).isEqualByComparingTo("170.0000");
        // Fuel/handling/insurance are still whatever the (unchanged) Pricing Engine call
        // returned — see the default stub in setUp().
        assertThat(captor.getValue().getFuelCharge()).isEqualByComparingTo("10.00");
        assertThat(captor.getValue().getHandlingCharge()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("an ODA destination's freight-calculation charge becomes the shipment's own "
            + "ODA line when the booking clerk types no manual override")
    void districtFreightOdaChargeAppliesByDefault() {
        when(freightCalculationService.calculate(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(freightCalculationResult(new BigDecimal("170.00"), new BigDecimal("250.00")));

        service.create(command());

        org.mockito.ArgumentCaptor<com.courier.modules.shipment.domain.ShipmentCharge> captor =
                org.mockito.ArgumentCaptor.forClass(com.courier.modules.shipment.domain.ShipmentCharge.class);
        verify(chargeRepository).save(captor.capture());

        assertThat(captor.getValue().getOdaCharge()).isEqualByComparingTo("250.0000");
    }

    @Test
    @DisplayName("a manual ODA override at booking still replaces District Level Freight's "
            + "own ODA figure — the 0.30.3 override behaviour is unchanged")
    void manualOdaOverrideStillWinsOverDistrictFreight() {
        when(freightCalculationService.calculate(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(freightCalculationResult(new BigDecimal("170.00"), new BigDecimal("250.00")));

        service.create(commandWithOdaOverride(new BigDecimal("300.00")));

        org.mockito.ArgumentCaptor<com.courier.modules.shipment.domain.ShipmentCharge> captor =
                org.mockito.ArgumentCaptor.forClass(com.courier.modules.shipment.domain.ShipmentCharge.class);
        verify(chargeRepository).save(captor.capture());

        assertThat(captor.getValue().getOdaCharge()).isEqualByComparingTo("300.0000");
    }

    @Test
    @DisplayName("a Rate/KG override below the calculated rate is refused before anything "
            + "is persisted")
    void ratePerKgOverrideBelowCalculatedRateRejected() {
        when(freightCalculationService.calculate(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(freightCalculationResult(new BigDecimal("100.00"), BigDecimal.ZERO));

        assertThatThrownBy(() -> service.create(commandWithRatePerKgOverride(new BigDecimal("15.00"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot be lowered");

        verify(shipmentRepository, never()).save(any());
        verify(chargeRepository, never()).save(any());
    }

    @Test
    @DisplayName("a Rate/KG override above the calculated rate raises freight and its GST "
            + "on the difference only — same delta-algebra the ODA override already uses")
    void ratePerKgOverrideAboveCalculatedRateRaisesFreightAndGst() {
        when(freightCalculationService.calculate(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(freightCalculationResult(new BigDecimal("100.00"), BigDecimal.ZERO));

        service.create(commandWithRatePerKgOverride(new BigDecimal("25.00")));

        org.mockito.ArgumentCaptor<com.courier.modules.shipment.domain.ShipmentCharge> captor =
                org.mockito.ArgumentCaptor.forClass(com.courier.modules.shipment.domain.ShipmentCharge.class);
        verify(chargeRepository).save(captor.capture());

        // ratePerKgOverride 25.00 x chargeableWeight 5.000 = 125.00, vs. the Pricing
        // Engine's own (now-superseded) freight of 100.00 — a 25.00 delta, GST'd at the
        // booking branch's own 18% on the difference only: 20.70 (priced) + 4.50 = 25.20.
        assertThat(captor.getValue().getFreight()).isEqualByComparingTo("125.00");
        assertThat(captor.getValue().getGstAmount()).isEqualByComparingTo("25.20");
    }

    private com.courier.modules.shipment.domain.ShipmentCharge bookAndCaptureCharge() {
        service.create(command());
        org.mockito.ArgumentCaptor<com.courier.modules.shipment.domain.ShipmentCharge> captor =
                org.mockito.ArgumentCaptor.forClass(com.courier.modules.shipment.domain.ShipmentCharge.class);
        verify(chargeRepository).save(captor.capture());
        return captor.getValue();
    }

    private void configureToPayCharge(String amount) {
        when(companySettingsService.get()).thenReturn(
                com.courier.modules.company.domain.CompanySettings.builder()
                        .toPayCharge(new BigDecimal(amount)).build());
    }

    @Test
    @DisplayName("a To-Pay booking gets the company's To-Pay charge added by the backend, "
            + "taxed with GST at the booking branch's rate")
    void toPayBookingAddsConfiguredChargeWithGst() {
        configureToPayCharge("50.00");

        var charge = bookAndCaptureCharge();

        assertThat(charge.getToPayCharge()).isEqualByComparingTo("50.00");
        // 20.70 (priced) + 18% of 50.00 = 9.00
        assertThat(charge.getGstAmount()).isEqualByComparingTo("29.70");
    }

    @Test
    @DisplayName("the To-Pay charge raises the stored net amount by charge + GST")
    void toPayChargeRaisesNetAmount() {
        var without = bookAndCaptureCharge();
        org.mockito.Mockito.clearInvocations(chargeRepository);
        configureToPayCharge("50.00");

        var with = bookAndCaptureCharge();

        // 59.00 before the company's round-off rule re-rounds the total
        assertThat(with.getNetAmount().subtract(without.getNetAmount()).doubleValue())
                .isBetween(55.0, 65.0);
    }

    @Test
    @DisplayName("a PAID booking never carries the To-Pay charge, whatever the setting says")
    void paidBookingHasNoToPayCharge() {
        configureToPayCharge("50.00");
        when(paymentModeService.getById(PAYMENT_MODE)).thenReturn(paymentMode(true));
        Wallet wallet = mock(Wallet.class);
        when(wallet.getAvailableBalance()).thenReturn(new BigDecimal("1000.00"));
        when(walletService.getForBranch(BOOKING_BRANCH)).thenReturn(wallet);

        assertThat(bookAndCaptureCharge().getToPayCharge()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a COD booking never carries the To-Pay charge — it collects at delivery "
            + "but is the consignee's money, not To-Pay freight")
    void codBookingHasNoToPayCharge() {
        configureToPayCharge("50.00");
        PaymentMode cod = paymentMode(false);
        cod.setCashOnDelivery(true);
        when(paymentModeService.getById(PAYMENT_MODE)).thenReturn(cod);

        assertThat(bookAndCaptureCharge().getToPayCharge()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a company that sets the To-Pay charge to zero charges nothing on To-Pay bookings")
    void zeroConfiguredToPayChargeChargesNothing() {
        configureToPayCharge("0");

        var charge = bookAndCaptureCharge();

        assertThat(charge.getToPayCharge()).isEqualByComparingTo("0");
        assertThat(charge.getGstAmount()).isEqualByComparingTo("20.70");
    }

    @Test
    @DisplayName("a weight outside every configured slab is refused before anything is "
            + "persisted, exactly like a missing configuration")
    void unsupportedWeightBlocksBooking() {
        when(freightCalculationService.calculate(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessRuleException(
                        "Weight 2001 KG is outside the configured 1-2000 KG range for ICHALKARANJI -> Pune."));

        assertThatThrownBy(() -> service.create(command()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("outside the configured");

        verify(shipmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("a PREPAID booking checks the wallet balance before committing and "
            + "publishes the AFTER_COMMIT debit event")
    void prepaidBookingChecksBalanceAndPublishesEvent() {
        when(paymentModeService.getById(PAYMENT_MODE)).thenReturn(paymentMode(true));
        Wallet wallet = mock(Wallet.class);
        when(wallet.getAvailableBalance()).thenReturn(new BigDecimal("1000.00"));
        when(walletService.getForBranch(BOOKING_BRANCH)).thenReturn(wallet);

        Shipment created = service.create(command());

        verify(walletService).getForBranch(BOOKING_BRANCH);
        verify(eventPublisher).publishEvent(any(ShipmentEvent.PrepaidBookingConfirmed.class));
        assertThat(created.getStatus()).isEqualTo(ShipmentStatus.BOOKED);
    }

    @Test
    @DisplayName("an insufficient wallet balance refuses the booking before anything is persisted")
    void insufficientBalanceRefused() {
        when(paymentModeService.getById(PAYMENT_MODE)).thenReturn(paymentMode(true));
        Wallet wallet = mock(Wallet.class);
        when(wallet.getAvailableBalance()).thenReturn(new BigDecimal("10.00"));
        when(walletService.getForBranch(BOOKING_BRANCH)).thenReturn(wallet);

        assertThatThrownBy(() -> service.create(command()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Insufficient wallet balance");

        verify(shipmentRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("the commission breakdown is computed from the booking branch's own "
            + "percentages and stored on the charge row — branch commission is taken on "
            + "freight AFTER the company's service charge is deducted, not the original freight")
    void commissionComputedFromBookingBranchPercentages() {
        // freight 100.00, otherCharges 0; PUNE's default percentages (V25): commission on
        // basic freight 10%, company service charge 10%, commission on other charges 20%
        // (so the branch keeps 80% of other charges — none here).
        // companyCommissionOnBasicFreight = 100 * 10% = 10
        // remainingFreight               = 100 - 10 = 90
        // commissionOnBasicFreight       = 90 * 10% = 9
        Shipment created = service.create(command());

        org.mockito.ArgumentCaptor<com.courier.modules.shipment.domain.ShipmentCharge> captor =
                org.mockito.ArgumentCaptor.forClass(com.courier.modules.shipment.domain.ShipmentCharge.class);
        verify(chargeRepository).save(captor.capture());

        assertThat(captor.getValue().getCommissionOnBasicFreight()).isEqualByComparingTo("9.0000");
        assertThat(captor.getValue().getBranchCommissionOnOtherAmount()).isEqualByComparingTo("0.0000");
        assertThat(captor.getValue().getCompanyCommissionOnBasicFreight()).isEqualByComparingTo("10.0000");
        // totalCommission is every line summed (9 + 0 + 10) — not just the branch's own
        // two, which is the (smaller) amount that actually gets credited to its wallet.
        assertThat(captor.getValue().getTotalCommission()).isEqualByComparingTo("19.0000");
        assertThat(created.getStatus()).isEqualTo(ShipmentStatus.BOOKED);
    }

    @Test
    @DisplayName("branch commission on freight after company service charge deduction — "
            + "worked example: freight 100, service charge 10%, branch commission 20% -> "
            + "company cut 10, remaining 90, branch commission 18")
    void commissionOnRemainingFreightWorkedExample() {
        when(branchService.getById(any())).thenReturn(Branch.builder().branchCode("PUNE")
                .commissionOnBasicFreight(new BigDecimal("20.00"))
                .companyServiceChargePercentage(new BigDecimal("10.00"))
                .build());

        service.create(command());

        org.mockito.ArgumentCaptor<com.courier.modules.shipment.domain.ShipmentCharge> captor =
                org.mockito.ArgumentCaptor.forClass(com.courier.modules.shipment.domain.ShipmentCharge.class);
        verify(chargeRepository).save(captor.capture());

        assertThat(captor.getValue().getCompanyCommissionOnBasicFreight()).isEqualByComparingTo("10.0000");
        assertThat(captor.getValue().getCommissionOnBasicFreight()).isEqualByComparingTo("18.0000");
    }

    @Test
    @DisplayName("0% company service charge — branch commission is taken on the full, "
            + "un-deducted freight")
    void commissionWithZeroServiceCharge() {
        when(branchService.getById(any())).thenReturn(Branch.builder().branchCode("PUNE")
                .commissionOnBasicFreight(new BigDecimal("20.00"))
                .companyServiceChargePercentage(BigDecimal.ZERO)
                .build());

        service.create(command());

        org.mockito.ArgumentCaptor<com.courier.modules.shipment.domain.ShipmentCharge> captor =
                org.mockito.ArgumentCaptor.forClass(com.courier.modules.shipment.domain.ShipmentCharge.class);
        verify(chargeRepository).save(captor.capture());

        assertThat(captor.getValue().getCompanyCommissionOnBasicFreight()).isEqualByComparingTo("0.0000");
        assertThat(captor.getValue().getCommissionOnBasicFreight()).isEqualByComparingTo("20.0000");
    }

    @Test
    @DisplayName("0% branch commission — company still takes its service charge, branch gets "
            + "nothing off freight")
    void commissionWithZeroBranchCommission() {
        when(branchService.getById(any())).thenReturn(Branch.builder().branchCode("PUNE")
                .commissionOnBasicFreight(BigDecimal.ZERO)
                .companyServiceChargePercentage(new BigDecimal("10.00"))
                .build());

        service.create(command());

        org.mockito.ArgumentCaptor<com.courier.modules.shipment.domain.ShipmentCharge> captor =
                org.mockito.ArgumentCaptor.forClass(com.courier.modules.shipment.domain.ShipmentCharge.class);
        verify(chargeRepository).save(captor.capture());

        assertThat(captor.getValue().getCompanyCommissionOnBasicFreight()).isEqualByComparingTo("10.0000");
        assertThat(captor.getValue().getCommissionOnBasicFreight()).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("a different freight amount (500) still deducts the company's share first")
    void commissionWithDifferentFreightAmount() {
        // Freight is District Level Freight's own figure now, not the Pricing Engine's —
        // varying it here means restubbing freightCalculationService, not pricingResult.
        when(freightCalculationService.calculate(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(freightCalculationResult(new BigDecimal("500.00"), BigDecimal.ZERO));
        when(branchService.getById(any())).thenReturn(Branch.builder().branchCode("PUNE")
                .commissionOnBasicFreight(new BigDecimal("20.00"))
                .companyServiceChargePercentage(new BigDecimal("10.00"))
                .build());

        service.create(command());

        org.mockito.ArgumentCaptor<com.courier.modules.shipment.domain.ShipmentCharge> captor =
                org.mockito.ArgumentCaptor.forClass(com.courier.modules.shipment.domain.ShipmentCharge.class);
        verify(chargeRepository).save(captor.capture());

        // companyCommissionOnBasicFreight = 500 * 10% = 50; remaining = 450; branch = 450 * 20% = 90
        assertThat(captor.getValue().getCompanyCommissionOnBasicFreight()).isEqualByComparingTo("50.0000");
        assertThat(captor.getValue().getCommissionOnBasicFreight()).isEqualByComparingTo("90.0000");
    }

    @Test
    @DisplayName("a decimal freight amount rounds the same way through both cuts")
    void commissionWithDecimalFreightAmount() {
        when(freightCalculationService.calculate(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(freightCalculationResult(new BigDecimal("133.33"), BigDecimal.ZERO));
        when(branchService.getById(any())).thenReturn(Branch.builder().branchCode("PUNE")
                .commissionOnBasicFreight(new BigDecimal("15.00"))
                .companyServiceChargePercentage(new BigDecimal("7.50"))
                .build());

        service.create(command());

        org.mockito.ArgumentCaptor<com.courier.modules.shipment.domain.ShipmentCharge> captor =
                org.mockito.ArgumentCaptor.forClass(com.courier.modules.shipment.domain.ShipmentCharge.class);
        verify(chargeRepository).save(captor.capture());

        // companyCommissionOnBasicFreight = 133.33 * 7.5% = 9.99975 -> 9.9998 (HALF_UP, 4dp)
        // remaining = 133.33 - 9.9998 = 123.3302; branch = 123.3302 * 15% = 18.49953 -> 18.4995
        assertThat(captor.getValue().getCompanyCommissionOnBasicFreight()).isEqualByComparingTo("9.9998");
        assertThat(captor.getValue().getCommissionOnBasicFreight()).isEqualByComparingTo("18.4995");
    }

    @Test
    @DisplayName("a PREPAID booking's commission is not published at booking time any more — "
            + "only the debit event is")
    void prepaidBookingNoLongerPublishesCommissionAtBooking() {
        when(paymentModeService.getById(PAYMENT_MODE)).thenReturn(paymentMode(true));
        Wallet wallet = mock(Wallet.class);
        when(wallet.getAvailableBalance()).thenReturn(new BigDecimal("1000.00"));
        when(walletService.getForBranch(BOOKING_BRANCH)).thenReturn(wallet);
        when(branchService.getById(any())).thenReturn(
                Branch.builder().branchCode("PUNE").instantCommission(true).build());

        service.create(command());

        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.InScanCommissionEarned.class));
        verify(eventPublisher).publishEvent(any(ShipmentEvent.PrepaidBookingConfirmed.class));
    }

    // Commission-on-dispatch tests moved to ShipmentMovementServiceImplTest's scanOneIn
    // section — commission now credits on in-scan at the final delivery branch, not on
    // Trip Challan (manifest dispatch) creation. See ShipmentEvent.InScanCommissionEarned.

    @Test
    @DisplayName("dispatching a manifest no longer publishes any commission event — "
            + "commission credits later, on in-scan at the final delivery branch")
    void dispatchNeverPublishesCommission() {
        Shipment shipment = existingShipment(ShipmentStatus.MANIFEST_CREATED);
        when(shipmentRepository.findAllByCompanyIdAndIdIn(COMPANY, List.of(shipment.getId())))
                .thenReturn(List.of(shipment));
        when(paymentModeService.getById(PAYMENT_MODE)).thenReturn(paymentMode(true));
        when(branchService.getById(BOOKING_BRANCH)).thenReturn(
                Branch.builder().branchCode("PUNE").instantCommission(true).build());

        service.transitionToDispatched(List.of(shipment.getId()), UUID.randomUUID(), UUID.randomUUID(),
                BOOKING_BRANCH);

        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.InScanCommissionEarned.class));
        verify(chargeRepository, never()).findByShipmentIdIn(any());
    }

    @Test
    @DisplayName("markPickedUpForDirectDelivery moves a DISPATCHED shipment straight to IN_SCAN, "
            + "publishing no wallet/commission event")
    void markPickedUpForDirectDeliveryMovesToInScan() {
        Shipment shipment = existingShipment(ShipmentStatus.DISPATCHED);
        when(shipmentRepository.findAllByCompanyIdAndIdIn(COMPANY, List.of(shipment.getId())))
                .thenReturn(List.of(shipment));

        List<Shipment> result = service.markPickedUpForDirectDelivery(List.of(shipment.getId()));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(ShipmentStatus.IN_SCAN);
        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.ToPayReceivedAtDeliveryBranch.class));
        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.InScanCommissionEarned.class));
        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.ReceivedAtBranch.class));
    }

    @Test
    @DisplayName("a blank sender name/address/contact is refused")
    void blankSenderRejected() {
        assertThatThrownBy(() -> service.create(command("", "", "")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("sender");
    }

    @Test
    @DisplayName("a missing pickup or delivery pincode is refused")
    void blankPincodeRejected() {
        CreateShipmentCommand withoutPincode = new CreateShipmentCommand(
                BOOKING_BRANCH, null, "", DELIVERY_PINCODE,
                "Asha Shah", "221B Baker Street, Pune", "9876543210",
                "Rahul Verma", "12 MG Road, Mumbai", "9876500000",
                SERVICE_TYPE, PACKAGE_TYPE, PAYMENT_MODE,
                null, LocalDate.of(2026, 7, 30), new BigDecimal("1000"), 1, "handle with care", null, null, null,
                List.of(new ShipmentItemCommand("Box", 1, new BigDecimal("5.000"),
                        null, null, null, null, false, false)),
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(withoutPincode))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("pincode");
    }

    @Test
    @DisplayName("actual weight above the package type's maximum is refused")
    void packageTypeCeilingExceeded() {
        when(packageTypeService.getById(PACKAGE_TYPE)).thenReturn(packageType(new BigDecimal("3.000")));

        assertThatThrownBy(() -> service.create(command()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    @DisplayName("a manually entered shipment number is used verbatim instead of the "
            + "auto-generated one")
    void manualShipmentNumberHonored() {
        Shipment created = service.create(commandWithManualNumber("MANUAL-001"));

        assertThat(created.getShipmentNumber()).isEqualTo("MANUAL-001");
        verify(branchShipmentSequenceRepository, never()).advance(any());
    }

    @Test
    @DisplayName("a manual shipment number already used by this company is refused")
    void manualShipmentNumberDuplicateRejected() {
        when(shipmentRepository.existsByCompanyIdAndShipmentNumber(COMPANY, "MANUAL-001")).thenReturn(true);

        assertThatThrownBy(() -> service.create(commandWithManualNumber("MANUAL-001")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already in use");

        verify(shipmentRepository, never()).save(any());
    }

    // ------------------------------------------------------------------- cancel

    @Test
    @DisplayName("a BOOKED shipment can be cancelled")
    void cancelSucceedsFromBooked() {
        Shipment existing = existingShipment(ShipmentStatus.BOOKED);
        when(shipmentRepository.findByIdWithinCompany(existing.getId(), COMPANY))
                .thenReturn(Optional.of(existing));

        Shipment cancelled = service.cancel(existing.getId(), "changed my mind");

        assertThat(cancelled.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        verify(historyRepository).save(any());

        org.mockito.ArgumentCaptor<ShipmentEvent.Cancelled> captor =
                org.mockito.ArgumentCaptor.forClass(ShipmentEvent.Cancelled.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().shipmentNumber()).isEqualTo(existing.getShipmentNumber());

        // The old/new status pair the Activity Log's automatic filter attaches to this
        // request — see ActivityContext's own doc for why this is set here rather than
        // requiring the filter to know Shipment's domain.
        var activity = com.courier.shared.activity.application.ActivityContext.get();
        assertThat(activity).isNotNull();
        assertThat(activity.oldValue()).containsEntry("status", "BOOKED");
        assertThat(activity.newValue()).containsEntry("status", "CANCELLED");
        com.courier.shared.activity.application.ActivityContext.clear();
    }

    @Test
    @DisplayName("a DISPATCHED shipment can no longer be cancelled — it has left the branch")
    void cancelRefusedOnceDispatched() {
        Shipment existing = existingShipment(ShipmentStatus.DISPATCHED);
        when(shipmentRepository.findByIdWithinCompany(existing.getId(), COMPANY))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.cancel(existing.getId(), null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("left the branch");

        verify(shipmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("a cancel with no reason is refused")
    void cancelRefusedWithoutReason() {
        Shipment existing = existingShipment(ShipmentStatus.BOOKED);
        when(shipmentRepository.findByIdWithinCompany(existing.getId(), COMPANY))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.cancel(existing.getId(), "   "))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("reason is required");

        verify(shipmentRepository, never()).save(any());
    }

    // ------------------------------------------------------------------- update

    @Test
    @DisplayName("a shipment can only be edited while still BOOKED")
    void updateRefusedOnceNotBooked() {
        Shipment existing = existingShipment(ShipmentStatus.MANIFEST_CREATED);
        when(shipmentRepository.findByIdWithinCompany(existing.getId(), COMPANY))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(existing.getId(), updateCommand(0L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("BOOKED");
    }

    @Test
    @DisplayName("a stale version on update is refused as a conflict")
    void staleVersionRejected() {
        Shipment existing = existingShipment(ShipmentStatus.BOOKED);
        when(shipmentRepository.findByIdWithinCompany(existing.getId(), COMPANY))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(existing.getId(), updateCommand(99L)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("updating a still-BOOKED shipment re-prices it and replaces the charge row")
    void updateReprices() {
        Shipment existing = existingShipment(ShipmentStatus.BOOKED);
        when(shipmentRepository.findByIdWithinCompany(existing.getId(), COMPANY))
                .thenReturn(Optional.of(existing));
        when(chargeRepository.findByShipmentIdWithinCompany(existing.getId(), COMPANY))
                .thenReturn(Optional.empty());
        PricingResult repricedResult = pricingResult(new BigDecimal("200.00"));
        when(pricingEngine.calculate(any())).thenReturn(repricedResult);

        Shipment updated = service.update(existing.getId(), updateCommand(0L));

        assertThat(updated.getDeliveryBranchId()).isEqualTo(DELIVERY_BRANCH);
        assertThat(updated.getReceiverName()).isEqualTo("Rahul Verma (updated)");
        verify(chargeRepository).save(any());
        verify(itemRepository).deleteAllByShipmentIdAndCompanyId(existing.getId(), COMPANY);
    }

    // ------------------------------------------------------------- uploadShipmentImage

    @Test
    @DisplayName("uploadShipmentImage refuses a file extension outside the accepted image set")
    void uploadShipmentImageRefusesUnacceptedExtension() {
        Shipment existing = existingShipment(ShipmentStatus.BOOKED);
        when(shipmentRepository.findByIdWithinCompany(existing.getId(), COMPANY))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.uploadShipmentImage(existing.getId(),
                new ShipmentService.UploadShipmentImageCommand(new byte[]{1}, "photo.mp4", "video/mp4")))
                .isInstanceOf(BusinessRuleException.class);
        verify(fileStoragePort, never()).upload(any());
    }

    @Test
    @DisplayName("uploadShipmentImage stores the file and records it as a BOOKING asset")
    void uploadShipmentImageHappyPath() {
        Shipment existing = existingShipment(ShipmentStatus.BOOKED);
        when(shipmentRepository.findByIdWithinCompany(existing.getId(), COMPANY))
                .thenReturn(Optional.of(existing));
        when(fileStoragePort.upload(any())).thenReturn(new FileStoragePort.StoredFile(
                "https://bucket.s3.amazonaws.com/shipment-photo/x.jpg", "shipment-photo/x.jpg"));

        String url = service.uploadShipmentImage(existing.getId(),
                new ShipmentService.UploadShipmentImageCommand(new byte[]{1, 2, 3}, "photo.JPG", "image/jpeg"));

        assertThat(url).isEqualTo("https://bucket.s3.amazonaws.com/shipment-photo/x.jpg");
        var captor = org.mockito.ArgumentCaptor.forClass(
                com.courier.modules.shipment.domain.ShipmentAsset.class);
        verify(shipmentAssetRepository).save(captor.capture());
        assertThat(captor.getValue().getAssetType())
                .isEqualTo(com.courier.modules.shipment.domain.ShipmentAssetType.BOOKING);
        assertThat(captor.getValue().getKind()).isEqualTo("PHOTO");
        assertThat(captor.getValue().getAssetUrl()).isEqualTo("https://bucket.s3.amazonaws.com/shipment-photo/x.jpg");
    }

    // ---------------------------------------------------------------- helpers

    private void signedIn(String role) {
        AuthenticatedUser principal = new AuthenticatedUser(
                CALLER, COMPANY, "admin@legacy.test", Set.of(role), "jti");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));
    }

    private static CreateShipmentCommand msrtcCommand(java.util.UUID deliveryBranchId, boolean crossing) {
        return new CreateShipmentCommand(
                BOOKING_BRANCH, null, PICKUP_PINCODE, DELIVERY_PINCODE,
                "Asha Shah", "221B Baker Street, Pune", "9876543210",
                "Rahul Verma", "12 MG Road, Mumbai", "9876500000",
                SERVICE_TYPE, PACKAGE_TYPE, PAYMENT_MODE,
                null, LocalDate.of(2026, 7, 30), new BigDecimal("1000"), 1, "handle with care", null, null, null,
                List.of(new ShipmentItemCommand("Box", 1, new BigDecimal("5.000"),
                        null, null, null, null, false, false)),
                null, null, null, null, crossing,
                crossing ? List.of(java.util.UUID.randomUUID()) : null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                com.courier.modules.shipment.domain.FlowType.MSRTC, deliveryBranchId);
    }

    @Test
    @DisplayName("an MSRTC booking is written straight to its delivery branch, as MSRTC")
    void msrtcBookingCarriesDeliveryBranch() {
        java.util.UUID to = java.util.UUID.randomUUID();
        when(branchLookup.findBranch(to, COMPANY)).thenReturn(java.util.Optional.of(
                new com.courier.modules.shipment.domain.BranchLookupPort.BranchRef(to, "SAT", "Satara", true)));

        Shipment created = service.create(msrtcCommand(to, false));

        assertThat(created.getFlowType()).isEqualTo(com.courier.modules.shipment.domain.FlowType.MSRTC);
        assertThat(created.getDeliveryBranchId()).isEqualTo(to);
        assertThat(created.getNextLocationId()).isEqualTo(to);
    }

    @Test
    @DisplayName("an MSRTC booking is refused without a delivery branch, to itself, to an unknown "
            + "branch, or with crossing")
    void msrtcBookingRules() {
        java.util.UUID unknown = java.util.UUID.randomUUID();
        when(branchLookup.findBranch(unknown, COMPANY)).thenReturn(java.util.Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(com.courier.shared.exception.BusinessRuleException.class,
                () -> service.create(msrtcCommand(null, false)));
        org.junit.jupiter.api.Assertions.assertThrows(com.courier.shared.exception.BusinessRuleException.class,
                () -> service.create(msrtcCommand(BOOKING_BRANCH, false)));
        org.junit.jupiter.api.Assertions.assertThrows(com.courier.shared.exception.BusinessRuleException.class,
                () -> service.create(msrtcCommand(unknown, false)));
        org.junit.jupiter.api.Assertions.assertThrows(com.courier.shared.exception.BusinessRuleException.class,
                () -> service.create(msrtcCommand(java.util.UUID.randomUUID(), true)));
    }

    private static CreateShipmentCommand command() {
        return command("Asha Shah", "221B Baker Street, Pune", "9876543210");
    }

    private static CreateShipmentCommand command(String senderName, String senderAddress, String senderContact) {
        return new CreateShipmentCommand(
                BOOKING_BRANCH, null, PICKUP_PINCODE, DELIVERY_PINCODE,
                senderName, senderAddress, senderContact,
                "Rahul Verma", "12 MG Road, Mumbai", "9876500000",
                SERVICE_TYPE, PACKAGE_TYPE, PAYMENT_MODE,
                null, LocalDate.of(2026, 7, 30), new BigDecimal("1000"), 1, "handle with care", null, null, null,
                List.of(new ShipmentItemCommand("Box", 1, new BigDecimal("5.000"),
                        null, null, null, null, false, false)),
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    private static CreateShipmentCommand commandWithOdaOverride(BigDecimal odaCharge) {
        return new CreateShipmentCommand(
                BOOKING_BRANCH, null, PICKUP_PINCODE, DELIVERY_PINCODE,
                "Asha Shah", "221B Baker Street, Pune", "9876543210",
                "Rahul Verma", "12 MG Road, Mumbai", "9876500000",
                SERVICE_TYPE, PACKAGE_TYPE, PAYMENT_MODE,
                null, LocalDate.of(2026, 7, 30), new BigDecimal("1000"), 1, "handle with care",
                null, odaCharge, null,
                List.of(new ShipmentItemCommand("Box", 1, new BigDecimal("5.000"),
                        null, null, null, null, false, false)),
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    private static CreateShipmentCommand commandWithRatePerKgOverride(BigDecimal ratePerKgOverride) {
        return new CreateShipmentCommand(
                BOOKING_BRANCH, null, PICKUP_PINCODE, DELIVERY_PINCODE,
                "Asha Shah", "221B Baker Street, Pune", "9876543210",
                "Rahul Verma", "12 MG Road, Mumbai", "9876500000",
                SERVICE_TYPE, PACKAGE_TYPE, PAYMENT_MODE,
                null, LocalDate.of(2026, 7, 30), new BigDecimal("1000"), 1, "handle with care",
                null, null, null,
                List.of(new ShipmentItemCommand("Box", 1, new BigDecimal("5.000"),
                        null, null, null, null, false, false)),
                null, null, null, null, null, null, null, null, null, null, ratePerKgOverride,
                null, null, null, null, null, null, null);
    }

    private static CreateShipmentCommand commandWithManualNumber(String manualShipmentNumber) {
        return new CreateShipmentCommand(
                BOOKING_BRANCH, manualShipmentNumber, PICKUP_PINCODE, DELIVERY_PINCODE,
                "Asha Shah", "221B Baker Street, Pune", "9876543210",
                "Rahul Verma", "12 MG Road, Mumbai", "9876500000",
                SERVICE_TYPE, PACKAGE_TYPE, PAYMENT_MODE,
                null, LocalDate.of(2026, 7, 30), new BigDecimal("1000"), 1, "handle with care", null, null, null,
                List.of(new ShipmentItemCommand("Box", 1, new BigDecimal("5.000"),
                        null, null, null, null, false, false)),
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    private static UpdateShipmentCommand updateCommand(Long expectedVersion) {
        return new UpdateShipmentCommand(
                expectedVersion, PICKUP_PINCODE, DELIVERY_PINCODE,
                "Asha Shah", "221B Baker Street, Pune", "9876543210",
                "Rahul Verma (updated)", "12 MG Road, Mumbai", "9876500000",
                SERVICE_TYPE, PACKAGE_TYPE, PAYMENT_MODE,
                null, LocalDate.of(2026, 7, 30), new BigDecimal("1000"), 1, "updated", null, null, null,
                List.of(new ShipmentItemCommand("Box", 1, new BigDecimal("5.000"),
                        null, null, null, null, false, false)),
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    private static CreateShipmentCommand command(String senderName, String senderAddress, String senderContact,
                                                   boolean crossing, java.util.UUID crossingBranchId) {
        return new CreateShipmentCommand(
                BOOKING_BRANCH, null, PICKUP_PINCODE, DELIVERY_PINCODE,
                senderName, senderAddress, senderContact,
                "Rahul Verma", "12 MG Road, Mumbai", "9876500000",
                SERVICE_TYPE, PACKAGE_TYPE, PAYMENT_MODE,
                null, LocalDate.of(2026, 7, 30), new BigDecimal("1000"), 1, "handle with care", null, null, null,
                List.of(new ShipmentItemCommand("Box", 1, new BigDecimal("5.000"),
                        null, null, null, null, false, false)),
                null, null, null, null, crossing,
                crossingBranchId == null ? null : List.of(crossingBranchId), null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    private static Shipment existingShipment(ShipmentStatus status) {
        Shipment shipment = Shipment.builder()
                .shipmentNumber("SHP2607300000001")
                .trackingNumber("AWB2607300000001")
                .bookingDate(LocalDate.of(2026, 7, 30))
                .bookingBranchId(BOOKING_BRANCH)
                .deliveryBranchId(DELIVERY_BRANCH)
                .pickupPincode(PICKUP_PINCODE)
                .deliveryPincode(DELIVERY_PINCODE)
                .senderName("Asha Shah")
                .senderAddress("221B Baker Street, Pune")
                .senderContact("9876543210")
                .receiverName("Rahul Verma")
                .receiverAddress("12 MG Road, Mumbai")
                .receiverContact("9876500000")
                .serviceTypeId(SERVICE_TYPE)
                .packageTypeId(PACKAGE_TYPE)
                .paymentModeId(PAYMENT_MODE)
                .actualWeight(new BigDecimal("5.000"))
                .volumetricWeight(BigDecimal.ZERO)
                .chargeableWeight(new BigDecimal("5.000"))
                .numberOfPackages(1)
                .status(status)
                .build();
        shipment.setId(UUID.randomUUID());
        shipment.setCompanyId(COMPANY);
        shipment.setVersion(0L);
        return shipment;
    }

    private static com.courier.modules.shipment.domain.ShipmentCharge charge(
            UUID shipmentId, String commissionOnBasicFreight, String branchCommissionOnOtherAmount) {
        return com.courier.modules.shipment.domain.ShipmentCharge.builder()
                .shipmentId(shipmentId)
                .commissionOnBasicFreight(new BigDecimal(commissionOnBasicFreight))
                .branchCommissionOnOtherAmount(new BigDecimal(branchCommissionOnOtherAmount))
                .build();
    }

    @Test
    void portalBookingIssuesTheShipmentAtTheQuotedPriceAndLeavesTheBranchWalletAlone() {
        UUID branch = UUID.randomUUID();
        when(branchLookup.findBranch(branch, COMPANY)).thenReturn(java.util.Optional.of(
                new com.courier.modules.shipment.domain.BranchLookupPort.BranchRef(branch, "PUN", "Pune", true)));
        PaymentMode paid = paymentMode(true);
        paid.setCompanyId(COMPANY);
        PaymentMode toPay = paymentMode(false);
        toPay.setCompanyId(COMPANY);
        when(paymentModeRepository.findAll()).thenReturn(List.of(paid, toPay));
        ServiceType surface = serviceType(2);
        surface.setCompanyId(COMPANY);
        when(serviceTypeRepository.findAll()).thenReturn(List.of(surface));
        PackageType box = packageType(null);
        box.setCompanyId(COMPANY);
        when(packageTypeRepository.findAll()).thenReturn(List.of(box));

        var command = new com.courier.modules.shipment.application.command.PortalShipmentCommand(branch, UUID.randomUUID(), "ref-1",
                "411016", "411004", "Acme", "12 MG Road", "9800000001", "Ravi", "5 Park St", "9800000002", "Pune", "Pune",
                "PAID", "OCCPL_STD", "Garments", new BigDecimal("2000"), 1, new BigDecimal("3"), new BigDecimal("3"),
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("150"), BigDecimal.ZERO, new BigDecimal("10"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("28.80"), BigDecimal.ZERO, new BigDecimal("189"));

        Shipment saved = service.createFromPortalBooking(command);

        assertThat(saved.getTrackingNumber()).isNotBlank();
        assertThat(saved.getShipmentNumber()).startsWith("PUN-");
        assertThat(saved.getStatus()).isEqualTo(ShipmentStatus.BOOKED);
        org.mockito.ArgumentCaptor<com.courier.modules.shipment.domain.ShipmentCharge> cap =
                org.mockito.ArgumentCaptor.forClass(com.courier.modules.shipment.domain.ShipmentCharge.class);
        verify(chargeRepository).save(cap.capture());
        assertThat(cap.getValue().getNetAmount()).isEqualByComparingTo("189");
        assertThat(cap.getValue().getFreight()).isEqualByComparingTo("150");
        assertThat(cap.getValue().getRoundOff()).isEqualByComparingTo("0.20");
        verify(freightCalculationService, never()).calculate(any(), any(), any(), any(), any(), any(), any());
        verify(walletService, never()).debitForBooking(any());
        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.PrepaidBookingConfirmed.class));
    }

    private static ServiceType serviceType(int deliveryDays) {
        ServiceType serviceType = new ServiceType();
        serviceType.setCode("STD");
        serviceType.setDeliveryDays(deliveryDays);
        return serviceType;
    }

    private static PackageType packageType(BigDecimal maxWeightKg) {
        PackageType packageType = new PackageType();
        packageType.setCode("BOX");
        packageType.setMaxWeightKg(maxWeightKg);
        return packageType;
    }

    private static PaymentMode paymentMode(boolean collectAtBooking) {
        PaymentMode paymentMode = new PaymentMode();
        paymentMode.setCode(collectAtBooking ? "PAID" : "TO_PAY");
        paymentMode.setCollectAtBooking(collectAtBooking);
        paymentMode.setCollectAtDelivery(!collectAtBooking);
        return paymentMode;
    }

    private static PricingResult pricingResult(BigDecimal netAmount) {
        return pricingResult(new BigDecimal("100.00"), netAmount);
    }

    private static PricingResult pricingResult(BigDecimal freight, BigDecimal netAmount) {
        Route route = mock(Route.class);
        when(route.getId()).thenReturn(UUID.randomUUID());
        when(route.getCode()).thenReturn("PNQ_BOM");
        Rate rate = mock(Rate.class);
        when(rate.getId()).thenReturn(UUID.randomUUID());
        when(rate.getRateCode()).thenReturn("RATE-PNQ-BOM-STD");

        return new PricingResult(route, rate, new BigDecimal("5.000"), BigDecimal.ZERO,
                new BigDecimal("5.000"), freight, new BigDecimal("10.00"),
                new BigDecimal("5.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(),
                new BigDecimal("20.70"), BigDecimal.ZERO, new BigDecimal("0.30"), netAmount, null);
    }

    private static FreightCalculationResult freightCalculationResult(BigDecimal baseFreight, BigDecimal odaCharge) {
        boolean odaApplicable = odaCharge.signum() > 0;
        return new FreightCalculationResult(UUID.randomUUID(), BOOKING_BRANCH, "PUNE", "Pune Hub",
                UUID.randomUUID(), "PUN", "Pune", UUID.randomUUID(), DELIVERY_PINCODE, "Pune",
                new BigDecimal("5.000"), "1-15 KG", new BigDecimal("20.00"), baseFreight, odaApplicable, odaCharge,
                baseFreight.add(odaCharge));
    }
}
