package com.courier.modules.master.infrastructure;

/**
 * Physical table names, for the one place that needs them: the soft-delete-aware
 * uniqueness check.
 *
 * <p>Constants rather than {@code @Table} lookups so that the allow-list in
 * {@link MasterUniquenessChecker} and the value a service passes are the same literal,
 * checked by the compiler.
 */
public final class MasterTable {

    public static final String COUNTRIES = "master_countries";
    public static final String STATES = "master_states";
    public static final String DISTRICTS = "master_districts";
    public static final String CITIES = "master_cities";
    public static final String AREAS = "master_areas";
    public static final String PINCODES = "master_pincodes";
    public static final String VEHICLE_TYPES = "master_vehicle_types";
    public static final String PACKAGE_TYPES = "master_package_types";
    public static final String SERVICE_TYPES = "master_service_types";
    public static final String PAYMENT_MODES = "master_payment_modes";
    public static final String WEIGHT_SLABS = "master_weight_slabs";
    public static final String ROUTES = "master_routes";
    public static final String REGIONAL_ZONES = "master_regional_zones";

    private MasterTable() {
    }
}
