-- =============================================================================
-- V138 — Zone tariff, Phase 1: company tariff zones and the places in them
--
-- A tariff zone (PUNE, METRO, KERALA…) belongs to the COMPANY, not to a customer (compare
-- customer_pricing_zones, V106). zone_usage says whether shipments start there (FROM), are
-- delivered there (TO) or both. Rate cards priced by these zones come in Phase 2.
--
-- A location is one state, district, city or pincode. The ids above its scope are stored too
-- (a CITY row also carries its district and state). Geography ids point at the global masters:
-- validated in the service, no physical FK, like customer_zone_locations. "A place may sit in
-- only one zone per direction" and "code unique per company" are enforced in the service over
-- live rows, so a soft-deleted row does not reserve anything.
-- =============================================================================

CREATE TABLE tariff_zones (
    id            BINARY(16)   NOT NULL,
    company_id    BINARY(16)   NOT NULL,
    code          VARCHAR(30)  NOT NULL,
    name          VARCHAR(100) NOT NULL,
    zone_usage    VARCHAR(10)  NOT NULL DEFAULT 'TO',
    description   VARCHAR(255) NULL,
    display_order INT          NOT NULL DEFAULT 0,
    status        VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',

    created_at TIMESTAMP(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL, updated_by BINARY(16) NULL,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL, deleted_by BINARY(16) NULL,
    version    BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    KEY idx_tz_company_code (company_id, code),
    KEY idx_tz_company_status (company_id, status),
    CONSTRAINT ck_tz_usage CHECK (zone_usage IN ('FROM', 'TO', 'BOTH')),
    CONSTRAINT ck_tz_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Company tariff zones: named groups of states/districts/cities/pincodes';

CREATE TABLE tariff_zone_locations (
    id          BINARY(16) NOT NULL,
    company_id  BINARY(16) NOT NULL,
    zone_id     BINARY(16) NOT NULL,
    scope       VARCHAR(10) NOT NULL,
    state_id    BINARY(16) NULL,
    district_id BINARY(16) NULL,
    city_id     BINARY(16) NULL,
    pincode     VARCHAR(6)  NULL,

    created_at TIMESTAMP(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL, updated_by BINARY(16) NULL,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL, deleted_by BINARY(16) NULL,
    version    BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    KEY idx_tzl_company_zone (company_id, zone_id),
    KEY idx_tzl_company_state (company_id, scope, state_id),
    KEY idx_tzl_company_district (company_id, scope, district_id),
    KEY idx_tzl_company_city (company_id, scope, city_id),
    KEY idx_tzl_company_pincode (company_id, scope, pincode),
    CONSTRAINT fk_tzl_zone FOREIGN KEY (zone_id) REFERENCES tariff_zones (id) ON DELETE RESTRICT,
    CONSTRAINT ck_tzl_scope CHECK (
        (scope = 'STATE'    AND state_id IS NOT NULL) OR
        (scope = 'DISTRICT' AND district_id IS NOT NULL) OR
        (scope = 'CITY'     AND city_id IS NOT NULL) OR
        (scope = 'PINCODE'  AND pincode IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'States, districts, cities and pincodes that belong to a tariff zone';

-- --- permissions: one TARIFF module (MASTER shape) for zones now and rate cards later ----------
INSERT INTO permissions (id, permission_code, permission_name, module, resource, action,
                         display_order, required_feature_flag, is_system_permission, status,
                         created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), d.code, d.name, d.module, d.resource, d.action,
       d.display_order, NULL, TRUE, 'ACTIVE',
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (
  SELECT 'TARIFF_CREATE' AS code, 'Create Zone Tariff' AS name, 'TARIFF' AS module, 'tariff' AS resource, 'CREATE' AS action, 1260 AS display_order
  UNION ALL SELECT 'TARIFF_READ', 'View Zone Tariff', 'TARIFF', 'tariff', 'READ', 1261
  UNION ALL SELECT 'TARIFF_UPDATE', 'Update Zone Tariff', 'TARIFF', 'tariff', 'UPDATE', 1262
  UNION ALL SELECT 'TARIFF_DELETE', 'Delete Zone Tariff', 'TARIFF', 'tariff', 'DELETE', 1263
  UNION ALL SELECT 'TARIFF_SEARCH', 'Search Zone Tariff', 'TARIFF', 'tariff', 'SEARCH', 1264
  UNION ALL SELECT 'TARIFF_EXPORT', 'Export Zone Tariff', 'TARIFF', 'tariff', 'EXPORT', 1265
  UNION ALL SELECT 'TARIFF_IMPORT', 'Import Zone Tariff', 'TARIFF', 'tariff', 'IMPORT', 1266
  UNION ALL SELECT 'TARIFF_ACTIVATE', 'Activate Zone Tariff', 'TARIFF', 'tariff', 'ACTIVATE', 1267
  UNION ALL SELECT 'TARIFF_DEACTIVATE', 'Deactivate Zone Tariff', 'TARIFF', 'tariff', 'DEACTIVATE', 1268
) AS d;

-- Existing companies' COMPANY_ADMIN roles get the new codes (the JWT is built from role_permissions
-- rows). A deliberately removed grant (soft-deleted row) is left alone. Same pattern as V107.
INSERT INTO role_permissions
    (id, company_id, role_id, permission_id, permission_code, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), r.company_id, r.id, p.id, p.permission_code, NOW(6), NOW(6), 0, 0
FROM company_roles r
JOIN permissions p ON p.module = 'TARIFF' AND p.deleted = 0
WHERE r.role_code = 'COMPANY_ADMIN' AND r.deleted = 0 AND r.status = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM role_permissions x
                  WHERE x.company_id = r.company_id AND x.role_id = r.id AND x.permission_id = p.id);
