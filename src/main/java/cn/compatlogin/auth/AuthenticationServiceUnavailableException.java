package cn.compatlogin.auth;

public final class AuthenticationServiceUnavailableException extends Exception {
    public AuthenticationServiceUnavailableException(String message) {
        super(message);
    }

    public AuthenticationServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
