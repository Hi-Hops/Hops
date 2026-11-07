package com.hihops.hops.inquiry.privacy;

import java.util.List;
import java.util.regex.Pattern;

/** 탐지 가능한 형식만 가린다. 다른 개인정보가 없음을 보증하지 않는다. */
public final class SensitiveTextMasker {
    private static final String REPLACEMENT = "[가림]";
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}(?![a-z0-9-])"),
            Pattern.compile("(?<!\\d)01[016789][ -]?\\d{3,4}[ -]?\\d{4}(?!\\d)"),
            Pattern.compile("(?<!\\d)\\d{6}[ -]?[1-8]\\d{6}(?!\\d)"));
    private static final Pattern LABELED_SECRET = Pattern.compile(
            "(?i)(비밀번호|패스워드|password|인증번호|otp)(\\s*[:=]\\s*)([^\\s,;]+)");

    private SensitiveTextMasker() {
    }

    public static String mask(String content) {
        String result = LABELED_SECRET.matcher(content).replaceAll("$1$2*");
        for (Pattern pattern : PATTERNS) {
            result = pattern.matcher(result).replaceAll(REPLACEMENT);
        }
        return result;
    }
}
