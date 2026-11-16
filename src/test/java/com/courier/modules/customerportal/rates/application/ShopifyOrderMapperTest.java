package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.rates.application.PortalOrderService.OrderInput;
import com.courier.modules.customerportal.rates.domain.PortalOrder;
import com.courier.shared.exception.BusinessRuleException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShopifyOrderMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode order(String extra) throws Exception {
        return JSON.readTree("""
                {"id": 5551234, "name": "#1001", "order_number": 1001, "cancelled_at": null,
                 "current_total_price": "1098.00", "total_price": "1098.00", "total_weight": 850,
                 "financial_status": "paid", "payment_gateway_names": ["razorpay"], "note": "Leave at gate",
                 "shipping_address": {"first_name": "Ravi", "last_name": "Kumar", "name": "Ravi Kumar", "phone": "+91 98765 43210",
                   "address1": "12 MG Road", "address2": "Camp", "city": "Pune", "province": "Maharashtra", "zip": "411 001"},
                 "line_items": [{"name": "T-shirt - M", "quantity": 2, "price": "499.00"}, {"name": "Cap", "quantity": 1, "price": "100.00"}]
                 %s}""".formatted(extra));
    }

    @Test
    void mapsAPrepaidOrder() throws Exception {
        OrderInput in = ShopifyOrderMapper.map(order(""));
        assertThat(in.orderNumber()).isEqualTo("#1001");
        assertThat(in.buyer().name()).isEqualTo("Ravi Kumar");
        assertThat(in.buyer().pincode()).isEqualTo("411001");
        assertThat(in.buyer().address()).isEqualTo("12 MG Road, Camp");
        assertThat(in.parcel().weightKg()).isEqualByComparingTo("0.85");
        assertThat(in.parcel().declaredValue()).isEqualByComparingTo("1098");
        assertThat(in.parcel().contents()).isEqualTo("T-shirt - M, Cap");
        assertThat(in.payment().mode()).isEqualTo("PREPAID");
        assertThat(in.items()).hasSize(2);
        assertThat(in.notes()).isEqualTo("Leave at gate");

        PortalOrder o = PortalOrder.builder().build();
        PortalOrderService.apply(o, in);
        assertThat(o.getDeliveryPhone()).isEqualTo("9876543210");
    }

    @Test
    void cashOnDeliveryUnpaidIsCodForTheAmountDue() throws Exception {
        JsonNode o = JSON.readTree(order("").toString()
                .replace("\"paid\"", "\"pending\"").replace("\"razorpay\"", "\"Cash on Delivery (COD)\"")
                .replace("\"note\"", "\"total_outstanding\":\"1000.00\",\"note\""));
        OrderInput in = ShopifyOrderMapper.map(o);
        assertThat(in.payment().mode()).isEqualTo("COD");
        assertThat(in.payment().codAmount()).isEqualByComparingTo("1000");
    }

    @Test
    void codGatewayButAlreadyPaidIsPrepaid() throws Exception {
        JsonNode o = JSON.readTree(order("").toString().replace("\"razorpay\"", "\"Cash on Delivery (COD)\""));
        assertThat(ShopifyOrderMapper.map(o).payment().mode()).isEqualTo("PREPAID");
    }

    @Test
    void phoneFallsBackToTheCustomerAndBadPhoneIsRefused() throws Exception {
        JsonNode noPhone = JSON.readTree(order(", \"customer\": {\"phone\": \"9123456780\"}").toString().replace("\"+91 98765 43210\"", "null"));
        assertThat(ShopifyOrderMapper.map(noPhone).buyer().phone()).isEqualTo("9123456780");

        JsonNode bad = JSON.readTree(order("").toString().replace("\"+91 98765 43210\"", "\"12345\""));
        assertThatThrownBy(() -> PortalOrderService.apply(PortalOrder.builder().build(), ShopifyOrderMapper.map(bad)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("phone");
    }

    @Test
    void cancelledOrNoAddressIsNotShippable() throws Exception {
        assertThat(ShopifyOrderMapper.notShippable(order(""))).isNull();
        assertThat(ShopifyOrderMapper.notShippable(JSON.readTree(order("").toString()
                .replace("\"cancelled_at\":null", "\"cancelled_at\":\"2026-11-01T10:00:00Z\"")))).contains("Cancelled");
        JsonNode digital = JSON.readTree("{\"id\":1,\"name\":\"#9\",\"shipping_address\":null}");
        assertThat(ShopifyOrderMapper.notShippable(digital)).contains("No shipping address");
    }

    @Test
    void noWeightTakesTheDefault() throws Exception {
        JsonNode o = JSON.readTree(order("").toString().replace("\"total_weight\":850", "\"total_weight\":0"));
        PortalOrder p = PortalOrder.builder().build();
        PortalOrderService.apply(p, ShopifyOrderMapper.map(o));
        assertThat(p.getWeightKg()).isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    void domainIsNormalisedAndOnlyShopifyIsAllowed() {
        assertThat(new ShopifyPlatform(null).address("https://My-Shop.myshopify.com/admin")).isEqualTo("my-shop.myshopify.com");
        assertThat(new ShopifyPlatform(null).address("myshop")).isEqualTo("myshop.myshopify.com");
        assertThatThrownBy(() -> new ShopifyPlatform(null).address("evil.com")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new ShopifyPlatform(null).address("169.254.169.254")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new ShopifyPlatform(null).address("a.myshopify.com.evil.com")).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void skippedKeepsNewestFirstWithoutRepeats() {
        List<Map<String, Object>> old = List.of(Map.of("orderNumber", "#1", "reason", "old", "at", "t0"));
        List<Map<String, Object>> merged = ShopConnectionService.mergeSkipped(old,
                List.of(new ShopConnectionService.Skipped("#2", "bad pincode", "t1"), new ShopConnectionService.Skipped("#1", "bad phone", "t1")));
        assertThat(merged).extracting(m -> m.get("orderNumber")).containsExactly("#2", "#1");
        assertThat(merged.get(1).get("reason")).isEqualTo("bad phone");
    }
}
