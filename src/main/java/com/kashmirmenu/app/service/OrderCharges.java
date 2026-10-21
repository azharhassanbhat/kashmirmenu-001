package com.kashmirmenu.app.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Service charge and tax on an order. Pure arithmetic with no database, so every rule is testable.
 *
 * <p>Nothing here knows about any country's tax law and no rate is built in: the restaurant supplies
 * its own rates, and with the defaults (0 and 0) an order's total is exactly subtotal minus discount,
 * as it was before this feature existed. Which taxes apply to a restaurant, and whether its prices
 * should include them, is for the restaurant and its accountant to decide.
 *
 * <p>Order of working, each step rounded to 2 decimal places (half up):
 * <ol>
 *   <li><b>net</b> = subtotal - discount. A coupon reduces what is taxed, never the other way round.</li>
 *   <li><b>service charge</b> = net x service rate.</li>
 *   <li><b>EXCLUSIVE pricing</b> (menu prices do not include tax): tax = (net + service charge) x tax
 *       rate, added on top. The service charge is taxed, as it is part of what the guest pays the
 *       restaurant.</li>
 *   <li><b>INCLUSIVE pricing</b> (menu prices already include tax): nothing is added. The tax inside
 *       the dishes is worked out for display only: net x rate / (100 + rate). A service charge on top
 *       of inclusive prices is added untaxed here; a restaurant whose service charge is taxable should
 *       use exclusive pricing.</li>
 * </ol>
 */
public final class OrderCharges {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private OrderCharges() {
    }

    /**
     * @param net            subtotal minus discount, never negative
     * @param serviceCharge  added to the total
     * @param tax            added to the total (always 0 with inclusive pricing)
     * @param taxIncluded    tax contained in the dish prices, for display (always 0 with exclusive pricing)
     * @param total          what the guest owes
     */
    public record Breakdown(BigDecimal net, BigDecimal serviceCharge, BigDecimal tax,
                            BigDecimal taxIncluded, BigDecimal total) {
    }

    public static Breakdown compute(BigDecimal subtotal, BigDecimal discount,
                                    BigDecimal serviceRatePercent, BigDecimal taxRatePercent,
                                    boolean pricesIncludeTax) {

        BigDecimal serviceRate = checked(serviceRatePercent, "service charge rate");
        BigDecimal taxRate = checked(taxRatePercent, "tax rate");

        BigDecimal net = money(orZero(subtotal).subtract(orZero(discount)));
        if (net.signum() < 0) {
            net = money(BigDecimal.ZERO);
        }

        BigDecimal service = percentOf(net, serviceRate);

        BigDecimal tax = money(BigDecimal.ZERO);
        BigDecimal taxIncluded = money(BigDecimal.ZERO);

        if (pricesIncludeTax) {
            taxIncluded = net.multiply(taxRate).divide(HUNDRED.add(taxRate), 2, RoundingMode.HALF_UP);
        } else {
            tax = percentOf(net.add(service), taxRate);
        }

        BigDecimal total = money(net.add(service).add(tax));
        return new Breakdown(net, service, tax, taxIncluded, total);
    }

    private static BigDecimal percentOf(BigDecimal amount, BigDecimal percent) {
        return amount.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** A missing rate means none. A negative one, or one over 100%, is a mistake and is refused. */
    private static BigDecimal checked(BigDecimal rate, String what) {
        BigDecimal value = orZero(rate);
        if (value.signum() < 0 || value.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("The " + what + " must be between 0 and 100.");
        }
        return value;
    }
}
