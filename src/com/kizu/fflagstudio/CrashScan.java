package com.kizu.fflagstudio;

import java.util.Locale;

/**
 * Reads the client crash buffer through the shell and guesses what to dial back.
 * Deliberately a heuristic: a native crash stack does not name the flag that caused it,
 * so this only separates memory pressure, graphics/GPU faults and the rest.
 */
final class CrashScan {
    static final String NONE = "none";

    /** none | memory | graphics | native */
    final String kind;
    final String evidence;

    private CrashScan(String kind, String evidence) {
        this.kind = kind;
        this.evidence = evidence;
    }

    static CrashScan run(String pkg) {
        String raw;
        try {
            raw = ShizukuShell.run("logcat -b crash -d -t 600 2>/dev/null || true");
        } catch (Throwable t) {
            return new CrashScan(NONE, "crash buffer unreadable");
        }
        return of(raw, pkg);
    }

    static CrashScan of(String raw, String pkg) {
        if (raw == null || raw.trim().isEmpty()) {
            return new CrashScan(NONE, "no crash logged since boot");
        }
        String block = lastBlock(raw, pkg);
        String low = block.toLowerCase(Locale.ROOT);
        String kind;
        if (low.contains("outofmemory") || low.contains("low memory") || low.contains("lmkd")
                || low.contains("cannot allocate") || low.contains("pthread_create")) {
            kind = "memory";
        } else if (low.contains("vulkan") || low.contains("egl") || low.contains("gralloc")
                || low.contains("gles") || low.contains("gpu")) {
            kind = "graphics";
        } else {
            kind = "native";
        }
        return new CrashScan(kind, evidence(block));
    }

    private static String lastBlock(String raw, String pkg) {
        String[] blocks = raw.split("\n\n");
        for (int i = blocks.length - 1; i >= 0; i--) {
            if (blocks[i].contains(pkg) || blocks[i].contains("libroblox")) return blocks[i];
        }
        return blocks[blocks.length - 1];
    }

    private static String evidence(String block) {
        StringBuilder sb = new StringBuilder();
        for (String line : block.split("\n")) {
            String low = line.toLowerCase(Locale.ROOT);
            if (!(low.contains("fatal") || low.contains("signal") || low.contains("abort")
                    || low.contains("outofmemory") || low.contains("vulkan") || low.contains("egl")
                    || low.contains("pthread_create"))) {
                continue;
            }
            sb.append(line.trim()).append('\n');
            if (sb.length() > 400) break;
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? block.trim() : out;
    }
}
