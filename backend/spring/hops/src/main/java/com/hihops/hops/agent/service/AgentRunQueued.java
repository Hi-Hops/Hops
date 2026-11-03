package com.hihops.hops.agent.service;

import java.util.UUID;

/** 실행 입력이 업무 거래와 함께 저장된 뒤에만 전달한다. */
public record AgentRunQueued(UUID runId) { }
