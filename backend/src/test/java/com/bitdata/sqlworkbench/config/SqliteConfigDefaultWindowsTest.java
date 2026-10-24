package com.bitdata.sqlworkbench.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@EnabledOnOs(OS.WINDOWS)
class SqliteConfigDefaultWindowsTest {

    @Autowired DataSource dataSource;

    @Test
    void defaultDataDirUsesAppDataOnWindows() {
        String appData = System.getenv("APPDATA");
        // Skip defensively if env var is somehow absent (e.g., service account)
        org.junit.jupiter.api.Assumptions.assumeTrue(appData != null && !appData.isBlank(),
            "APPDATA env var not set");

        String url = ((DriverManagerDataSource) dataSource).getUrl();
        // APPDATA is e.g. "C:\Users\foo\AppData\Roaming"; normalize separators so the assertion
        // works regardless of whether the SpEL result uses \ or /.
        String normalizedAppData = appData.replace('\\', '/');
        assertTrue(url.replace('\\', '/').contains(normalizedAppData),
            () -> "URL should derive from APPDATA on Windows: " + url);
    }
}
