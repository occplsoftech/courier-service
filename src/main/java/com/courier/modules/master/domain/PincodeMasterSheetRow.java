package com.courier.modules.master.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * One row of OCCPL's pincode master sheet, exactly as given (V176): every cell is the text Excel shows. Read only;
 * the working data (zones, regional zones, coverage) lives on {@link Pincode}.
 */
@Entity
@Immutable
@Getter
@NoArgsConstructor
@Table(name = "pincode_master_sheet")
public class PincodeMasterSheetRow {

    /** The Excel row number (the header is row 1). */
    @Id
    @Column(name = "row_no")
    private Integer rowNo;

    @Column(name = "pincode") private String pincode;
    @Column(name = "city") private String city;
    @Column(name = "state") private String state;
    @Column(name = "region") private String region;
    @Column(name = "zone") private String zone;
    @Column(name = "doc_is_oda") private String docIsOda;
    @Column(name = "doc_serv") private String docServ;
    @Column(name = "doc_tat") private String docTat;
    @Column(name = "surface_serv") private String surfaceServ;
    @Column(name = "surface_tat") private String surfaceTat;
    @Column(name = "air_service") private String airService;
    @Column(name = "air_tat") private String airTat;
    // V185 columns.
    @Column(name = "air_cargo_tat") private String airCargoTat;
    @Column(name = "surface_cargo_tat") private String surfaceCargoTat;
    @Column(name = "air_cargo_oda") private String airCargoOda;
    @Column(name = "surface_cargo_oda") private String surfaceCargoOda;
    @Column(name = "premium_service") private String premiumService;
    @Column(name = "premium_tat") private String premiumTat;
}
