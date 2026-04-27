package com.yangtze.bankwarning.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record BanksCreateRequest(
        List<BankPayload> banks,
        Boolean overwrite) {
}
