package com.okxpilot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.math.BigDecimal;
import java.util.Map;
import static com.okxpilot.Domain.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"pilot.operator-token=mysql-test-token-01234567890123456789","pilot.poll-ms=3600000"})
@EnabledIfEnvironmentVariable(named="TEST_MYSQL_URL",matches=".+")
class MySqlSmokeTest {
    @Autowired Store store;
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->System.getenv("TEST_MYSQL_URL"));
        p.add("spring.datasource.username",()->System.getenv("TEST_MYSQL_USER"));
        p.add("spring.datasource.password",()->System.getenv("TEST_MYSQL_PASSWORD"));
    }
    @Test void migratesAndPersistsConfigurationOrdersAndLeaseOnMySql(){
        Settings settings=Settings.defaults();store.settings(settings);assertThat(store.settings()).isEqualTo(settings);
        String id="mysql"+System.currentTimeMillis();
        Decision d=new Decision(Action.CLOSE,"BTC-USDT-SWAP",null,null,null,null,"MySQL persistence acceptance");
        store.intent(id,d,Map.of("reduceOnly",true));store.state(id,"filled","test-order","persisted");
        assertThat(store.orders()).anySatisfy(row->{assertThat(row.get("client_id")).isEqualTo(id);assertThat(row.get("state")).isEqualTo("filled");});
        assertThat(store.acquire()).isTrue();store.renew();store.release();
        assertThat(store.baseline(new BigDecimal("1000"))).isPositive();
        store.audit("TEST","MySQL acceptance",Map.of("passed",true));assertThat(store.events()).isNotEmpty();
    }
}
