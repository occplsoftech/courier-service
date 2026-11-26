package com.courier.modules.manifest.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.SQLRestriction;

/**
 * An outside transporter or agent a branch hands parcels to at outscan (SELF VEHICLE, NIGHT BIRD, S T LOGISTICS ...),
 * as on the earlier OCCPL portal's Scan → Assign. One list per company (Masters → Co-Loaders).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "co_loaders")
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class CoLoader extends CompanyOwnedEntity {

    @Column(name = "name", nullable = false, length = 150) private String name;
    @Column(name = "contact_person", length = 150) private String contactPerson;
    @Column(name = "phone", length = 20) private String phone;
    @Column(name = "notes", length = 500) private String notes;
    @Column(name = "active", nullable = false) private boolean active = true;
}
