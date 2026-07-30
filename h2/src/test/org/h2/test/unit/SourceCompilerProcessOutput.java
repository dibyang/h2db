/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.unit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * 为 SourceCompiler 子进程测试生成可控输出或阻塞状态。
 */
public final class SourceCompilerProcessOutput {

    private SourceCompilerProcessOutput() {
    }

    /**
     * 运行测试子进程。
     *
     * @param args 模式及可选就绪文件
     * @throws Exception 文件操作失败
     */
    public static void main(String... args) throws Exception {
        if ("output".equals(args[0])) {
            writeOutput();
            System.exit(1);
        }
        Path marker = Paths.get(args[1]);
        Files.write(marker, new byte[] { 1 });
        Thread.sleep(TimeUnit.SECONDS.toMillis(30));
    }

    private static void writeOutput() throws IOException {
        StringBuilder padding = new StringBuilder(256);
        for (int i = 0; i < 256; i++) {
            padding.append('x');
        }
        for (int i = 0; i < 256; i++) {
            System.out.println("stdout-" + i + '-' + padding);
            System.err.println("stderr-" + i + '-' + padding);
        }
        System.out.println("STDOUT-END");
        System.err.println("STDERR-END");
    }
}
