package com.courier.modules.customerportal.application;

import com.courier.modules.master.application.PincodeGeography;
import com.courier.modules.master.application.PincodeService;
import com.courier.modules.master.domain.Pincode;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pincode details for the booking form: where it is, and whether OCCPL's own network serves it
 * (the pincode master's flags). Serviceability by external providers is answered per provider
 * when rates are compared.
 */
@Service
@RequiredArgsConstructor
public class PincodeLookupService {

    static final Pattern INDIAN_PINCODE = Pattern.compile("^[1-9][0-9]{5}$");

    private final CurrentCustomerResolver resolver;
    private final PincodeService pincodes;

    public PincodeInfo lookup(String raw) {
        resolver.require();
        String code = raw == null ? "" : raw.trim();
        if (!INDIAN_PINCODE.matcher(code).matches()) {
            throw new BusinessRuleException("A pincode is 6 digits and does not start with 0.");
        }
        Pincode pincode;
        try {
            pincode = pincodes.findByCode(code);
        } catch (ResourceNotFoundException e) {
            return PincodeInfo.unknown(code);
        }
        Optional<PincodeGeography> geography = pincodes.geography(code);
        return new PincodeInfo(code, true,
                geography.map(PincodeGeography::areaName).orElse(null),
                geography.map(PincodeGeography::cityName).orElse(null),
                geography.map(PincodeGeography::districtName).orElse(null),
                geography.map(PincodeGeography::stateName).orElse(null),
                pincode.isServiceable(), pincode.isPickupAvailable(), pincode.isCodAvailable(),
                pincode.isPrepaidAvailable(), pincode.isOdaApplicable());
    }

    /** {@code known=false}: not in the pincode master — OCCPL's network cannot serve it. */
    public record PincodeInfo(String pincode, boolean known, String area, String city, String district, String state,
                              boolean serviceable, boolean pickupAvailable, boolean codAvailable,
                              boolean prepaidAvailable, boolean odaApplicable) {
        static PincodeInfo unknown(String code) {
            return new PincodeInfo(code, false, null, null, null, null, false, false, false, false, false);
        }
    }
}
