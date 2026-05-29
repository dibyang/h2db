/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.h2.api.H2Plugin;
import org.h2.api.PluginProvider;

/**
 * 数据库生命周期内的插件 provider 注册中心。
 */
public final class PluginRegistry {

    private final HashMap<String, HashMap<String, RegisteredProvider>> providers = new HashMap<>();

    /**
     * 注册一个插件中的全部 provider。
     *
     * @param plugin 插件
     * @param source 来源
     */
    public void registerPlugin(H2Plugin plugin, PluginSource source) {
        for (PluginProvider provider : plugin.getProviders()) {
            registerProvider(plugin.getId(), plugin.getVersion(), provider, source);
        }
    }

    /**
     * 注册 provider。
     *
     * @param pluginId 插件标识
     * @param pluginVersion 插件版本
     * @param provider provider
     * @param source 来源
     */
    public void registerProvider(String pluginId, String pluginVersion, PluginProvider provider, PluginSource source) {
        String type = provider.getType();
        String id = provider.getId();
        HashMap<String, RegisteredProvider> byId = providers.get(type);
        if (byId == null) {
            byId = new HashMap<>();
            providers.put(type, byId);
        }
        RegisteredProvider existing = byId.get(id);
        if (existing != null) {
            if (existing.source == PluginSource.BUILTIN || source == PluginSource.BUILTIN) {
                throw new IllegalArgumentException("Duplicate built-in plugin provider: " + type + '/' + id);
            }
            throw new IllegalArgumentException("Duplicate plugin provider: " + type + '/' + id);
        }
        byId.put(id, new RegisteredProvider(pluginId, pluginVersion, provider, source));
    }

    /**
     * 查找 provider。
     *
     * @param type provider 类型
     * @param id provider 标识
     * @return provider；未找到时返回 null
     */
    public PluginProvider findProvider(String type, String id) {
        RegisteredProvider registered = findRegisteredProvider(type, id);
        return registered == null ? null : registered.provider;
    }

    /**
     * 判断 provider 是否支持能力。
     *
     * @param type provider 类型
     * @param id provider 标识
     * @param capability 能力名称
     * @return 支持时返回 true
     */
    public boolean supports(String type, String id, String capability) {
        PluginProvider provider = findProvider(type, id);
        return provider != null && provider.supports(capability);
    }

    /**
     * 获取指定类型的 provider 注册视图。
     *
     * @param type provider 类型
     * @return 注册视图
     */
    public Map<String, RegisteredProvider> getProviders(String type) {
        HashMap<String, RegisteredProvider> byId = providers.get(type);
        return byId == null ? Collections.emptyMap() : Collections.unmodifiableMap(byId);
    }

    private RegisteredProvider findRegisteredProvider(String type, String id) {
        HashMap<String, RegisteredProvider> byId = providers.get(type);
        return byId == null ? null : byId.get(id);
    }

    /**
     * 已注册 provider 的描述。
     */
    public static final class RegisteredProvider {
        private final String pluginId;
        private final String pluginVersion;
        private final PluginProvider provider;
        private final PluginSource source;

        RegisteredProvider(String pluginId, String pluginVersion, PluginProvider provider, PluginSource source) {
            this.pluginId = pluginId;
            this.pluginVersion = pluginVersion;
            this.provider = provider;
            this.source = source;
        }

        /**
         * 获取插件标识。
         *
         * @return 插件标识
         */
        public String getPluginId() {
            return pluginId;
        }

        /**
         * 获取插件版本。
         *
         * @return 插件版本
         */
        public String getPluginVersion() {
            return pluginVersion;
        }

        /**
         * 获取 provider。
         *
         * @return provider
         */
        public PluginProvider getProvider() {
            return provider;
        }

        /**
         * 获取注册来源。
         *
         * @return 注册来源
         */
        public PluginSource getSource() {
            return source;
        }
    }
}
