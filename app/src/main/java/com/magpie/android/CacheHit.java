package com.magpie.android;

import org.json.JSONObject;

/**
 * 缓存命中率（0-100）计算，三处 UI 共用。
 *
 * <p>优先用 core 新增的 {@code NativeData.cacheHit(JSONObject)}（反射探测，未到位也不影响编译）；
 * 否则本地按 {@code cache_read / (cache_read + cache_write + in|input)} 算。
 * 无有效分母时返回 -1，调用方据此**不显示**，绝不显示「缓存 0%」误导。
 */
final class CacheHit {

    private CacheHit() {
    }

    static double pct(JSONObject o) {
        if (o == null) {
            return -1;
        }
        long read = NativeData.lng(o, "cache_read");
        long write = NativeData.lng(o, "cache_write");
        long input = o.has("input") ? NativeData.lng(o, "input") : NativeData.lng(o, "in");
        long denom = read + write + input;
        if (denom <= 0) {
            return -1;
        }
        Double core = corePct(o);
        double v = core != null ? core : (read * 100.0 / denom);
        return Math.max(0, Math.min(100, v));
    }

    /** 若 core 已提供 public static cacheHit(JSONObject)（返回数值），优先用它。 */
    private static Double corePct(JSONObject o) {
        try {
            java.lang.reflect.Method m = NativeData.class.getMethod("cacheHit", JSONObject.class);
            Class<?> rt = m.getReturnType();
            if (rt == double.class || rt == Double.class) {
                Object r = m.invoke(null, o);
                if (r instanceof Number) {
                    return ((Number) r).doubleValue();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
