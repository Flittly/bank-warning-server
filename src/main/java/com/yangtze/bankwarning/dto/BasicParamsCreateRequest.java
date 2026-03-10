package com.yangtze.bankwarning.dto;

import java.util.List;

public record BasicParamsCreateRequest(
        List<BasicParamPayload> params,
        Boolean overwrite) {
}
