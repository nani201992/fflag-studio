package com.kizu.fflagstudio;

import android.app.ActivityManager;
import android.content.Context;

import java.util.Locale;

/** What this phone can take; drives the per-tier flag caps in the Rust engine. */
final class DeviceProfile {
    final String tier;   // low | mid | high
    final String label;  // shown in the footer and in the optimize report

    private DeviceProfile(String tier, String label) {
        this.tier = tier;
        this.label = label;
    }

    static DeviceProfile of(Context ctx) {
        long ram = 0;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            ram = info.totalMem;
        } catch (Throwable ignored) {
        }
        int cores = Runtime.getRuntime().availableProcessors();
        long gb = 1024L * 1024L * 1024L;
        String tier = (ram < 4 * gb || cores <= 4) ? "low"
                : (ram >= 7 * gb && cores >= 8) ? "high" : "mid";
        String ramText = ram > 0
                ? String.format(Locale.ROOT, "%.1f GB", ram / (double) gb)
                : "? GB";
        return new DeviceProfile(tier, tier + " · " + cores + " cores · " + ramText);
    }
}
