package com.hihops.hops.itsystem.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.hihops.hops.itsystem.domain.ItSystem;

/** it_system 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface ItSystemRepository extends JpaRepository<ItSystem, UUID> {

    /**
     * 이 회원이 주담당자인 시스템 ID (CurrentUser.primarySystemIds). 삭제된 시스템은 뺀다.
     * 화면이 매번 같은 순서로 보이도록 시스템 코드 순으로 돌려준다.
     */
    @Query("SELECT s.id FROM ItSystem s WHERE s.primaryOwner.id = :memberId AND s.deletedAt IS NULL ORDER BY s.code")
    List<UUID> findActiveIdsByPrimaryOwnerId(UUID memberId);

    List<ItSystem> findByDeletedAtIsNullOrderByCodeAsc();

    Optional<ItSystem> findByIdAndDeletedAtIsNull(UUID id);

    @Query("SELECT s.id FROM ItSystem s WHERE s.deletedAt IS NULL ORDER BY s.code")
    List<UUID> findActiveIds();

    @Query("SELECT s.primaryOwner.id FROM ItSystem s WHERE s.id = :systemId AND s.deletedAt IS NULL "
            + "AND s.primaryOwner.deletedAt IS NULL AND s.primaryOwner.department.deletedAt IS NULL")
    Optional<UUID> findActivePrimaryOwnerId(UUID systemId);
}
