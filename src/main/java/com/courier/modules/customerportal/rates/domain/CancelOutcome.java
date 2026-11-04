package com.courier.modules.customerportal.rates.domain;

/** What a courier said to a cancel: CANCELLED (now or already), NOT_FOUND (it holds no such order) or FAILED (unclear: do not assume). */
public record CancelOutcome(Result result, String message) {

    public enum Result { CANCELLED, NOT_FOUND, FAILED }

    public static CancelOutcome cancelled(String message) {
        return new CancelOutcome(Result.CANCELLED, message);
    }

    public static CancelOutcome notFound(String message) {
        return new CancelOutcome(Result.NOT_FOUND, message);
    }

    public static CancelOutcome failed(String message) {
        return new CancelOutcome(Result.FAILED, message);
    }
}
