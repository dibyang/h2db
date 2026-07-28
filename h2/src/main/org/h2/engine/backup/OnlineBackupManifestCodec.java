/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.backup;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.h2.util.json.JSONArray;
import org.h2.util.json.JSONByteArrayTarget;
import org.h2.util.json.JSONBytesSource;
import org.h2.util.json.JSONNumber;
import org.h2.util.json.JSONObject;
import org.h2.util.json.JSONString;
import org.h2.util.json.JSONValue;
import org.h2.util.json.JSONValueTarget;

/**
 * Stable UTF-8 codec for coordinated backup manifests.
 */
public final class OnlineBackupManifestCodec {

    private OnlineBackupManifestCodec() {
    }

    /**
     * Encode a manifest with stable field ordering.
     *
     * @param manifest manifest
     * @return UTF-8 JSON bytes
     */
    public static byte[] encode(OnlineBackupManifest manifest) {
        if (manifest == null) {
            throw new IllegalArgumentException("manifest must not be null");
        }
        JSONByteArrayTarget target = new JSONByteArrayTarget();
        target.startObject();
        number(target, "formatVersion", manifest.getFormatVersion());
        string(target, "status", manifest.getStatus());
        string(target, "backupId", manifest.getBackupId().toString());
        string(target, "cutId", manifest.getCutId().toString());
        string(target, "databaseName", manifest.getDatabaseName());
        string(target, "databaseId", manifest.getDatabaseId().toString());
        string(target, "sourceGenerationId",
                manifest.getSourceGenerationId().toString());
        number(target, "schemaEpoch", manifest.getSchemaEpoch());
        string(target, "h2dbVersion", manifest.getH2dbVersion());
        string(target, "createdAt", manifest.getCreatedAt());
        number(target, "preparePauseMillis",
                manifest.getPreparePauseMillis());
        target.member("h2dbArtifact");
        writeArtifact(target, manifest.getH2dbArtifact());
        target.member("participants");
        target.startArray();
        for (OnlineBackupManifest.Participant participant
                : manifest.getParticipants()) {
            target.startObject();
            string(target, "participantId", participant.getParticipantId());
            string(target, "pluginId", participant.getPluginId());
            string(target, "pluginVersion", participant.getPluginVersion());
            string(target, "snapshotId", participant.getSnapshotId());
            target.member("attributes");
            target.startObject();
            for (Map.Entry<String, String> entry
                    : participant.getAttributes().entrySet()) {
                string(target, entry.getKey(), entry.getValue());
            }
            target.endObject();
            target.member("artifacts");
            target.startArray();
            for (OnlineBackupManifest.Artifact artifact
                    : participant.getArtifacts()) {
                writeArtifact(target, artifact);
            }
            target.endArray();
            target.endObject();
        }
        target.endArray();
        target.endObject();
        return target.getResult();
    }

