package com.hihops.hops.itsystem.repository;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class ItSystemSeedTest {
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @Test void 같은코드의_수동_시스템이_있어도_기능시드가_실제_시스템에_연결된다() {
        new ResourceDatabasePopulator(new ClassPathResource("db/seed/R__seed_dev_department_member.sql")).execute(dataSource);
        var existing = UUID.randomUUID();
        jdbc.update("INSERT INTO it_system(id,code,name,primary_owner_member_id) VALUES (?,'ELECTRONIC_APPROVAL','수동 데모','a0000000-0000-7000-8000-000000000011')", existing);
        var seed = new ResourceDatabasePopulator(new ClassPathResource("db/seed/R__seed_dev_it_system.sql"));
        seed.execute(dataSource); seed.execute(dataSource);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM system_feature WHERE it_system_id=?", Integer.class, existing)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM it_system", Integer.class)).isEqualTo(3);
    }
}
