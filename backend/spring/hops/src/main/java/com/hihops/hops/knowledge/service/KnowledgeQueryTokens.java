package com.hihops.hops.knowledge.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 검색어를 1차 SQL 검색용 토큰으로 바꾼다. 형태소 분석이 아니다: 글자·숫자가 아닌 문자로 나누고,
 * 길이 2 미만과 흔한 질문 어미(불용어)를 빼고, 흔한 조사를 떼어 어간 부분 문자열로 찾게 한다.
 *
 * <p>토큰에는 글자(\p{L})와 숫자(\p{N})만 남으므로 SQL LIKE의 특수 문자(%, _, \)가 들어올 수 없다.
 * 어미 변화·동의어는 찾지 못한다(2차 검색에서 개선).
 */
public final class KnowledgeQueryTokens {
    public static final int MAX_TOKENS = 20;

    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    // 긴 조사부터 확인한다. 떼고 남은 어간이 2자 이상일 때만 뗀다.
    private static final List<String> PARTICLES = List.of("에서는", "으로는", "에서", "으로", "에게", "까지", "부터", "이나",
            "하고", "라도", "이며", "이고", "에는", "에도", "은", "는", "이", "가", "을", "를", "의", "에", "로", "와", "과", "도", "만");
    private static final Set<String> STOPWORDS = Set.of("어떻게", "어떤", "무엇", "어디", "하나요", "합니다", "있나요", "있는",
            "있습니다", "하는", "되나요", "해야", "해요", "주세요", "싶어요", "싶습니다", "대해", "대한", "관련", "그리고", "그런데",
            "또는", "제가", "저는", "저희", "알려", "알고");

    private KnowledgeQueryTokens() {
    }

    /** 첫 등장 순서를 유지하고 중복을 없애 최대 {@link #MAX_TOKENS}개를 돌려준다. */
    public static List<String> of(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : SPLIT.split(query.toLowerCase(Locale.ROOT))) {
            if (raw.codePointCount(0, raw.length()) < 2 || STOPWORDS.contains(raw)) {
                continue;
            }
            String token = stem(raw);
            if (token.codePointCount(0, token.length()) >= 2 && !STOPWORDS.contains(token)) {
                tokens.add(token);
            }
            if (tokens.size() >= MAX_TOKENS) {
                break;
            }
        }
        return new ArrayList<>(tokens);
    }

    private static String stem(String token) {
        for (String particle : PARTICLES) {
            if (token.endsWith(particle) && token.codePointCount(0, token.length()) - particle.length() >= 2) {
                return token.substring(0, token.length() - particle.length());
            }
        }
        return token;
    }
}
