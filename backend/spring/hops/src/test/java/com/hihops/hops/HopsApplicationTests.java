package com.hihops.hops;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("local")
class HopsApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void localProfileStartsWithoutDataSource() {
        assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
    }

}
