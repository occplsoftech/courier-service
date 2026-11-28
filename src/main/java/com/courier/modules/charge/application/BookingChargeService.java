package com.courier.modules.charge.application;

import com.courier.modules.charge.domain.BookingChargeCalc;
import com.courier.modules.charge.domain.BookingChargeCode;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Charges Master (V192): the company's booking charges (Handling, Pick up, Delivery, AWB, Green Tax, Reverse Pick Up, COD,
 * Other), a business customer's overrides, and what they come to on one booking. The clerk picks which apply.
 */
public interface BookingChargeService {

    /** One charge's setting. */
    record Rate(BookingChargeCode code, String name, BookingChargeCalc calc, BigDecimal value, BigDecimal minAmount,
                boolean active) {
    }

    /** A charge for one customer: the company's setting, the customer's override (null when none). */
    record CustomerRate(BookingChargeCode code, String name, Rate company, Rate override) {
        public Rate effective() {
            return override != null ? override : company;
        }
    }

    record RateInput(BookingChargeCalc calc, BigDecimal value, BigDecimal minAmount, Boolean active) {
    }

    /** What an active charge comes to on one booking, for the clerk to tick. */
    record QuoteLine(BookingChargeCode code, String name, BookingChargeCalc calc, BigDecimal value, BigDecimal minAmount,
                     BigDecimal amount, boolean customerRate) {
    }

    /** A charge the clerk ticked; {@code amount} null = as calculated (COD is always calculated). */
    record Picked(BookingChargeCode code, BigDecimal amount) {
    }

    record Line(BookingChargeCode code, String name, BigDecimal amount) {
    }

    /** What the picked charges add to a booking: the COD charge, the rest together, and each line. */
    record Resolved(BigDecimal codCharge, BigDecimal othersTotal, List<Line> lines) {
    }

    List<Rate> companyRates();

    Rate saveCompanyRate(BookingChargeCode code, RateInput input);

    List<CustomerRate> customerRates(UUID customerId);

    Rate saveCustomerRate(UUID customerId, BookingChargeCode code, RateInput input);

    void resetCustomerRate(UUID customerId, BookingChargeCode code);

    /** Every active charge for this booking (the customer's value where overridden). */
    List<QuoteLine> quote(UUID customerId, BigDecimal freight, BigDecimal chargeableKg, int pieces, BigDecimal codAmount);

    /**
     * The picked charges on a booking. {@code picked} null (a screen without the Charges Master): only COD, from the
     * master, when there is a COD amount. Otherwise only what was picked; COD only if picked.
     */
    Resolved resolve(UUID customerId, List<Picked> picked, BigDecimal freight, BigDecimal chargeableKg, int pieces,
                     BigDecimal codAmount);

    /** Keeps a booking's picked charges with it. */
    void record(UUID shipmentId, List<Line> lines);

    List<Line> linesFor(UUID shipmentId);
}
