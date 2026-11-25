package com.courier.modules.master.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.SQLRestriction;

/**
 * A pricing region pincodes are grouped into, such as PUNE, X_PUNE, MAHARASHTRA, X_MAHARASHTRA,
 * VIDHARBHA, REST_OF_INDIA or NORTH_EAST (V171).
 *
 * <p>Global, like the pincodes it groups ({@link GlobalMasters}). Not the direction zone a pincode
 * also carries in {@link Pincode#getZone()} (NORTH, SOUTH…): a region spans several directions and a
 * direction holds several regions, so a pincode stores both. A tariff zone can take a whole regional
 * zone as one of its locations.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(
        name = "master_regional_zones",
        uniqueConstraints = @UniqueConstraint(name = "uk_master_regional_zones_code",
                columnNames = {"company_id", "code"}),
        indexes = @Index(name = "idx_master_regional_zones_status", columnList = "company_id, status"))
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class RegionalZone extends MasterDataEntity {

    @Override
    protected void applySpecificInvariants() {
        // code, name and status are all the base class checks; a region has nothing of its own yet
    }
}