    /**
     * Decode a manifest. Unknown fields are ignored.
     *
     * @param bytes UTF-8 JSON bytes
     * @return decoded manifest
     */
    public static OnlineBackupManifest decode(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }
        JSONValue value = JSONBytesSource.parse(bytes,
                new JSONValueTarget());
        JSONObject object = object(value, "manifest");
        int formatVersion = integer(object, "formatVersion");
        String status = string(object, "status");
        UUID backupId = uuid(object, "backupId");
        UUID cutId = uuid(object, "cutId");
        String databaseName = string(object, "databaseName");
        UUID databaseId = uuid(object, "databaseId");
        UUID generationId = uuid(object, "sourceGenerationId");
        long schemaEpoch = longValue(object, "schemaEpoch");
        String h2dbVersion = string(object, "h2dbVersion");
        String createdAt = string(object, "createdAt");
        long preparePauseMillis = longValue(object, "preparePauseMillis");
        OnlineBackupManifest.Artifact h2dbArtifact = readArtifact(
                required(object, "h2dbArtifact"), "h2dbArtifact");
        JSONArray participantArray = array(required(object, "participants"),
                "participants");
        ArrayList<OnlineBackupManifest.Participant> participants =
                new ArrayList<>();
        for (JSONValue participantValue : participantArray.getArray()) {
            JSONObject participant = object(participantValue, "participant");
            String participantId = string(participant, "participantId");
            String pluginId = string(participant, "pluginId");
            String pluginVersion = string(participant, "pluginVersion");
            String snapshotId = string(participant, "snapshotId");
            JSONObject attributesObject = object(
                    required(participant, "attributes"), "attributes");
            TreeMap<String, String> attributes = new TreeMap<>();
            for (Map.Entry<String, JSONValue> entry
                    : attributesObject.getMembers()) {
                if (attributes.put(entry.getKey(),
                        string(entry.getValue(), "attribute value")) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate attribute: " + entry.getKey());
                }
            }
            JSONArray artifactArray = array(
                    required(participant, "artifacts"), "artifacts");
            ArrayList<OnlineBackupManifest.Artifact> artifacts =
                    new ArrayList<>();
            for (JSONValue artifactValue : artifactArray.getArray()) {
                artifacts.add(readArtifact(artifactValue,
                        "participant artifact"));
            }
            participants.add(new OnlineBackupManifest.Participant(
                    participantId, pluginId, pluginVersion, snapshotId,
                    attributes, artifacts));
        }
        return new OnlineBackupManifest(formatVersion, status, backupId,
                cutId, databaseName, databaseId, generationId, schemaEpoch,
                h2dbVersion, createdAt, preparePauseMillis, h2dbArtifact,
                participants);
    }

    private static void writeArtifact(JSONByteArrayTarget target,
            OnlineBackupManifest.Artifact artifact) {
        target.startObject();
        string(target, "path", artifact.getPath());
        number(target, "length", artifact.getLength());
        string(target, "sha256", artifact.getSha256());
        target.endObject();
    }

    private static OnlineBackupManifest.Artifact readArtifact(JSONValue value,
            String name) {
        JSONObject object = object(value, name);
        return new OnlineBackupManifest.Artifact(string(object, "path"),
                longValue(object, "length"), string(object, "sha256"));
    }

    private static void string(JSONByteArrayTarget target, String name,
            String value) {
        target.member(name);
        target.valueString(value);
    }

    private static void number(JSONByteArrayTarget target, String name,
            long value) {
        target.member(name);
        target.valueNumber(BigDecimal.valueOf(value));
    }

    private static JSONValue required(JSONObject object, String name) {
        JSONValue value = object.getFirst(name);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Missing manifest field: " + name);
        }
        return value;
    }

    private static String string(JSONObject object, String name) {
        return string(required(object, name), name);
    }

    private static String string(JSONValue value, String name) {
        if (!(value instanceof JSONString)) {
            throw new IllegalArgumentException(
                    "Manifest field is not a string: " + name);
        }
        return ((JSONString) value).getString();
    }

    private static long longValue(JSONObject object, String name) {
        JSONValue value = required(object, name);
        if (!(value instanceof JSONNumber)) {
            throw new IllegalArgumentException(
                    "Manifest field is not a number: " + name);
        }
        try {
            return ((JSONNumber) value).getBigDecimal().longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Manifest field is not an integer: " + name, e);
        }
    }

    private static int integer(JSONObject object, String name) {
        long value = longValue(object, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Manifest integer is out of range: " + name);
        }
        return (int) value;
    }

    private static UUID uuid(JSONObject object, String name) {
        try {
            return UUID.fromString(string(object, name));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid manifest UUID: " + name, e);
        }
    }

    private static JSONObject object(JSONValue value, String name) {
        if (!(value instanceof JSONObject)) {
            throw new IllegalArgumentException(
                    "Manifest field is not an object: " + name);
        }
        JSONObject object = (JSONObject) value;
        HashSet<String> names = new HashSet<>();
        for (Map.Entry<String, JSONValue> entry : object.getMembers()) {
            if (!names.add(entry.getKey())) {
                throw new IllegalArgumentException(
                        "Duplicate manifest field in " + name + ": "
                                + entry.getKey());
            }
        }
        return object;
    }

    private static JSONArray array(JSONValue value, String name) {
        if (!(value instanceof JSONArray)) {
            throw new IllegalArgumentException(
                    "Manifest field is not an array: " + name);
        }
        return (JSONArray) value;
    }
}
