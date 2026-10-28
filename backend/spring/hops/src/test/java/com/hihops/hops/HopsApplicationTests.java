package com.hihops.hops;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    @Test
    void 서버가_Redis에_연결된_상태로_시작된다() {
        // Refresh 토큰·로그인 세션 저장소(#58). 연결되면 PING에 PONG으로 답한다.
        StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
        String pong = redis.execute((RedisCallback<String>) connection -> connection.ping());
        assertThat(pong).isEqualTo("PONG");
    }
}
