package com.launchapk.java.launcher.game;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.util.*;

/** Original Java 8 bootstrap. This class and the game run in HotSpot, not ART. */
public final class GameMain {
    private static void record(Path file, String state) throws IOException {
        Properties value = new Properties();
        try (InputStream input = Files.newInputStream(file)) { value.load(input); }
        value.setProperty("state", state);
        Path temporary = file.resolveSibling(file.getFileName() + ".jvm.tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) { value.store(output, "LaunchAPK game session"); }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    public static void main(String[] args) throws Throwable {
        if (args.length < 2) throw new IllegalArgumentException("Missing game session/main class");
        final Path receipt = Paths.get(args[0]);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Properties latest = new Properties();
                try (InputStream input = Files.newInputStream(receipt)) { latest.load(input); }
                if (!"error".equals(latest.getProperty("state"))) record(receipt, "vm_shutdown");
            } catch (IOException failure) { failure.printStackTrace(); }
        }, "LaunchAPK-game-shutdown"));
        record(receipt, "java_main");
        System.out.println("[LaunchAPK] Calling game main: " + args[1]);
        try {
            Class<?> game = Class.forName(args[1], true, ClassLoader.getSystemClassLoader());
            game.getMethod("main", String[].class).invoke(null, (Object) Arrays.copyOfRange(args, 2, args.length));
        } catch (InvocationTargetException wrapped) {
            record(receipt, "error");
            throw wrapped.getCause();
        } catch (Throwable failure) {
            record(receipt, "error"); throw failure;
        }
        record(receipt, "main_returned");
    }
}
