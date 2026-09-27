package com.chris64233.berthwindow;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类：每个测试方法前清空全部业务表并重置自增主键。
 * （Spring 测试上下文在类之间缓存，H2 内存库数据会跨方法累积。）
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbcTemplate.execute("TRUNCATE TABLE tug_assignment");
            jdbcTemplate.execute("TRUNCATE TABLE berth_occupation");
            jdbcTemplate.execute("TRUNCATE TABLE change_history");
            jdbcTemplate.execute("TRUNCATE TABLE berth_swap_side_tug");
            jdbcTemplate.execute("TRUNCATE TABLE berth_swap_side_vessel_type");
            jdbcTemplate.execute("TRUNCATE TABLE berth_swap_side");
            jdbcTemplate.execute("TRUNCATE TABLE berth_swap_tide_snapshot");
            jdbcTemplate.execute("TRUNCATE TABLE berth_swap_proposal");
            jdbcTemplate.execute("TRUNCATE TABLE berth_application");
            jdbcTemplate.execute("TRUNCATE TABLE tide_window");
            jdbcTemplate.execute("TRUNCATE TABLE berth_accepted_vessel_type");
            jdbcTemplate.execute("TRUNCATE TABLE tug");
            jdbcTemplate.execute("TRUNCATE TABLE berth");
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }
}
