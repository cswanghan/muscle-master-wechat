package com.jisuodashi.card;

import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.rbac.Audited;
import com.jisuodashi.rbac.RequirePerm;
import com.jisuodashi.rbac.StoreScoped;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前台储值。收钱那一步走线下（现金 / 扫码），这里只记账 ——
 * 把充值也接进微信支付是另一件事，先不混在一起。
 */
@RestController
@RequestMapping("/api/v1/f/cards")
public class FrontCardController {

    private final CardService cards;

    public FrontCardController(CardService cards) {
        this.cards = cards;
    }

    @PostMapping("/topup")
    @StoreScoped
    @RequirePerm("frontdesk:order:*")
    @Audited(action = "CARD_TOPUP", resourceType = "CARD")
    public ApiResponse<CardDtos.TopUpResponse> topUp(@RequestBody CardDtos.TopUpRequest request) {
        return ApiResponse.ok(cards.topUpByPhone(request));
    }

    @PostMapping("/lookup")
    @StoreScoped
    @RequirePerm("frontdesk:order:*")
    public ApiResponse<CardDtos.Wallet> lookup(@RequestParam("phone") String phone) {
        return ApiResponse.ok(cards.walletByPhone(phone));
    }
}
