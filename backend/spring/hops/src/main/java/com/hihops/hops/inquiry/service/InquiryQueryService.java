package com.hihops.hops.inquiry.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.agent.service.RunSummaryReader;
import com.hihops.hops.global.dto.PageResponse;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.attachment.AttachmentHistoryReader;
import com.hihops.hops.inquiry.attachment.AttachmentHistoryReader.AttachmentRef;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.MessagePayload;
import com.hihops.hops.inquiry.domain.MessageRevision;
import com.hihops.hops.inquiry.dto.ChatMessageResponse;
import com.hihops.hops.inquiry.dto.InquiryResultResponse;
import com.hihops.hops.inquiry.dto.InquirySummaryResponse;
import com.hihops.hops.inquiry.dto.MessagePageResponse;
import com.hihops.hops.inquiry.dto.MessageRevisionResponse;
import com.hihops.hops.inquiry.repository.InquiryProblemCounts;
import com.hihops.hops.inquiry.repository.InquiryProblemRepository;
import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.inquiry.repository.MessageRevisionRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * 문의자 본인의 문의·대화 조회 (WIRE_CONTRACT 12.2 GET /inquiries, /inquiries/{id}, /inquiries/{id}/messages).
 *
 * <p>남의 문의·삭제된 문의·없는 문의는 모두 같은 404다. 남의 문의가 있다는 사실도 알려 주지 않으려고 구분하지 않는다 (12.3).
 */
@Service
@RequiredArgsConstructor
public class InquiryQueryService {

    // 최근 수정 순, 같은 시각이면 ID 역순 (WIRE_CONTRACT 12.2 "updatedAt DESC,id DESC")
    private static final Sort LIST_ORDER = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));

    private final InquiryRepository inquiryRepository;
    private final InquiryProblemRepository inquiryProblemRepository;
    private final MessageRepository messageRepository;
    private final MessageRevisionRepository messageRevisionRepository;
    private final RunSummaryReader runSummaryReader;
    private final ProblemProjector problemProjector;
    private final GuideVisibility guideVisibility;
    private final JsonMapper jsonMapper;
    private final ObjectProvider<AttachmentHistoryReader> attachmentHistory;

    /** 내 문의 목록 한 페이지. page·size 범위는 컨트롤러에서 검사한다. */
    @Transactional(readOnly = true)
    public PageResponse<InquirySummaryResponse> findMyInquiries(UUID requesterId, int page, int size) {
        Page<Inquiry> inquiries = inquiryRepository.findByRequester_IdAndDeletedAtIsNull(requesterId,
                PageRequest.of(page, size, LIST_ORDER));
        List<UUID> ids = inquiries.getContent().stream().map(Inquiry::getId).toList();
        Map<UUID, InquiryProblemCounts> counts = ids.isEmpty() ? Map.of()
                : inquiryProblemRepository.countByInquiryIds(ids).stream()
                        .collect(Collectors.toMap(InquiryProblemCounts::inquiryId, Function.identity()));
        return PageResponse.of(inquiries, inquiry -> InquirySummaryResponse.of(inquiry, counts.get(inquiry.getId())));
    }

    /** 내 문의 상세. 문제별 결과는 {@link ProblemProjector}가 모은다. */
    @Transactional(readOnly = true)
    public InquiryResultResponse findMyInquiry(UUID requesterId, UUID inquiryId) {
        Inquiry inquiry = myInquiry(requesterId, inquiryId);
        return InquiryResultResponse.of(inquiry, problemProjector.project(inquiry),
                inquiry.getCurrentRun() == null ? null : runSummaryReader.read(inquiry.getCurrentRun()),
                messageRepository.findPendingTargetIds(inquiryId));
    }

    /**
     * 내 문의의 대화 한 페이지. 가장 최근 size개, 또는 beforeSequenceNo보다 앞선 size개를 오래된 것부터 돌려준다.
     * AI 안내의 근거를 지금 문의자가 볼 수 없으면 그 안내 부분을 숨긴다({@link GuideVisibility}).
     *
     * @param beforeSequenceNo 이 순번 미만만 (null이면 최신 페이지)
     */
    @Transactional(readOnly = true)
    public MessagePageResponse findMyMessages(UUID requesterId, UUID inquiryId, int size,
            @Nullable Integer beforeSequenceNo) {
        Inquiry inquiry = myInquiry(requesterId, inquiryId);
        // 하나 더 읽어서 이전 메시지가 남았는지 안다
        Limit limit = Limit.of(size + 1);
        List<Message> newestFirst = beforeSequenceNo == null
                ? messageRepository.findLatestCompleted(inquiryId, limit)
                : messageRepository.findCompletedBefore(inquiryId, beforeSequenceNo, limit);
        boolean hasOlder = newestFirst.size() > size;
        List<Message> messages = new ArrayList<>(newestFirst.subList(0, Math.min(size, newestFirst.size())));
        Collections.reverse(messages);

        Map<UUID, List<MessageRevision>> revisions = revisionsOf(messages);
        Map<UUID, GuideVisibility.GuideView> guides = guideVisibility.viewsOf(inquiry, messages);
        AttachmentHistoryReader reader = attachmentHistory.getIfAvailable();
        Map<UUID, List<AttachmentRef>> attachments = reader == null ? Map.of()
                : reader.forMessages(requesterId, inquiryId, messages.stream().map(Message::getId).toList());
        List<ChatMessageResponse> items = messages.stream()
                .map(message -> ChatMessageResponse.of(message, guides.get(message.getId()).contentOf(message),
                        payloadOf(message),
                        MessageRevisionResponse.listOf(message, revisions.getOrDefault(message.getId(), List.of())),
                        attachments.getOrDefault(message.getId(), List.of())))
                .toList();
        return new MessagePageResponse(items, hasOlder ? messages.getFirst().getSequenceNo() : null);
    }

    private Inquiry myInquiry(UUID requesterId, UUID inquiryId) {
        return inquiryRepository.findByIdAndRequester_IdAndDeletedAtIsNull(inquiryId, requesterId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    // 메시지별 정정 전 원문. 저장소가 차수 순으로 돌려주고 groupingBy는 그 순서를 유지한다.
    private Map<UUID, List<MessageRevision>> revisionsOf(List<Message> messages) {
        if (messages.isEmpty()) {
            return Map.of();
        }
        return messageRevisionRepository.findByMessageIds(messages.stream().map(Message::getId).toList()).stream()
                .collect(Collectors.groupingBy(revision -> revision.getMessage().getId()));
    }

    // payload는 서버만 쓰는 값이다. 형식이 깨져 있으면 빈 값으로 넘기지 않고 오류(500)로 드러낸다.
    private MessagePayload payloadOf(Message message) {
        return jsonMapper.readValue(message.getPayload(), MessagePayload.class);
    }
}
