package com.revealz.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.revealz.backend.web.ApiException;

class WorkerLifecycleTest {
    @TempDir
    Path tempDirectory;

    @Test
    void spawnFailureReturnsReservedPortExactlyOnce() {
        WorkerProperties properties = new WorkerProperties();
        properties.setPortStart(7799);
        properties.setPortEnd(7799);
        properties.setWorkerBin("definitely-missing-worker");
        WorkerManager workers = new WorkerManager(properties);
        try {
            assertThatThrownBy(workers::acquire).isInstanceOf(ApiException.class);
            assertThat(workers.status()).containsEntry("rooms", 0).containsEntry("freePorts", 1);
        } finally { workers.shutdown(); }
    }

    @Test
    void fingerprintIsReusedForTheProcessLifetime() throws Exception {
        Path worker = tempDirectory.resolve("worker");
        Files.writeString(worker, "first");
        WorkerProperties properties = new WorkerProperties();
        properties.setWorkerBin(worker.toString());
        WorkerManager workers = new WorkerManager(properties);
        try {
            var first = workers.fingerprint();
            Files.writeString(worker, "changed");

            assertThat(workers.fingerprint()).isSameAs(first);
            assertThat(first).containsEntry("bytes", 5L);
        } finally { workers.shutdown(); }
    }

    @Test
    void linuxWorkerReadyOutputIsDelivered() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path worker = tempDirectory.resolve("worker.sh");
        Files.writeString(worker, "#!/bin/sh\necho '[MP-SERVER] listening'\nsleep 30\n");
        Files.setPosixFilePermissions(worker, PosixFilePermissions.fromString("rwx------"));
        WorkerProperties properties = new WorkerProperties();
        properties.setWorkerBin(worker.toString());
        properties.setPortStart(7899);
        properties.setPortEnd(7899);
        properties.setWorkerReadyMs(2_000);
        WorkerManager workers = new WorkerManager(properties);
        try {
            assertThat(workers.acquire().port()).isEqualTo(7899);
        } finally { workers.shutdown(); }
    }
}
