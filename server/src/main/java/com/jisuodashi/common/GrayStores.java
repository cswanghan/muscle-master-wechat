package com.jisuodashi.common;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/** C-end gray slice: only these store ids are visible. */
@Component
public class GrayStores {

    private final AppProperties properties;

    public GrayStores(AppProperties properties) {
        this.properties = properties;
    }

    public Set<Long> ids() {
        return parse(properties.getGray().getStoreIds());
    }

    public boolean allows(long storeId) {
        return ids().contains(storeId);
    }

    public void require(long storeId) {
        if (!allows(storeId)) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "门店不存在");
        }
    }

    static Set<Long> parse(String raw) {
        Set<Long> ids = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        for (String part : raw.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(token));
            } catch (NumberFormatException ignored) {
                // skip malformed tokens
            }
        }
        return Set.copyOf(ids);
    }
}
