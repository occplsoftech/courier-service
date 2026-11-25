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

/** OCCPL's pincode master sheet exactly as given (V176), read only. */
@RestController
@RequestMapping("/api/v1/global-masters/pincode-sheet")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Global Masters - Pincode Sheet", description = "The pincode master sheet, verbatim")
public class PincodeMasterSheetController {

    private final PincodeMasterSheetRepository rows;

    /** One sheet row, column for column. Every value is the text the sheet shows. */
    public record SheetRowResponse(Integer rowNo, String pincode, String city, String state, String region, String zone,
                                   String docIsOda, String docServ, String docTat, String surfaceServ,
                                   String surfaceTat, String airService, String airTat) {
        static SheetRowResponse of(PincodeMasterSheetRow r) {
            return new SheetRowResponse(r.getRowNo(), r.getPincode(), r.getCity(), r.getState(), r.getRegion(),
                    r.getZone(), r.getDocIsOda(), r.getDocServ(), r.getDocTat(), r.getSurfaceServ(), r.getSurfaceTat(),
                    r.getAirService(), r.getAirTat());
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
        return ApiResponse.success(PageResponse.from(rows.search(q, blankToNull(region), blankToNull(zone), pageable),
                SheetRowResponse::of));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
