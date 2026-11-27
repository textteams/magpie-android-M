package com.magpie.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs magpie.

 * The Go binary is packed into the APK as {@code assets/bin/magpie.bin}. On
 * first run it is extracted to {@code filesDir}/magpie/magpie, beside a
 * {@code .portable} marker — that one marker is what makes magpie keep
 * everything it owns (settings.json, providers.json, the cache, the logs)
 * inside {@code filesDir}/magpie/data instead of a desktop home folder, and
 * it is how the wrapper needs no root and no symlinks.

 * magpie is then started as

 *     MAGPIE_ADDR=127.0.0.1:P magpie serve --web=127.0.0.1:W

 * one process, two ports: P is the gateway (OpenAI/Anthropic/… for the
 * agents), W is the Web UI the Activity puts in a WebView. They have to be
 * different ports — magpie dials its own gateway address to ask whether the
 * port is taken, and with one port it would be asking itself.

 * Android 10 and newer refuse exec() on files inside an app's home directory
 * to apps that target SDK 29+ (W^X). This app targets 28 for exactly that
 * reason; if the system still refuses, the service falls back to
 * /system/bin/linker64, which Android lets load an app-data ELF.
 */
public class GatewayService extends Service {

    private static final String TAG = "MagpieGateway";
    private static final int NOTIF_ID = 9101;
    /** 只有用户明确停止服务时才移除；运行/重试状态均保持前台通知常驻。 */
    private static final int FOREGROUND_FLAGS = Notification.FLAG_ONGOING_EVENT;
    /** 前台通知上一次真正展示的状态；用于去重，也用于自愈对账。 */
    private static final int ST_UNKNOWN = 0, ST_STARTING = 1, ST_RUNNING = 2, ST_DOWN = 3;
    private static volatile int lastPostedState = ST_UNKNOWN;
    /** 后台「通知对账」线程：每几秒按真实端口状态校正通知，兜住各种卡在「正在启动」。 */
    private Thread notifThread;
    /** 对账线程的存活标志：与 stopping 解耦，restart（stopping 会短暂 true→false）
     *  期间不会把线程误杀；只有 onDestroy 才置 false。 */
    private volatile boolean notifTickerRunning;
    private static final String CHANNEL_ID = "magpie_gateway";
    public static final String ACTION_RESTART = "com.magpie.android.action.RESTART";
    public static final String EXTRA_FORCE = "force";

    /** Ports above this are the gateway's own range; the Web UI sits P+5
     *  away from it (3425 → 3430, as upstream's Dockerfile exposes). */
    private static final int WEB_PORT_OFFSET = 5;

    private static final Object LOCK = new Object();
    private static Process process;
    private static boolean ready;
    private static boolean starting;
    private static int exitCode = -1;
    private static String bootError = "";
    private static final StringBuilder tail = new StringBuilder();

    private static volatile PowerManager.WakeLock wakeLock;
    /** 最近一次网关活动（有日志输出）的时间戳，休眠守夜用它判断「多久没动静」。 */
    private static volatile long lastActivityAt = System.currentTimeMillis();
    /** 休眠守夜线程在跑（避免重复起）。 */
    private static volatile boolean sleeperRunning;
    /** 息屏状态（壳自己维护，避免读 PowerManager 在不同 ROM 上的坑）。 */
    private static volatile boolean screenOff;
    /** 休眠守夜监测的注册器，Service 停止时取消。 */
    private static android.content.BroadcastReceiver screenReceiver;
    /** 休眠守夜线程引用：stop 时 interrupt + join，避免快速 stop→start 叠出两个。 */
    private static volatile Thread wakeSleeperThread;
    /** 定时缓存清理线程是否已在跑：避免每次 boot/自动重启都叠一条线程。 */
    private static volatile boolean autocleanRunning;

    private final AtomicBoolean stopping = new AtomicBoolean(false);
    /** 重启一次只跑一条线：以前两次 restart 挨着来，两个进程会抢同一个端口。 */
    private static final Object RESTART_LOCK = new Object();
    private static volatile boolean restarting;
    /** 网关意外退出后的自动重试次数（成功就绪后清零）。 */
    private final java.util.concurrent.atomic.AtomicInteger retries =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int MAX_RETRIES = 3;

    // ------------------------------------------------------------------
    // paths
    // ------------------------------------------------------------------

    /** filesDir/magpie — the portable root: binary + .portable + data/. */
    static File rootDir(Context ctx) {
        return new File(ctx.getFilesDir(), "magpie");
    }

    static File binary(Context ctx) {
        return new File(rootDir(ctx), "magpie");
    }

    /**
     * 已经装了可用内核？（ELF 存在且是合法 ELF）
     *
     * <p>壳更新用它决定下载哪种包：老用户（有内核）默认下「壳子包」-shell.apk（792K，
     * 不带内核，不重复下 99M）；全新/清空（没内核）才下完整包。
     */
    static boolean kernelInstalled(Context ctx) {
        File bin = binary(ctx);
        return bin.exists() && bin.length() >= 4 && looksLikeElf(bin);
    }

    static File dataDir(Context ctx) {
        return new File(rootDir(ctx), "data");
    }

    /** The $HOME magpie gives the agents (~/.claude, ~/.codex, …). */
    static File homeDir(Context ctx) {
        return new File(ctx.getFilesDir(), "home");
    }

    static File logDir(Context ctx) {
        return new File(ctx.getFilesDir(), "logs");
    }

    static File logFile(Context ctx) {
        return new File(logDir(ctx), "magpie.log");
    }

    static File callerKeysFile(Context ctx) {
        return new File(dataDir(ctx), "caller-keys.json");
    }

    // ------------------------------------------------------------------
    // ports
    // ------------------------------------------------------------------

    static int gatewayPort(Context ctx) {
        int p = Prefs.port(ctx);
        // 端口下限校验：<1024 是特权端口，普通 App 绑定会 EACCES 空转；回退默认端口。
        return p < 1024 ? Prefs.DEFAULT_PORT : p;
    }

    static int webPort(Context ctx) {
        int p = gatewayPort(ctx) + WEB_PORT_OFFSET;
        if (p > 65535) {
            p = 3430;
        }
        return p;
    }

    /** The address magpie binds for the gateway; 0.0.0.0 in LAN mode. */
    static String bindHost(Context ctx) {
        return Prefs.lan(ctx) ? "0.0.0.0" : Prefs.DEFAULT_HOST;
    }

