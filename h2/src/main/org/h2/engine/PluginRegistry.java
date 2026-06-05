/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.h2.api.H2Plugin;
import org.h2.api.PluginCapability;
import org.h2.api.PluginProvider;

/**
 * 数据库生命周期内的插件 provider 注册中心。
 */
public final class PluginRegistry {

    private final HashMap<String, HashMap<String, RegisteredProvider>> providers = new HashMap<>();
    private final HashMap<String, HashSet<String>> pluginVersions = new HashMap<>();

    /**
     * 注册一个插件中的全部 provider。
     *
     * @param plugin 插件
     * @param source 来源
     */
    public void registerPlugin(H2Plugin plugin, PluginSource source) {
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin must not be null");
        }
        String pluginId = requireNonBlank(plugin.getId(), "Plugin id");
        String pluginVersion = requireNonBlank(plugin.getVersion(), "Plugin version");
        if (source == null) {
            throw new IllegalArgumentException("Plugin source must not be null");
        }
        Iterable<? extends PluginProvider> pluginProviders = plugin.getProviders();
        if (pluginProviders == null) {
            throw new IllegalArgumentException("Plugin providers must not be null: plugin=" + pluginId);
        }
        ArrayList<PluginProvider> checkedProviders = new ArrayList<>();
        for (PluginProvider provider : pluginProviders) {
            if (provider == null) {
                throw new IllegalArgumentException("Plugin provider must not be null: plugin=" + pluginId);
            }
            checkedProviders.add(provider);
        }
        if (checkedProviders.isEmpty()) {
            throw new IllegalArgumentException("Plugin must provide at least one provider: plugin=" + pluginId);
        }
        if (hasPluginVersion(pluginId, pluginVersion)) {
            throw new IllegalArgumentException("Duplicate plugin version: plugin=" + pluginId
                    + ", version=" + pluginVersion);
        }
        for (PluginProvider provider : checkedProviders) {
            registerProvider(pluginId, pluginVersion, provider, source);
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
        pluginId = requireNonBlank(pluginId, "Plugin id");
        pluginVersion = requireNonBlank(pluginVersion, "Plugin version");
        if (provider == null) {
            throw new IllegalArgumentException("Plugin provider must not be null: plugin=" + pluginId);
        }
        if (source == null) {
            throw new IllegalArgumentException("Plugin source must not be null: plugin=" + pluginId);
        }
        String type = requireNonBlank(provider.getType(), "Provider type");
        String id = requireNonBlank(provider.getId(), "Provider id");
        HashMap<String, RegisteredProvider> byId = providers.get(type);
        if (byId == null) {
            byId = new HashMap<>();
            providers.put(type, byId);
        }
        RegisteredProvider existing = byId.get(id);
        if (existing != null) {
            if (existing.source == PluginSource.BUILTIN || source == PluginSource.BUILTIN) {
                throw duplicateProviderException("Duplicate built-in plugin provider", type, id, existing, source);
            }
            throw duplicateProviderException("Duplicate plugin provider", type, id, existing, source);
        }
        byId.put(id, new RegisteredProvider(pluginId, pluginVersion, provider, source));
        addPluginVersion(pluginId, pluginVersion);
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

    /**
     * 判断插件是否已经注册 provider。
     *
     * @param pluginId 插件标识
     * @return 已注册时返回 true
     */
    public boolean hasPlugin(String pluginId) {
        return pluginVersions.containsKey(pluginId);
    }

    /**
     * 判断指定版本范围的插件是否已注册。
     *
     * @param pluginId 插件标识
     * @param versionRange 版本或版本范围
     * @return 存在匹配版本时返回 true
     */
    public boolean hasPlugin(String pluginId, String versionRange) {
        HashSet<String> versions = pluginVersions.get(pluginId);
        if (versions == null) {
            return false;
        }
        for (String version : versions) {
            if (PluginVersionRange.matches(version, versionRange)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPluginVersion(String pluginId, String pluginVersion) {
        HashSet<String> versions = pluginVersions.get(pluginId);
        return versions != null && versions.contains(pluginVersion);
    }

    private void addPluginVersion(String pluginId, String pluginVersion) {
        HashSet<String> versions = pluginVersions.get(pluginId);
        if (versions == null) {
            versions = new HashSet<>();
            pluginVersions.put(pluginId, versions);
        }
        versions.add(pluginVersion);
    }

    /**
     * 获取 provider 诊断快照。
     *
     * @return provider 诊断快照列表
     */
    public List<ProviderDiagnostic> getProviderDiagnostics() {
        ArrayList<ProviderDiagnostic> diagnostics = new ArrayList<>();
        for (Map.Entry<String, HashMap<String, RegisteredProvider>> byType : providers.entrySet()) {
            String type = byType.getKey();
            for (RegisteredProvider registered : byType.getValue().values()) {
                PluginProvider provider = registered.provider;
                ArrayList<String> capabilities = new ArrayList<>();
                for (String capability : PluginCapability.all()) {
                    if (provider.supports(capability)) {
                        capabilities.add(capability);
                    }
                }
                diagnostics.add(new ProviderDiagnostic(type, provider.getId(), registered.pluginId,
                        registered.pluginVersion, registered.source, capabilities));
            }
        }
        return Collections.unmodifiableList(diagnostics);
    }

    private RegisteredProvider findRegisteredProvider(String type, String id) {
        HashMap<String, RegisteredProvider> byId = providers.get(type);
        return byId == null ? null : byId.get(id);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    private static IllegalArgumentException duplicateProviderException(String prefix, String type, String id,
            RegisteredProvider existing, PluginSource newSource) {
        return new IllegalArgumentException(prefix + ": type=" + type + ", id=" + id + ", existingPlugin="
                + existing.pluginId + ", existingSource=" + existing.source + ", newSource=" + newSource);
    }

    /**
     * provider 诊断快照。
     */
    public static final class ProviderDiagnostic {
        private final String type;
        private final String id;
        private final String pluginId;
        private final String pluginVersion;
        private final PluginSource source;
        private final List<String> capabilities;

        ProviderDiagnostic(String type, String id, String pluginId, String pluginVersion, PluginSource source,
                List<String> capabilities) {
            this.type = type;
            this.id = id;
            this.pluginId = pluginId;
            this.pluginVersion = pluginVersion;
            this.source = source;
            this.capabilities = Collections.unmodifiableList(new ArrayList<>(capabilities));
        }

        /**
         * 获取 provider 类型。
         *
         * @return provider 类型
         */
        public String getType() {
            return type;
        }

        /**
         * 获取 provider 标识。
         *
         * @return provider 标识
         */
        public String getId() {
            return id;
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
         * 获取注册来源。
         *
         * @return 注册来源
         */
        public PluginSource getSource() {
            return source;
        }

        /**
         * 获取支持的 capability。
         *
         * @return 支持的 capability 只读列表
         */
        public List<String> getCapabilities() {
            return capabilities;
        }
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
