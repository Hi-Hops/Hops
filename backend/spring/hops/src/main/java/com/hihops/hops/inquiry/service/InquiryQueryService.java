package com.hihops.hops.inquiry.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.global.dto.PageResponse;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.dto.InquiryResultResponse;
import com.hihops.hops.inquiry.dto.InquirySummaryResponse;
import com.hihops.hops.inquiry.repository.InquiryProblemCounts;
import com.hihops.hops.inquiry.repository.InquiryProblemRepository;
import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.inquiry.repository.MessageRepository;

import lombok.RequiredArgsConstructor;

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

    /**
     * 내 문의 상세.
     *
     * @throws IllegalStateException 문제나 실행이 있는 문의. 그 형식(Problem·RunSummary)은 아직 만들지 않아, 빈 값으로 숨기지 않고 실패시킨다(#26)
     */
    @Transactional(readOnly = true)
    public InquiryResultResponse findMyInquiry(UUID requesterId, UUID inquiryId) {
        Inquiry inquiry = myInquiry(requesterId, inquiryId);
        // 지연 로딩 참조라 null 확인만으로는 agent_run을 읽지 않는다
        if (inquiry.getCurrentRun() != null || inquiryProblemRepository.existsByInquiry_IdAndDeletedAtIsNull(inquiryId)) {
            throw new IllegalStateException("문제·실행이 있는 문의의 상세 조회는 아직 만들지 않았다: " + inquiryId);
        }
        return InquiryResultResponse.withoutProblems(inquiry, messageRepository.findPendingTargetIds(inquiryId));
    }

    private Inquiry myInquiry(UUID requesterId, UUID inquiryId) {
        return inquiryRepository.findByIdAndRequester_IdAndDeletedAtIsNull(inquiryId, requesterId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
