package com.courier.modules.customerportal.rates.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Where a booked shipment is, in one vocabulary for every courier. Couriers word their statuses differently
 * ("Manifested", "Pickup Scheduled", "In Transit", "Reached at hub", "RTO Initiated"…), so {@link #of} reads keywords.
 * The portal's status flow (e-commerce-business {@code shipment-progress.ts}) uses the same rules; keep them in step.
 */
public enum DeliveryStage {
    AWAITING_PICKUP, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, RETURNED, CANCELLED;

    private static final Pattern RETURNED_P = Pattern.compile("\\b(rto|return(ed)?|returning)\\b");
    private static final Pattern NOT_DELIVERED = Pattern.compile("\\b(un|not |non[- ]?)delivered\\b|delivery (failed|attempt)|undeliver");
    private static final Pattern DELIVERED_P = Pattern.compile("\\bdelivered\\b");
    private static final Pattern NOT_PICKED = Pattern.compile("not picked|pickup (failed|pending|scheduled|rescheduled)|awaiting pickup");
    private static final Pattern OFD = Pattern.compile("out for delivery|\\bofd\\b|dispatched for delivery");
    private static final Pattern TRANSIT = Pattern.compile("in[- ]?transit|transit|reached|arrived|dispatched|shipped|departed|forwarded|handed (over )?to|connected|bagged|hub");
    private static final Pattern PICKED = Pattern.compile("picked|pickup (done|complete)|collected|received at origin|origin scan");
    private static final Pattern BOOKED = Pattern.compile("manifest|pickup|booked|created|scheduled|pending|ready|\\bnew\\b");

    /** The stage a courier's status text describes, or null when the words say nothing we recognise. */
    public static DeliveryStage of(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.toLowerCase(Locale.ROOT);
        if (RETURNED_P.matcher(t).find()) return RETURNED;
        if (t.contains("cancel")) return CANCELLED;
        if (NOT_DELIVERED.matcher(t).find()) return OUT_FOR_DELIVERY;
        if (DELIVERED_P.matcher(t).find()) return DELIVERED;
        if (NOT_PICKED.matcher(t).find()) return AWAITING_PICKUP;
        if (OFD.matcher(t).find()) return OUT_FOR_DELIVERY;
        if (TRANSIT.matcher(t).find()) return IN_TRANSIT;
        if (PICKED.matcher(t).find()) return PICKED_UP;
        if (BOOKED.matcher(t).find()) return AWAITING_PICKUP;
        return null;
    }

    /**
     * Nothing more will happen: stop asking. A return is finished only once it is back ("RTO Delivered"); until then it is
     * still moving.
     */
    public static boolean finished(DeliveryStage stage, String text) {
        if (stage == DELIVERED || stage == CANCELLED) {
            return true;
        }
        return stage == RETURNED && text != null && DELIVERED_P.matcher(text.toLowerCase(Locale.ROOT)).find();
    }
}
