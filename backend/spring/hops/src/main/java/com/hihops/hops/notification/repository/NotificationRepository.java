package com.hihops.hops.notification.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.notification.domain.Notification;

/** notification 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {
}
