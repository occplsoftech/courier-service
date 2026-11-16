package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.rates.application.PortalOrderService.OrderInput;
import com.courier.modules.customerportal.rates.domain.PortalOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WooOrderMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode order(String payment, String shippingPhone) throws Exception {
        return JSON.readTree("""
                {"id": 731, "number": "731", "status": "processing", "total": "1248.00", "payment_method": "%s",
                 "customer_note": "Ring the bell",
                 "billing": {"first_name": "Ravi", "last_name": "Kumar", "phone": "+91-98765-43210", "address_1": "Office 3",
                   "postcode": "400001", "city": "Mumbai", "state": "MH", "country": "IN"},
                 "shipping": {"first_name": "Ravi", "last_name": "Kumar", "address_1": "12 MG Road", "address_2": "Camp",
                   "postcode": "411 001", "city": "Pune", "state": "MH", "country": "IN", "phone": %s},
                 "line_items": [{"product_id": 11, "name": "Kurta", "quantity": 2, "price": 499},
                                {"product_id": 12, "name": "Dupatta", "quantity": 1, "price": 250}]}""".formatted(payment, shippingPhone));
    }

    @Test
    void mapsShippingAddressStateNameAndPrepaid() throws Exception {
        OrderInput in = WooOrderMapper.map(order("razorpay", "\"\""), Map.of());
        assertThat(in.orderNumber()).isEqualTo("731");
        assertThat(in.buyer().name()).isEqualTo("Ravi Kumar");
        assertThat(in.buyer().address()).isEqualTo("12 MG Road, Camp");
        assertThat(in.buyer().pincode()).isEqualTo("411001");
        assertThat(in.buyer().state()).isEqualTo("Maharashtra");
        assertThat(in.buyer().phone()).isEqualTo("+91-98765-43210"); // billing phone when shipping has none
        assertThat(in.parcel().declaredValue()).isEqualByComparingTo("1248");
        assertThat(in.parcel().weightKg()).isNull();
        assertThat(in.parcel().contents()).isEqualTo("Kurta, Dupatta");
        assertThat(in.payment().mode()).isEqualTo("PREPAID");
        assertThat(in.notes()).isEqualTo("Ring the bell");

        PortalOrder o = PortalOrder.builder().build();
        PortalOrderService.apply(o, in);
        assertThat(o.getDeliveryPhone()).isEqualTo("9876543210");
        assertThat(o.getWeightKg()).isEqualByComparingTo("0.5");
    }

    @Test
    void codAndWeightsFromProducts() throws Exception {
        OrderInput in = WooOrderMapper.map(order("cod", "\"9123456780\""), Map.of(11L, new BigDecimal("0.4"), 12L, new BigDecimal("0.2")));
        assertThat(in.payment().mode()).isEqualTo("COD");
        assertThat(in.payment().codAmount()).isEqualByComparingTo("1248");
        assertThat(in.buyer().phone()).isEqualTo("9123456780");
        assertThat(in.parcel().weightKg()).isEqualByComparingTo("1.0");
    }

    @Test
    void aProductWithoutWeightMeansTheDefault() throws Exception {
        assertThat(WooOrderMapper.map(order("cod", "null"), Map.of(11L, new BigDecimal("0.4"))).parcel().weightKg()).isNull();
    }

    @Test
    void billingAddressWhenNoShippingAndNothingAtAllIsLeftOut() throws Exception {
        JsonNode noShip = JSON.readTree("{\"id\":5,\"number\":\"5\",\"billing\":{\"address_1\":\"Flat 2, Shivaji Nagar\",\"postcode\":\"411005\"},"
                + "\"shipping\":{\"address_1\":\"\",\"postcode\":\"\"}}");
        assertThat(WooOrderMapper.notShippable(noShip)).isNull();
        assertThat(WooOrderMapper.map(noShip, Map.of()).buyer().pincode()).isEqualTo("411005");
        JsonNode none = JSON.readTree("{\"id\":6,\"number\":\"6\",\"billing\":{},\"shipping\":{}}");
        assertThat(WooOrderMapper.notShippable(none)).contains("No shipping address");
    }

    @Test
    void weightUnits() {
        assertThat(WooOrderMapper.toKg("500", "g")).isEqualByComparingTo("0.5");
        assertThat(WooOrderMapper.toKg("2", "kg")).isEqualByComparingTo("2");
        assertThat(WooOrderMapper.toKg("1", "lbs")).isEqualByComparingTo("0.45359237");
        assertThat(WooOrderMapper.toKg("", "kg")).isNull();
        assertThat(WooOrderMapper.toKg("abc", "kg")).isNull();
    }
}
