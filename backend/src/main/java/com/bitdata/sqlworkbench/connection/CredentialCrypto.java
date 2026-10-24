package com.bitdata.sqlworkbench.connection;

public interface CredentialCrypto {
    void requireAvailable();
    byte[] protect(byte[] plaintext);
    byte[] unprotect(byte[] ciphertext);
}
