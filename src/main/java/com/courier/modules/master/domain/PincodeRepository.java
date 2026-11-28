package com.courier.modules.master.domain;

import java.util.List;
import java.util.UUID;

/**
 * Pincodes.
 *
 * <p>The leaf of the hierarchy, so it is only ever counted <i>by</i> an area's delete
 * check, never the one doing the counting.
 */
public interface PincodeRepository extends MasterDataRepository<Pincode> {

    long countByCompanyIdAndAreaId(UUID companyId, UUID areaId);

    List<Pincode> findByCompanyIdAndAreaIdOrderByCodeAsc(UUID companyId, UUID areaId);

    List<Pincode> findByCompanyIdAndCodeIn(UUID companyId, java.util.Collection<String> codes);
}
