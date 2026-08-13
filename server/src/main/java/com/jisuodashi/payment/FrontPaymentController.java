package com.jisuodashi.payment;

import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.rbac.RequirePerm;
import com.jisuodashi.rbac.StoreScoped;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** D23: front-desk Native QR poll. */
@RestController
@RequestMapping("/api/v1/f/payments")
public class FrontPaymentController {

    private final PaymentService payments;

    public FrontPaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @GetMapping("/{paymentNo}")
    @StoreScoped
    @RequirePerm("frontdesk:order:*")
    public ApiResponse<PaymentDtos.PaymentView> get(@PathVariable("paymentNo") String paymentNo) {
        return ApiResponse.ok(payments.getByPaymentNo(paymentNo));
    }
}
