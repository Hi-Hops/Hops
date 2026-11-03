package com.hihops.hops.agent.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.agent.domain.AgentRun;

/** agent_run 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {
}
