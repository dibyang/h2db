/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.backup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Collections;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.h2.api.MaterializedParticipantArtifact;
import org.h2.api.OnlineBackupContext;
import org.h2.api.OnlineBackupOptions;
import org.h2.api.OnlineBackupParticipantProvider;
import org.h2.api.ParticipantArtifactTarget;
import org.h2.api.PluginCapability;
import org.h2.api.PreparedBackupParticipant;
import org.h2.api.PreparedParticipantMetadata;
import org.h2.engine.Database;
import org.h2.engine.PluginSource;
import org.h2.engine.SessionLocal;
import org.h2.engine.backup.OnlineBackupManifest;
import org.h2.engine.backup.OnlineBackupManifestCodec;
import org.h2.engine.backup.OnlineBackupPublishResult;
import org.h2.engine.backup.OnlineBackupSession;
import org.h2.jdbc.JdbcConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P5 bundle publication, manifest, isolation, and failure tests.
 */
public class OnlineBackupBundlePublisherTest {

    @TempDir
    Path directory;

    /**
     * H2-only bundles contain an immutable manifest and a checksum-protected
     * database artifact.
     */
    @Test
    public void publishesH2OnlyBundleAndStableManifest() throws Exception {
        UUID backupId = UUID.randomUUID();
        Path bundle = directory.resolve("bundle-h2");
        try (Connection connection = connect("h2-only");
                OnlineBackupSession session = OnlineBackupSession.prepare(
                        database(connection), options(backupId))) {
            OnlineBackupPublishResult result = session.publish(bundle);
            assertFalse(result.isReused());
            assertEquals(bundle.toAbsolutePath(), result.getBundleDirectory());
            OnlineBackupManifest manifest = result.getManifest();
            assertEquals(OnlineBackupManifest.FORMAT_VERSION,
                    manifest.getFormatVersion());
            assertEquals(OnlineBackupManifest.STATUS_PUBLISHED,
                    manifest.getStatus());
            assertEquals(backupId, manifest.getBackupId());
            assertEquals("h2/database.mv.db",
                    manifest.getH2dbArtifact().getPath());
            assertTrue(manifest.getH2dbArtifact().getLength() > 0L);
            assertEquals(64, manifest.getH2dbArtifact().getSha256().length());
            assertTrue(Files.isRegularFile(bundle.resolve("manifest.json")));
            assertTrue(Files.isRegularFile(
                    bundle.resolve("h2/database.mv.db")));
            assertTrue(Files.isRegularFile(result.getAuditFile()));

            byte[] first = Files.readAllBytes(bundle.resolve("manifest.json"));
            assertArrayEquals(first,
                    OnlineBackupManifestCodec.encode(
                            OnlineBackupManifestCodec.decode(first)));
        }
    }

    /**
     * Participant metadata and files use deterministic ordering independent of
     * callback creation order.
     */
    @Test
    public void publishesSortedParticipantArtifactsAndMetadata()
            throws Exception {
        Path bundle = directory.resolve("bundle-participant");
        try (Connection connection = connect("participant")) {
            Database database = database(connection);
            TreeMap<String, String> attributes = new TreeMap<>();
            attributes.put("z", "last");
            attributes.put("a", "first");
            register(database, new FileProvider("external", attributes,
                    new String[] { "nested/z.bin", "a.bin" },
                    new byte[][] { { 3 }, { 1, 2 } }));
            try (OnlineBackupSession session = OnlineBackupSession.prepare(
                    database, options(UUID.randomUUID(), "external"))) {
                OnlineBackupManifest manifest =
                        session.publish(bundle).getManifest();
                assertEquals(1, manifest.getParticipants().size());
                OnlineBackupManifest.Participant participant =
                        manifest.getParticipants().get(0);
                assertEquals("external", participant.getParticipantId());
                assertEquals(Arrays.asList("a", "z"),
                        Arrays.asList(participant.getAttributes().keySet()
                                .toArray(new String[0])));
                assertEquals(2, participant.getArtifacts().size());
                assertTrue(participant.getArtifacts().get(0).getPath()
                        .endsWith("/a.bin"));
                assertTrue(participant.getArtifacts().get(1).getPath()
                        .endsWith("/nested/z.bin"));
            }
        }
    }

