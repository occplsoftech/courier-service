package com.courier.modules.tariff.domain;

/** Lifecycle of a rate card or a charge rule. A rate card starts INACTIVE so it can be filled in before it prices anything. */
public enum TariffStatus {
    ACTIVE,
    INACTIVE
}
