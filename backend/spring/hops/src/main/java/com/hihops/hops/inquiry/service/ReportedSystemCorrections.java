package com.hihops.hops.inquiry.service;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.InquiryProblem;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.MessagePayload;
import com.hihops.hops.inquiry.domain.MessageStatus;
import com.hihops.hops.inquiry.domain.ProblemStatus;
import com.hihops.hops.inquiry.domain.SenderType;
import com.hihops.hops.inquiry.repository.InquiryProblemRepository;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.inquiry.repository.StandardRequestRepository;
import com.hihops.hops.itsystem.dto.SupportedSystemResponse;
import com.hihops.hops.ticket.repository.TicketRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 명시적인 보고 시스템 정정의 원문과 보완 대상을 검증하고, 승인된 정정을 원문 메시지에 기록한다. */
@Component
@RequiredArgsConstructor
public class ReportedSystemCorrections {

    private static final String KEY = "reportedSystemCorrection";
    private static final Pattern REPLACEMENT = Pattern.compile(
            "^(?:(?:아|아니요|정정합니다)[,，:]\\s*)?(?:(?:보고 시스템은|시스템은)\\s+)?(.+?)(?:이|가)\\s*아니(?:라|고)\\s+"
                    + "(.+?)(?:\\s*시스템)?(?:\\s*(?:이야기|얘기))?\\s*(?:입니다|이에요|예요|이었어요|였어요)?$");

    private final InquiryProblemRepository problems;
    private final StandardRequestRepository requests;
    private final MessageRepository messages;
    private final TicketRepository tickets;
    private final JsonMapper mapper;

    record Replacement(UUID previousSystemId, UUID systemId, String systemName) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Applied(UUID problemId, UUID previousSystemId, UUID systemId, String systemName,
            UUID sourceMessageId, int sourceRevision, int inquiryVersion) {
        public String summary() {
            // 이전 시스템이 없으면 정정이 아니라 문의자가 처음 고른 것이다 (system-confirmations)
            return (previousSystemId == null ? "선택한" : "정정한") + " 보고 시스템은 " + systemName + "입니다.";
        }
    }

    static Optional<Replacement> parse(String content, List<SupportedSystemResponse> systems) {
        Replacement result = null;
        for (String sentence : Normalizer.normalize(content, Normalizer.Form.NFC).split("[.!。！\\n]+")) {
            var match = REPLACEMENT.matcher(sentence.strip());
            if (!match.matches()) continue;
            var previous = systemNamed(match.group(1), systems);
            var replacement = systemNamed(match.group(2), systems);
            if (result != null || previous.isEmpty() || replacement.isEmpty() || previous.equals(replacement)) {
                return Optional.empty();
            }
            var system = replacement.orElseThrow();
            result = new Replacement(previous.orElseThrow().id(), system.id(), system.name());
        }
        return Optional.ofNullable(result);
    }

