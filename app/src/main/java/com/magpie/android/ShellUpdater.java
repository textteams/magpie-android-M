package com.magpie.android;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 检查这个 App（壳）自己在 GitHub 开源仓库里的新版本。
 *
 * <p>壳更新和内核更新是两回事：内核是 {@code filesDir/magpie/magpie} 那个 ELF，
 * 走 {@link Updater}；壳是安装的这个 APK，走这里。只做「提示 + 跳到 Release 页」，
 * 下载安装交给系统浏览器和安装器，壳不碰 APK 文件、不请求安装权限。
 *
 * <p>检查源有两级：先问 GitHub Releases API（结构好解析）；API 对未认证请求
 * 返回 403（2026-11 起 GitHub 收紧了匿名访问）时，回退到 {@code releases.atom}
 * —— 那是普通网页端点，匿名可读、不限流。两条都不带 token，避免把凭据写进 APK。
 */
public final class ShellUpdater {

    private static final String TAG = "MagpieShellUpdater";

    /** 开源仓库 owner。 */
    private static final String OWNER = "yqing004-boop";

    /** 开源仓库名。 */
    private static final String REPO = "magpie-android";

    /** 给「上报问题」用的仓库坐标。 */
    static String owner() {
        return OWNER;
    }

    static String repo() {
        return REPO;
    }

    /** 内置镜像前缀（空串 = GitHub 官方直连）。用户可在设置页手动添加更多。 */
    public static final String[] MIRROR_PREFIXES = {
            "",
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
    };

    public static final String[] MIRROR_NAMES = {
            "GitHub 官方",
            "gh-proxy.com",
            "ghproxy.net",
    };

    /** 测镜像用的固定样例 asset（1.8.52 壳子包，真实存在即可，只为连通性探测）。 */
    static final String SAMPLE_ASSET_URL =
            "https://github.com/yqing004-boop/magpie-android/releases/download/v1.8.52/"
                    + "magpie-android-1.8.52-shell.apk";

    /** 给 UI 的自定义镜像输入提示。 */
    public static final String CUSTOM_MIRROR_HINT = "例如 https://gh-proxy.com/ 或自建反代前缀";

    public interface Callback {
        void result(Result result);
    }

    /** 一次检查的结果。 */
    public static final class Result {
        public final String latestTag;
        public final String page;
        /** APK 的 GitHub 原始直链（未拼镜像）。 */
        public final String downloadUrl;
        public final String error;

        Result(String tag, String page, String downloadUrl, String error) {
            this.latestTag = tag;
            this.page = page;
            this.downloadUrl = downloadUrl;
            this.error = error;
        }

        public boolean ok() {
            return error == null && latestTag != null && !latestTag.isEmpty();
        }

        /** 是否有可下载的 APK。 */
        public boolean hasApk() {
            return downloadUrl != null && !downloadUrl.isEmpty();
        }
    }

    private ShellUpdater() {
    }

    public static String releasesPage() {
        return "https://github.com/" + OWNER + "/" + REPO + "/releases";
    }

