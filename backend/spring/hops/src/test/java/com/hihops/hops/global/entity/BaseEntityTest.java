package com.hihops.hops.global.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 로컬 PostgreSQL 테스트 DB(hops_test)에 실제로 연결해 Flyway와 공통 Entity를 확인한다.
 *
 * <p>준비: PostgreSQL 18 실행, hops_test DB 생성, application-db.local.properties 작성 (Spring README 참고).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/hops_test",
        "spring.flyway.locations=classpath:db/migration,classpath:db/test-migration"
})
@ActiveProfiles("db")
class BaseEntityTest {

    @Autowired
    private SampleRepository sampleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void Flyway가_적용_이력을_DB에_남긴다() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isPositive();
    }

    @Test
    void 저장하면_uuidv7_ID와_생성_수정_시각이_자동으로_들어간다() {
        Sample saved = sampleRepository.saveAndFlush(new Sample("저장 테스트"));

        assertThat(saved.getId().version()).isEqualTo(7);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.isDeleted()).isFalse();
    }

    @Test
    void 수정하면_수정_시각만_바뀐다() throws InterruptedException {
        Sample saved = sampleRepository.saveAndFlush(new Sample("수정 전"));
        Instant createdAt = saved.getCreatedAt();
        Instant firstUpdatedAt = saved.getUpdatedAt();
        Thread.sleep(5);

        Sample updated = transactionTemplate.execute(status -> {
            Sample found = sampleRepository.findById(saved.getId()).orElseThrow();
            found.rename("수정 후");
            return sampleRepository.saveAndFlush(found);
        });

        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedAt()).isAfter(firstUpdatedAt);
    }

    @Test
    void 삭제는_행을_지우지_않고_삭제_시각만_기록한다() {
        Sample saved = sampleRepository.saveAndFlush(new Sample("삭제 테스트"));

        transactionTemplate.executeWithoutResult(status -> {
            Sample found = sampleRepository.findById(saved.getId()).orElseThrow();
            found.markDeleted();
        });

        Sample reloaded = sampleRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.isDeleted()).isTrue();
        assertThat(reloaded.getDeletedAt()).isNotNull();
    }

    /** 테스트 전용 Entity. 실제 기능 Entity도 이렇게 BaseEntity를 상속한다. */
    @Entity
    @Table(name = "test_sample")
    static class Sample extends BaseEntity {

        @Column(nullable = false, length = 50)
        private String name;

        protected Sample() {
        }

        Sample(String name) {
            this.name = name;
        }

        void rename(String name) {
            this.name = name;
        }
    }

    interface SampleRepository extends JpaRepository<Sample, java.util.UUID> {
    }

    @TestConfiguration
    @EnableJpaRepositories(basePackageClasses = BaseEntityTest.class, considerNestedRepositories = true)
    static class SampleRepositoryConfig {
    }
}
