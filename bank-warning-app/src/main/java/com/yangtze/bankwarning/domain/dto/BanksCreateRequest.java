package com.yangtze.bankwarning.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record BanksCreateRequest(
        List<BankPayload> banks,
        Boolean overwrite) {
}
