package com.courier.modules.master.domain;

/**
 * How one network covers a pincode: whether it delivers there, whether as ODA, and in how many
 * hours. Every part may be null: no per-network data, so the pincode's own serviceable and ODA
 * flags decide (see {@link #serviceableOr} and {@link #odaOr}).
 */
public record NetworkCoverage(Boolean serviceable, Boolean oda, Integer tatHours) {

    public static final NetworkCoverage NONE = new NetworkCoverage(null, null, null);

    public static NetworkCoverage orNone(NetworkCoverage c) {
        return c == null ? NONE : c;
    }

    public boolean serviceableOr(boolean fallback) {
        return serviceable == null ? fallback : serviceable;
    }

    public boolean odaOr(boolean fallback) {
        return oda == null ? fallback : oda;
    }

    public boolean isEmpty() {
        return serviceable == null && oda == null && tatHours == null;
    }
}
