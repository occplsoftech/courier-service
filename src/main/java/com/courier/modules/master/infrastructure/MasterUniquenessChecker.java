package com.courier.modules.master.infrastructure;

import com.courier.shared.domain.TimeOrderedUuid;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Answers "is this code (or this name, in this parent) already taken?" for the master
 * tables — <b>including rows that have been soft deleted</b>.
 *
 * <p>It has to be native, and it has to be here rather than on a repository, for two
 * reasons that pull in the same direction:
 *
 * <ul>
 *   <li>The unique keys in {@code V11} do not mention {@code deleted}. A soft-deleted
 *       country still occupies {@code (company_id, code)}, so a check that cannot see it
 *       reports the code free and the insert then fails with a raw constraint violation —
 *       a 500 where the user should have got a 409.</li>
 *   <li>{@code @SQLRestriction("deleted = false")} hides exactly those rows from every
 *       JPQL query in the module, and there is no way to opt one query out of it.</li>
 * </ul>
 *
 * <p>This mirrors {@code BranchRepository.isCodeTaken}, which solved the same problem the
 * same way; the difference is that twelve tables would have needed twenty-four
 * near-identical native queries, so the SQL is assembled once here instead.
 *
 * <p><b>On assembling SQL by concatenation.</b> Table and column names are never taken
 * from a request. They come from {@link MasterTable} constants written in this module,
 * and both are re-validated below — the table against a closed set, each column against
 * {@code [a-z_]+}. Every <i>value</i> is a bound parameter. The check is deliberately
 * strict rather than trusting the call sites, because the day someone passes a variable
 * here is the day the argument that this is safe stops holding on its own.
 */
@Component
@RequiredArgsConstructor
public class MasterUniquenessChecker {

    /** The only tables this may be pointed at. */
    private static final Set<String> TABLES = Set.of(
            MasterTable.COUNTRIES, MasterTable.STATES, MasterTable.DISTRICTS,
            MasterTable.CITIES, MasterTable.AREAS, MasterTable.PINCODES,
            MasterTable.VEHICLE_TYPES, MasterTable.PACKAGE_TYPES, MasterTable.SERVICE_TYPES,
            MasterTable.PAYMENT_MODES, MasterTable.WEIGHT_SLABS, MasterTable.ROUTES,
            MasterTable.REGIONAL_ZONES);

    private final EntityManager entityManager;

    /**
     * @param table      one of {@link MasterTable}
     * @param companyId   the owning company
     * @param excludeId  the row being updated, so it does not clash with itself; null on create
     * @param columns    column name to value, all of which must match for a row to count.
     *                   String values compare case-insensitively; UUIDs are bound as
     *                   {@code BINARY(16)}; a null value means {@code IS NULL}.
     * @return true when some row — live or soft deleted — already holds this combination
     */
    @Transactional(readOnly = true)
    public boolean isTaken(String table, UUID companyId, UUID excludeId, Map<String, Object> columns) {
        requireKnownTable(table);
        if (companyId == null || columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("A uniqueness check needs a company and at least one column.");
        }

        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ")
                .append(table)
                .append(" WHERE company_id = :companyId AND (:excludeId IS NULL OR id <> :excludeId)");

        Map<String, Object> bindings = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<String, Object> entry : columns.entrySet()) {
            String column = requireValidColumn(entry.getKey());
            Object value = entry.getValue();
            if (value == null) {
                sql.append(" AND ").append(column).append(" IS NULL");
                continue;
            }
            String parameter = "p" + index++;
            if (value instanceof String) {
                sql.append(" AND LOWER(").append(column).append(") = LOWER(:").append(parameter).append(')');
                bindings.put(parameter, value);
            } else if (value instanceof UUID uuid) {
                sql.append(" AND ").append(column).append(" = :").append(parameter);
                bindings.put(parameter, TimeOrderedUuid.toBytes(uuid));
            } else {
                sql.append(" AND ").append(column).append(" = :").append(parameter);
                bindings.put(parameter, value);
            }
        }

        Query query = entityManager.createNativeQuery(sql.toString())
                .setParameter("companyId", TimeOrderedUuid.toBytes(companyId))
                .setParameter("excludeId", TimeOrderedUuid.toBytes(excludeId));
        bindings.forEach(query::setParameter);

        // MySQL returns COUNT(*) as BIGINT. Comparing in Java rather than asking SQL for
        // a boolean is the fix for the defect recorded in CHANGELOG 0.3.0: a repository
        // that declared `boolean` for `COUNT(*) > 0` made every create a 500.
        Number count = (Number) query.getSingleResult();
        return count != null && count.longValue() > 0;
    }

    /** Convenience for the common case: is this code taken in this company? */
    public boolean isCodeTaken(String table, UUID companyId, UUID excludeId, String code) {
        return isTaken(table, companyId, excludeId, Map.of("code", code));
    }

    private static void requireKnownTable(String table) {
        if (!TABLES.contains(table)) {
            throw new IllegalArgumentException("Unknown master table: " + table);
        }
    }

    private static String requireValidColumn(String column) {
        if (column == null || !column.matches("^[a-z][a-z0-9_]*$")) {
            throw new IllegalArgumentException("Illegal column name: " + column);
        }
        return column;
    }
}
