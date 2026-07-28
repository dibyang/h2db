/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.restore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.h2.api.ErrorCode;
import org.h2.api.PluginCapability;
import org.h2.api.PluginProvider;
import org.h2.engine.PluginRegistry;
import org.h2.engine.PluginRegistry.RegisteredProvider;
import org.h2.engine.PluginSource;
import org.h2.message.DbException;

/**
 * 将一次 shadow validation 明确允许的必要 provider 绑定到当前打开线程。
 * <p>
 * Database 构造器只消费一次该上下文；不扫描 ServiceLoader，也不把 provider
 * 选择放入全局静态 registry。Scope 关闭后始终清除 ThreadLocal。
 */
public final class ValidationProviderRegistry {

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private ValidationProviderRegistry() {
    }

    /**
     * 创建一次性 provider 注入作用域。
     *
     * @param registrations 已校验的必要 provider
     * @return 必须关闭的作用域
     */
    public static Scope open(List<Registration> registrations) {
        if (CURRENT.get() != null) {
            throw new IllegalStateException(
                    "Validation provider scope is already active");
        }
        Context context = new Context(registrations);
        CURRENT.set(context);
        return new Scope(context);
    }

    /**
     * 由 validation Database 构造器消费白名单并注册 provider。
     *
     * @param registry 新 shadow Database 的 registry
     */
    public static void installInto(PluginRegistry registry) {
        Context context = CURRENT.get();
        if (context == null || context.consumed) {
            throw DbException.get(ErrorCode.UNVALIDATABLE_PROVIDER_1,
                    "Missing one-shot validation provider context");
        }
        context.consumed = true;
        for (Registration registration : context.registrations) {
            PluginProvider provider = registration.provider;
            if (!provider.supports(PluginCapability.VALIDATION_OPEN)) {
                throw DbException.get(ErrorCode.UNVALIDATABLE_PROVIDER_1,
                        registration.type + '/' + registration.id);
            }
            RegisteredProvider existing = registry
                    .getProviders(registration.type).get(registration.id);
            if (existing != null) {
                if (!existing.getPluginId().equals(registration.pluginId)
                        || !existing.getPluginVersion().equals(
                                registration.pluginVersion)
                        || !existing.getProvider().supports(
                                PluginCapability.VALIDATION_OPEN)) {
                    throw DbException.get(ErrorCode.UNVALIDATABLE_PROVIDER_1,
                            registration.type + '/' + registration.id);
                }
                continue;
            }
            registry.registerProvider(registration.pluginId,
                    registration.pluginVersion, provider,
                    PluginSource.VALIDATION_ALLOWLIST);
        }
    }

    /**
     * 必要 provider 的不可变注册描述。
     */
    public static final class Registration {

        private final String type;
        private final String id;
        private final String pluginId;
        private final String pluginVersion;
        private final PluginProvider provider;

        /**
         * 创建描述。
         *
         * @param type provider 类型
         * @param id provider ID
         * @param pluginId 插件 ID
         * @param pluginVersion 插件版本
         * @param provider provider 实例
         */
        public Registration(String type, String id, String pluginId,
                String pluginVersion, PluginProvider provider) {
            this.type = requireNonBlank(type, "type");
            this.id = requireNonBlank(id, "id");
            this.pluginId = requireNonBlank(pluginId, "pluginId");
            this.pluginVersion = requireNonBlank(pluginVersion,
                    "pluginVersion");
            if (provider == null || !type.equals(provider.getType())
                    || !id.equals(provider.getId())) {
                throw new IllegalArgumentException(
                        "Provider registration identity mismatch");
            }
            this.provider = provider;
        }

        /**
         * @return provider 类型
         */
        public String getType() {
            return type;
        }

        /**
         * @return provider ID
         */
        public String getId() {
            return id;
        }

        /**
         * @return 插件 ID
         */
        public String getPluginId() {
            return pluginId;
        }

        /**
         * @return 插件版本
         */
        public String getPluginVersion() {
            return pluginVersion;
        }

        /**
         * @return provider 实例
         */
        public PluginProvider getProvider() {
            return provider;
        }
    }

    /**
     * 当前线程的单次 validation scope。
     */
    public static final class Scope implements AutoCloseable {

        private final Context context;
        private boolean closed;

        Scope(Context context) {
            this.context = context;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                if (CURRENT.get() == context) {
                    CURRENT.remove();
                }
            }
        }
    }

    private static final class Context {

        final List<Registration> registrations;
        boolean consumed;

        Context(List<Registration> registrations) {
            if (registrations == null) {
                throw new IllegalArgumentException(
                        "registrations must not be null");
            }
            this.registrations = Collections.unmodifiableList(
                    new ArrayList<>(registrations));
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }
}
