package com.launchapk.java.launcher;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** JVM test fixture, never packaged in the app. */
public final class GameMainFixture {
    public static void main(String[] args) throws Exception {
        try (FileOutputStream output = new FileOutputStream(args[0])) {
            output.write(args[1].getBytes(StandardCharsets.UTF_8));
        }
    }
}
