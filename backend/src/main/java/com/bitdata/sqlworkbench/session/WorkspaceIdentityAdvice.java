package com.bitdata.sqlworkbench.session;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class WorkspaceIdentityAdvice {
    @ExceptionHandler(WorkspaceIdentity.Failure.class)
    public ResponseEntity<?> denied(WorkspaceIdentity.Failure error) {
        return ResponseEntity.status(error.status).body(Map.of("degraded",true,"code",error.code,"errorReason",error.code));
    }
}
