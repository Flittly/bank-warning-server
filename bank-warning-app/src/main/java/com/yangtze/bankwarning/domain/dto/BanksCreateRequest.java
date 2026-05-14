package com.yangtze.bankwarning.domain.dto;

import java.util.List;

public record BanksCreateRequest(
        List<BankPayload> banks,
        Boolean overwrite) {
}
