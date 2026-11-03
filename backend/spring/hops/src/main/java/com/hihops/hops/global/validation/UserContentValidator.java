package com.hihops.hops.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link UserContent} 검사.
 *
 * <p>공백 집합은 DATA_TYPES 신규 절의 UserContent와 같다: U+0009~000D, U+0020, U+0085, U+00A0, U+1680, U+2000~200A,
 * U+2028, U+2029, U+202F, U+205F, U+3000, U+FEFF. Java의 {@code isBlank}·{@code isWhitespace}는 이 집합과 달라 쓰지 않는다.
 *
 * <p>NUL(U+0000)도 거부한다. PostgreSQL TEXT·JSONB가 저장하지 못해, 통과시키면 입력 오류가 400이 아닌 저장 단계 500이 된다.
 */
public class UserContentValidator implements ConstraintValidator<UserContent, String> {

    static final int MAX_CODE_POINTS = 4000;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (value.isEmpty() || value.indexOf('\u0000') >= 0 || hasLoneSurrogate(value)) {
            return false;
        }
        return value.codePointCount(0, value.length()) <= MAX_CODE_POINTS
                && !value.codePoints().allMatch(UserContentValidator::isContractWhitespace);
    }

    private static boolean hasLoneSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                i++; // 짝이 맞는 surrogate 쌍은 문자 하나다
            } else if (Character.isSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /** DATA_TYPES UserContent의 공백인지. 제목 만들기처럼 같은 공백 기준이 필요한 곳에서도 쓴다. */
    public static boolean isContractWhitespace(int codePoint) {
        return (codePoint >= 0x0009 && codePoint <= 0x000D)
                || codePoint == 0x0020 || codePoint == 0x0085 || codePoint == 0x00A0 || codePoint == 0x1680
                || (codePoint >= 0x2000 && codePoint <= 0x200A)
                || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202F || codePoint == 0x205F
                || codePoint == 0x3000 || codePoint == 0xFEFF;
    }
}
