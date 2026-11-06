package com.courier.modules.communication.domain;

import java.util.List;

/**
 * Seed content for {@link CommunicationEventType#DEFAULT_ENABLED} x every
 * {@link CommunicationChannel} — 12 rows, written once per company by {@code
 * CommunicationTemplateServiceImpl.getOrSeedDefaults} the first time that company's
 * templates are read. Not seeded for {@code SHIPMENT_RECEIVED}/{@code SHIPMENT_CANCELLED}/
 * the two RTO events — those exist for a Company Admin to create if they want them, per the
 * brief's own "Default Events" list naming only four.
 */
public final class DefaultCommunicationTemplates {

    private DefaultCommunicationTemplates() {
    }

    public record Seed(CommunicationEventType eventType, CommunicationChannel channel,
                String templateName, String subject, String content) {
    }

    /** The one non-shipment default: the owner's morning summary, WhatsApp only. */
    public static final Seed DAILY_SUMMARY = new Seed(CommunicationEventType.DAILY_BUSINESS_SUMMARY,
            CommunicationChannel.WHATSAPP, "daily_business_summary", null,
            "Daily business summary for {{companyName}} - {{reportDate}}\n\n"
                    + "Bookings: {{bookings}}\n"
                    + "Revenue: Rs {{revenue}}\n"
                    + "Wallet recharges: Rs {{rechargeAmount}} ({{rechargeCount}} recharges)\n"
                    + "Delivered: {{delivered}}\n"
                    + "Cancelled: {{cancelled}}\n"
                    + "COD / To-Pay settled: Rs {{codToPaySettled}}\n\n"
                    + "Right now: {{inTransit}} in transit, {{outForDelivery}} out for delivery.\n\n"
                    + "Have a great day!");

