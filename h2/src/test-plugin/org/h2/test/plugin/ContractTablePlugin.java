/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import java.util.Arrays;

import org.h2.api.H2Plugin;
import org.h2.api.PluginProvider;

/**
 * Table SPI contract plugin used by plugin architecture tests.
 */
public final class ContractTablePlugin implements H2Plugin {

    @Override
    public String getId() {
        return ContractTableProvider.PLUGIN_ID;
    }

    @Override
    public String getVersion() {
        return "1";
    }

    @Override
    public String getDisplayName() {
        return "Contract Table Plugin";
    }

    @Override
    public Iterable<? extends PluginProvider> getProviders() {
        return Arrays.asList(new ContractTableProvider(), new FailingContractTableProvider());
    }
}
