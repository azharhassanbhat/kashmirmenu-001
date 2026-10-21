package com.kashmirmenu.app.enums;

/** What a settlement covers. The guest chooses; nothing is combined automatically. */
public enum SettlementKind {
    /** The paying guest's own orders only ("Pay My Bill"). */
    SEPARATE,
    /** Every guest's outstanding orders at the table ("Pay Entire Table Bill"). */
    COMBINED
}
