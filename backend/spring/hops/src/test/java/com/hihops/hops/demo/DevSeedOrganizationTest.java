package com.hihops.hops.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개발용 조직·직원·담당 seed가 정본(docs/knowledge/VIRTUAL_COMPANY.md 4절)의 구성과 같은지 확인한다.
 * seed는 테스트 DB에 기본으로 들어가지 않으므로 이 테스트가 직접 실행하고 트랜잭션과 함께 되돌린다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class DevSeedOrganizationTest {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void 부서_5개와_직원_8명이_정본_구성대로_들어가고_IT운영팀만_처리_대상이다() {
        loadSeed();

        assertThat(jdbc.queryForList("SELECT code FROM department ORDER BY code", String.class))
                .containsExactly("HR", "IT_OPS", "MARKETING", "SALES", "SYS_ADMIN");
        assertThat(jdbc.queryForList("SELECT code FROM department WHERE is_routable", String.class))
                .containsExactly("IT_OPS");
        assertThat(jdbc.queryForList("SELECT login_id FROM member ORDER BY login_id", String.class))
                .containsExactly("admin", "ea_owner", "file_owner", "login_owner",
                        "requester1", "requester2", "requester3", "requester4");
        assertThat(jdbc.queryForList(
                "SELECT m.login_id FROM member m JOIN department d ON d.id = m.department_id "
                        + "WHERE d.code = 'IT_OPS' ORDER BY m.login_id", String.class))
                .containsExactly("ea_owner", "file_owner", "login_owner");
    }

    @Test
    void 역할은_관리자_한_명이_두_역할을_모두_가진다() {
        loadSeed();

        List<String> roles = jdbc.queryForList(
                "SELECT m.login_id || ':' || r.role FROM member_role r JOIN member m ON m.id = r.member_id "
                        + "ORDER BY 1", String.class);
        assertThat(roles).containsExactly("admin:AGENT_OPS_ADMIN", "admin:BIZ_ADMIN");
    }

    @Test
    void 시스템마다_서로_다른_IT운영팀_직원_한_명이_주담당자다() {
        loadSeed();

        List<String> owners = jdbc.queryForList(
                "SELECT s.code || ':' || m.login_id FROM it_system s JOIN member m ON m.id = s.primary_owner_member_id "
                        + "JOIN department d ON d.id = m.department_id WHERE d.code = 'IT_OPS' ORDER BY 1", String.class);
        assertThat(owners).containsExactly(
                "COMMON_LOGIN:login_owner", "ELECTRONIC_APPROVAL:ea_owner", "FILE_SHARING:file_owner");
    }

    @Test
    void 두_번_적재해도_행이_늘지_않는다() {
        loadSeed();
        loadSeed();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM department", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member", Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_role", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM it_system", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM system_feature", Integer.class)).isEqualTo(10);
    }

    private void loadSeed() {
        new ResourceDatabasePopulator(new ClassPathResource("db/seed/R__seed_dev_department_member.sql")).execute(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("db/seed/R__seed_dev_it_system.sql")).execute(dataSource);
    }
}
