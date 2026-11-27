package com.courier.modules.manifest.domain;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class ManifestSpecifications {

    private static final char LIKE_ESCAPE = '\\';

    private ManifestSpecifications() {
    }

    public static Specification<Manifest> matching(ManifestCriteria criteria) {
        ManifestCriteria safe = criteria == null ? ManifestCriteria.none() : criteria;

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (safe.status() != null) {
                predicates.add(cb.equal(root.get("status"), safe.status()));
            }
            if (Boolean.TRUE.equals(safe.sent())) {
                predicates.add(cb.notEqual(root.get("status"), ManifestStatus.CREATED));
            }
            if (safe.bookingBranchId() != null) {
                predicates.add(cb.equal(root.get("bookingBranchId"), safe.bookingBranchId()));
            }
            if (safe.deliveryBranchId() != null) {
                predicates.add(cb.equal(root.get("deliveryBranchId"), safe.deliveryBranchId()));
            }
            if (safe.flowType() != null) {
                predicates.add(cb.equal(root.get("flowType"), safe.flowType()));
            }
            if (safe.search() != null && !safe.search().isBlank()) {
                String pattern = "%" + escapeLike(safe.search().trim().toLowerCase()) + "%";
                predicates.add(cb.like(cb.lower(root.get("manifestNumber")), pattern, LIKE_ESCAPE));
            }

            return predicates.isEmpty()
                    ? cb.conjunction()
                    : cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String escapeLike(String raw) {
        return raw.replace(String.valueOf(LIKE_ESCAPE), LIKE_ESCAPE + "\\")
                .replace("%", LIKE_ESCAPE + "%")
                .replace("_", LIKE_ESCAPE + "_");
    }
}