    /** 本 App 的版本号（versionName），例如 "1.0.25"。 */
    public static String localVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** 后台查一次最新版本，结果回主线程。 */
    public static void check(Context ctx, Callback cb) {
        new Thread(() -> {
            Result result;
            try {
                result = fetch(ctx);
            } catch (IOException e) {
                Log.w(TAG, "检查壳更新失败: " + e);
                result = new Result("", "", "", e.getMessage());
            }
            final Result r = result;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.result(r));
        }, "magpie-shell-update").start();
    }

    /** 单个来源的一次尝试：result!=null 表示拿到确定结果（成功或 404），否则 error 是失败原因。 */
    private static final class Attempt {
        final Result result;
        final String error;

        Attempt(Result result, String error) {
            this.result = result;
            this.error = error;
        }
    }

    /**
     * 依次尝试：镜像 API → 镜像 atom → 直连 API → 直连 atom，第一个拿到确定结果就用它。
     * 全部失败时把每个来源的原因拼成一句可读错误（而非静默「GitHub 不可达」）。
     */
    private static Result fetch(Context ctx) throws IOException {
        StringBuilder err = new StringBuilder();
        String mirror = Prefs.shellMirror(ctx);
        if (mirror != null && !mirror.trim().isEmpty()) {
            Attempt ma = fetchApi(ctx, mirror, "镜像 API");
            if (ma.result != null) {
                return ma.result;
            }
            appendErr(err, ma.error);
            Attempt mt = fetchAtom(ctx, mirror, "镜像 atom");
            if (mt.result != null) {
                return mt.result;
            }
            appendErr(err, mt.error);
        }
        Attempt da = fetchApi(ctx, "", "GitHub API");
        if (da.result != null) {
            return da.result;
        }
        appendErr(err, da.error);
        Attempt dt = fetchAtom(ctx, "", "atom");
        if (dt.result != null) {
            return dt.result;
        }
        appendErr(err, dt.error);
        String msg = err.length() == 0 ? "GitHub 不可达（API 与 atom 都没读到）" : err.toString();
        if (msg.length() > 120) {
            msg = msg.substring(0, 120) + "…";
        }
        return new Result("", "", "", msg);
    }

    private static void appendErr(StringBuilder sb, String e) {
        if (e == null || e.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("；");
        }
        sb.append(e);
    }

    /**
     * 第一级：GitHub Releases API（可带镜像前缀）。
     *
     * <p>稳定版拿 {@code /releases/latest}（不含 prerelease）；测试版拿
     * {@code /releases?per_page=1}（列表第一项，含 prerelease）。
     * 失败不静默：返回 {@link Attempt}，{@code error} 里写明 HTTP 码/超时/解析失败。
     */
    private static Attempt fetchApi(Context ctx, String prefix, String label) {
        String api = Prefs.shellStableChannel(ctx)
                ? "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest"
                : "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases?per_page=1";
        String url = withPrefix(prefix, api);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "magpie-android-shell");
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            int code = c.getResponseCode();
            if (code == 404) {
                return new Attempt(new Result("", "", "", "仓库还没有 Release"), null);
            }
            if (code != 200) {
                String detail = code == 403
                        ? " 403（匿名限流，可在下方换镜像后重试）" : (" " + code);
                return new Attempt(null, label + detail);
            }
            String body = readAll(c.getInputStream());
            JSONObject j;
            if (!Prefs.shellStableChannel(ctx)) {
                // 测试版：返回的是数组，取第一个
                JSONArray arr = new JSONArray(body);
                if (arr.length() == 0) {
                    return new Attempt(new Result("", "", "", "仓库还没有 Release"), null);
                }
                j = arr.optJSONObject(0);
                if (j == null) {
                    return new Attempt(null, label + " 解析失败（空数组项）");
                }
            } else {
                j = new JSONObject(body);
            }
            String tag = j.optString("tag_name", "");
            String page = j.optString("html_url", releasesPage());
            String dl = "";
            JSONArray assets = j.optJSONArray("assets");
            if (assets != null) {
                // 有内核的老用户优先下壳子包（不重复下 99M 内核）；全新/清空的下整包。
                dl = pickApkAsset(assets, GatewayService.kernelInstalled(ctx));
            }
            return new Attempt(new Result(stripV(tag), page, dl, null), null);
        } catch (Exception e) {
            return new Attempt(null, label + " " + describe(e));
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /**
     * 从 assets 里挑一个 APK 下载直链：
     * <ul>
     *   <li>有内核（老用户）：优先 {@code -shell.apk}（壳子包，792K，不带内核）</li>
     *   <li>无内核（全新/清空）：优先完整包（{@code .apk} 且不以 {@code -shell.apk} 结尾）</li>
     *   <li>首选那种没有就退回另一种，再没有就退回第一个 .apk</li>
     * </ul>
     */
    private static String pickApkAsset(JSONArray assets, boolean hasKernel) {
        String firstApk = "";
        String shell = "";
        String whole = "";
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) {
                continue;
            }
            String name = a.optString("name", "");
            String low = name.toLowerCase(java.util.Locale.US);
            if (!low.endsWith(".apk")) {
                continue;
            }
            String url = a.optString("browser_download_url", "");
            if (firstApk.isEmpty()) {
                firstApk = url;
            }
            if (low.endsWith("-shell.apk")) {
                if (shell.isEmpty()) {
                    shell = url;
                }
            } else {
                if (whole.isEmpty()) {
                    whole = url;
                }
            }
        }
        if (hasKernel) {
            if (!shell.isEmpty()) {
                return shell;
            }
            if (!whole.isEmpty()) {
                return whole;
            }
        } else {
            if (!whole.isEmpty()) {
                return whole;
            }
            if (!shell.isEmpty()) {
                return shell;
            }
        }
        return firstApk;
    }

    /**
     * 第二级：从 releases.atom 读最新 tag（可带镜像前缀）。
     *
     * <p>atom 是普通网页端点，匿名可读；ghproxy.net 这类只转发 release 下载、不转发 API，
     * 会自动落到这里。里面没有 APK 直链，按发布脚本命名约定拼下载直链。
     * 失败不静默：返回 {@link Attempt}，{@code error} 写明 HTTP 码/超时/解析失败。
     */
    private static Attempt fetchAtom(Context ctx, String prefix, String label) {
        String url = withPrefix(prefix,
                "https://github.com/" + OWNER + "/" + REPO + "/releases.atom");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("User-Agent", "magpie-android-shell");
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            int code = c.getResponseCode();
            if (code == 404) {
                return new Attempt(new Result("", "", "", "仓库还没有 Release"), null);
            }
            if (code != 200) {
                return new Attempt(null, label + " " + code);
            }
            String body = readAll(c.getInputStream());
            String marker = "Repository/";
            int i = body.indexOf(marker);
            if (i < 0) {
                return new Attempt(null, label + " 解析失败（无 Repository 链接）");
            }
            int start = i + marker.length();
            int end = body.indexOf('<', start);
            if (end < 0) {
                return new Attempt(null, label + " 解析失败（tag 截断）");
            }
            String tag = body.substring(start, end).trim();
            if (!tag.startsWith("v")) {
                return new Attempt(null, label + " 解析失败（tag 格式异常）");
            }
            String ver = stripV(tag);
            String page = "https://github.com/" + OWNER + "/" + REPO
                    + "/releases/tag/" + tag;
            // atom 里没有 asset 列表，只能按发布脚本的命名约定拼：
            // 有内核拼壳子包（-shell.apk），无内核拼完整包（.apk）。
            String asset = GatewayService.kernelInstalled(ctx)
                    ? REPO + "-" + ver + "-shell.apk"
                    : REPO + "-" + ver + ".apk";
            String dl = "https://github.com/" + OWNER + "/" + REPO
                    + "/releases/download/" + tag + "/" + asset;
            return new Attempt(new Result(ver, page, dl, null), null);
        } catch (Exception e) {
            return new Attempt(null, label + " " + describe(e));
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    /** tag 里的 "v" 前缀去掉，好和 versionName 比较。 */
    static String stripV(String tag) {
        if (tag == null) {
            return "";
        }
        String t = tag.trim();
        return t.length() > 1 && (t.charAt(0) == 'v' || t.charAt(0) == 'V')
                && Character.isDigit(t.charAt(1)) ? t.substring(1) : t;
    }

    /** 把镜像/反代前缀拼到一个完整 URL 前面；prefix 空则原样返回；prefix 不以 / 结尾时自动补一个。 */
    public static String mirrored(String prefix, String url) {
        return withPrefix(prefix, url);
    }

    /** 统一的前缀拼接：接受任意前缀（含用户手填的自建反代）；不以 / 结尾自动补 /，避免拼出 x.comhttps://…。 */
    private static String withPrefix(String prefix, String url) {
        if (prefix == null || prefix.isEmpty()) {
            return url;
        }
        String p = prefix;
        if (!p.endsWith("/")) {
            p = p + "/";
        }
        return p + url;
    }

    private static HttpURLConnection openConn(String url, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", "magpie-android-shell");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        return c;
    }

    private static String secs(long t0) {
        return String.format(java.util.Locale.US, "%.1fs", (System.nanoTime() - t0) / 1e9);
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        if (t instanceof java.net.SocketTimeoutException) {
            return "超时";
        }
        if (t instanceof java.net.ConnectException) {
            return "无法连接";
        }
        if (t instanceof java.net.UnknownHostException) {
            return "域名解析失败";
        }
        String m = t.getMessage();
        return (m == null || m.trim().isEmpty()) ? t.getClass().getSimpleName() : m;
    }

    /**
     * 测一个更新/下载地址是否可达：先 HEAD，失败或非 2xx 再用 GET 拉首字节（Range 0-0）。
     * 10s 超时；调用方应在后台线程调（会阻塞到响应或超时）。
     *
     * @return 人类可读结果，如「HTTP 206 · 1.2s」「超时/无法连接：xxx」
     */
    public static String test(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "地址为空";
        }
        long t0 = System.nanoTime();
        String headErr = null;
        HttpURLConnection c = null;
        try {
            c = openConn(url, "HEAD");
            int code = c.getResponseCode();
            if (code >= 200 && code < 400) {
                return "HTTP " + code + " · " + secs(t0);
            }
            headErr = "HTTP " + code;
        } catch (Exception e) {
            headErr = describe(e);
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
        // HEAD 不行：GET 拉首字节（不少 CDN/重定向对 HEAD 不友好）
        c = null;
        try {
            c = openConn(url, "GET");
            c.setRequestProperty("Range", "bytes=0-0");
            int code = c.getResponseCode();
            if (code == 200 || code == 206) {
                InputStream in = c.getInputStream();
                if (in != null) {
                    try {
                        in.read();
                    } catch (Exception ignored) {
                    }
                    in.close();
                }
            }
            String suffix = headErr == null ? "" : "（HEAD：" + headErr + "）";
            return "HTTP " + code + " · " + secs(t0) + suffix;
        } catch (Exception e) {
            return "超时/无法连接：" + describe(e)
                    + (headErr == null ? "" : "（HEAD：" + headErr + "）");
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** 测某个镜像前缀（拼已知样例 asset 后调 {@link #test}）。 */
    static String testMirror(String prefix) {
        return test(mirrored(prefix, SAMPLE_ASSET_URL));
    }

    /** 测当前内核更新源（{@link Updater#feed}）是否可达。 */
    static String testFeed(Context ctx) {
        String f;
        try {
            f = Updater.feed(ctx);
        } catch (Exception e) {
            return "取更新源失败：" + describe(e);
        }
        if (f == null || f.trim().isEmpty()) {
            return "更新源未设置";
        }
        return test(f);
    }

    /** 按 "1.0.25" 这种点分数字比较；latest > local 返回 true。 */
    public static boolean newer(String latest, String local) {
        if (latest == null || latest.isEmpty()) {
            return false;
        }
        if (local == null || local.isEmpty()) {
            return true;
        }
        String[] a = latest.split("\\.");
        String[] b = local.split("\\.");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? num(a[i]) : 0;
            int y = i < b.length ? num(b[i]) : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim().replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
