package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.dto.response.DinerBillResponse;
import com.kashmirmenu.app.dto.response.TableBillResponse;
import com.kashmirmenu.app.service.BillService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bills for guests at a table. Read-only; both act as the guest named by the X-Diner-Token header,
 * and answer 404 "session has ended" without a valid one, like the cart endpoints.
 */
@RestController
@RequestMapping("/api/public/tables/{qr}/bill")
@RequiredArgsConstructor
public class PublicBillController {

    private final BillService billService;

    @Operation(summary = "My bill: the calling guest's own orders and what they still owe")
    @GetMapping
    public DinerBillResponse myBill(
            @PathVariable String qr,
            @RequestHeader(value = PublicDinerController.TOKEN_HEADER, required = false) String dinerToken) {
        return billService.myBill(qr, dinerToken);
    }

    @Operation(summary = "Table bill: what the whole visit still owes, by guest (names and amounts only)")
    @GetMapping("/table")
    public TableBillResponse tableBill(
            @PathVariable String qr,
            @RequestHeader(value = PublicDinerController.TOKEN_HEADER, required = false) String dinerToken) {
        return billService.tableBill(qr, dinerToken);
    }
}
