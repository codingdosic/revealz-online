package com.revealz.backend.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lobby")
public class WorkerProperties {
    private String publicHost = "127.0.0.1";
    private int portStart = 7700;
    private int portEnd = 7799;
    private long roomTtlMs = 600_000;
    private long matchTimeoutMs = 60_000;
    private long workerReadyMs = 120_000;
    private int warmPoolSize = 0;
    private String workerBin = "";
    private String metaLobbyUrl = "http://127.0.0.1:8080";
    private long credentialGraceMs = 300_000;
    private long admissionSeconds = 60;

    public String getPublicHost() { return publicHost; }
    public void setPublicHost(String value) { publicHost = value; }
    public int getPortStart() { return portStart; }
    public void setPortStart(int value) { portStart = value; }
    public int getPortEnd() { return portEnd; }
    public void setPortEnd(int value) { portEnd = value; }
    public long getRoomTtlMs() { return roomTtlMs; }
    public void setRoomTtlMs(long value) { roomTtlMs = value; }
    public long getMatchTimeoutMs() { return matchTimeoutMs; }
    public void setMatchTimeoutMs(long value) { matchTimeoutMs = value; }
    public long getWorkerReadyMs() { return workerReadyMs; }
    public void setWorkerReadyMs(long value) { workerReadyMs = value; }
    public int getWarmPoolSize() { return warmPoolSize; }
    public void setWarmPoolSize(int value) { warmPoolSize = Math.max(0, value); }
    public String getWorkerBin() { return workerBin; }
    public void setWorkerBin(String value) { workerBin = value; }
    public String getMetaLobbyUrl() { return metaLobbyUrl; }
    public void setMetaLobbyUrl(String value) { metaLobbyUrl = value; }
    public long getCredentialGraceMs() { return credentialGraceMs; }
    public void setCredentialGraceMs(long value) { credentialGraceMs = value; }
    public long getAdmissionSeconds() { return admissionSeconds; }
    public void setAdmissionSeconds(long value) { admissionSeconds = Math.max(1, value); }
}
