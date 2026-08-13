package com.jisuodashi.auth;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.PhoneCrypto;
import com.jisuodashi.common.SnowflakeIdGenerator;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

@Service
public class CustomerAuthService {

    private final WeChatClient weChat;
    private final CustomerMergeService merge;
    private final CustomerRepository customers;
    private final JwtService jwt;
    private final PhoneCrypto phoneCrypto;
    private final AuthSessionRepository sessions;
    private final SnowflakeIdGenerator ids;
    private final Clock clock;
    private final boolean mock;

    public CustomerAuthService(
            WeChatClient weChat,
            CustomerMergeService merge,
            CustomerRepository customers,
            JwtService jwt,
            PhoneCrypto phoneCrypto,
            AuthSessionRepository sessions,
            SnowflakeIdGenerator ids,
            Clock clock,
            AppProperties properties) {
        this.weChat = weChat;
        this.merge = merge;
        this.customers = customers;
        this.jwt = jwt;
        this.phoneCrypto = phoneCrypto;
        this.sessions = sessions;
        this.ids = ids;
        this.clock = clock;
        this.mock = properties.getWechat().isMock();
    }

    public AuthDtos.CustomerLoginResponse login(AuthDtos.WeChatLoginRequest request) {
        WeChatSession session = weChat.code2Session(request.code(), WeChatApp.CUSTOMER);
        PhoneCrypto.PhoneParts phone = resolvePhone(request.phoneCode(), null);
        Customer customer = merge.merge(
                session.openid(),
                session.unionid(),
                phone == null ? null : phone.hash(),
                phone == null ? null : phone.cipher());
        return issue(customer);
    }

    public AuthDtos.CustomerLoginResponse bindPhone(JwtPrincipal principal, AuthDtos.BindPhoneRequest request) {
        if (principal == null || principal.typ() != TokenType.C) {
            throw new ApiException(ErrorCodes.UNAUTHORIZED, "未登录");
        }
        PhoneCrypto.PhoneParts phone = resolvePhone(
                request == null ? null : request.phoneCode(),
                request == null ? null : request.phone());
        if (phone == null) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "phoneCode 或 phone 不能为空");
        }
        Customer current = customers.findById(principal.subjectId())
                .orElseThrow(() -> new ApiException(ErrorCodes.UNAUTHORIZED, "未登录"));
        if (current.getWxOpenid() == null) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "当前账号无 openid");
        }
        Customer survivor = merge.merge(current.getWxOpenid(), current.getWxUnionid(), phone.hash(), phone.cipher());
        return issue(survivor);
    }

    private PhoneCrypto.PhoneParts resolvePhone(String phoneCode, String rawPhone) {
        if (rawPhone != null && !rawPhone.isBlank()) {
            if (!mock) {
                throw new ApiException(ErrorCodes.BAD_REQUEST, "仅 mock 可直接传 phone");
            }
            return phoneCrypto.sealMobile(rawPhone);
        }
        String phone = weChat.phoneFromCode(phoneCode, WeChatApp.CUSTOMER);
        if (phone == null) {
            return null;
        }
        return phoneCrypto.sealMobile(phone);
    }

    private AuthDtos.CustomerLoginResponse issue(Customer customer) {
        JwtService.IssuedToken issued = jwt.issue(JwtPrincipal.customer(customer.getId()));
        AuthSession session = new AuthSession();
        session.setId(ids.nextId());
        session.setSubjectType("CUSTOMER");
        session.setSubjectId(customer.getId());
        session.setTokenHash(TokenHasher.sha256(issued.token()));
        session.setExpireAt(issued.expireAt());
        session.setCreatedAt(Instant.now(clock));
        sessions.insert(session);
        return new AuthDtos.CustomerLoginResponse(
                issued.token(),
                issued.expiresIn(),
                String.valueOf(customer.getId()),
                !customer.hasPhone());
    }
}