    public static List<Seed> all() {
        return List.of(
                new Seed(CommunicationEventType.SHIPMENT_BOOKED, CommunicationChannel.WHATSAPP,
                        "shipment_booked", null,
                        "Hi {{customerName}}, your shipment {{shipmentNumber}} (AWB {{trackingNumber}}) "
                                + "from {{pickupLocation}} to {{deliveryLocation}} has been booked with "
                                + "{{companyName}}. Track it here: {{trackingUrl}} - thank you!"),
                new Seed(CommunicationEventType.SHIPMENT_BOOKED, CommunicationChannel.SMS,
                        "Shipment Booked - SMS", null,
                        "{{companyName}}: Shipment {{shipmentNumber}} booked, AWB {{trackingNumber}}. "
                                + "Track: {{trackingUrl}}"),
                new Seed(CommunicationEventType.SHIPMENT_BOOKED, CommunicationChannel.EMAIL,
                        "Shipment Booked - Email", "Your shipment {{shipmentNumber}} has been booked",
                        "<p>Hi {{customerName}},</p><p>Your shipment <b>{{shipmentNumber}}</b> "
                                + "(AWB {{trackingNumber}}) from {{pickupLocation}} to {{deliveryLocation}} "
                                + "has been booked with {{companyName}}.</p>"
                                + "<p>Expected delivery: {{deliveryDate}}</p>"
                                + "<p><a href=\"{{trackingUrl}}\">Track your shipment</a></p>"),

                new Seed(CommunicationEventType.SHIPMENT_PICKED_UP, CommunicationChannel.WHATSAPP,
                        "shipment_picked_up", null,
                        "Hi {{customerName}}, your parcel {{trackingNumber}} for {{deliveryLocation}} has been "
                                + "collected by {{companyName}}. Track it here: {{trackingUrl}} - thank you!"),
                new Seed(CommunicationEventType.SHIPMENT_PICKED_UP, CommunicationChannel.SMS,
                        "Shipment Picked Up - SMS", null,
                        "{{companyName}}: Parcel {{trackingNumber}} collected, heading to {{deliveryLocation}}. "
                                + "Track: {{trackingUrl}}"),
                new Seed(CommunicationEventType.SHIPMENT_PICKED_UP, CommunicationChannel.EMAIL,
                        "Shipment Picked Up - Email", "Your parcel {{shipmentNumber}} has been collected",
                        "<p>Hi {{customerName}},</p><p>Your parcel <b>{{shipmentNumber}}</b> "
                                + "(AWB {{trackingNumber}}) for {{deliveryLocation}} has been collected by {{companyName}}.</p>"
                                + "<p><a href=\"{{trackingUrl}}\">Track your shipment</a></p>"),

                new Seed(CommunicationEventType.SHIPMENT_DISPATCHED, CommunicationChannel.WHATSAPP,
                        "shipment_dispatched", null,
                        "Hi {{customerName}}, your shipment {{trackingNumber}} has been dispatched from "
                                + "{{pickupLocation}} and is on its way to {{deliveryLocation}}. "
                                + "Track it here: {{trackingUrl}} - thank you!"),
                new Seed(CommunicationEventType.SHIPMENT_DISPATCHED, CommunicationChannel.SMS,
                        "Shipment Dispatched - SMS", null,
                        "{{companyName}}: Shipment {{trackingNumber}} dispatched, heading to "
                                + "{{deliveryLocation}}. Track: {{trackingUrl}}"),
                new Seed(CommunicationEventType.SHIPMENT_DISPATCHED, CommunicationChannel.EMAIL,
                        "Shipment Dispatched - Email", "Your shipment {{shipmentNumber}} is on its way",
                        "<p>Hi {{customerName}},</p><p>Your shipment <b>{{shipmentNumber}}</b> "
                                + "(AWB {{trackingNumber}}) has been dispatched and is on its way to "
                                + "{{deliveryLocation}}.</p><p><a href=\"{{trackingUrl}}\">Track your shipment</a></p>"),

                new Seed(CommunicationEventType.OUT_FOR_DELIVERY, CommunicationChannel.WHATSAPP,
                        "out_for_delivery", null,
                        "Hi {{receiverName}}, your shipment {{trackingNumber}} is out for delivery today. "
                                + "Track it here: {{trackingUrl}} - thank you!"),
                new Seed(CommunicationEventType.OUT_FOR_DELIVERY, CommunicationChannel.SMS,
                        "Out For Delivery - SMS", null,
                        "{{companyName}}: Shipment {{trackingNumber}} is out for delivery. "
                                + "Track: {{trackingUrl}}"),
                new Seed(CommunicationEventType.OUT_FOR_DELIVERY, CommunicationChannel.EMAIL,
                        "Out For Delivery - Email", "Your shipment {{shipmentNumber}} is out for delivery",
                        "<p>Hi {{receiverName}},</p><p>Your shipment <b>{{shipmentNumber}}</b> "
                                + "(AWB {{trackingNumber}}) is out for delivery today.</p>"
                                + "<p><a href=\"{{trackingUrl}}\">Track your shipment</a></p>"),

                new Seed(CommunicationEventType.SHIPMENT_DELIVERED, CommunicationChannel.WHATSAPP,
                        "shipment_delivered", null,
                        "Hi {{receiverName}}, your shipment {{trackingNumber}} has been delivered. "
                                + "Amount: {{amount}}. Proof of delivery: {{podUrl}}. Thank you for choosing "
                                + "{{companyName}}!"),
                new Seed(CommunicationEventType.SHIPMENT_DELIVERED, CommunicationChannel.SMS,
                        "Shipment Delivered - SMS", null,
                        "{{companyName}}: Shipment {{trackingNumber}} delivered. Amount: {{amount}}. "
                                + "Thank you!"),
                new Seed(CommunicationEventType.SHIPMENT_DELIVERED, CommunicationChannel.EMAIL,
                        "Shipment Delivered - Email", "Your shipment {{shipmentNumber}} has been delivered",
                        "<p>Hi {{receiverName}},</p><p>Your shipment <b>{{shipmentNumber}}</b> "
                                + "(AWB {{trackingNumber}}) has been delivered. Amount: {{amount}}.</p>"
                                + "<p>Proof of delivery: <a href=\"{{podUrl}}\">view</a></p>"
                                + "<p>Thank you for choosing {{companyName}}!</p>"),
                new Seed(CommunicationEventType.DELIVERY_FAILED, CommunicationChannel.WHATSAPP,
                        "delivery_failed", null,
                        "Hi {{customerName}}, we could not deliver your shipment {{trackingNumber}} to {{receiverName}} today. "
                                + "Please tell {{companyName}} what to do next: {{trackingUrl}} - thank you!"),
                new Seed(CommunicationEventType.DELIVERY_FAILED, CommunicationChannel.SMS,
                        "Delivery Failed - SMS", null,
                        "{{companyName}}: Shipment {{trackingNumber}} could not be delivered today. Track: {{trackingUrl}}"),
                new Seed(CommunicationEventType.DELIVERY_FAILED, CommunicationChannel.EMAIL,
                        "Delivery Failed - Email", "We could not deliver your shipment {{shipmentNumber}}",
                        "<p>Hi {{customerName}},</p><p>We could not deliver your shipment <b>{{shipmentNumber}}</b> "
                                + "(AWB {{trackingNumber}}) to {{receiverName}} today.</p>"
                                + "<p>Please sign in to your account to choose a new attempt, a new address or a return, "
                                + "or contact {{companyName}}.</p><p><a href=\"{{trackingUrl}}\">Track your shipment</a></p>"),
                DAILY_SUMMARY
        );
    }
}
