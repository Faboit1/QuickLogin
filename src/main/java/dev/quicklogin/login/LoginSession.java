package dev.quicklogin.login;

/**
 * Per-connection state held between the login-start and encryption-response
 * packets while a premium handshake is in progress.
 */
public final class LoginSession {

    private final String username;
    private final String usernameLower;
    private final byte[] verifyToken;

    public LoginSession(String username, byte[] verifyToken) {
        this.username = username;
        this.usernameLower = username.toLowerCase(java.util.Locale.ROOT);
        this.verifyToken = verifyToken;
    }

    public String username() {
        return username;
    }

    public String usernameLower() {
        return usernameLower;
    }

    public byte[] verifyToken() {
        return verifyToken;
    }
}