    /** The Web UI is always reached over loopback by the WebView, but in
     *  LAN mode it is also bound on every interface so a computer on the
     *  same Wi-Fi can open it in its own browser. */
    static String webBind(Context ctx) {
        return Prefs.lan(ctx) ? "0.0.0.0" : Prefs.DEFAULT_HOST;
    }

    static String webUrl(Context ctx) {
        return "http://" + Prefs.browserHost(ctx) + ":" + webPort(ctx)
                + "/?k=" + Prefs.webKey(ctx);
    }

    // ------------------------------------------------------------------
    // service lifecycle
    // ------------------------------------------------------------------

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // startForeground 每次都要求带一个通知，但绝不能无脑写「正在启动网关…」：
        // 服务本来就活着、内核也在跑时，那样会把已经显示「运行中」的通知打回启动文案，
        // 而下面的纠正分支若因 process 引用过期 / starting 残留没执行，就永久卡在「正在启动」。
        // 所以这里按「当前真实状态」决定文案，并起一个对账线程兜底。
        int st = startState();
        startForeground(NOTIF_ID, stateNotification(this, st));
        lastPostedState = st;
        startNotifTicker();

        if (intent != null && ACTION_RESTART.equals(intent.getAction())) {
            restart();
            return START_STICKY;
        }
        synchronized (LOCK) {
            if (process != null && process.isAlive()) {
                postState(this, ST_RUNNING);
                return START_STICKY;
            }
            // 端口已经在监听 = 内核确实在跑（可能是上一个实例的进程，或服务被系统重建但内核还在）。
            // 别再起第二个（会抢 3425 又退出），直接把通知端正。
            if (st == ST_RUNNING) {
                return START_STICKY;
            }
            if (starting) {
                return START_STICKY;
            }
            starting = true;
            ready = false;
            bootError = "";
            exitCode = -1;
        }
        Thread t = new Thread(this::boot, "magpie-boot");
        t.setDaemon(true);
        t.start();
        return START_STICKY;
    }

    /** 把网关切掉（服务也停），下次点重试或重新打开 App 再起来。 */
    public static void stop(Context ctx) {
        synchronized (LOCK) {
            if (process != null && process.isAlive()) {
                process.destroy();
            }
            process = null;
            ready = false;
            starting = false;
        }
        DiagServer.stop();
        LocalProxy.stop();
        try {
            ctx.stopService(new Intent(ctx, GatewayService.class));
        } catch (Exception ignored) {
        }
    }

    /** Stops magpie and starts it again in one go. */
    public static void restart(Context ctx) {
        synchronized (LOCK) {
            if (process != null && process.isAlive()) {
                process.destroy();
            }
        }
        Intent i = new Intent(ctx, GatewayService.class);
        i.setAction(ACTION_RESTART);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    private void boot() {
        try {
            // 后台线程里先探端口：服务被系统重建但旧内核还在监听时，跳过重复启动并把通知端正
            // （这是「不在主线程 portOpen」的替代，见 #41）。
            if (portOpen(Prefs.browserHost(this), webPort(this))) {
                synchronized (LOCK) {
                    ready = true;
                    starting = false;
                }
                postState(this, ST_RUNNING);
                appendLog("网关已在运行，跳过重复启动");
                return;
            }
            patchPlugins(this);
            File bin = ensureBinary(this);
            startProcess(this, bin);
            // 关闭 magpie 自带更新后置到后台：两次 execQuiet 各可等 30s，别拖住首启（见 #48）。
            Thread q = new Thread(() -> quietOwnUpdater(bin), "magpie-quiet-updater");
            q.setDaemon(true);
            q.start();
        } catch (Exception e) {
            Log.e(TAG, "boot failed", e);
            synchronized (LOCK) {
                starting = false;
                bootError = e.toString();
            }
            postNotice(this, "Magpie 启动失败", e.toString());
        }
    }

    /**
     * 让 magpie 自己的自动更新闭嘴。
     *
     * <p>它按 {@code runtime.GOOS} 算资产名：这个壳发的是 {@code GOOS=android}
     * 的二进制，算出来是对的（magpie-cli-android-arm64），但 Web UI 里那个「更新」
     * 按钮走的是 magpie 自己的替换逻辑，在手机沙箱里换完还得重启整个 App，体验不如下面
     * 壳里的更新页；而自建版（GOOS=linux）算出来的是桌面 GUI 版，换上就段错误。
     * 所以两边都只留壳的更新页这一条路。
     */
    private void quietOwnUpdater(File bin) {
        if (Prefs.updatesManaged(this)) {
            return;
        }
        try {
            execQuiet(bin, "settings", "set", "noAutoUpdate", "on");
            execQuiet(bin, "settings", "set", "noUpdatePill", "on");
            Prefs.setUpdatesManaged(this, true);
            appendLog("已关掉 magpie 自己的自动更新，更新走 App 的「⋮ → 检查更新」");
        } catch (Exception e) {
            appendLog("关 magpie 自带更新失败（不影响使用）：" + e.getMessage());
        }
    }

    private void execQuiet(File bin, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        for (String a : args) {
            cmd.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(rootDir(this));
        pb.redirectErrorStream(true);
        java.util.Map<String, String> env = pb.environment();
        env.put("HOME", homeDir(this).getAbsolutePath());
        Process p = pb.start();
        // 先把子进程输出读干再等退出：出错时 magpie 可能打印长堆栈，
        // 塞满 pipe 缓冲（~64KB）会让它卡在 write 上，waitFor 就白等 30 秒。
        // 输出丢弃（这本来就是 execQuiet）。
        Thread drain = new Thread(() -> {
            try (java.io.InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                while (in.read(buf) != -1) {
                    // 丢
                }
            } catch (Exception ignored) {
            }
        }, "execQuiet-drain");
        drain.setDaemon(true);
        drain.start();
        boolean finished = false;
        try {
            finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!finished) {
            p.destroy(); // 超时：强制收尾，避免首启被卡死的子进程拖住
        }
        // 等 drain 把剩余输出读完（否则线程泄漏），再看退出码
        try {
            drain.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            int code = p.exitValue();
            if (code != 0) {
                appendLog("execQuiet " + args[0] + " 退出码 " + code);
            }
        } catch (IllegalThreadStateException ignored) {
            // 还没退出（极少见），忽略
        }
    }

    private void startProcess(Context ctx, File bin) throws IOException {
        String gwHost = bindHost(ctx);
        int gwPort = gatewayPort(ctx);
        int web = webPort(ctx);

        // magpie 是 CGO 关掉的静态 Go：域名解析读 /etc/resolv.conf，读不到就去连
        // 127.0.0.1:53 / ::1:53（Android 上没人监听）。把它的出站 HTTP 交给本机
        // 代理，域名由 Java 走 netd 解析 —— 见 LocalProxy 的注释。
        String proxyUrl = "";
        try {
            proxyUrl = LocalProxy.ensure();
        } catch (IOException e) {
            appendLog("本地代理起不来（" + e.getMessage() + "），域名解析可能会失败");
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        cmd.add("serve");
        cmd.add("--web=" + webBind(ctx) + ":" + web);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(rootDir(ctx));
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        env.put("HOME", homeDir(ctx).getAbsolutePath());
        env.put("USERPROFILE", homeDir(ctx).getAbsolutePath());
        env.put("PATH", bin.getParentFile().getAbsolutePath() + ":" + safePath());
        env.put("MAGPIE_ADDR", gwHost + ":" + gwPort);
        env.put("MAGPIE_WEB_KEY", Prefs.webKey(ctx));
        env.put("MAGPIE_UPDATE_FEED", Prefs.updateFeed(ctx));
        env.put("MAGPIE_TRUST_PROXY", "");
        if (proxyUrl.length() > 0) {
            // magpie 的 netproxy 在 Settings 没设 Proxy 时会读环境变量
            env.put("HTTP_PROXY", proxyUrl);
            env.put("HTTPS_PROXY", proxyUrl);
            env.put("http_proxy", proxyUrl);
            env.put("https_proxy", proxyUrl);
            env.put("NO_PROXY", "127.0.0.1,localhost,::1");
            env.put("no_proxy", "127.0.0.1,localhost,::1");
        }
        env.put("TERM", "xterm-256color");
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");
        env.put("TZ", java.util.TimeZone.getDefault().getID());

        Process p;
        try {
            p = pb.start();
            appendLog("以 targetSdk 28 直接 exec 启动");
        } catch (IOException direct) {
            // Android 10+ W^X: the system linker is allowed to load an
            // ELF out of app data even where exec() is refused.
            List<String> alt = new ArrayList<>();
            alt.add("/system/bin/linker64");
            alt.addAll(cmd);
            ProcessBuilder pb2 = new ProcessBuilder(alt);
            pb2.directory(rootDir(ctx));
            pb2.redirectErrorStream(true);
            pb2.environment().putAll(env);
            appendLog("直接 exec 被拒（" + direct.getMessage() + "），改用 /system/bin/linker64 启动");
            p = pb2.start();
        }

        synchronized (LOCK) {
            process = p;
            starting = false;
        }

        // 诊断端口跟着内核走：内核在，端口就在（AI 排查用，见 DiagServer 注释）
        DiagServer.ensure(ctx);

        // 定时缓存清理：到点了静默清一轮（不弹 UI，日志里记一条）。加「已在跑」守卫 + daemon，
        // 避免每次 boot/自动重启都叠一条线程。
        if (!autocleanRunning) {
            autocleanRunning = true;
            Thread ac = new Thread(() -> {
                try {
                    CacheCleaner.autoCleanIfDue(ctx);
                } catch (Exception e) {
                    Log.w(TAG, "auto clean: " + e.getMessage());
                } finally {
                    autocleanRunning = false;
                }
            }, "magpie-autoclean");
            ac.setDaemon(true);
            ac.start();
        }

        acquireWakeLock(ctx);
        startWakeSleeper(ctx);

        final Process child = p;
        Thread lt = new Thread(() -> pumpLog(ctx, child), "magpie-log");
        lt.setDaemon(true);
        lt.start();

        Thread wt = new Thread(() -> watch(ctx, child, gwHost, gwPort, web), "magpie-watch");
        wt.setDaemon(true);
        wt.start();
    }

    private static String safePath() {
        String p = System.getenv("PATH");
        return p == null ? "/system/bin:/system/xbin:/vendor/bin" : p;
    }

    /** Reads magpie's own output into the log ring and Logcat, then waits. */
    private void pumpLog(Context ctx, Process p) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                lastActivityAt = System.currentTimeMillis();
                // 有新请求/活动时，若息屏省电把唤醒锁放了，这里补回（补齐原来只能等亮屏的死代码）
                ensureWakeLockForActivity(ctx);
                appendLog(line);
                Log.i(TAG, line);
            }
        } catch (Exception ignored) {
        }
        appendLog("— magpie 的输出结束 —");
    }

    /** Waits for the gateway to answer, then for it to go away. */
    private void watch(Context ctx, Process p, String host, int gwPort, int webPort) {
        for (int i = 0; i < 400 && p.isAlive(); i++) {
            if (portOpen(Prefs.browserHost(ctx), webPort)) {
                synchronized (LOCK) {
                    ready = true;
                }
                retries.set(0);
                notifyReady(ctx);
                break;
            }
            sleep(300);
        }
        // 以前这里「进程死了直接 return」：就绪等待期死掉的内核不记退出码、
        // 不通知、不重试，UI 停在「正在启动」。删掉提前 return，统一落到
        // 下面 waitFor() 记退出码 + 自动恢复的老路。
        int code = -1;
        try {
            code = p.waitFor();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        synchronized (LOCK) {
            exitCode = code;
            ready = false;
        }
        appendLog("magpie 退出，退出码 " + code);
        notifyDown(ctx, code);

        // 主动停止/重启时 stopping 为 true，不走自动恢复。
        if (!stopping.get() && code != 0 && retries.incrementAndGet() <= MAX_RETRIES) {
            long delay = 2000L * retries.get();
            appendLog("网关异常退出，将在 " + delay + "ms 后自动重启（第 "
                    + retries.get() + "/" + MAX_RETRIES + " 次）");
            sleep(delay);
            // 旧进程退出到内核真的放开 3425/3430 有延迟（尤其 magpie-bun 还持有
            // 继承来的监听 fd 时）。以前这里直接 boot()，新进程 bind 撞端口 →
            // 「address already in use」→ 退出码 1 → 又进这个分支，直到 MAX_RETRIES
            // 用完。手动重启那条路一直有 waitPortsFree()，这里漏了。
            waitPortsFree();
            if (!stopping.get()) {
                // restart() 可能已把 LOCK 里的 process 换成了新进程：那旧 watch
                // 线程（我们自己）就该退场了，再 boot() 会叠出第二个内核，
                // stopSelf() 会把新服务也停掉。只有当前 process 仍是我们等的
                // 这个 p 时，这条自动恢复才归我们管。
                boolean mine;
                synchronized (LOCK) {
                    mine = process == p;
                }
                if (mine) {
                    synchronized (LOCK) {
                        starting = false;
                    }
                    boot();
                    return;
                }
                appendLog("已有新的重启流程接管，这条旧的退出恢复不再执行");
                return;
            }
        }
        // stopSelf 同理：只有 process 仍是我们等的 p（说明没人接管）才停服务
        boolean mine;
        synchronized (LOCK) {
            mine = process == p;
        }
        if (mine) {
            stopSelf();
        }
    }

    /**
     * 重启网关：串行执行，并等旧进程真的退出、端口释放后再启动。
     *
     * <p>以前这里是「destroy → sleep 1.2s → boot」，而且可以被连着调用两次
     * （设置页保存一次、主界面 onActivityResult 又一次）：第二个 boot 会和
     * 第一个抢 3425，日志里就是连着两条「exec 启动」。重启期间正在跑的请求
     * 会被 SIGTERM 打断（退出码 143）。
     */
    private void restart() {
        synchronized (RESTART_LOCK) {
            if (restarting) {
                appendLog("已有一次重启在进行，忽略这次重复的重启请求");
                return;
            }
            restarting = true;
        }
        Thread rt = new Thread(() -> {
            try {
                stopping.set(true);
                killProcess();
                waitPortsFree();
                retries.set(0);
                synchronized (LOCK) {
                    starting = false;
                }
                stopping.set(false);
                boot();
            } finally {
                restarting = false;
            }
        }, "magpie-restart");
        rt.setDaemon(true);
        rt.start();
    }

    /** 结束当前 magpie 进程，等它真的退出（最多 5s），不行就强行结束。 */
    private void killProcess() {
        Process p;
        synchronized (LOCK) {
            p = process;
        }
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (LOCK) {
            process = null;
            ready = false;
        }
        LocalProxy.stop();
    }

    /** 等旧进程把 3425/3430 放开，别让新进程 bind 失败。 */
    private void waitPortsFree() {
        String host = Prefs.browserHost(this);
        int gw = gatewayPort(this);
        int web = webPort(this);
        for (int i = 0; i < 20; i++) {
            if (!portOpen(host, gw) && !portOpen(host, web)) {
                return;
            }
            sleep(150);
        }
        appendLog("旧进程的端口还没释放，仍然尝试启动");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 停掉通知对账线程：只有真正销毁服务才结束它，restart 不会走到这里。
        notifTickerRunning = false;
        Thread nt = notifThread;
        if (nt != null) {
            nt.interrupt();
        }
        Process p;
        synchronized (LOCK) {
            stopping.set(true);
            // 复位状态：服务被系统销毁再重建（同一个 App 进程内）时，别让旧的
            // starting/process 残留把新实例堵在「正在启动」的早退分支上。
            starting = false;
            p = process;
            process = null;
            ready = false;
        }
        if (p != null && p.isAlive()) {
            p.destroy();
        }
        DiagServer.stop();
        LocalProxy.stop();
        stopWakeSleeper(this);
        releaseWakeLock();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 划掉最近任务时**不要**重启网关：以前这里发 ACTION_RESTART，
        // 会把正在处理请求的 magpie 杀掉重起（日志里的退出码 143 就是这么来的），
        // 后台的 AI 请求跟着断。任务被移除不影响前台服务，交给 START_STICKY 兜底。
        appendLog("最近任务被移除，网关保持运行");
        super.onTaskRemoved(rootIntent);
    }

    // ------------------------------------------------------------------
    // binary extraction
    // ------------------------------------------------------------------

    private static final String ASSET = "bin/magpie.bin";

    /**
     * 把 assets 里的内核释放到 filesDir/magpie/magpie。
     *
     * <p>**绝不覆盖已安装内核**：只要 files/magpie/magpie 存在且是有效 ELF，
     * 就直接用，不论 APK 是否升级过。这样用户在 App 里「检查更新」拉到的
     * 新版永远不会被旧壳重新覆盖（之前按 APK 的 lastUpdateTime 判断，装新版
     * APK 会把用户更新的内核退回旧版）。assets 只是首次安装/内核丢失或损坏时
     * 的种子。
     */
    static File ensureBinary(Context ctx) throws IOException {
        File root = rootDir(ctx);
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("无法创建目录 " + root);
        }
        File marker = new File(root, ".portable");
        if (!marker.exists()) {
            writeFile(marker, "magpie portable here\n");
        }
        File home = homeDir(ctx);
        if (!home.isDirectory() && !home.mkdirs()) {
            throw new IOException("无法创建目录 " + home);
        }
        File logs = logDir(ctx);
        if (!logs.isDirectory() && !logs.mkdirs()) {
            throw new IOException("无法创建目录 " + logs);
        }
        File data = dataDir(ctx);
        if (!data.isDirectory() && !data.mkdirs()) {
            throw new IOException("无法创建目录 " + data);
        }

        File dst = binary(ctx);
        // 已安装的内核存在且能认出来是 ELF 就直接用，不覆盖。
        if (dst.exists() && dst.length() >= 4 && looksLikeElf(dst)) {
            return dst;
        }

        // 壳子包（SKIP_KERNEL=1 构建产物）里没有 assets/bin/magpie.bin：
        // 手机上又没有已装内核 → 明确报错，而不是 AssetsNotFoundException 崩
        boolean hasAsset = true;
        try {
            ctx.getAssets().open(ASSET).close();
        } catch (java.io.FileNotFoundException e) {
            hasAsset = false;
        } catch (IOException e) {
            hasAsset = false;
        }
        if (!hasAsset) {
            throw new IOException(
                    "这个安装包是壳子包（不含内核），而手机上没有已安装的内核。"
                    + "请先安装完整版（内含内核），或在设置里完成内核更新后再使用。");
        }

        File tmp = new File(root, "magpie.tmp");
        long n = 0;
        try (InputStream in = new BufferedInputStream(ctx.getAssets().open(ASSET), 1 << 16);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
                n += read;
            }
            out.flush();
            ((FileOutputStream) out).getFD().sync();
        }
        if (n < 4 || !looksLikeElf(tmp)) {
            throw new IOException("解压出来的文件不是 ELF（" + n + " 字节）");
        }
        dst.delete();
        if (!tmp.renameTo(dst)) {
            throw new IOException("无法写出 " + dst);
        }
        dst.setReadable(true, false);
        dst.setWritable(true, false);
        dst.setExecutable(true, false);
        appendLog("已从 APK 释放 magpie 二进制（首次/丢失恢复）：" + dst + "（" + n + " 字节）");
        return dst;
    }


    private static boolean looksLikeElf(File f) {
        if (f == null || !f.exists() || f.length() < 4) {
            return false;
        }
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[4];
            return in.read(b) == 4
                    && (b[0] & 0xff) == 0x7f && b[1] == 'E' && b[2] == 'L' && b[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // log ring
    // ------------------------------------------------------------------

    private static final Object TAIL_LOCK = new Object();

    /** 磁盘日志上限：50 MB。超了就把 magpie.log 改成 magpie.log.1（只留一份旧的）。 */
    private static final long LOG_MAX = 50L * 1024 * 1024;
    /** 当前 magpie.log 的大致字节数。-1 表示还没 stat 过。 */
    private static volatile long logSize = -1;
    private static final Object LOG_LOCK = new Object();

    static void appendLog(String line) {
        synchronized (TAIL_LOCK) {
            if (tail.length() > 0) {
                tail.append('\n');
            }
            tail.append(line);
            if (tail.length() > 64 * 1024) {
                // 超限不是整段清零（那会让 lastLog(3000) 突然只剩几行），
                // 而是从中间最近的行界截掉头部，保留一半
                String all = tail.toString();
                int cut = all.indexOf('\n', all.length() / 2);
                if (cut >= 0) {
                    tail.setLength(0);
                    tail.append(all, cut + 1, all.length());
                } else {
                    tail.setLength(0);
                }
            }
        }
        try {
            File f = logFile(App.context());
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            synchronized (LOG_LOCK) {
                if (logSize < 0) {
                    logSize = f.length();
                }
                // 写之前先看会不会超：超了就轮转，保证单文件不超过 LOG_MAX
                if (logSize + bytes.length > LOG_MAX) {
                    rotateLog(f);
                    logSize = 0;
                }
                try (OutputStream out = new FileOutputStream(f, true)) {
                    out.write(bytes);
                }
                logSize += bytes.length;
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 把当前的 magpie.log 改成 magpie.log.1，只留一份旧的。
     *
     * <p>以前这里只有 append，没有上限：日志从装 App 那天起只增不减。
     * 现在 50 MB 一轮，最坏占用 100 MB（当前 + 上一份）。
     * 旧的 .1 先删再改名，避免 renameTo 在目标已存在时失败。
     */
    private static void rotateLog(File f) {
        try {
            File old = new File(f.getParentFile(), f.getName() + ".1");
            if (old.exists()) {
                //noinspection ResultOfMethodCallIgnored
                old.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            f.renameTo(old);
        } catch (Exception ignored) {
        }
    }

    static String lastLog(int lines) {
        synchronized (TAIL_LOCK) {
            String all = tail.toString();
            if (lines <= 0) {
                return all;
            }
            int from = 0;
            int count = 0;
            for (int i = all.length() - 1; i >= 0 && count < lines; i--) {
                if (all.charAt(i) == '\n') {
                    count++;
                    from = i + 1;
                }
            }
            return all.substring(from);
        }
    }

    static void clearLog() {
        synchronized (TAIL_LOCK) {
            tail.setLength(0);
        }
        synchronized (LOG_LOCK) {
            File f = logFile(App.context());
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            File old = new File(f.getParentFile(), f.getName() + ".1");
            if (old.exists()) {
                //noinspection ResultOfMethodCallIgnored
                old.delete();
            }
            logSize = 0;
        }
    }

    // ------------------------------------------------------------------
    // state
    // ------------------------------------------------------------------

    static boolean isReady() {
        synchronized (LOCK) {
            return ready && process != null && process.isAlive();
        }
    }

    static boolean isStarting() {
        synchronized (LOCK) {
            return starting;
        }
    }

    static boolean isAlive() {
        synchronized (LOCK) {
            return process != null && process.isAlive();
        }
    }

    static String bootError() {
        synchronized (LOCK) {
            return bootError;
        }
    }

    static int exitCode() {
        synchronized (LOCK) {
            return exitCode;
        }
    }

    static void resetState() {
        synchronized (LOCK) {
            ready = false;
            starting = false;
            bootError = "";
            exitCode = -1;
        }
    }

    public static boolean portOpen(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 插件补丁表：桌面浏览器 / 桌面进程内存这些东西 Android 上没有，
    // 靠这张表把插件的登录换成一条 Android 能走的路
    // ------------------------------------------------------------------

    /**
     * 一条插件补丁。
     *
     * <p>{@code anchor} 是插件里稳定的一段源码，{@code replacement} 是换成带
     * Android 兜底的版本，{@code helper} 追加到文件末尾（新增的函数定义）。
     *
     * <p>只做锚点替换：锚点找不到就跳过、只记一行日志 —— 绝不整文件覆盖，
     * 否则插件作者的新逻辑会被一起丢掉。
     */
    private static final class Patch {
        final String id;
        final String file;
        final String mark;
        final String helperMark;
        final String anchor;
        final String replacement;
        final String helper;

        Patch(String id, String file, String mark, String helperMark,
              String anchor, String replacement, String helper) {
            this.id = id;
            this.file = file;
            this.mark = mark;
            this.helperMark = helperMark;
            this.anchor = anchor;
            this.replacement = replacement;
            this.helper = helper;
        }
    }

    private static final String MARVIS_FILE =
            "data/plugins/node_modules/magpie-marvis/src/hooks/methods.mjs";
    private static final String MARVIS_MARK = "MAGPIE_ANDROID_PASTE";
    private static final String MARVIS_HELPER_MARK = "MAGPIE_ANDROID_PASTE_HELPER";

    private static final String MARVIS_ANCHOR =
            "        async callback(_url, _input, signal) {\n"
                    + "          const browser = findBrowser()";

    private static final String MARVIS_REPLACEMENT =
            "        async callback(_url, _input, signal) {\n"
                    + "          /* MAGPIE_ANDROID_PASTE */\n"
                    + "          // Android 上没有可 spawn 的 Chromium：等壳把用户粘贴的回调地址写进文件。\n"
                    + "          if (!findBrowser()) {\n"
                    + "            const pasted = await waitPastedCallback(signal)\n"
                    + "            const st = stateOfUrl(pasted)\n"
                    + "            const vg = pendingDevices.take(st) || (loginType === \"WX\" ? st : \"\")\n"
                    + "            if (!vg) {\n"
                    + "              throw new Error(\"登录流程已失效（找不到这次登录的设备标识）。请重新点一次登录。\")\n"
                    + "            }\n"
                    + "            let acct = await parseCallback(pasted, \"\", { loginType, guid: vg })\n"
                    + "            acct = await withProfile({ ...acct, guid: vg }, loginType)\n"
                    + "            return authOf({ ...acct, guid: vg, loginType: acct.loginType ?? LOGIN_TYPE[loginType] ?? \"1\" })\n"
                    + "          }\n"
                    + "          const browser = findBrowser()";

    private static final String MARVIS_HELPER = "\n\n/* MAGPIE_ANDROID_PASTE_HELPER */\n"
            + "// 壳把粘贴的回调地址写在 HOME/marvis-login-url.txt，读完即删。\n"
            + "async function waitPastedCallback(signal) {\n"
            + "  const fs = await import(\"node:fs/promises\")\n"
            + "  const os = await import(\"node:os\")\n"
            + "  const path = await import(\"node:path\")\n"
            + "  const file = path.join(os.homedir(), \"marvis-login-url.txt\")\n"
            + "  const started = Date.now()\n"
            + "  while (Date.now() - started < 10 * 60_000) {\n"
            + "    if (signal && signal.aborted) throw new Error(\"登录已取消\")\n"
            + "    try {\n"
            + "      const raw = (await fs.readFile(file, \"utf8\")).trim()\n"
            + "      if (raw) {\n"
            + "        await fs.unlink(file).catch(() => {})\n"
            + "        return raw\n"
            + "      }\n"
            + "    } catch {\n"
            + "      /* 还没写进来 */\n"
            + "    }\n"
            + "    await new Promise((r) => setTimeout(r, 500))\n"
            + "  }\n"
            + "  throw new Error(\"等待粘贴 Marvis 授权回调超时（10 分钟）。请重新点一次登录。\")\n"
            + "}\n";

    // ---- Antigravity 桥接插件：install.mjs 的 agyPlatform() 少了 android 分支 ----
    //     Google 官方其实有 android_arm64 清单；插件 platform 判断到 android 直接 return null →
    //     误报 "Antigravity CLI has no build for android arm64"。补一条 android 分支即可
    //     （插件随后自己会去读 manifests/android_arm64.json 下载安装）。
    private static final String AGY_PATCH_ID = "opencode-antigravity-bridge";
    private static final String AGY_ANCHOR =
            "  if (platform === \"linux\") return `linux_${cpu}${musl ? \"_musl\" : \"\"}`";
    private static final String AGY_REPLACEMENT =
            "  if (platform === \"android\") return `android_${cpu}`\n"
                    + "  if (platform === \"linux\") return `linux_${cpu}${musl ? \"_musl\" : \"\"}`";
    /** 幂等标记：已含这段就说明补过。 */
    private static final String AGY_MARK = "android_${cpu}";

    /** 已知需要 Android 适配的插件。加新插件就往这里加一条。 */
    private static final Patch[] PATCHES = {
            new Patch("magpie-marvis", MARVIS_FILE, MARVIS_MARK, MARVIS_HELPER_MARK,
                    MARVIS_ANCHOR, MARVIS_REPLACEMENT, MARVIS_HELPER),
    };

    /** 补丁出问题时留给 UI 的一句话（一次性，读完即清）。 */
    private static volatile String patchNote = "";

    static String takePatchNote() {
        String n = patchNote;
        patchNote = "";
        return n;
    }

    /** 每次启动网关前跑一遍：已经补过的跳过，插件更新后被覆盖的补回来。 */
    static void patchPlugins(Context ctx) {
        for (Patch p : PATCHES) {
            applyPatch(ctx, p);
        }
        // Antigravity 桥接插件的 install.mjs 装在版本化目录里，路径不固定，单独扫描补。
        patchAntigravityPlugin(ctx);
    }

    private static void applyPatch(Context ctx, Patch p) {
        File f = new File(rootDir(ctx), p.file);
        if (!f.isFile()) {
            return; // 没装这个插件
        }
        String src;
        try {
            src = readText(f);
        } catch (IOException e) {
            appendLog("插件补丁：读不到 " + p.id + "（" + e.getMessage() + "）");
            return;
        }
        if (src.contains(p.mark) && src.contains(p.helperMark)) {
            return; // 已经补过
        }
        if (!src.contains(p.anchor)) {
            patchNote = p.id + " 的兼容补丁没生效：插件已改版，需要更新这个 App";
            appendLog("插件补丁跳过：" + p.id + " 锚点找不到（插件结构变了）");
            return;
        }
        String out = src.replace(p.anchor, p.replacement);
        if (!out.contains(p.helperMark)) {
            out = out + p.helper;
        }
        try {
            File parent = f.getParentFile();
            // 先备份原文件（.bak），再写临时文件、最后 rename 原子替换：崩溃也不会写坏 methods.mjs。
            File bak = new File(parent, f.getName() + ".bak");
            try (FileOutputStream bo = new FileOutputStream(bak)) {
                bo.write(src.getBytes(StandardCharsets.UTF_8));
            }
            File tmp = new File(parent, f.getName() + ".magpie-tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(out.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) {
                // rename 失败（极少见）：保留 .bak 与原文件，报错，不留半截文件
                tmp.delete();
                throw new IOException("临时文件 rename 失败");
            }
            appendLog("插件补丁已写入：" + p.id);
        } catch (IOException e) {
            patchNote = p.id + " 的兼容补丁写不进去";
            appendLog("插件补丁写入失败：" + p.id + " " + e.getMessage());
        }
    }

    /**
     * 补 Antigravity 桥接插件：install.mjs 的 agyPlatform() 缺 android 分支。
     *
     * <p>插件装在版本化目录（含 @GH@…install-cache），落点不固定，所以遍历 data/plugins 下
     * 所有名为 install.mjs、且含 agyPlatform 的文件做锚点替换；找不到锚点就跳过记日志，
     * 绝不整文件覆盖。已含 {@link #AGY_MARK} 视为已补，幂等跳过。每次 boot 都会跑，
     * 插件更新后被覆盖会自动补回。
     */
    private static void patchAntigravityPlugin(Context ctx) {
        File plugins = new File(dataDir(ctx), "plugins");
        if (!plugins.isDirectory()) {
            return;
        }
        java.util.List<File> hits = new java.util.ArrayList<>();
        collectInstallMjs(plugins, hits, 0, new int[]{0});
        for (File f : hits) {
            String src;
            try {
                src = readText(f);
            } catch (IOException e) {
                continue;
            }
            if (src == null || !src.contains("agyPlatform")) {
                continue;
            }
            if (src.contains(AGY_MARK)) {
                continue; // 已补过
            }
            if (!src.contains(AGY_ANCHOR)) {
                appendLog("插件补丁跳过：" + AGY_PATCH_ID + " 锚点找不到（install.mjs 结构变了）");
                continue;
            }
            String out = src.replace(AGY_ANCHOR, AGY_REPLACEMENT);
            if (writePatched(f, src, out)) {
                appendLog("已补 antigravity 插件 android 平台支持：" + f.getAbsolutePath());
            }
        }
    }

    /** 递归收集 data/plugins 下名为 install.mjs 的文件（深度/数量/遍历目录数设上限，避免拖慢启动）。 */
    private static void collectInstallMjs(File dir, java.util.List<File> out, int depth, int[] dirs) {
        if (dir == null || depth > 12 || out.size() > 200 || dirs[0] > 3000) {
            return;
        }
        dirs[0]++;
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            if (k.isDirectory()) {
                if (".git".equals(k.getName())) {
                    continue;
                }
                collectInstallMjs(k, out, depth + 1, dirs);
            } else if ("install.mjs".equals(k.getName())) {
                out.add(k);
            }
        }
    }

    /** 原子写补丁：备份 .bak → 写 .magpie-tmp → rename 覆盖。成功返回 true。 */
    private static boolean writePatched(File f, String src, String out) {
        try {
            File parent = f.getParentFile();
            File bak = new File(parent, f.getName() + ".bak");
            try (FileOutputStream bo = new FileOutputStream(bak)) {
                bo.write(src.getBytes(StandardCharsets.UTF_8));
            }
            File tmp = new File(parent, f.getName() + ".magpie-tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(out.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) {
                tmp.delete();
                return false;
            }
            return true;
        } catch (IOException e) {
            appendLog("插件补丁写入失败：" + AGY_PATCH_ID + " " + e.getMessage());
            return false;
        }
    }

    /** 给「粘贴 Marvis 授权回调」对话框看的：插件在不在、版本、补丁状态。 */
    static String patchStatus(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (Patch p : PATCHES) {
            File f = new File(rootDir(ctx), p.file);
            String ver = pluginVersion(ctx, p.id);
            sb.append(p.id).append(ver.isEmpty() ? "" : " " + ver).append("：");
            if (!f.isFile()) {
                sb.append("没装这个插件");
            } else {
                String src;
                try {
                    src = readText(f);
                } catch (IOException e) {
                    src = "";
                }
                if (src.contains(p.mark) && src.contains(p.helperMark)) {
                    sb.append("兼容补丁已生效");
                } else if (src.contains(p.anchor)) {
                    sb.append("补丁待写入（重启网关即可）");
                } else {
                    sb.append("补丁不适用（插件已改版，需要更新这个 App）");
                }
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static String pluginVersion(Context ctx, String id) {
        File f = new File(rootDir(ctx), "data/plugins/node_modules/" + id + "/package.json");
        if (!f.isFile()) {
            return "";
        }
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(readText(f));
            return m.find() ? m.group(1) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    // ------------------------------------------------------------------
    // wake lock
    // ------------------------------------------------------------------

    private static void acquireWakeLock(Context ctx) {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "magpie:gateway");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private static void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 息屏省电（实验室功能）：息屏后如果连续 N 分钟没有网关活动，就主动释放
     * CPU 唤醒锁让手机深度休眠；一旦亮屏立刻重新持有。
     *
     * <p>为什么这么设计：网关要随时响应 AI 工具请求，所以默认一直持有
     * PARTIAL_WAKE_LOCK。但很多人晚上睡觉不用，整夜抱着唤醒锁纯浪费。
     * 折中方案——息屏且闲置够久就放开，亮屏马上恢复，代价只是「息屏期间
     * 首次请求可能慢一点（等 CPU 唤醒）」，甚至失败重试一次就过。
     *
     * <p>监控活动的方式：网关每处理一个请求都会往 stdout 打日志，pumpLog
     * 每读到一行就刷新 {@link #lastActivityAt}，所以「有没有 AI 请求」等价于
     * 「日志多久没新行」。
     *
     * <p>开关与阈值在设置 → 省电策略实验室里配（{@link Prefs}），默认关闭。
     */
    private static void startWakeSleeper(final Context ctx) {
        if (sleeperRunning) {
            return;
        }
        if (!Prefs.wakeSaverEnabled(ctx)) {
            return;
        }
        sleeperRunning = true;

        // 息屏/亮屏广播：壳自己维护状态，比轮询 isInteractive 稳
        if (screenReceiver == null) {
            screenReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, android.content.Intent in) {
                    String a = in == null ? "" : in.getAction();
                    if (android.content.Intent.ACTION_SCREEN_OFF.equals(a)) {
                        screenOff = true;
                        lastActivityAt = System.currentTimeMillis();
                    } else if (android.content.Intent.ACTION_SCREEN_ON.equals(a)) {
                        screenOff = false;
                        // 亮屏立刻恢复唤醒锁
                        acquireWakeLock(c);
                    }
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(android.content.Intent.ACTION_SCREEN_OFF);
            f.addAction(android.content.Intent.ACTION_SCREEN_ON);
            try {
                ctx.registerReceiver(screenReceiver, f);
            } catch (Exception ignored) {
            }
        }

        Thread t = new Thread(() -> {
            while (sleeperRunning) {
                try {
                    Thread.sleep(30_000); // 30 秒看一次，够用且省电
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    int minutes = Prefs.wakeSaverMinutes(ctx);
                    if (minutes <= 0 || !screenOff) {
                        continue;
                    }
                    long idleMs = System.currentTimeMillis() - lastActivityAt;
                    if (idleMs >= minutes * 60_000L) {
                        if (wakeLock != null && wakeLock.isHeld()) {
                            releaseWakeLock();
                            appendLog("息屏闲置 " + minutes + " 分钟，已释放 CPU 唤醒锁（亮屏或新请求后恢复）");
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }, "magpie-wake-sleeper");
        t.setDaemon(true);
        wakeSleeperThread = t;
        t.start();
        appendLog("息屏省电已启用：闲置 " + Prefs.wakeSaverMinutes(ctx) + " 分钟后释放唤醒锁");
    }

    private static void stopWakeSleeper(Context ctx) {
        sleeperRunning = false;
        Thread t = wakeSleeperThread;
        wakeSleeperThread = null;
        if (t != null) {
            t.interrupt(); // 让守夜线程立刻从 sleep 醒来退出
            try {
                t.join(1000); // 等它真退出，避免快速 stop→start 叠出两个守夜线程
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (screenReceiver != null) {
            try {
                ctx.unregisterReceiver(screenReceiver);
            } catch (Exception ignored) {
            }
            screenReceiver = null;
        }
        screenOff = false;
    }

    /** 有新请求进来时（pumpLog 看到新日志）如果唤醒锁被省电放掉了，重新拿。 */
    private static void ensureWakeLockForActivity(Context ctx) {
        if (!Prefs.wakeSaverEnabled(ctx)) {
            return;
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            acquireWakeLock(ctx);
        }
    }

    // ------------------------------------------------------------------
    // notification
    // ------------------------------------------------------------------

    static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Magpie 网关", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("后台运行 magpie 网关");
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private static Notification notification(Context ctx, String title, String body, int flags) {
        Intent open = new Intent(ctx, NativeHome.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 1, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(ctx, CHANNEL_ID);
        } else {
            b = new Notification.Builder(ctx);
        }
        return b
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pi)
                .setOngoing((flags & Notification.FLAG_ONGOING_EVENT) != 0)
                .setWhen(System.currentTimeMillis())
                .getNotification();
    }

    private void notifyReady(Context ctx) {
        postState(ctx, ST_RUNNING);
    }

    private void notifyDown(Context ctx, int code) {
        postState(ctx, ST_DOWN);
    }

    /** 按状态生成前台通知。 */
    private static Notification stateNotification(Context ctx, int st) {
        if (st == ST_RUNNING) {
            return notification(ctx, "Magpie 运行中",
                    "网关 http://" + Prefs.browserHost(ctx) + ":" + gatewayPort(ctx)
                            + (Prefs.lan(ctx) ? " · 局域网共享已开" : ""), FOREGROUND_FLAGS);
        }
        if (st == ST_DOWN) {
            return notification(ctx, "Magpie 正在确认网关状态", "网关进程未运行", FOREGROUND_FLAGS);
        }
        return notification(ctx, "Magpie 正在启动网关…", "正在拉起内核，请稍候", FOREGROUND_FLAGS);
    }

    /** 去重地更新前台通知。状态没变就不重发（notify 有系统频控）。 */
    private static void postState(Context ctx, int st) {
        if (st == lastPostedState) {
            return;
        }
        lastPostedState = st;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, stateNotification(ctx, st));
        }
    }

    /**
     * 兼容旧调用（MainActivity 通知点击）。按真实端口状态对齐通知，
     * 不再只看 process.isAlive() —— 那正是「网关在跑但通知卡启动」的老坑。
     */
    static void syncNotification(Context ctx, boolean aliveIgnored) {
        // 调用来自 UI 线程：不在这里 portOpen（500ms connect 会卡主线程）。用进程/ready 快判，
        // 真实端口状态由后台对账线程校正（最多 7s）。
        boolean serving = isAlive() || ready;
        postState(ctx, serving ? ST_RUNNING : ST_DOWN);
    }

    /** onStartCommand 用：不在主线程探测端口（500ms connect 会卡主线程），只用快速状态；
     *  真实端口状态由后台对账线程 7s 内校正，boot() 进后台前也会再查一次端口防重复启动。 */
    private int startState() {
        return ready ? ST_RUNNING : ST_STARTING;
    }

    /** 对账线程用：综合 ready / 端口 / 进程，给出真实状态。 */
    private int effectiveState() {
        if (isServing()) {
            return ST_RUNNING;
        }
        synchronized (LOCK) {
            if (starting || (process != null && process.isAlive())) {
                return ST_STARTING;
            }
        }
        return ST_DOWN;
    }

    /** 网关是否真的在服务：ready 标记，或网页端口本机可连。 */
    private boolean isServing() {
        if (ready) {
            return true;
        }
        try {
            return portOpen(Prefs.browserHost(this), webPort(this));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 起一个「通知对账」后台线程：每 7 秒按真实端口状态校正通知。
     *
     * <p>这是治「通知卡在正在启动网关」的兜底：不管哪条路径把通知重置成了启动文案
     * （startForeground 重发、服务被系统重建、process 引用过期、starting 残留…），
     * 只要网关其实在监听，最多 7 秒后就会自愈成「运行中」。
     */
    private void startNotifTicker() {
        if (notifTickerRunning) {
            return;
        }
        notifTickerRunning = true;
        Thread t = new Thread(() -> {
            while (notifTickerRunning) {
                try {
                    postState(GatewayService.this, effectiveState());
                } catch (Exception ignored) {
                }
                sleep(7000);
            }
            // 退出时把引用清空，允许后续（服务重建）再启动一个对账线程。
            // 循环条件用独立的 notifTickerRunning，不看 stopping：restart 期间
            // stopping 会 true→false，若拿它当条件线程会被误杀且永不再起。
            synchronized (LOCK) {
                if (notifThread == Thread.currentThread()) {
                    notifThread = null;
                }
            }
        }, "magpie-notif");
        t.setDaemon(true);
        notifThread = t;
        t.start();
    }

    static void postNotice(Context ctx, String title, String body) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID + 7, notification(ctx, title, body, 0));
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void writeFile(File f, String content) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建目录 " + dir);
        }
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
