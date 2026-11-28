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

    /** A network's live state at a pincode in the pincode master: on, off, or null when not set (follows the pincode). */
    public record LiveCoverage(Boolean premium, Boolean airCargo, Boolean surfaceCargo) {
        static LiveCoverage of(com.courier.modules.master.domain.Pincode p) {
            if (p == null) return null;
            return new LiveCoverage(p.coverage(com.courier.modules.master.domain.ServiceNetwork.PREMIUM).serviceable(),
                    p.coverage(com.courier.modules.master.domain.ServiceNetwork.AIR_CARGO).serviceable(),
                    p.coverage(com.courier.modules.master.domain.ServiceNetwork.SURFACE_CARGO).serviceable());
        }
    }

    public record SwitchRequest(@jakarta.validation.constraints.NotNull com.courier.modules.master.domain.ServiceNetwork network,
                                boolean serviceable) {
    }

    /** One sheet row, column for column. Every value is the text the sheet shows. */
    public record SheetRowResponse(Integer rowNo, String pincode, String city, String state, String region, String zone,
                                   String docIsOda, String docServ, String docTat, String surfaceServ,
                                   String surfaceTat, String airService, String airTat, String airCargoTat,
                                   String surfaceCargoTat, String airCargoOda, String surfaceCargoOda, String premiumService,
                                   String premiumTat,
                                   /** Live, from the pincode master: true on, false off, null not set or pincode not on file. */
                                   Boolean livePremium, Boolean liveAirCargo, Boolean liveSurfaceCargo) {
        static SheetRowResponse of(PincodeMasterSheetRow r, LiveCoverage live) {
            return new SheetRowResponse(r.getRowNo(), r.getPincode(), r.getCity(), r.getState(), r.getRegion(),
                    r.getZone(), r.getDocIsOda(), r.getDocServ(), r.getDocTat(), r.getSurfaceServ(), r.getSurfaceTat(),
                    r.getAirService(), r.getAirTat(), r.getAirCargoTat(), r.getSurfaceCargoTat(), r.getAirCargoOda(),
                    r.getSurfaceCargoOda(), r.getPremiumService(), r.getPremiumTat(),
                    live == null ? null : live.premium(), live == null ? null : live.airCargo(),
                    live == null ? null : live.surfaceCargo());
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
    @Operation(summary = "Switch Premium, Air Cargo or Surface Cargo on or off at a pincode",
            description = "Changes the pincode master (what bookings check), keeping that network's ODA and TAT. The sheet's own "
                    + "text is not changed. Super admin or company admin; audited like any pincode edit.")
    public ApiResponse<LiveCoverage> switchCoverage(@org.springframework.web.bind.annotation.PathVariable String pincode,
                                                    @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody SwitchRequest request) {
        return ApiResponse.success(LiveCoverage.of(pincodes.setNetworkServiceable(pincode, request.network(), request.serviceable())),
                "%s %s at %s".formatted(label(request.network()), request.serviceable() ? "on" : "off", pincode));
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
