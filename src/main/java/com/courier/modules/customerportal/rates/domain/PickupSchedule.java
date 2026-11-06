package com.courier.modules.customerportal.rates.domain;

import java.time.LocalDate;

/** When the customer asked for the parcel to be collected: a date and a slot, either of which may be unset. */
public record PickupSchedule(LocalDate date, String slot) {

    public static final PickupSchedule NONE = new PickupSchedule(null, null);
}
