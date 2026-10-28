package com.hihops.hops.department.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.department.domain.Department;

public interface DepartmentRepository extends JpaRepository<Department, UUID> {
    boolean existsByIdAndDeletedAtIsNull(UUID id);
}
