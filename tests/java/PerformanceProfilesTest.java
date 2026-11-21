package io.github.p5rrecomp;

import java.util.LinkedHashMap;
import java.util.Map;

/** Standalone regression tests: javac -d <out> <launcher helper> <this file>; java ...PerformanceProfilesTest */
public final class PerformanceProfilesTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void mapping(String mode, String resolution, String fsr, String fps, String shadows,
                        String lowShadows, String graphics, String crowd, String bloom) {
        Map<String, String> actual = PerformanceProfiles.values(mode);
        check(actual.size() == 8, mode + " setting count");
        check(resolution.equals(actual.get("RESOLUTION")), mode + " resolution");
        check(fsr.equals(actual.get("NX_FSR")), mode + " FSR");
        check(fps.equals(actual.get("NX_FPS_CAP")), mode + " FPS");
        check(shadows.equals(actual.get("NX_SHADOWS")), mode + " shadows");
        check(lowShadows.equals(actual.get("NX_MALI_LOW_SHADOWS")), mode + " shadow quality");
        check(graphics.equals(actual.get("GRAPHICS")), mode + " texture quality");
        check(crowd.equals(actual.get("NX_NO_CROWD")), mode + " crowd");
        check(bloom.equals(actual.get("NX_DISABLE_BLOOM")), mode + " bloom");
    }

    public static void main(String[] args) {
        mapping("Performance", "480p", "1", "30", "0", "1", "low", "1", "1");
        mapping("Balanced", "720p", "1", "30", "0", "1", "medium", "0", "0");
        mapping("Quality", "1080p", "0", "30", "1", "0", "high", "0", "0");
        check(PerformanceProfiles.values(PerformanceProfiles.MANUAL).isEmpty(), "Manual must not map to a preset");

        Map<String, String> manual = new LinkedHashMap<>();
        manual.put("RESOLUTION", "720p");
        manual.put("NX_FSR", "custom-value");
        manual.put("NX_SHADOWS", "odd");
        manual.put("NX_LOD_BIAS", "0.37");
        manual.put("resolution", "720p");
        manual.put("player_custom_entry", "keep-me");
        Map<String, String> original = new LinkedHashMap<>(manual);
        Map<String, String> snapshot = PerformanceProfiles.snapshot(manual);
        String persisted = PerformanceProfiles.encode(snapshot);

        manual.putAll(PerformanceProfiles.values("Performance"));
        manual.putAll(PerformanceProfiles.values("Quality")); // profile-to-profile switch leaves the snapshot alone
        Map<String, String> decoded = PerformanceProfiles.decode(persisted);
        PerformanceProfiles.restore(manual, decoded);
        check(manual.equals(original), "Manual restore must retain nonstandard and absent values");
        check("keep-me".equals(manual.get("player_custom_entry")), "unrelated config entry must survive");

        Map<String, String> edited = PerformanceProfiles.decode(persisted);
        PerformanceProfiles.updateManualValue(edited, "RESOLUTION", "480p");
        PerformanceProfiles.updateManualValue(edited, "GRAPHICS", "low");
        check("480p".equals(edited.get("resolution")) && "1".equals(edited.get("NX_HANDHELD")),
                "Manual resolution edit aliases");
        check("1".equals(edited.get("NX_LOD_BIAS")), "Manual texture edit bias");
        check("custom-value".equals(edited.get("NX_FSR")), "Manual edit must retain other original values");
        check(PerformanceProfiles.decode("bad-entry\n").isEmpty(), "damaged preference should decode safely");
        System.out.println("PerformanceProfilesTest: all checks passed");
    }
}
