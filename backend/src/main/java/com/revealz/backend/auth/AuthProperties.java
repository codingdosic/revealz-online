package com.revealz.backend.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("auth")
public class AuthProperties {
    private String googleClientId = "";
    private String jwtSecret = "";
    private String issuer = "revealz-backend";
    private String audience = "revealz-windows";
    private long accessSeconds = 900;
    private long refreshSeconds = 604800;
    private long loginAttemptSeconds = 300;
    private int loginAttemptLimit = 1000;
    public String getGoogleClientId() { return googleClientId; }
    public void setGoogleClientId(String value) { googleClientId = value; }
    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String value) { jwtSecret = value; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String value) { issuer = value; }
    public String getAudience() { return audience; }
    public void setAudience(String value) { audience = value; }
    public long getAccessSeconds() { return accessSeconds; }
    public void setAccessSeconds(long value) { accessSeconds = value; }
    public long getRefreshSeconds() { return refreshSeconds; }
    public void setRefreshSeconds(long value) { refreshSeconds = value; }
    public long getLoginAttemptSeconds() { return loginAttemptSeconds; }
    public void setLoginAttemptSeconds(long value) { loginAttemptSeconds = value; }
    public int getLoginAttemptLimit() { return loginAttemptLimit; }
    public void setLoginAttemptLimit(int value) { loginAttemptLimit = value; }
}
