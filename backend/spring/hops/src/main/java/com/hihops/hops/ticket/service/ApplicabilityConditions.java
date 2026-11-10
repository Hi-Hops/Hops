package com.hihops.hops.ticket.service;

import java.util.*;
import java.util.regex.Pattern;

/** 승인 원문에 명시된 조건만 비교한다. 부정·대안·추정은 일치로 바꾸지 않는다. */
public final class ApplicabilityConditions {
    public static final List<String> FIELDS = List.of("조회 상태", "문서 상태", "기안자", "상신 여부",
            "조회 기간", "제목 검색어", "파일 형식", "첨부 상태", "파일 크기", "첨부 개수", "화면 문구", "세션 상태",
            "열람 관계", "기안 시점", "누락 항목", "제목 길이", "업로드 경과", "기능");
    private static final Pattern UNCERTAIN = Pattern.compile("모르|미확인|불명|추정|아마|일\\s*수|\\?");
    private ApplicabilityConditions() {}

    public static Map<String, String> explicit(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String field : FIELDS) {
            var m = Pattern.compile("(?m)^\\s*" + Pattern.quote(field) + "\\s*[:：]\\s*([^\\n;]+)").matcher(text);
            List<String> values = new ArrayList<>();
            while (m.find()) values.add(m.group(1).strip());
            if (values.size() == 1 && !UNCERTAIN.matcher(values.getFirst()).find()) result.put(field, values.getFirst());
        }
        return result;
    }

    public static Map<String, String> current(List<String> facts) {
        String all = String.join("\n", facts);
        Map<String, String> result = explicit(all);
        Set<String> unresolvedExplicit = new HashSet<>();
        for (String field : FIELDS) if (!result.containsKey(field) && Pattern.compile("(?m)^\\s*"+Pattern.quote(field)+"\\s*[:：]").matcher(all).find()) unresolvedExplicit.add(field);
        // 비구조화 원문에는 제한된, 긍정·현재형 표현만 보완한다. 애매하면 미확인이다.
        if (!result.containsKey("조회 상태")) {
            Set<String> values = new HashSet<>();
            boolean ambiguous = false;
            for (String fact : facts) {
                var m = Pattern.compile("조회\\s*상태(?:가|는|를|:)?\\s*[‘'\"“]?(승인|전체|상신|결재 중|반려|회수)[’'\"”]?(?=으로|로|이고|이며|입니다|이에요|예요|인|일|[,.;\\n]|$)").matcher(fact);
                if (m.find()) {
                    if (Pattern.compile("아니|아닌|아닙|않|모르|또는|였|변경|바꿨|예정|어야|하세요|\\?").matcher(fact).find()) ambiguous = true;
                    else values.add(m.group(1));
                }
            }
            if (!ambiguous && values.size() == 1) result.put("조회 상태", values.iterator().next());
        }
        if (!result.containsKey("기안자") && !Pattern.compile("기안.*(?:아니|아닌|않|모르)").matcher(all).find()
                && Pattern.compile("(?:본인|문의자|제가|직접).{0,12}(?:기안|상신)").matcher(all).find()) result.put("기안자", "본인");
        if (!result.containsKey("상신 여부") && !Pattern.compile("상신(?:하기)?\\s*전|상신.{0,8}(?:아니|않|모르|예정)").matcher(all).find()
                && Pattern.compile("상신(?:했|까지 (?:했|완료)|한(?:\\s|문서)| 완료|을 완료)").matcher(all).find()) result.put("상신 여부", "완료");
        if (!result.containsKey("기안자") && Pattern.compile("기안자(?:가|는)? (?:아니|아닌)|다른 (?:사람|직원|분).{0,8}기안").matcher(all).find()) result.put("기안자", "타인");
        if (!result.containsKey("파일 형식")) {
            var formats=Pattern.compile("(?i)(?<![a-z])(?:pdf|png|jpg|txt|docx|xlsx|pptx|hwp|zip|exe|bat|sh)(?![a-z])").matcher(all);
            Set<String> found=new HashSet<>();while(formats.find())found.add(formats.group().toLowerCase(Locale.ROOT));
            if(found.size()==1 && !UNCERTAIN.matcher(all).find())result.put("파일 형식",found.iterator().next());
        }
        if (!result.containsKey("첨부 상태") && !UNCERTAIN.matcher(all).find()) {
            boolean scanning=all.contains("검사 중"), completed=all.contains("검사 완료");
            if(scanning!=completed)result.put("첨부 상태",scanning?"검사 중":"검사 완료");
        }
        if (!result.containsKey("제목 검색어") && Pattern.compile("(?:키워드|검색어).{0,12}(?:없|비워|입력하지 않|입력 안)").matcher(all).find())result.put("제목 검색어","없음");
        if (!result.containsKey("조회 기간")) {
            Set<String> periods = new HashSet<>();
            boolean ambiguous = false;
            for (String clause : all.split("\\n|(?<=[;,.!?。])\\s*")) {
                if (!clause.matches(".*조회\\s*기간.*기안일.*|.*기안일.*조회\\s*기간.*")) continue;
                if (UNCERTAIN.matcher(clause).find() || Pattern.compile(
                        "되는지|포함인지|포함할지|해야|하도록|하세요|주세요|예정|권장|가정|경우|확인해").matcher(clause).find()) {
                    ambiguous = true;
                    continue;
                }
                if (Pattern.compile("기안일.{0,30}조회\\s*기간\\s*(?:의|보다)?\\s*밖|(?:기안일.{0,30}조회\\s*기간|조회\\s*기간.{0,30}기안일).{0,15}(?:미포함|포함(?:되지|하지)\\s*않|포함(?:이)?\\s*안)").matcher(clause).find()) {
                    periods.add("기안일 미포함");
                } else if (Pattern.compile("(?:조회\\s*기간.{0,30}기안일|기안일.{0,30}조회\\s*기간).{0,15}(?:포함(?:됩니다|돼|되|된|되어|됐|했)|범위\\s*안|안에\\s*있)").matcher(clause).find()) {
                    periods.add("기안일 포함");
                }
            }
            if (!ambiguous && periods.size() == 1) result.put("조회 기간", periods.iterator().next());
        }
        unresolvedExplicit.forEach(result::remove);
        return result;
    }

    public static String compare(String actual, String expected) {
        if (actual == null || expected == null || UNCERTAIN.matcher(actual).find()) return "UNKNOWN";
        var range = Pattern.compile("(\\d+)(MB|개|자|개월) 초과(?: (\\d+)개월 이내)?").matcher(expected);
        var number = Pattern.compile("(\\d+)(MB|개|자|개월)(?: 전)?").matcher(actual);
        if (range.matches() && number.matches() && range.group(2).equals(number.group(2))) {
            int n = Integer.parseInt(number.group(1));
            return n > Integer.parseInt(range.group(1)) && (range.group(3) == null || n <= Integer.parseInt(range.group(3))) ? "MATCHED" : "MISMATCHED";
        }
        Set<String> accepted = new HashSet<>(Arrays.asList(expected.split("\\s*또는\\s*")));
        return accepted.contains(actual) ? "MATCHED" : "MISMATCHED";
    }
}
