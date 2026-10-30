package com.courier.modules.customerpricing.application;

import com.courier.modules.customerpricing.domain.PricingZone;
import com.courier.modules.customerpricing.domain.ZoneRate;
import com.courier.modules.customerpricing.domain.ZoneRateSlab;

import java.util.List;

public record ZoneRateView(ZoneRate rate, PricingZone zone, String customerName, List<ZoneRateSlab> slabs,
                           String serviceTypeCode, String serviceTypeName) {
}
