package com.kizu.fflagstudio;

import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/** Shell access through Shizuku (uid 2000, no root). */
final class ShizukuShell {
    private ShizukuShell() {}

    static boolean alive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean granted() {
        if (!alive()) return false;
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    static void request(int code) {
        Shizuku.requestPermission(code);
    }

    static String uid() {
        try {
            return run("id -u").trim();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Runs a command through sh and returns stdout; throws with the full picture on failure. */
    static String run(String cmd) throws Exception {
        IShizukuService service = IShizukuService.Stub.asInterface(Shizuku.getBinder());
        if (service == null) throw new IllegalStateException("Shizuku service unavailable");
        IRemoteProcess proc = service.newProcess(new String[]{"sh", "-c", cmd}, null, null);
        String out = read(proc.getInputStream());
        String err = read(proc.getErrorStream());
        int code = proc.waitFor();
        if (code != 0) {
            // devices differ in whether the failing step talks on stderr: keep both
            throw new IllegalStateException("exit " + code
                    + "\ncmd: " + cmd
                    + (err.trim().isEmpty() ? "" : "\nstderr: " + err.trim())
                    + (out.trim().isEmpty() ? "" : "\nstdout: " + out.trim()));
        }
        return out;
    }

    /** Runs a command and feeds `input` to its stdin (used to write the flag file verbatim). */
    static String runWithInput(String cmd, String input) throws Exception {
        IShizukuService service = IShizukuService.Stub.asInterface(Shizuku.getBinder());
        if (service == null) throw new IllegalStateException("Shizuku service unavailable");
        IRemoteProcess proc = service.newProcess(new String[]{"sh", "-c", cmd}, null, null);
        try (OutputStream stdin = new ParcelFileDescriptor.AutoCloseOutputStream(proc.getOutputStream())) {
            stdin.write(input.getBytes(StandardCharsets.UTF_8));
        }
        String out = read(proc.getInputStream());
        String err = read(proc.getErrorStream());
        int code = proc.waitFor();
        if (code != 0) {
            throw new IllegalStateException("exit " + code
                    + "\ncmd: " + cmd
                    + (err.trim().isEmpty() ? "" : "\nstderr: " + err.trim())
                    + (out.trim().isEmpty() ? "" : "\nstdout: " + out.trim()));
        }
        return out;
    }

    private static String read(ParcelFileDescriptor pfd) throws Exception {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return buf.toString(StandardCharsets.UTF_8.name());
        }
    }
}
