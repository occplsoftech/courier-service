package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.application.ActingCustomer;
import com.courier.modules.customerportal.application.CurrentCustomerResolver;
import com.courier.modules.customerportal.integrations.application.CourierSecretsCodec;
import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.PortalBooking;
import com.courier.modules.customerportal.rates.domain.PortalBookingRepository;
import com.courier.modules.customerportal.rates.domain.PortalOrder;
import com.courier.modules.customerportal.rates.domain.PortalOrderRepository;
import com.courier.modules.customerportal.rates.domain.ShopConnection;
import com.courier.modules.customerportal.rates.domain.ShopConnectionRepository;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.ApiException;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A business customer's online store (13.3b Shopify, 13.3c WooCommerce, one {@link StorePlatform} each). The customer pastes the
 * credentials made in their own store admin; they are checked against the store and kept encrypted. Every {@link #EVERY} the store's
 * orders waiting to ship (changed since the last import) come into "Orders to ship" (or at once with "Import now"); one that cannot be
 * used is listed with the reason. When an imported order is booked, its tracking number is sent back to the store (unless switched off).
 */
@Slf4j
@Service
public class ShopConnectionService {

    static final Duration EVERY = Duration.ofMinutes(15);
    /** The first import looks this far back; later ones overlap the previous by {@link #OVERLAP} (an order is never imported twice). */
    static final Duration FIRST_LOOK_BACK = Duration.ofDays(30);
    static final Duration OVERLAP = Duration.ofMinutes(30);
    static final int MAX_ORDERS_PER_IMPORT = 1000;
    static final int SKIPPED_KEPT = 50;

    private final ShopConnectionRepository connections;
    private final PortalOrderRepository orders;
    private final PortalBookingRepository bookings;
    private final PortalOrderService orderService;
    private final CurrentCustomerResolver resolver;
    private final List<StorePlatform> platforms;
    private final CourierSecretsCodec secrets;
    private final AuditService auditService;
    private final List<CourierProviderAdapter> adapters;
    private final TransactionTemplate tx;

    public ShopConnectionService(ShopConnectionRepository connections, PortalOrderRepository orders, PortalBookingRepository bookings,
                                 PortalOrderService orderService, CurrentCustomerResolver resolver, List<StorePlatform> platforms,
                                 CourierSecretsCodec secrets, AuditService auditService, List<CourierProviderAdapter> adapters,
                                 org.springframework.transaction.PlatformTransactionManager txManager) {
        this.connections = connections;
        this.orders = orders;
        this.bookings = bookings;
        this.orderService = orderService;
        this.resolver = resolver;
        this.platforms = platforms;
        this.secrets = secrets;
        this.auditService = auditService;
        this.adapters = adapters;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    public record Skipped(String orderNumber, String reason, String at) {
    }

    public record ConnectionView(UUID id, String platform, String shopDomain, String shopName, boolean paused, boolean fulfilBack,
                                 Instant connectedAt, Instant lastImportAt, int importedCount, String lastError, List<Skipped> skipped) {
    }

    public record ImportResult(int checked, int imported, int alreadyIn, int skipped, String error) {
    }

    // ------------------------------------------------------------------ the portal

    @Transactional(readOnly = true)
    public Optional<ConnectionView> view(ShopConnection.Platform platform) {
        ActingCustomer a = resolver.require();
        return connections.findForCustomer(a.companyId(), a.customerId(), platform).map(ShopConnectionService::view);
    }

    /**
     * Checks the credentials with the store and saves them encrypted (replacing an earlier store or key); the first import starts at
     * once. {@code pasted}: Shopify accessToken; WooCommerce consumerKey and consumerSecret.
     */
    public ConnectionView connect(ShopConnection.Platform platform, String addressInput, Map<String, String> pasted, Boolean fulfilBack) {
        ActingCustomer a = resolver.require();
        StorePlatform store = platform(platform);
        String address = store.address(addressInput);
        Map<String, String> creds = store.credentials(pasted);
        String name;
        try {
            name = store.check(address, creds);
        } catch (StorePlatform.StoreException e) {
            throw new BusinessRuleException(e.getMessage());
        }
        ShopConnection saved = tx.execute(s -> {
            ShopConnection c = connections.findForCustomer(a.companyId(), a.customerId(), platform)
                    .orElseGet(() -> ShopConnection.builder().customerId(a.customerId()).platform(platform).build());
            if (c.getShopDomain() != null && !c.getShopDomain().equals(address)) {
                c.setLastImportAt(null);
                c.setImportedCount(0);
                c.setSkipped(null);
            }
            c.setShopDomain(address);
            c.setShopName(trim(name, 150));
            c.setSecretsEnc(secrets.encryptSecrets(creds));
            c.setPaused(false);
            c.setLastError(null);
            if (fulfilBack != null) {
                c.setFulfilBack(fulfilBack);
            }
            return connections.save(c);
        });
        auditService.record(AuditAction.SHOP_CONNECTED, "ShopConnection", saved.getId(),
                Map.of("customerId", a.customerId().toString(), "platform", platform.name(), "shop", address));
        importNow(saved);
        return view(reload(saved));
    }

    @Transactional
    public ConnectionView settings(ShopConnection.Platform platform, Boolean paused, Boolean fulfilBack) {
        ShopConnection c = owned(platform);
        if (paused != null) {
            c.setPaused(paused);
        }
        if (fulfilBack != null) {
            c.setFulfilBack(fulfilBack);
        }
        return view(connections.save(c));
    }

    /** Forgets the store and its credentials; orders already imported stay in the inbox. */
    @Transactional
    public void disconnect(ShopConnection.Platform platform) {
        ShopConnection c = owned(platform);
        c.setSecretsEnc(null);
        c.softDelete(resolver.require().userId());
        connections.save(c);
        auditService.record(AuditAction.SHOP_DISCONNECTED, "ShopConnection", c.getId(), Map.of("shop", c.getShopDomain()));
    }

    public ImportResult importNow(ShopConnection.Platform platform) {
        ShopConnection c = tx.execute(s -> owned(platform));
        if (c.getSecretsEnc() == null) {
            throw new BusinessRuleException("Connect your store again: its key is no longer saved.");
        }
        return importNow(c);
    }

    // ------------------------------------------------------------------ the job

    @Scheduled(fixedDelayString = "${app.customer-portal.shop-import.job-delay-ms:120000}", initialDelayString = "240000")
    public void importDue() {
        try {
            List<ShopConnection> due = connections.findDueAcrossCompanies(Instant.now().minus(EVERY), PageRequest.of(0, 20));
            for (ShopConnection c : due) {
                importNow(c);
            }
        } catch (RuntimeException e) {
            log.error("Shop import run failed", e);
        }
    }

    ImportResult importNow(ShopConnection c) {
        Instant started = Instant.now();
        UUID companyId = c.getCompanyId();
        StorePlatform store = platform(c.getPlatform());
        Map<String, String> creds;
        try {
            creds = secrets.decryptSecrets(c.getSecretsEnc());
        } catch (RuntimeException e) {
            creds = Map.of();
        }
        if (creds.isEmpty()) {
            String why = "The saved key cannot be read any more. Connect your store again.";
            finish(c.getId(), companyId, null, 0, List.of(), why, true);
            return new ImportResult(0, 0, 0, 0, why);
        }
        Instant since = c.getLastImportAt() == null ? started.minus(FIRST_LOOK_BACK) : c.getLastImportAt().minus(OVERLAP);
        List<StorePlatform.StoreOrder> found;
        try {
            Map<String, String> k = creds;
            found = CompanyContext.runAs(companyId, () -> store.openOrders(c.getShopDomain(), k, since, MAX_ORDERS_PER_IMPORT));
        } catch (StorePlatform.StoreException e) {
            // keep lastImportAt so nothing is missed next time; refused credentials pause the store until it is connected again
            finish(c.getId(), companyId, null, 0, List.of(), e.getMessage(), e.auth());
            return new ImportResult(0, 0, 0, 0, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Import for connection {} failed", c.getId(), e);
            String why = "The store could not be reached. It will be tried again.";
            finish(c.getId(), companyId, null, 0, List.of(), why, false);
            return new ImportResult(0, 0, 0, 0, why);
        }
        PortalOrder.Source source = c.getPlatform() == ShopConnection.Platform.WOOCOMMERCE ? PortalOrder.Source.WOOCOMMERCE
                : PortalOrder.Source.SHOPIFY;
        int imported = 0;
        int already = 0;
        List<Skipped> skipped = new ArrayList<>();
        for (StorePlatform.StoreOrder o : found) {
            if (o.notShippable() != null) {
                continue;
            }
            if (o.input() == null) {
                skipped.add(new Skipped(o.number(), o.mappingError() == null ? "Could not be read." : o.mappingError(), started.toString()));
                continue;
            }
            try {
                if (orderService.importFromShop(companyId, c.getCustomerId(), source, c.getId(), o.externalId(), o.input())) {
                    imported++;
                } else {
                    already++;
                }
            } catch (ApiException e) {
                skipped.add(new Skipped(o.number(), e.getMessage(), started.toString()));
            } catch (RuntimeException e) {
                log.warn("Order {} of connection {} could not be imported", o.number(), c.getId(), e);
                skipped.add(new Skipped(o.number(), "Could not be imported.", started.toString()));
            }
        }
        finish(c.getId(), companyId, started, imported, skipped, null, false);
        return new ImportResult(found.size(), imported, already, skipped.size(), null);
    }

    /** Saves the import's outcome. {@code at} null keeps the previous import time (the import failed); {@code pause} stops the job. */
    private void finish(UUID id, UUID companyId, Instant at, int imported, List<Skipped> skipped, String error, boolean pause) {
        tx.executeWithoutResult(s -> CompanyContext.runAs(companyId, () -> connections.findById(id).ifPresent(c -> {
            if (at != null) {
                c.setLastImportAt(at);
            }
            if (pause) {
                c.setPaused(true);
            }
            c.setImportedCount(c.getImportedCount() + imported);
            c.setLastError(trim(error, 300));
            c.setSkipped(mergeSkipped(c.getSkipped(), skipped));
            connections.save(c);
        })));
    }

    /** The newest reasons first; an order that was skipped again replaces its older entry; at most {@link #SKIPPED_KEPT}. */
    static List<Map<String, Object>> mergeSkipped(List<Map<String, Object>> old, List<Skipped> fresh) {
        Map<String, Map<String, Object>> by = new LinkedHashMap<>();
        for (Skipped s : fresh) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderNumber", s.orderNumber());
            m.put("reason", s.reason());
            m.put("at", s.at());
            by.putIfAbsent(s.orderNumber(), m);
        }
        if (old != null) {
            for (Map<String, Object> m : old) {
                by.putIfAbsent(String.valueOf(m.get("orderNumber")), m);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(by.values());
        return out.size() > SKIPPED_KEPT ? new ArrayList<>(out.subList(0, SKIPPED_KEPT)) : out;
    }

    // ------------------------------------------------------------------ tracking back to Shopify

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBooked(PortalOrderService.OrderBooked e) {
        try {
            sendTracking(e.companyId(), e.orderId());
        } catch (RuntimeException ex) {
            log.warn("Sending tracking for order {} to the store failed", e.orderId(), ex);
            orderService.recordShopSync(e.companyId(), e.orderId(), "FAILED", "Could not be sent.");
        }
    }

    void sendTracking(UUID companyId, UUID orderId) {
        record Target(PortalOrder order, ShopConnection conn, PortalBooking booking) {
        }
        Target t = tx.execute(s -> CompanyContext.runAs(companyId, () -> {
            PortalOrder o = orders.findById(orderId).filter(x -> companyId.equals(x.getCompanyId())).orElse(null);
            if (o == null || o.getConnectionId() == null || o.getBookingId() == null || o.getExternalId() == null) {
                return null;
            }
            ShopConnection c = connections.findById(o.getConnectionId()).filter(x -> companyId.equals(x.getCompanyId())).orElse(null);
            PortalBooking b = bookings.findById(o.getBookingId()).orElse(null);
            return new Target(o, c, b);
        }));
        if (t == null) {
            return;
        }
        if (t.conn() == null || t.conn().getSecretsEnc() == null) {
            orderService.recordShopSync(companyId, orderId, "FAILED", "The store is no longer connected.");
            return;
        }
        if (!t.conn().isFulfilBack()) {
            return;
        }
        String awb = t.booking() == null ? null : t.booking().getAwbNumber();
        if (awb == null || awb.isBlank()) {
            orderService.recordShopSync(companyId, orderId, "FAILED", "The booking has no tracking number yet.");
            return;
        }
        String courier = courierName(t.booking().getProviderCode());
        try {
            Map<String, String> creds = secrets.decryptSecrets(t.conn().getSecretsEnc());
            StorePlatform store = platform(t.conn().getPlatform());
            CompanyContext.runAs(companyId, () -> store.shipped(t.conn().getShopDomain(), creds, t.order().getExternalId(), courier, awb));
            orderService.recordShopSync(companyId, orderId, "SENT", null);
        } catch (StorePlatform.StoreException e) {
            orderService.recordShopSync(companyId, orderId, "FAILED", e.getMessage());
        }
    }

    private String courierName(String code) {
        if (code == null) {
            return "OCCPL";
        }
        return adapters.stream().filter(a -> a.code().equals(code)).map(CourierProviderAdapter::displayName).findFirst().orElse(code);
    }

    // ------------------------------------------------------------------ helpers

    private ShopConnection owned(ShopConnection.Platform platform) {
        ActingCustomer a = resolver.require();
        return connections.findForCustomer(a.companyId(), a.customerId(), platform)
                .orElseThrow(() -> new ResourceNotFoundException("Store connection", a.customerId()));
    }

    private StorePlatform platform(ShopConnection.Platform p) {
        return platforms.stream().filter(x -> x.platform() == p).findFirst()
                .orElseThrow(() -> new BusinessRuleException(p.name() + " stores cannot be connected yet."));
    }

    private ShopConnection reload(ShopConnection c) {
        return tx.execute(s -> CompanyContext.runAs(c.getCompanyId(), () -> connections.findById(c.getId()).orElse(c)));
    }

    private static String trim(String s, int max) {
        return s == null ? null : (s.length() > max ? s.substring(0, max) : s);
    }

    @SuppressWarnings("unchecked")
    static ConnectionView view(ShopConnection c) {
        List<Skipped> skipped = c.getSkipped() == null ? List.of() : c.getSkipped().stream()
                .map(m -> new Skipped(String.valueOf(m.get("orderNumber")), String.valueOf(m.get("reason")), String.valueOf(m.get("at"))))
                .toList();
        return new ConnectionView(c.getId(), c.getPlatform().name(), c.getShopDomain(), c.getShopName(), c.isPaused(), c.isFulfilBack(),
                c.getCreatedAt(), c.getLastImportAt(), c.getImportedCount(), c.getLastError(), skipped);
    }
}
