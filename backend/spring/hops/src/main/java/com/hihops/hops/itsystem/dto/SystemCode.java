package com.hihops.hops.itsystem.dto;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;

public enum SystemCode {
    ELECTRONIC_APPROVAL, COMMON_LOGIN, FILE_SHARING;

    public static SystemCode fromStored(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}
