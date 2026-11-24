package io.github.p5rrecomp;

import android.os.Build;

import java.util.Locale;
import java.util.Map;

/** Conservative display/frame-rate suggestions keyed to the handset identity, separate from graphics profiles. */
final class DeviceRecommendations {
    static final String PREF_ENABLED = "device_recommendations_enabled";
    static final String PREF_SEEN_ID = "device_recommendations_seen_id";
    static final String PREF_PREVIOUS_DRIVER_PRESENT = "device_recommendations_previous_driver_present";
    static final String PREF_PREVIOUS_DRIVER_VALUE = "device_recommendations_previous_driver_value";
    static final String PREF_PREVIOUS_RESOLUTION_PRESENT = "device_recommendations_previous_resolution_present";
    static final String PREF_PREVIOUS_RESOLUTION_VALUE = "device_recommendations_previous_resolution_value";
    static final String PREF_PREVIOUS_FPS_PRESENT = "device_recommendations_previous_fps_present";
    static final String PREF_PREVIOUS_FPS_VALUE = "device_recommendations_previous_fps_value";

    enum Tier { HIGH, MID, UNKNOWN }

    private DeviceRecommendations() {}

    static String manufacturer() {
        return safe(Build.MANUFACTURER, "Android");
    }

    static String model() {
        return safe(Build.MODEL, "Android device");
    }

    static String soc() {
        String value = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : "";
        return value == null || value.trim().isEmpty() ? safe(Build.HARDWARE, "") : value.trim();
    }

    static String identity() {
        return (manufacturer() + "/" + model() + "/" + soc()).toLowerCase(Locale.ROOT);
    }

    static String displayName() {
        String id = identity();
        if (id.contains("sm-a346") || id.contains("exynos 1280") || id.contains("s5e8825"))
            return "Samsung Galaxy A34 5G";
        if (id.contains("snapdragon 685") || id.contains("sm6225") || id.contains("23021raae"))
            return "Xiaomi Redmi Note 12 4G";
        return manufacturer() + " " + model();
    }

    static Tier tier() {
        String id = identity();
        if (containsAny(id, "snapdragon 8 elite", "sm8750", "snapdragon 8 gen 3", "sm8650", "snapdragon 8 gen 2",
                "sm8550", "adreno 7", "adreno 8", "dimensity 9300", "exynos 2400", "mali-g720", "mali-g725",
                "mali-g925", "xclipse 940")) return Tier.HIGH;
        if (containsAny(id, "sm-a346", "exynos 1280", "s5e8825", "snapdragon 685", "sm6225", "adreno 6",
                "mali-g68", "mali-g57", "dimensity 1080", "dimensity 7050", "xclipse 530")) return Tier.MID;
        return Tier.UNKNOWN;
    }

    static String recommendation(boolean customDriverSelected) {
        String resolution = tier() == Tier.HIGH ? "1080p" : "720p";
        String base = "Recommended for " + displayName() + ": " + resolution + ", 30 FPS";
        if (customDriverSelected) base += ", system Vulkan driver";
        else base += ", current Vulkan driver";
        base += ". This is a cautious starting point, not a guarantee; device/driver behavior can vary. "
                + "Texture files and graphics effects are left unchanged.";
        return base;
    }

    static void apply(Map<String, String> config, boolean customDriverSelected) {
        String resolution = tier() == Tier.HIGH ? "1080p" : "720p";
        config.put("RESOLUTION", resolution);
        config.put("NX_FPS_CAP", "30");
        config.put("NX_HANDHELD", "1080p".equals(resolution) ? "0" : "1");
        config.put("resolution", resolution);
        config.put("NX_RENDER_RES", resolution);
        config.put("NX_RES", resolution);
        if (customDriverSelected) config.put("NX_SYSTEM_DRIVER", "1");
    }

    private static boolean containsAny(String value, String... choices) {
        for (String choice : choices) if (value.contains(choice)) return true;
        return false;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
