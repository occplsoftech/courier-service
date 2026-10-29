package com.courier.modules.master.application;

/**
 * The Area/City/District names already on file for a pincode — read-only, no postal
 * directory call and no row created if a link is missing. Distinct from
 * {@link PincodeAreaLookupResult}, which can create master rows and is gated accordingly;
 * this is what a printed document (a consignment note's booking/delivery pincode line)
 * needs, safe for any authenticated company user to read.
 */
public record PincodeGeography(String pincodeName, String areaName, String cityName, String districtName, String stateName) {
}
