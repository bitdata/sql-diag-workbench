package com.bitdata.sqlworkbench.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SqliteWriteReservationTest {
    @TempDir Path dir;
    @Test void reservationRequiresMatchingTransactionAndExistingGenerationRow() {
        var ds=new StorageDataSource();ds.setUrl("jdbc:sqlite:"+dir.resolve("one.db"));
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE storage_generation(id INTEGER PRIMARY KEY,revision INTEGER NOT NULL)");
        jdbc.update("INSERT INTO storage_generation VALUES(1,42)");
        assertThrows(IllegalStateException.class,()->SqliteWriteReservation.acquire(jdbc));
        var foreign=new StorageDataSource();foreign.setUrl("jdbc:sqlite:"+dir.resolve("two.db"));
        new TransactionTemplate(new DataSourceTransactionManager(foreign)).executeWithoutResult(s ->
            assertThrows(IllegalStateException.class,()->SqliteWriteReservation.acquire(jdbc)));
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.executeWithoutResult(s -> SqliteWriteReservation.acquire(jdbc));
        assertEquals(42,jdbc.queryForObject("SELECT revision FROM storage_generation WHERE id=1",Integer.class));
        jdbc.update("DELETE FROM storage_generation");
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s -> SqliteWriteReservation.acquire(jdbc)));
    }
}
