package com.bitdata.sqlworkbench.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "sql-workbench.data-dir=${java.io.tmpdir}/sqldiag-property-test"
})
class SqliteConfigPropertyTest {

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @Test
    void dataDirPropertyOverridesAppDataDefault(@TempDir Path tmp) throws Exception {
        String url = ((org.springframework.jdbc.datasource.DriverManagerDataSource) dataSource).getUrl();
        assertTrue(url.contains(tmp.toAbsolutePath().toString())
                || url.contains("sqldiag-property-test"),
            () -> "DataSource URL should reflect property: " + url);
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='connections'",
            Integer.class);
        assertEquals(1, count, "connections table should exist");
    }
}
