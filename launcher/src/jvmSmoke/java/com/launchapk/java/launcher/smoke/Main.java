package com.launchapk.java.launcher.smoke;

import java.io.FileOutputStream;
import java.util.Properties;

/** Original Java 8 bytecode probe, run by the imported OpenJDK, never by Android ART. */
public final class Main {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !"LaunchAPKJava".equals(args[0]) || !"含空格 参数 🌍".equals(args[1])) {
            throw new IllegalArgumentException("Java main argv verification failed");
        }
        Properties result = new Properties();
        result.setProperty("marker", "LaunchAPKJava.main.v1");
        result.setProperty("argument", args[1]);
        result.setProperty("java.version", System.getProperty("java.version"));
        result.setProperty("java.vm.name", System.getProperty("java.vm.name"));
        result.setProperty("user.dir", System.getProperty("user.dir"));
        try (FileOutputStream output = new FileOutputStream(args[2])) {
            result.store(output, "LaunchAPK Java main invocation probe");
            output.getFD().sync();
        }
        System.out.println("LaunchAPK Java main completed");
    }
}
