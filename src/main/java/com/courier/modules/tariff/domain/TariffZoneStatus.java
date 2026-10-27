package com.courier.modules.tariff.domain;

/** Lifecycle of a tariff zone. A zone is withdrawn (INACTIVE), never hard-deleted while rates may point at it. */
public enum TariffZoneStatus {
    ACTIVE,
    INACTIVE
}