    /**
     * Readers tolerate unknown fields while preserving known fields.
     */
    @Test
    public void manifestReaderIgnoresUnknownFields() throws Exception {
        Path bundle = directory.resolve("bundle-future");
        try (Connection connection = connect("future");
                OnlineBackupSession session = OnlineBackupSession.prepare(
                        database(connection), options(UUID.randomUUID()))) {
            byte[] original = OnlineBackupManifestCodec.encode(
                    session.publish(bundle).getManifest());
            String extended = new String(original, StandardCharsets.UTF_8)
                    .replaceFirst("\\{",
                            "{\"future\":{\"nested\":[1,true]},");
            OnlineBackupManifest decoded = OnlineBackupManifestCodec.decode(
                    extended.getBytes(StandardCharsets.UTF_8));
            assertEquals(session.getContext().getBackupId(),
                    decoded.getBackupId());
        }
    }

    /**
     * The final directory remains invisible until participant materialization
     * and manifest durability complete.
     */
    @Test
    public void finalDirectoryAppearsOnlyAfterMaterialization()
            throws Exception {
        Path bundle = directory.resolve("bundle-visibility");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection connection = connect("visibility")) {
            Database database = database(connection);
            register(database, new BlockingProvider("blocking", entered,
                    release));
            try (OnlineBackupSession session = OnlineBackupSession.prepare(
                    database, options(UUID.randomUUID(), "blocking"))) {
                Future<OnlineBackupPublishResult> future = executor.submit(
                        () -> session.publish(bundle));
                assertTrue(entered.await(5L, TimeUnit.SECONDS));
                assertFalse(Files.exists(bundle));
                release.countDown();
                assertFalse(future.get(10L, TimeUnit.SECONDS).isReused());
                assertTrue(Files.isDirectory(bundle));
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * Traversal attempts fail closed, leave no final directory, and clean the
     * staging directory.
     */
    @Test
    public void rejectsParticipantTraversalAndCleansStaging()
            throws Exception {
        UUID backupId = UUID.randomUUID();
        Path bundle = directory.resolve("bundle-traversal");
        try (Connection connection = connect("traversal")) {
            Database database = database(connection);
            register(database, new TraversalProvider("unsafe"));
            try (OnlineBackupSession session = OnlineBackupSession.prepare(
                    database, options(backupId, "unsafe"))) {
                assertThrows(IllegalArgumentException.class,
                        () -> session.publish(bundle));
                assertFalse(Files.exists(bundle));
                assertFalse(Files.exists(directory.resolve(
                        ".bundle-traversal.staging-" + backupId)));
                Path audit = directory.resolve(".bundle-traversal."
                        + backupId + ".audit.json");
                assertTrue(new String(Files.readAllBytes(audit),
                        StandardCharsets.UTF_8).contains(
                                "\"status\":\"FAILED\""));
            }
        }
    }

    /**
     * Repeated calls reuse an identical published result, while a new cut with
     * the same backup ID cannot overwrite it.
     */
    @Test
    public void idempotentReuseAndIdentityConflict() throws Exception {
        UUID backupId = UUID.randomUUID();
        Path bundle = directory.resolve("bundle-idempotent");
        try (Connection connection = connect("idempotent")) {
            Database database = database(connection);
            OnlineBackupSession first = OnlineBackupSession.prepare(database,
                    options(backupId));
            OnlineBackupPublishResult initial = first.publish(bundle);
            assertTrue(initial == first.publish(bundle));
            first.close();

            try (OnlineBackupSession second = OnlineBackupSession.prepare(
                    database, options(backupId))) {
                assertThrows(IllegalStateException.class,
                        () -> second.publish(bundle));
                assertTrue(Files.isDirectory(bundle));
            }
        }
    }

    /**
     * Participant streams must be closed before the callback returns.
     */
    @Test
    public void rejectsLeakedParticipantOutputStream() throws Exception {
        Path bundle = directory.resolve("bundle-leaked-stream");
        try (Connection connection = connect("leaked-stream")) {
            Database database = database(connection);
            register(database, new LeakingProvider("leaking"));
            try (OnlineBackupSession session = OnlineBackupSession.prepare(
                    database, options(UUID.randomUUID(), "leaking"))) {
                Exception failure = assertThrows(Exception.class,
                        () -> session.publish(bundle));
                assertTrue(failure.getMessage().contains(
                        "left artifact streams open"));
                assertFalse(Files.exists(bundle));
            }
        }
    }

    private OnlineBackupOptions options(UUID backupId,
            String... participantIds) {
        return new OnlineBackupOptions(backupId,
                Arrays.asList(participantIds), 5_000L, 30_000L);
    }

    private static void register(Database database,
            OnlineBackupParticipantProvider provider) {
        database.getPluginRegistry().registerProvider(
                "test." + provider.getId(), "1", provider,
                PluginSource.CONFIGURED_CLASS);
    }

    private Connection connect(String name) throws Exception {
        String url = "jdbc:h2:"
                + directory.resolve(name).toAbsolutePath().toString()
                        .replace(File.separatorChar, '/')
                + ";ONLINE_BACKUP_COORDINATION=TRUE"
                + ";ONLINE_BACKUP_GENERATION_ID=" + UUID.randomUUID();
        return DriverManager.getConnection(url, "sa", "");
    }

    private static Database database(Connection connection) {
        return ((SessionLocal) ((JdbcConnection) connection).getSession())
                .getDatabase();
    }

    private abstract static class BaseProvider
            implements OnlineBackupParticipantProvider {

        private final String id;

        BaseProvider(String id) {
            this.id = id;
        }

        @Override
        public String getType() {
            return TYPE;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public boolean supports(String capability) {
            return PluginCapability.ONLINE_BACKUP_PREPARE.equals(capability);
        }

        PreparedParticipantMetadata metadata() {
            return new PreparedParticipantMetadata(id, "snapshot-" + id,
                    Collections.<String, String>emptyMap());
        }
    }

    private static final class FileProvider extends BaseProvider {

        private final TreeMap<String, String> attributes;
        private final String[] paths;
        private final byte[][] contents;

        FileProvider(String id, TreeMap<String, String> attributes,
                String[] paths, byte[][] contents) {
            super(id);
            this.attributes = attributes;
            this.paths = paths;
            this.contents = contents;
        }

        @Override
        public PreparedBackupParticipant prepare(OnlineBackupContext context) {
            return new PreparedBackupParticipant() {
                @Override
                public PreparedParticipantMetadata getPreparedMetadata() {
                    return new PreparedParticipantMetadata(getId(),
                            "snapshot-" + getId(), attributes);
                }

                @Override
                public MaterializedParticipantArtifact materialize(
                        ParticipantArtifactTarget target) throws Exception {
                    for (int i = 0; i < paths.length; i++) {
                        try (OutputStream output = target.create(paths[i])) {
                            output.write(contents[i]);
                        }
                    }
                    return new MaterializedParticipantArtifact(getId(),
                            Arrays.asList(paths));
                }

                @Override
                public void abort() {
                }
            };
        }
    }

    private static final class BlockingProvider extends BaseProvider {

        private final CountDownLatch entered;
        private final CountDownLatch release;

        BlockingProvider(String id, CountDownLatch entered,
                CountDownLatch release) {
            super(id);
            this.entered = entered;
            this.release = release;
        }

        @Override
        public PreparedBackupParticipant prepare(OnlineBackupContext context) {
            return new PreparedBackupParticipant() {
                @Override
                public PreparedParticipantMetadata getPreparedMetadata() {
                    return metadata();
                }

                @Override
                public MaterializedParticipantArtifact materialize(
                        ParticipantArtifactTarget target) throws Exception {
                    entered.countDown();
                    if (!release.await(5L, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("release timeout");
                    }
                    return new MaterializedParticipantArtifact(getId(),
                            Collections.<String>emptyList());
                }

                @Override
                public void abort() {
                }
            };
        }
    }

    private static final class TraversalProvider extends BaseProvider {

        TraversalProvider(String id) {
            super(id);
        }

        @Override
        public PreparedBackupParticipant prepare(OnlineBackupContext context) {
            return new PreparedBackupParticipant() {
                @Override
                public PreparedParticipantMetadata getPreparedMetadata() {
                    return metadata();
                }

                @Override
                public MaterializedParticipantArtifact materialize(
                        ParticipantArtifactTarget target) throws Exception {
                    target.create("../escape.bin");
                    return null;
                }

                @Override
                public void abort() {
                }
            };
        }
    }

    private static final class LeakingProvider extends BaseProvider {

        LeakingProvider(String id) {
            super(id);
        }

        @Override
        public PreparedBackupParticipant prepare(OnlineBackupContext context) {
            return new PreparedBackupParticipant() {
                @Override
                public PreparedParticipantMetadata getPreparedMetadata() {
                    return metadata();
                }

                @Override
                public MaterializedParticipantArtifact materialize(
                        ParticipantArtifactTarget target) throws Exception {
                    target.create("leaked.bin").write(1);
                    return new MaterializedParticipantArtifact(getId(),
                            Collections.singletonList("leaked.bin"));
                }

                @Override
                public void abort() {
                }
            };
        }
    }
}
