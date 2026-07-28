/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import java.util.Collections;
import java.util.UUID;

import org.h2.api.H2Plugin;
import org.h2.api.PluginCapability;
import org.h2.api.PluginProvider;
import org.h2.api.TransactionContext;
import org.h2.api.TransactionEventProvider;
import org.h2.engine.SessionLocal;
import org.h2.mvstore.tx.TransactionMap;
import org.h2.mvstore.type.StringDataType;

/**
 * 身份元数据存储原型插件。
 *
 * <p>该插件只在测试显式启用时，把身份元数据写入当前 DDL 的
 * TransactionStore 事务，用于验证独立内部 map 的可行性。它不是正式接口或实现。</p>
 */
public final class IdentityMetadataPrototypePlugin implements H2Plugin {

    static final String MAP_NAME = "h2.onlineBackup.meta";
    static final String DATABASE_ID = "databaseId";
    static final String SCHEMA_EPOCH = "schemaEpoch";

    private static final PrototypeTransactionProvider PROVIDER = new PrototypeTransactionProvider();

    private static volatile boolean enabled;
    private static volatile boolean failAfterWrite;

    /**
     * 启用原型写入。
     *
     * @param fail 是否在写入后、提交前注入失败
     */
    static void enable(boolean fail) {
        failAfterWrite = fail;
        enabled = true;
    }

    /**
     * 禁用原型写入并清除故障注入。
     */
    static void disable() {
        enabled = false;
        failAfterWrite = false;
    }

    @Override
    public String getId() {
        return "test.online-backup.identity-metadata-prototype";
    }

    @Override
    public String getVersion() {
        return "1";
    }

    @Override
    public String getDisplayName() {
        return "Online Backup Identity Metadata Prototype";
    }

    @Override
    public Iterable<? extends PluginProvider> getProviders() {
        return Collections.singleton(PROVIDER);
    }

    /**
     * 在 DDL commit 前把 identity 和 epoch 写入同一个 TransactionStore 事务。
     */
    private static final class PrototypeTransactionProvider implements TransactionEventProvider {

        @Override
        public String getType() {
            return TYPE;
        }

        @Override
        public String getId() {
            return "online_backup_identity_metadata_prototype";
        }

        @Override
        public boolean supports(String capability) {
            return PluginCapability.TRANSACTION_EVENTS.equals(capability);
        }

        @Override
        public void beforeCommit(TransactionContext context) {
            if (!enabled || !context.isDdl()) {
                return;
            }
            SessionLocal session = findSession(context);
            TransactionMap<String, String> map = session.getTransaction().openMap(
                    MAP_NAME, StringDataType.INSTANCE, StringDataType.INSTANCE);
            String databaseId = map.get(DATABASE_ID);
            if (databaseId == null) {
                map.put(DATABASE_ID, UUID.randomUUID().toString());
            }
            String epochValue = map.get(SCHEMA_EPOCH);
            long epoch = epochValue == null ? 0L : Long.parseLong(epochValue);
            map.put(SCHEMA_EPOCH, Long.toString(epoch + 1L));
            if (failAfterWrite) {
                throw new IllegalStateException("simulated failure after identity metadata write");
            }
        }

        /**
         * 根据事务事件中的 session ID 找回正在提交的本地 session。
         */
        private static SessionLocal findSession(TransactionContext context) {
            int sessionId = context.getSessionId();
            for (SessionLocal session : context.getDatabase().getSessions(true)) {
                if (session.getId() == sessionId) {
                    return session;
                }
            }
            throw new IllegalStateException("Session not found: " + sessionId);
        }
    }
}
