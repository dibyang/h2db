/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

/**
 * MVStore 空间回收维护态的最小访问规则。
 *
 * <p>该对象只表达受控维护入口的请求准入语义，不直接接入 SQL、TCP 或
 * TransactionStore。维护态允许新的只读请求进入，阻止新的写请求；只要仍有
 * 活跃读写事务，最终切换窗口就必须继续等待或超时失败。</p>
 */
public final class MVStoreSpaceReclamationMaintenance {

    private boolean active;
    private int activeReads;
    private int activeWrites;

    /**
     * 进入维护态。
     */
    public void enter() {
        active = true;
    }

    /**
     * 退出维护态。
     */
    public void exit() {
        active = false;
    }

    /**
     * 是否处于维护态。
     *
     * @return true 表示处于维护态
     */
    public boolean isActive() {
        return active;
    }

    /**
     * 尝试进入只读请求。
     *
     * @return true 表示允许读取
     */
    public boolean tryEnterRead() {
        activeReads++;
        return true;
    }

    /**
     * 退出只读请求。
     */
    public void exitRead() {
        if (activeReads > 0) {
            activeReads--;
        }
    }

    /**
     * 尝试进入写请求。
     *
     * @return true 表示允许写入
     */
    public boolean tryEnterWrite() {
        if (active) {
            return false;
        }
        activeWrites++;
        return true;
    }

    /**
     * 退出写请求。
     */
    public void exitWrite() {
        if (activeWrites > 0) {
            activeWrites--;
        }
    }

    /**
     * 活跃事务是否阻塞最终切换。
     *
     * @return true 表示需要等待或超时
     */
    public boolean isSwitchBlockedByActiveTransactions() {
        return activeReads > 0 || activeWrites > 0;
    }
}
