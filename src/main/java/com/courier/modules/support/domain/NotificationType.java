package com.courier.modules.support.domain;

/** What a {@link Notification} is about. Mirrors the spec's own event list, minus
 *  {@code TICKET_CREATED} (no single clear recipient without a company-admin
 *  directory lookup this module doesn't have — a documented gap, not an oversight). */
public enum NotificationType {
    TICKET_ASSIGNED,
    TICKET_REASSIGNED,
    TICKET_ESCALATED,
    NEW_REPLY,
    INTERNAL_UPDATE,
    STATUS_CHANGED,
    PRIORITY_CHANGED,
    TICKET_RESOLVED,
    TICKET_CLOSED,
    TICKET_REOPENED,
    SLA_APPROACHING,
    SLA_BREACHED,

    // --- follow-up management (V44), reusing this same in-app feed rather than
    // building a second notification architecture — see FollowUpServiceImpl/
    // FollowUpSweepJob.
    FOLLOWUP_ASSIGNED,
    FOLLOWUP_DUE_TODAY,
    FOLLOWUP_OVERDUE,
    FOLLOWUP_URGENT,

    // --- cash handover (finance, V191): same in-app feed. SUBMITTED goes to the branch's approvers; the rest to the
    // user who handed the cash over. The web opens Reports → User Performance → Cash handovers for all four.
    CASH_HANDOVER_SUBMITTED,
    CASH_HANDOVER_APPROVED,
    CASH_HANDOVER_REJECTED,
    CASH_HANDOVER_VOIDED
}
