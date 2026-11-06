package com.hihops.hops.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** 내부 판단 경계(옵션)가 켜진 채 시작하면 눈에 띄게 알린다. 기본은 꺼짐이며 SECURITY.md 개정과 보안 리뷰가 선행돼야 한다. */
@Component
@RequiredArgsConstructor
class InternalJudgementNotice {
    private static final Logger log = LoggerFactory.getLogger(InternalJudgementNotice.class);

    private final AnalysisRunProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    void announce() {
        if (properties.internalJudgement().enabled()) {
            log.warn("내부 판단 경계(hops.agent.run.internal-judgement.enabled)가 켜져 있습니다. 직원 상담 실행의 AI가 담당자 전용 자료를 "
                    + "내부 판단 입력으로 읽습니다. SECURITY.md 개정과 보안 리뷰 없이 운영하지 않습니다. 리뷰 기록: {}",
                    properties.internalJudgement().reviewReference());
        }
    }
}
