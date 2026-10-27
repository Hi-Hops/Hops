package com.hihops.hops;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles({"db", "test"})
class HopsApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void 서버가_DB에_연결된_상태로_시작된다() {
        assertThat(context.getBeansOfType(DataSource.class)).isNotEmpty();
    }
}
