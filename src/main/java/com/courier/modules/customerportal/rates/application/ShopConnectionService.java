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
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A business customer's Shopify store (13.3b). The customer creates a custom app in their own Shopify admin and pastes its Admin API
 * access token here; it is checked against the store and kept encrypted. Every {@link #EVERY} the store's open, unfulfilled orders
 * (changed since the last import) come into "Orders to ship" (or at once with "Import now"); one that cannot be used is listed with the
 * reason. When an imported order is booked, its tracking number is sent back and Shopify marks it fulfilled (unless switched off).
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
    private static final Pattern DOMAIN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,60}\\.myshopify\\.com$");

    private final ShopConnectionRepository connections;
    private final PortalOrderRepository orders;
    private final PortalBookingRepository bookings;
    private final PortalOrderService orderService;
    private final CurrentCustomerResolver resolver;
    private final ShopifyClient shopify;
    private final CourierSecretsCodec secrets;
    private final AuditService auditService;
    private final List<CourierProviderAdapter> adapters;
    private final TransactionTemplate tx;

    public ShopConnectionService(ShopConnectionRepository connections, PortalOrderRepository orders, PortalBookingRepository bookings,
                                 PortalOrderService orderService, CurrentCustomerResolver resolver, ShopifyClient shopify,
                                 CourierSecretsCodec secrets, AuditService auditService, List<CourierProviderAdapter> adapters,
                                 org.springframework.transaction.PlatformTransactionManager txManager) {
        this.connections = connections;
        this.orders = orders;
        this.bookings = bookings;
        this.orderService = orderService;
        this.resolver = resolver;
        this.shopify = shopify;
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
    public Optional<ConnectionView> view() {
        ActingCustomer a = resolver.require();
        return connections.findForCustomer(a.companyId(), a.customerId(), ShopConnection.Platform.SHOPIFY).map(ShopConnectionService::view);
    }

    /** Checks the token against the store and saves it (replacing an earlier store or token); the first import starts at once. */
    public ConnectionView connect(String domainInput, String token, Boolean fulfilBack) {
        ActingCustomer a = resolver.require();
        String domain = domain(domainInput);
        String t = token == null ? "" : token.trim();
        if (t.length() < 20 || t.length() > 200 || t.contains(" ")) {
            throw new BusinessRuleException("Paste the Admin API access token from your Shopify app (it starts with shpat_).");
        }
        String name;
        try {
            name = shopify.shopName(domain, t);
        } catch (ShopifyClient.ShopifyException e) {
            throw new BusinessRuleException(e.getMessage());
        }
        ShopConnection saved = tx.execute(s -> {
            ShopConnection c = connections.findForCustomer(a.companyId(), a.customerId(), ShopConnection.Platform.SHOPIFY)
                    .orElseGet(() -> ShopConnection.builder().customerId(a.customerId()).platform(ShopConnection.Platform.SHOPIFY).build());
            if (c.getShopDomain() != null && !c.getShopDomain().equals(domain)) {
                c.setLastImportAt(null);
                c.setImportedCount(0);
                c.setSkipped(null);
            }
            c.setShopDomain(domain);
            c.setShopName(trim(name, 150));
            c.setSecretsEnc(secrets.encryptSecrets(Map.of("token", t)));
            c.setPaused(false);
            c.setLastError(null);
            if (fulfilBack != null) {
                c.setFulfilBack(fulfilBack);
            }
            return connections.save(c);
        });
        auditService.record(AuditAction.SHOP_CONNECTED, "ShopConnection", saved.getId(),
                Map.of("customerId", a.customerId().toString(), "platform", "SHOPIFY", "shop", domain));
        importNow(saved);
        return view(reload(saved));
    }

    @Transactional
    public ConnectionView settings(Boolean paused, Boolean fulfilBack) {
        ShopConnection c = owned();
        if (paused != null) {
            c.setPaused(paused);
        }
        if (fulfilBack != null) {
            c.setFulfilBack(fulfilBack);
        }
        return view(connections.save(c));
    }

    /** Forgets the store and its token; orders already imported stay in the inbox. */
    @Transactional
    public void disconnect() {
        ShopConnection c = owned();
        c.setSecretsEnc(null);
        c.softDelete(resolver.require().userId());
        connections.save(c);
        auditService.record(AuditAction.SHOP_DISCONNECTED, "ShopConnection", c.getId(), Map.of("shop", c.getShopDomain()));
    }

    public ImportResult importNow() {
        ShopConnection c = tx.execute(s -> owned());
        if (c.getSecretsEnc() == null) {
            throw new BusinessRuleException("Connect your store again: its token is no longer saved.");
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
        String token;
        try {
            token = secrets.decryptSecrets(c.getSecretsEnc()).get("token");
        } catch (RuntimeException e) {
            token = null;
        }
        if (token == null) {
            finish(c.getId(), companyId, null, 0, List.of(), "The saved token cannot be read any more. Connect your store again.", true);
            return new ImportResult(0, 0, 0, 0, "The saved token cannot be read any more. Connect your store again.");
        }
        Instant since = c.getLastImportAt() == null ? started.minus(FIRST_LOOK_BACK) : c.getLastImportAt().minus(OVERLAP);
        List<JsonNode> found;
        try {
            String t = token;
            found = CompanyContext.runAs(companyId, () -> shopify.openOrders(c.getShopDomain(), t, since, MAX_ORDERS_PER_IMPORT));
        } catch (ShopifyClient.ShopifyException e) {
            // keep lastImportAt so nothing is missed next time; a refused token pauses the store until it is connected again
            finish(c.getId(), companyId, null, 0, List.of(), e.getMessage(), e.auth());
            return new ImportResult(0, 0, 0, 0, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Shopify import for connection {} failed", c.getId(), e);
            finish(c.getId(), companyId, null, 0, List.of(), "Shopify could not be reached. It will be tried again.", false);
            return new ImportResult(0, 0, 0, 0, "Shopify could not be reached. It will be tried again.");
        }
        int imported = 0;
        int already = 0;
        List<Skipped> skipped = new ArrayList<>();
        for (JsonNode o : found) {
            String number = ShopifyOrderMapper.number(o);
            String not = ShopifyOrderMapper.notShippable(o);
            if (not != null) {
                continue;
            }
            try {
                boolean created = orderService.importFromShop(companyId, c.getCustomerId(), PortalOrder.Source.SHOPIFY, c.getId(),
                        o.path("id").asText(), ShopifyOrderMapper.map(o));
                if (created) {
                    imported++;
                } else {
                    already++;
                }
            } catch (ApiException e) {
                skipped.add(new Skipped(number, e.getMessage(), started.toString()));
            } catch (RuntimeException e) {
                log.warn("Shopify order {} of connection {} could not be imported", number, c.getId(), e);
                skipped.add(new Skipped(number, "Could not be imported.", started.toString()));
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
            String token = secrets.decryptSecrets(t.conn().getSecretsEnc()).get("token");
            CompanyContext.runAs(companyId, () -> shopify.fulfil(t.conn().getShopDomain(), token, t.order().getExternalId(), courier, awb, true));
            orderService.recordShopSync(companyId, orderId, "SENT", null);
        } catch (ShopifyClient.ShopifyException e) {
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

    private ShopConnection owned() {
        ActingCustomer a = resolver.require();
        return connections.findForCustomer(a.companyId(), a.customerId(), ShopConnection.Platform.SHOPIFY)
                .orElseThrow(() -> new ResourceNotFoundException("Shopify connection", a.customerId()));
    }

    private ShopConnection reload(ShopConnection c) {
        return tx.execute(s -> CompanyContext.runAs(c.getCompanyId(), () -> connections.findById(c.getId()).orElse(c)));
    }

    /** "My Shop.myshopify.com", "https://myshop.myshopify.com/admin" or just "myshop" → myshop.myshopify.com; anything else is refused. */
    static String domain(String raw) {
        String d = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        d = d.replaceFirst("^https?://", "");
        int slash = d.indexOf('/');
        if (slash >= 0) {
            d = d.substring(0, slash);
        }
        if (!d.isEmpty() && !d.contains(".")) {
            d = d + ".myshopify.com";
        }
        if (!DOMAIN.matcher(d).matches()) {
            throw new BusinessRuleException("Enter your store's Shopify address, like yourshop.myshopify.com (Settings → Domains in Shopify).");
        }
        return d;
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
