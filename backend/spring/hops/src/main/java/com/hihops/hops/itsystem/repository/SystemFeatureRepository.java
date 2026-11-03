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
}
