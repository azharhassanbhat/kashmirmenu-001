package com.kashmirmenu.app.analytics;

import java.util.List;

/** Plain-language definition of every dashboard metric, shown next to the numbers. */
public final class Definitions {

    private Definitions() {
    }

    public static final List<AnalyticsOverview.Definition> ALL = List.of(
            new AnalyticsOverview.Definition("gross", "Collected (gross)",
                    "Sum of payment amounts whose status is Successful, Partially refunded or Refunded, "
                            + "dated by when the payment was received. Pending, failed and cancelled payments are excluded."),
            new AnalyticsOverview.Definition("refunds", "Refunds & reversals",
                    "Sum of refund records created in the period (counted on the day the refund was issued), "
                            + "for payments that match the active filters."),
            new AnalyticsOverview.Definition("net", "Net collections",
                    "Collected (gross) minus refunds issued in the same period."),
            new AnalyticsOverview.Definition("transactions", "Successful transactions",
                    "Number of captured payment records. An order paid with two methods counts as two transactions."),
            new AnalyticsOverview.Definition("uniqueOrders", "Orders paid",
                    "Number of distinct orders with at least one captured payment in the period."),
            new AnalyticsOverview.Definition("average", "Average transaction value",
                    "Collected (gross) divided by successful transactions."),
            new AnalyticsOverview.Definition("sales", "Gross sales",
                    "Total of orders placed in the period, excluding cancelled orders. It is a billing figure "
                            + "and is not reduced by refunds; it is hidden while payment filters are active."),
            new AnalyticsOverview.Definition("tax", "Tax in gross sales",
                    "Gross sales include any tax and service charge billed to guests. Tax added on top of menu "
                            + "prices, and tax already inside menu prices when the restaurant's prices include it, "
                            + "are each taken from the rates stored on the order when it was placed."),
            new AnalyticsOverview.Definition("salesExTax", "Sales excluding tax",
                    "Gross sales less all tax. Tax is owed onward and is not income, so this is the figure "
                            + "that shows what dishes and the service charge earned. The service charge stays in it."),
            new AnalyticsOverview.Definition("share", "Share of collections",
                    "A method's (or provider's) collected amount divided by total collected amount in the same period."),
            new AnalyticsOverview.Definition("provider", "UPI app / wallet",
                    "Taken from the provider recorded when the payment was confirmed. Payments without a "
                            + "recorded provider are reported as Unknown, never guessed."),
            new AnalyticsOverview.Definition("comparison", "Change vs previous period",
                    "Compared with the same elapsed time of the preceding period, e.g. today so far versus "
                            + "yesterday up to the same time. Shown only when the previous period has data."),
            new AnalyticsOverview.Definition("pending", "Pending payments",
                    "Payments chosen or started but not yet confirmed by staff. Not part of collected revenue."),
            new AnalyticsOverview.Definition("timezone", "Day boundaries",
                    "All days and hours use the restaurant's configured business time zone.")
    );
}
