package com.magpie.android;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * 安装 Antigravity CLI（android arm64）。
 *
 * <p>背景：magpie 上游装 Antigravity 时误判「android arm64 没有构建」，其实官方有
 * android_arm64 包。这里直接按官方清单下载、校验、解出单个 {@code antigravity} 二进制，
 * 放到 {@code filesDir/magpie/antigravity}（magpie 的环境 PATH 第一个就是 filesDir/magpie），
 * 用户随后去供应商页点「重试」即可登录。
 *
 * <p>流程：拉 manifest（{version,url,sha512}）→ 下载 tar.gz（约 65MB）→ 校验 SHA-512
 * → 解出 tar 里名为 {@code antigravity} 的条目 → 写临时文件再 rename 原子替换 → chmod 755。
 *
 * <p>只用 JDK/Android 自带能力（GZIPInputStream + 最小 tar 解析），不引第三方库。
 */
public final class AntigravityCli {

    private static final String TAG = "MagpieAntigravity";

    /** 官方 android_arm64 清单。 */
    static final String MANIFEST =
            "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/android_arm64.json";
    private static final String HOST =
            "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app";

    /** 安装进度/结果回调（均在主线程被调用）。 */
    public interface Progress {
        /** 下载进度百分比 0..100；大小未知时给 -1。 */
        void onProgress(int percent);

        /** 成功：版本、落地绝对路径、字节数。 */
        void onDone(String version, String path, long size);

        /** 失败：具体原因（网络 / 校验 / 解压 / 写盘 分开）。 */
        void onError(String reason);
    }

    private AntigravityCli() {
    }

