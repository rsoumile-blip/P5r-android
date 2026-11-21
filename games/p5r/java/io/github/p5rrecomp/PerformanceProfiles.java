package io.github.p5rrecomp;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Launcher-only graphics profiles and the user's saved Manual graphics configuration. */
final class PerformanceProfiles {
    static final String MANUAL = "Manual";
    static final String[] MODES = {"Performance", "Balanced", "Quality", MANUAL};
    static final String[] KEYS = {"RESOLUTION", "NX_FSR", "NX_FPS_CAP", "NX_SHADOWS",
            "NX_MALI_LOW_SHADOWS", "GRAPHICS", "NX_NO_CROWD", "NX_DISABLE_BLOOM"};
    static final String[] CONFIG_KEYS = {"RESOLUTION", "NX_FSR", "NX_FPS_CAP", "NX_SHADOWS",
            "NX_MALI_LOW_SHADOWS", "GRAPHICS", "NX_NO_CROWD", "NX_DISABLE_BLOOM", "NX_HANDHELD",
            "resolution", "NX_RENDER_RES", "NX_RES", "NX_LOD_BIAS"};

    private PerformanceProfiles() {}

    static boolean isProfile(String mode) {
        for (String candidate : MODES) if (candidate.equals(mode)) return true;
        return false;
    }

    static Map<String, String> values(String mode) {
        Map<String, String> out = new LinkedHashMap<>();
        if ("Performance".equals(mode)) {
            put(out, "480p", "1", "30", "0", "1", "low", "1", "1");
        } else if ("Balanced".equals(mode)) {
            put(out, "720p", "1", "30", "0", "1", "medium", "0", "0");
        } else if ("Quality".equals(mode)) {
            put(out, "1080p", "0", "30", "1", "0", "high", "0", "0");
        }
        return out;
    }

    private static void put(Map<String, String> out, String resolution, String fsr, String fps, String shadows,
                            String lowShadows, String graphics, String crowd, String bloom) {
        out.put("RESOLUTION", resolution);
        out.put("NX_FSR", fsr);
        out.put("NX_FPS_CAP", fps);
        out.put("NX_SHADOWS", shadows);
        out.put("NX_MALI_LOW_SHADOWS", lowShadows);
        out.put("GRAPHICS", graphics);
        out.put("NX_NO_CROWD", crowd);
        out.put("NX_DISABLE_BLOOM", bloom);
    }

    static Map<String, String> snapshot(Map<String, String> config) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : CONFIG_KEYS) if (config.containsKey(key)) out.put(key, config.get(key));
        return out;
    }

    static boolean controls(String key) {
        for (String known : KEYS) if (known.equals(key)) return true;
        return false;
    }

    static void updateManualValue(Map<String, String> snapshot, String key, String value) {
        snapshot.put(key, value);
        if ("RESOLUTION".equals(key)) {
            String handheld = "1080p".equals(value) ? "0" : "1";
            snapshot.put("NX_HANDHELD", handheld);
            snapshot.put("resolution", value);
            snapshot.put("NX_RENDER_RES", value);
            snapshot.put("NX_RES", value);
        } else if ("GRAPHICS".equals(key)) {
            if ("low".equals(value)) snapshot.put("NX_LOD_BIAS", "1");
            else if ("medium".equals(value)) snapshot.put("NX_LOD_BIAS", "0.5");
            else snapshot.remove("NX_LOD_BIAS");
        }
    }

    static void restore(Map<String, String> config, Map<String, String> snapshot) {
        for (String key : CONFIG_KEYS) config.remove(key);
        config.putAll(snapshot);
    }

    // Base64 keeps arbitrary manually edited values and delimiters intact in SharedPreferences.
    static String encode(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        for (String key : CONFIG_KEYS) {
            if (!values.containsKey(key)) continue;
            out.append(enc(key)).append(':').append(enc(values.get(key))).append('\n');
        }
        return out.toString();
    }

    static Map<String, String> decode(String encoded) {
        Map<String, String> out = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) return out;
        for (String line : encoded.split("\\n")) {
            int sep = line.indexOf(':');
            if (sep < 0) continue;
            try {
                String key = dec(line.substring(0, sep));
                boolean valid = false;
                for (String known : CONFIG_KEYS) if (known.equals(key)) valid = true;
                if (valid) out.put(key, dec(line.substring(sep + 1)));
            } catch (IllegalArgumentException ignored) {
                // A damaged or old preference is treated as an empty snapshot.
            }
        }
        return out;
    }

    private static String enc(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String dec(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
