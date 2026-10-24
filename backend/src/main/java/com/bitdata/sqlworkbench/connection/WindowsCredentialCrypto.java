package com.bitdata.sqlworkbench.connection;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;
import org.springframework.stereotype.Component;

@Component
public class WindowsCredentialCrypto implements CredentialCrypto {
    public void requireAvailable() {
        if (!Platform.isWindows()) throw unavailable();
        try { com.sun.jna.platform.win32.Crypt32.INSTANCE.getClass(); }
        catch (LinkageError | RuntimeException e) { throw unavailable(); }
    }
    public byte[] protect(byte[] plaintext) {
        requireAvailable();
        try {
            return Crypt32Util.cryptProtectData(plaintext, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
        } catch (LinkageError e) {
            throw unavailable();
        } catch (RuntimeException e) {
            throw new CredentialFailure("CREDENTIAL_SAVE_FAILED",503,"Windows could not protect the password. Retry saving.");
        }
    }
    public byte[] unprotect(byte[] ciphertext) {
        requireAvailable();
        try {
            return Crypt32Util.cryptUnprotectData(ciphertext, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
        } catch (LinkageError e) {
            throw unavailable();
        } catch (RuntimeException e) {
            throw new CredentialFailure("CREDENTIAL_DECRYPT_FAILED",409,"The password cannot be unlocked by this Windows user. Edit the connection and re-enter it.");
        }
    }
    private static CredentialFailure unavailable() {
        return new CredentialFailure("CREDENTIAL_BACKEND_UNAVAILABLE",503,"Connection credentials require Windows DPAPI. This platform or native runtime is unavailable.");
    }
}
