package com.jisuodashi.card;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端钱包：余额 + 最近流水。只读。 */
@RestController
@RequestMapping("/api/v1/c/card")
public class CustomerCardController {

    private final CardService cards;

    public CustomerCardController(CardService cards) {
        this.cards = cards;
    }

    @GetMapping
    public ApiResponse<CardDtos.Wallet> mine() {
        return ApiResponse.ok(cards.wallet(AuthContext.requireCustomer().customerId()));
    }
}
