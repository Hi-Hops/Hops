package com.hihops.hops.inquiry.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * AI 안내문(GuideProposal.messageText)이 승인 본문에서 나왔는지 확인한다.
 *
 * <p>AI는 승인 본문을 빈 줄 기준 문단으로 나눠 문의에 답하는 문단만 골라 원래 순서로 잇고, 끝에 "참고 자료: 제목" 줄을 붙인다
 * (ai/src/hops_ai/agents/knowledge/excerpt.py). 그래서 출처 줄을 뗀 안내문의 문단은 모두 근거 문서 본문의 연속된 부분 문자열이어야 한다.
 * 모델이 쓴 문장이나 다른 문서의 문단은 통과하지 못한다. 본문 전체를 그대로 쓴 안내도 같은 규칙으로 통과한다.
 */
final class GuideText {

    static final String SOURCE_PREFIX = "참고 자료: ";

    private GuideText() {
    }

    /**
     * @param text     안내문
     * @param titles   인용한 근거의 제목. 출처 줄은 이 중 하나여야 한다(없어도 된다)
     * @param contents 인용한 근거의 승인 본문
     */
    static boolean fromApproved(String text, Collection<String> titles, Collection<String> contents) {
        String body = normalize(text);
        for (String title : titles) {
            String source = "\n\n" + SOURCE_PREFIX + title;
            if (body.endsWith(source)) {
                body = body.substring(0, body.length() - source.length());
                break;
            }
        }
        List<String> approved = contents.stream().map(GuideText::normalize).toList();
        List<String> blocks = blocks(body);
        return !blocks.isEmpty() && blocks.stream().allMatch(block -> approved.stream().anyMatch(content -> content.contains(block)));
    }

    /** 빈 줄 기준 문단. AI의 split_blocks와 같은 규칙이다. */
    static List<String> blocks(String text) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : normalize(text).split("\n", -1)) {
            if (!line.isBlank()) {
                if (!current.isEmpty()) {
                    current.append('\n');
                }
                current.append(line);
            } else if (!current.isEmpty()) {
                blocks.add(current.toString().strip());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            blocks.add(current.toString().strip());
        }
        blocks.removeIf(String::isEmpty);
        return blocks;
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }
}
