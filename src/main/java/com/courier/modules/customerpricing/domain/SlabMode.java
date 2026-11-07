package com.courier.modules.customerpricing.domain;

/** How a rate card's weight slabs price a parcel. */
public enum SlabMode {
    /** The slab the chargeable weight falls in prices the whole weight (7 kg in 5-10 kg @ 30 = 210). */
    WHOLE,
    /** Each slab prices only the kilos inside it (0-5 @ 40, 5-10 @ 30: 7 kg = 5 x 40 + 2 x 30 = 260). */
    TIERED
}
