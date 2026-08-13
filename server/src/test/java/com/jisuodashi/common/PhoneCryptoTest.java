package com.jisuodashi.common;

import com.jisuodashi.auth.CollisionKeys;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhoneCryptoTest {

    @Test
    void encryptRoundTripAndStableHash() {
        AppProperties props = new AppProperties();
        props.getCrypto().setPhonePepper("dev-phone-pepper");
        props.getCrypto().setDekBase64("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        PhoneCrypto crypto = new PhoneCrypto(props);
        PhoneCrypto.PhoneParts parts = crypto.sealMobile("13800138000");
        assertThat(parts.e164()).isEqualTo("+8613800138000");
        assertThat(parts.hash()).hasSize(64);
        assertThat(crypto.decrypt(parts.cipher())).isEqualTo("+8613800138000");
        assertThat(crypto.hashE164("+8613800138000")).isEqualTo(parts.hash());
        assertThat(CollisionKeys.bizKey(parts.hash()).length()).isLessThanOrEqualTo(64);
    }

    @Test
    void rejectsShortPhone() {
        assertThatThrownBy(() -> PhoneCrypto.normalizeCnMobile("123"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.BAD_REQUEST);
    }
}
