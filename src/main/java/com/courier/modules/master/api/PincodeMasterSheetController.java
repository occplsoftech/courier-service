package com.courier.modules.master.api;

import com.courier.modules.master.domain.PincodeMasterSheetRepository;
import com.courier.modules.master.domain.PincodeMasterSheetRow;
import com.courier.shared.api.ApiResponse;
import com.courier.shared.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * OCCPL's pincode master sheet exactly as given (V176), read only, with each row's live Premium / Air Cargo / Surface Cargo
 * switches from the pincode master beside it. The switches change the pincode master (what bookings check), never the
 * sheet's own text.
 */
@RestController
@RequestMapping("/api/v1/global-masters/pincode-sheet")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Global Masters - Pincode Sheet", description = "The pincode master sheet, verbatim")
public class PincodeMasterSheetController {

    private final PincodeMasterSheetRepository rows;
    private final com.courier.modules.master.application.PincodeService pincodes;

    /** One network's live coverage at a pincode: on/off, ODA, TAT hours (each null when not set). */
    public record Live(Boolean serviceable, Boolean oda, Integer tatHours) {
        static Live of(com.courier.modules.master.domain.Pincode p, com.courier.modules.master.domain.ServiceNetwork n) {
            var c = p.coverage(n);
            return new Live(c.serviceable(), c.oda(), c.tatHours());
        }
    }

    /** The three switchable networks' live coverage at a pincode in the pincode master. */
    public record LiveCoverage(Live premium, Live airCargo, Live surfaceCargo) {
        static LiveCoverage of(com.courier.modules.master.domain.Pincode p) {
            if (p == null) return null;
            return new LiveCoverage(Live.of(p, com.courier.modules.master.domain.ServiceNetwork.PREMIUM),
                    Live.of(p, com.courier.modules.master.domain.ServiceNetwork.AIR_CARGO),
                    Live.of(p, com.courier.modules.master.domain.ServiceNetwork.SURFACE_CARGO));
        }
    }

    /** What to change; a field left out (null) stays as it is. */
    public record SwitchRequest(@jakarta.validation.constraints.NotNull com.courier.modules.master.domain.ServiceNetwork network,
                                Boolean serviceable, Boolean oda,
                                @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(2400) Integer tatHours) {
    }

    /** One sheet row, column for column. Every value is the text the sheet shows. */
    public record SheetRowResponse(Integer rowNo, String pincode, String city, String state, String region, String zone,
                                   String docIsOda, String docServ, String docTat, String surfaceServ,
                                   String surfaceTat, String airService, String airTat, String airCargoTat,
                                   String surfaceCargoTat, String airCargoOda, String surfaceCargoOda, String premiumService,
                                   String premiumTat,
                                   /** Live, from the pincode master (what bookings check); null when not set or not on file. */
                                   Boolean livePremium, Boolean liveAirCargo, Boolean liveSurfaceCargo,
                                   Boolean livePremiumOda, Boolean liveAirCargoOda, Boolean liveSurfaceCargoOda,
                                   Integer livePremiumTat, Integer liveAirCargoTat, Integer liveSurfaceCargoTat,
                                   /** True when the pincode is in the pincode master (so it can be switched). */
                                   boolean inMaster) {
        static SheetRowResponse of(PincodeMasterSheetRow r, LiveCoverage live) {
            return new SheetRowResponse(r.getRowNo(), r.getPincode(), r.getCity(), r.getState(), r.getRegion(),
                    r.getZone(), r.getDocIsOda(), r.getDocServ(), r.getDocTat(), r.getSurfaceServ(), r.getSurfaceTat(),
                    r.getAirService(), r.getAirTat(), r.getAirCargoTat(), r.getSurfaceCargoTat(), r.getAirCargoOda(),
                    r.getSurfaceCargoOda(), r.getPremiumService(), r.getPremiumTat(),
                    live == null ? null : live.premium().serviceable(), live == null ? null : live.airCargo().serviceable(),
                    live == null ? null : live.surfaceCargo().serviceable(),
                    live == null ? null : live.premium().oda(), live == null ? null : live.airCargo().oda(),
                    live == null ? null : live.surfaceCargo().oda(),
                    live == null ? null : live.premium().tatHours(), live == null ? null : live.airCargo().tatHours(),
                    live == null ? null : live.surfaceCargo().tatHours(),
                    live != null);
        }
    }

    @GetMapping
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The pincode master sheet, page by page",
            description = "Sheet order. `search` matches pincode, city or state; `region` and `zone` filter exactly. "
                    + "`size` up to 500 (default 50).")
    public ApiResponse<PageResponse<SheetRowResponse>> list(@RequestParam(required = false) String search,
                                                            @RequestParam(required = false) String region,
                                                            @RequestParam(required = false) String zone,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "50") int size) {
        String q = search == null || search.isBlank() ? null : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
        var pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 500));
        var found = rows.search(q, blankToNull(region), blankToNull(zone), pageable);
        var live = pincodes.findByCodes(found.getContent().stream().map(PincodeMasterSheetRow::getPincode)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet()));
        return ApiResponse.success(PageResponse.from(found,
                r -> SheetRowResponse.of(r, LiveCoverage.of(live.get(r.getPincode())))));
    }

    @org.springframework.web.bind.annotation.PutMapping("/{pincode}/coverage")
    @Operation(summary = "Change Premium, Air Cargo or Surface Cargo coverage at a pincode",
            description = "On/off, ODA and TAT hours; each field left out stays as it is. Changes the pincode master (what "
                    + "bookings check), never the sheet's own text. Super admin or company admin; audited like any pincode edit.")
    public ApiResponse<LiveCoverage> switchCoverage(@org.springframework.web.bind.annotation.PathVariable String pincode,
                                                    @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody SwitchRequest request) {
        return ApiResponse.success(LiveCoverage.of(pincodes.setNetworkCoverage(pincode, request.network(), request.serviceable(),
                        request.oda(), request.tatHours())),
                "%s updated at %s".formatted(label(request.network()), pincode));
    }

    private static String label(com.courier.modules.master.domain.ServiceNetwork n) {
        return switch (n) {
            case PREMIUM -> "Premium";
            case AIR_CARGO -> "Air Cargo";
            case SURFACE_CARGO -> "Surface Cargo";
            default -> n.name();
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
