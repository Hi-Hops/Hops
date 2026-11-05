package com.hihops.hops.knowledge.service;

import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.auth.service.AuthService;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.itsystem.repository.ItSystemQueryRepository;
import com.hihops.hops.knowledge.domain.ResolvedCase;
import com.hihops.hops.knowledge.repository.ResolvedCaseRepository;

import lombok.RequiredArgsConstructor;

/**
 * 사례 변경 명령의 권한 검사. 같은 키 재확인에도 현재 권한을 검사하고, 최초 변경은 사례 행 잠금 안에서 다시 검증한다.
 *
 * <p>현재 사례 시스템의 주담당자만 변경한다. 그 밖의 사용자·삭제된 사례는 존재 여부를 숨기려고 404다(SEC-03, 조회 API와 같다).
 */
@Service
@RequiredArgsConstructor
public class CaseCommandAccess {
    private final AuthService auth;
    private final ItSystemQueryRepository systems;
    private final ResolvedCaseRepository cases;

    /** 멱등 처리 전 확인. 이미 처리한 요청의 재확인도 지금 권한이 없으면 결과를 돌려주지 않는다. */
    @Transactional(readOnly = true)
    public void check(Jwt jwt, UUID caseId) {
        requirePrimaryOwner(jwt, cases.findActiveDetail(caseId).orElseThrow(CaseCommandAccess::notFound));
    }

    /** 사례 행을 잠근 뒤 연결 조건과 권한을 다시 확인한다. 호출자의 거래 안에서 쓴다. */
    public ResolvedCase lock(Jwt jwt, UUID caseId) {
        cases.findForUpdate(caseId).orElseThrow(CaseCommandAccess::notFound);
        ResolvedCase resolved = cases.findActiveDetail(caseId).orElseThrow(CaseCommandAccess::notFound);
        requirePrimaryOwner(jwt, resolved);
        return resolved;
    }

    private void requirePrimaryOwner(Jwt jwt, ResolvedCase resolved) {
        var member = auth.currentMember(jwt);
        if (systems.findActivePrimaryOwnerId(resolved.getItSystem().getId()).filter(member.getId()::equals).isEmpty()) {
            throw notFound();
        }
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND);
    }
}
