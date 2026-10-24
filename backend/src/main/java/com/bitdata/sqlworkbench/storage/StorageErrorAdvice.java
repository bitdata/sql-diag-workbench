package com.bitdata.sqlworkbench.storage;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice
public class StorageErrorAdvice {
    @ExceptionHandler(StorageService.Failure.class)
    public ResponseEntity<Map<String,Object>> conflict(StorageService.Failure error) {
        return ResponseEntity.status(error.status).body(Map.of("code",error.code,"errorReason",error.code,"message",error.getMessage(),"degraded",true));
    }
    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    public ResponseEntity<Map<String,Object>> database(RuntimeException error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) if(cause instanceof StorageDataSource.Maintenance)
            return ResponseEntity.status(503).body(Map.of("code","STORAGE_MAINTENANCE","errorReason","STORAGE_MAINTENANCE","message","Storage maintenance is in progress. Retry saving.","degraded",true));
        return ResponseEntity.internalServerError().body(Map.of("code","STORAGE_ERROR","errorReason","STORAGE_ERROR","message","The storage operation could not be confirmed.","degraded",true));
    }
}
