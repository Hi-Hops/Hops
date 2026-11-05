package com.hihops.hops.ticket.dto;



/** nullable은 미확인 값이며 공백 문자열은 입력으로 받지 않는다. 길이는 Unicode 코드 포인트 기준이다. */
public final class CommandInput {
    private CommandInput() { }

    public static void text(String value, int max, boolean nullable) {
        if (value == null ? !nullable : value.isBlank() || value.codePointCount(0, value.length()) > max) {
            throw new IllegalArgumentException("입력 문자열의 필수 값 또는 길이가 올바르지 않습니다");
        }
    }
}
