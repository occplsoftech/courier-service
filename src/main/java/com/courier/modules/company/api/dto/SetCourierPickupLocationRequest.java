package com.courier.modules.company.api.dto;

import jakarta.validation.constraints.Size;

public record SetCourierPickupLocationRequest(@Size(max = 120) String name) {
}
