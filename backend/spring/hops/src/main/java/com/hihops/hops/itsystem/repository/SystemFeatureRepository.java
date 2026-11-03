package com.hihops.hops.itsystem.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.hihops.hops.itsystem.domain.SystemFeature;

/** system_feature 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface SystemFeatureRepository extends JpaRepository<SystemFeature, UUID> {
    @Query("SELECT f FROM SystemFeature f WHERE f.itSystem.id IN :systemIds AND f.deletedAt IS NULL ORDER BY f.code, f.id")
    List<SystemFeature> findActiveBySystemIds(Collection<UUID> systemIds);

    /** 삭제되지 않은 기능 전체. 화면이 매번 같은 순서로 보이도록 기능 코드 순으로 돌려준다. */
    @Query("SELECT f FROM SystemFeature f WHERE f.deletedAt IS NULL ORDER BY f.code, f.id")
    List<SystemFeature> findAllActive();
}