    private static Optional<SupportedSystemResponse> systemNamed(String name, List<SupportedSystemResponse> systems) {
        String label = normalizedName(name.strip().replaceFirst("\\s*시스템$", ""));
        var matches = systems.stream().filter(system -> label.equals(normalizedName(system.name()))
                || label.equals(normalizedName(system.code().name()))).toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private static String normalizedName(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    // 문의 잠금 안에서 호출한다. 기존 링크를 재검사해 다른 문제나 티켓에 적용하지 않는다.
    void apply(Message source, Replacement replacement) {
        var payload = mapper.readValue(source.getPayload(), MessagePayload.class);
        var links = payload.problemIds(source.getRevision());
        if (source.getSenderType() != SenderType.USER || source.getMessageStatus() != MessageStatus.COMPLETED
                || source.isDeleted() || payload.isPendingTarget() || links.size() != 1) return;

        var inquiry = source.getInquiry();
        UUID problemId = links.getFirst();
        var target = problems.findById(problemId)
                .filter(problem -> !problem.isDeleted() && problem.getInquiry().getId().equals(inquiry.getId()))
                .filter(ReportedSystemCorrections::canCorrect)
                .filter(problem -> tickets.findByInquiryProblem_Id(problem.getId()).isEmpty());
        if (target.isEmpty()) return;

        var existing = latest(inquiry.getId()).get(problemId);
        var request = requests.findAppliedNewestFirst(List.of(problemId)).stream().findFirst().orElseThrow();
        UUID currentSystem = existing != null ? existing.systemId()
                : request.getReportedSystem() == null ? null : request.getReportedSystem().getId();
        if (!replacement.previousSystemId().equals(currentSystem)) return;

        var applied = new Applied(problemId, currentSystem, replacement.systemId(), replacement.systemName(),
                source.getId(), source.getRevision(), inquiry.getVersion());
        var node = (ObjectNode) mapper.readTree(source.getPayload());
        node.set(KEY, mapper.valueToTree(applied));
        source.updatePayload(node.toString());
        messages.flush();
    }

    /**
     * 보고 시스템이 정해지지 않은 문제에 문의자가 고른 시스템을 기록한다 (POST system-confirmations). 문의 잠금 안에서, 입력 버전을
     * 올린 뒤 호출한다. 정정과 같은 기록(reportedSystemCorrection, 이전 시스템 없음)을 문제에 연결된 원문 한 건에 남기고 그 원문을 돌려준다.
     *
     * <p>고를 수 없으면 INVALID_STATE다: 문제가 이 문의의 것이 아니거나, 질문 중·시스템 대기가 아니거나, 티켓·티켓 생성 작업이 있거나,
     * 보고 시스템이 이미 정해졌거나(요청서 또는 정정), 기록을 남길 원문(문제에 연결된 USER 메시지)이 없을 때. 정정 기록은 원문 하나에
     * 문제 하나만 둘 수 있어 다른 문제의 기록이 있는 원문은 쓰지 않는다.
     */
    Message confirm(Inquiry inquiry, InquiryProblem problem, SupportedSystemResponse system) {
        if (problem.isDeleted() || !problem.getInquiry().getId().equals(inquiry.getId()) || !canCorrect(problem)
                || tickets.findByInquiryProblem_Id(problem.getId()).isPresent()) {
            throw invalid();
        }
        var request = requests.findAppliedNewestFirst(List.of(problem.getId())).stream().findFirst().orElseThrow(ReportedSystemCorrections::invalid);
        var existing = latest(inquiry.getId()).get(problem.getId());
        boolean corrected = existing != null && existing.inquiryVersion() > request.getInquiryVersion();
        if (corrected || request.getReportedSystem() != null) {
            throw invalid();
        }
        Message source = null;
        for (var message : messages.findAllCompletedInOrder(inquiry.getId())) {
            var payload = mapper.readValue(message.getPayload(), MessagePayload.class);
            if (message.getSenderType() != SenderType.USER || payload.isPendingTarget()
                    || !payload.problemIds(message.getRevision()).contains(problem.getId())) continue;
            var recorded = mapper.readTree(message.getPayload()).path(KEY);
            if (recorded.isObject() && !problem.getId().toString().equals(recorded.path("problemId").asString())) continue;
            source = message; // 가장 나중 원문
        }
        if (source == null) throw invalid();

        var applied = new Applied(problem.getId(), null, system.id(), system.name(), source.getId(), source.getRevision(),
                inquiry.getVersion());
        var node = (ObjectNode) mapper.readTree(source.getPayload());
        node.set(KEY, mapper.valueToTree(applied));
        source.updatePayload(node.toString());
        messages.flush();
        return source;
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_STATE, "시스템을 선택할 수 있는 문제가 아닙니다.");
    }

    private static boolean canCorrect(InquiryProblem problem) {
        return problem.getCreationOutcome() == null
                && (problem.getProblemStatus() == ProblemStatus.CLARIFYING
                    || problem.getProblemStatus() == ProblemStatus.SYSTEM_PENDING);
    }

    public Map<UUID, Applied> latest(UUID inquiryId) {
        Map<UUID, Applied> latest = new LinkedHashMap<>();
        for (var message : messages.findReportedSystemCorrections(inquiryId)) {
            var applied = mapper.treeToValue(mapper.readTree(message.getPayload()).path(KEY), Applied.class);
            latest.merge(applied.problemId(), applied,
                    (left, right) -> left.inquiryVersion() > right.inquiryVersion() ? left : right);
        }
        return latest;
    }
}
