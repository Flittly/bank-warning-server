package com.yangtze.bankwarning.domain.dto;

import java.util.List;

public record BasicParamsCreateRequest(
        List<BasicParamPayload> params,
        Boolean overwrite) {
}