    /** 后台执行安装，回调在主线程。 */
    static void install(Context ctx, Progress cb) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> doInstall(app, cb), "antigravity-install").start();
    }

    /** 已装 CLI 的版本（跑 {@code antigravity --version} 取第一行）；没装或读不到返回 ""。 */
    static String installedVersion(Context ctx) {
        File bin = new File(GatewayService.rootDir(ctx), "antigravity");
        if (!bin.isFile()) {
            return "";
        }
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "--version");
            pb.redirectErrorStream(true);
            p = pb.start();
            try {
                p.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (p.isAlive()) {
                p.destroy();
            }
            String out = readAll(p.getInputStream()).trim();
            int nl = out.indexOf('\n');
            return (nl > 0 ? out.substring(0, nl) : out).trim();
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    // ------------------------------------------------------------------
    // 实现
    // ------------------------------------------------------------------

    /** 带分类前缀的失败，便于把「网络/校验/解压/写盘」原因分开回报。 */
    private static final class Fail extends Exception {
        Fail(String m) {
            super(m);
        }
    }

    private static void doInstall(Context ctx, Progress cb) {
        File tgz = null;
        try {
            // a) 清单
            String[] m = fetchManifest();
            final String version = m[0];
            String url = m[1];
            String sha512 = m[2];
            if (url.isEmpty()) {
                throw new Fail("清单里没有下载地址");
            }

            // b) 下载到 cacheDir
            File cache = ctx.getCacheDir();
            if (cache == null) {
                throw new Fail("写盘失败：拿不到缓存目录");
            }
            tgz = new File(cache, "antigravity.tar.gz");

            // c) 边下边算 SHA-512
            downloadAndVerify(url, tgz, sha512, cb);

            // d) 解出 antigravity
            File root = GatewayService.rootDir(ctx);
            if (!root.isDirectory() && !root.mkdirs()) {
                throw new Fail("写盘失败：无法创建目录 " + root);
            }
            File dest = new File(root, "antigravity");
            File tmpBin = new File(root, "antigravity.tmp");
            try {
                extractBinary(tgz, tmpBin);
            } catch (IOException e) {
                tmpBin.delete();
                throw new Fail("解压失败：" + safe(e));
            }

            // e) chmod 755 + rename 原子替换
            tmpBin.setExecutable(true, false);
            chmod755(tmpBin);
            if (dest.exists() && !dest.delete()) {
                tmpBin.delete();
                throw new Fail("写盘失败：旧文件删不掉");
            }
            if (!tmpBin.renameTo(dest)) {
                tmpBin.delete();
                throw new Fail("写盘失败：临时文件改名失败");
            }
            dest.setExecutable(true, false);
            chmod755(dest);

            // f) 成功
            final long size = dest.length();
            final String path = dest.getAbsolutePath();
            post(() -> cb.onDone(version, path, size));
        } catch (Fail f) {
            Log.w(TAG, "安装失败: " + f.getMessage());
            final String why = f.getMessage();
            post(() -> cb.onError(why));
        } catch (IOException e) {
            Log.w(TAG, "网络/IO 错误: " + e);
            final String why = "网络错误：" + safe(e);
            post(() -> cb.onError(why));
        } catch (Throwable t) {
            Log.w(TAG, "安装失败(未知): " + t);
            final String why = "安装失败：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "（" + t.getMessage() + "）");
            post(() -> cb.onError(why));
        } finally {
            if (tgz != null) {
                //noinspection ResultOfMethodCallIgnored
                tgz.delete();
            }
        }
    }

    /** 拉清单，返回 {version, url, sha512}。 */
    private static String[] fetchManifest() throws IOException, Fail {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(MANIFEST).openConnection();
            c.setRequestProperty("User-Agent", "magpie-android-antigravity");
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            int code = c.getResponseCode();
            if (code != 200) {
                throw new Fail("拉取清单失败：HTTP " + code);
            }
            String body = readAll(c.getInputStream());
            JSONObject o;
            try {
                o = new JSONObject(body);
            } catch (Exception e) {
                throw new Fail("清单解析失败：" + safe(e));
            }
            String version = o.optString("version", "");
            String url = o.optString("url", "");
            String sha = o.optString("sha512", "");
            if (url.startsWith("/")) {
                url = HOST + url;
            }
            return new String[]{version, url, sha};
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** 下载并边下边算 SHA-512；不匹配则删包报「校验失败」。 */
    private static void downloadAndVerify(String url, File dest, String sha512,
                                          Progress cb) throws IOException, Fail {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-512");
        } catch (Exception e) {
            throw new Fail("校验失败：本机不支持 SHA-512");
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("User-Agent", "magpie-android-antigravity");
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            int code = c.getResponseCode();
            if (code != 200) {
                throw new Fail("下载失败：HTTP " + code);
            }
            long len = -1;
            try {
                len = c.getContentLengthLong();
            } catch (Throwable ignored) {
            }
            try (InputStream in = c.getInputStream();
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[1 << 16];
                int n;
                long total = 0;
                int last = -2;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    total += n;
                    if (len > 0) {
                        int pct = (int) (total * 100 / len);
                        if (pct != last) {
                            last = pct;
                            final int p = pct;
                            post(() -> cb.onProgress(p));
                        }
                    }
                }
                out.flush();
            }
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
        String got = hex(md.digest());
        if (sha512 != null && !sha512.isEmpty() && !sha512.equalsIgnoreCase(got)) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            throw new Fail("校验失败：SHA-512 不匹配（期望 " + shorten(sha512)
                    + "，实际 " + shorten(got) + "）");
        }
    }

    /**
     * 从 tar.gz 里只取名为 {@code antigravity} 的普通文件，写到 destTmp。
     *
     * <p>最小 tar 解析：512 字节 header；name[0,100)、size[124,136) 八进制、type[156]、
     * ustar prefix[345,500)。支持 GNU longname(L) 与 pax(x/g) 跳过，绝不整包解压。
     * 因为只按「文件名等于 antigravity」匹配、且写到固定的 destTmp，天然没有路径穿越。
     */
    private static void extractBinary(File tarGz, File destTmp) throws IOException {
        try (InputStream fin = new FileInputStream(tarGz);
             GZIPInputStream gz = new GZIPInputStream(fin, 1 << 16)) {
            byte[] header = new byte[512];
            String longName = null;
            boolean wrote = false;
            while (true) {
                int n = readFully(gz, header, 0, 512);
                if (n < 512) {
                    break; // EOF
                }
                if (allZero(header)) {
                    break; // 结束块
                }
                String name = cstr(header, 0, 100);
                String prefix = cstr(header, 345, 155);
                long size = parseOctal(header, 124, 12);
                char type = (char) (header[156] & 0xff);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
                if (longName != null) {
                    name = longName;
                    longName = null;
                }
                // GNU 长文件名：本条是名字，真正的文件头在下一块
                if (type == 'L') {
                    longName = readName(gz, size);
                    skipPad(gz, size);
                    continue;
                }
                // pax 扩展头 / 全局头：跳过数据
                if (type == 'x' || type == 'g' || type == 'K') {
                    skipData(gz, size);
                    continue;
                }
                boolean regular = type == '0' || type == '\0' || type == '7';
                String base = name;
                while (base.startsWith("./")) {
                    base = base.substring(2);
                }
                if (regular && "antigravity".equals(base)) {
                    try (OutputStream out = new FileOutputStream(destTmp)) {
                        copyExact(gz, out, size);
                    }
                    skipPad(gz, size);
                    wrote = true;
                    break;
                } else {
                    skipData(gz, size);
                }
            }
            if (!wrote) {
                throw new IOException("tar 内没找到 antigravity 文件");
            }
        }
    }

    // ---- tar/流 小工具 ----

    private static int readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(b, off + total, len - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    private static boolean allZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }

    private static String cstr(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, StandardCharsets.UTF_8).trim();
    }

    private static long parseOctal(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) {
            // base-256（大文件用），这里不常见，仍尽量解析
            long v = b[off] & 0x7f;
            for (int i = 1; i < len; i++) {
                v = (v << 8) | (b[off + i] & 0xff);
            }
            return v;
        }
        int i = off;
        int end = off + len;
        while (i < end && (b[i] == ' ' || b[i] == 0)) {
            i++;
        }
        long v = 0;
        for (; i < end; i++) {
            int c = b[i] & 0xff;
            if (c < '0' || c > '7') {
                break;
            }
            v = v * 8 + (c - '0');
        }
        return v;
    }

    private static String readName(InputStream in, long size) throws IOException {
        if (size <= 0 || size > (1 << 20)) {
            // 异常长名：读掉不解析
            skipExact(in, size);
            return null;
        }
        byte[] b = new byte[(int) size];
        readFully(in, b, 0, b.length);
        int end = b.length;
        while (end > 0 && (b[end - 1] == 0 || b[end - 1] == '\n')) {
            end--;
        }
        return new String(b, 0, end, StandardCharsets.UTF_8);
    }

    private static void copyExact(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buf = new byte[1 << 16];
        long remaining = size;
        while (remaining > 0) {
            int want = (int) Math.min((long) buf.length, remaining);
            int n = in.read(buf, 0, want);
            if (n < 0) {
                throw new IOException("tar 数据提前结束");
            }
            out.write(buf, 0, n);
            remaining -= n;
        }
    }

    private static void skipData(InputStream in, long size) throws IOException {
        skipExact(in, size);
        skipPad(in, size);
    }

    private static void skipPad(InputStream in, long size) throws IOException {
        long pad = (512 - (size % 512)) % 512;
        skipExact(in, pad);
    }

    private static void skipExact(InputStream in, long n) throws IOException {
        byte[] buf = new byte[8192];
        long remaining = n;
        while (remaining > 0) {
            int want = (int) Math.min((long) buf.length, remaining);
            int got = in.read(buf, 0, want);
            if (got < 0) {
                return;
            }
            remaining -= got;
        }
    }

    private static void chmod755(File f) {
        try {
            ProcessBuilder pb = new ProcessBuilder("chmod", "755", f.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try {
                p.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (p.isAlive()) {
                p.destroy();
            }
        } catch (Exception ignored) {
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) != -1) {
            bos.write(b, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16))
                    .append(Character.forDigit(x & 0xf, 16));
        }
        return sb.toString();
    }

    private static String shorten(String s) {
        return s == null ? "" : (s.length() > 16 ? s.substring(0, 16) + "…" : s);
    }

    private static String safe(Throwable t) {
        return t == null ? "" : (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
    }

    private static void post(final Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
