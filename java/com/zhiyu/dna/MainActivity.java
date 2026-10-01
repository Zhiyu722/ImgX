package com.zhiyu.dna;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.zhiyu.dna.engine.DnaEngine;
import com.zhiyu.dna.engine.Progress;
import com.zhiyu.dna.engine.ToolPaths;
import com.zhiyu.dna.ui.GlassScene;
import com.zhiyu.dna.ui.DotIndicator;
import com.zhiyu.dna.ui.GlassButton;
import com.zhiyu.dna.ui.GlassCard;
import com.zhiyu.dna.ui.GlassPager;
import com.zhiyu.dna.ui.GlassSwitch;
import com.zhiyu.dna.ui.GlassSegmented;
import com.zhiyu.dna.ui.LogView;
import com.zhiyu.dna.util.Binaries;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static final int REQ_INPUT_FILE = 1001;
    static final int REQ_OUTPUT_DIR = 1002;
    static final int REQ_ALL_FILES = 1003;

    private GlassPager pager;
    private DotIndicator dots;
    private GlassSegmented topTabs;
    private TextView permHint;
    private GlassButton permBtn;

    // 解包页状态
    private EditText inputPathEt;
    private EditText outPathEt;
    private TextView typeLabel;
    private GlassSwitch autoPartsSw;
    private LogView unpackLog;

    // 打包页状态
    private EditText packSrcEt;
    private EditText packOutEt;
    private EditText packLabelEt;
    private GlassSegmented formatSeg;
    private LogView packLog;

    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private ToolPaths tools;
    private boolean engineReady;
    private TextView engineStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 全局崩溃捕获: 错误写入 /sdcard/DNA/crash_log.txt 便于排查
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                File crash = new File(Binaries.defaultOutDir(this).getParentFile(), "crash_log.txt");
                crash.getParentFile().mkdirs();
                java.io.FileOutputStream fos = new java.io.FileOutputStream(crash, true);
                fos.write(("==== " + new java.util.Date() + " ====\n" + sw.toString() + "\n").getBytes());
                fos.close();
                android.util.Log.e("DNA", "CRASH", e);
            } catch (Exception ignore) {
            }
        });
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 26) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        buildUi();
    }

    // ================= UI 组装 =================

    private GlassScene scene;
    private LinearLayout topArea;
    private float dragStartPos = -1;
    private View permBanner;
    private boolean allFilesRequested = false;
    private boolean tabDragging = false;   // 顶栏拖动中(胶囊需 1:1)
    private static final int REQ_PERM_LEGACY = 1004;  // 注意: 必须与其他请求码不同
    private float pillStartSeg = 0f;

    private void buildUi() {
        float density = getResources().getDisplayMetrics().density;
        scene = new GlassScene(this);
        FrameLayout root = scene;
        root.setBackgroundColor(0xFFD1D7DE);
        scene.getBackgroundView().setFrameCallback(scene::requestCapture);

        // ===== 顶部区域(品牌标题 + 玻璃顶栏, 整体随滑动视差移动) =====
        LinearLayout topArea = new LinearLayout(this);
        topArea.setOrientation(LinearLayout.VERTICAL);
        topArea.setGravity(Gravity.CENTER_HORIZONTAL);
        topArea.setPadding((int) dp(18), 0, (int) dp(18), 0);

        // 品牌标题: DNA(渐变) + 副标题
        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        TextView brand = new TextView(this);
        brand.setText("DNA");
        brand.setTextSize(23);
        brand.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        brand.setTextColor(0xFF169AFF);
        brand.setShadowLayer(dp(10), 0, dp(2), 0x44169AFF);
        brand.setLetterSpacing(0.02f);
        brandRow.addView(brand);
        TextView brandSub = new TextView(this);
        brandSub.setText("固件解包助手");
        brandSub.setTextSize(11.5f);
        brandSub.setTextColor(0xFF7A8794);
        brandSub.setLetterSpacing(0.12f);
        android.widget.LinearLayout.LayoutParams subLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.leftMargin = (int) dp(8);
        brandRow.addView(brandSub, subLp);
        topArea.addView(brandRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (int) dp(30)));

        // 玻璃顶栏: 宽度自适应(左右留 18dp)
        topTabs = new GlassSegmented(this, new String[]{"解包", "打包", "关于"});
        topTabs.setOnSelectedListener(index -> {
            tabDragging = false;
            pager.setCurrentPage(index, true);
        });
        topTabs.setOnDragListener(pos -> {
            tabDragging = true;
            pager.setPositionFraction(pos, false);
        });
        topTabs.attachScene(scene);
        topArea.addView(topTabs, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) dp(50)));
        this.topArea = topArea;

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        topLp.topMargin = (int) (dp(8) + statusBarHeight());
        // 分辨率适配: 整屏铺满, 只按屏宽比例留一点边(不再硬性限宽居中)
        int screenW = getResources().getDisplayMetrics().widthPixels;
        float dpWidth = screenW / density;
        int sidePad = adaptiveSidePadding(dpWidth);
        topArea.setPadding(sidePad, 0, sidePad, 0);
        root.addView(topArea, topLp);

        // 分页器
        pager = new GlassPager(this);
        pager.addPage(buildUnpackPage());
        pager.addPage(buildPackPage());
        pager.addPage(buildAboutPage());
        FrameLayout.LayoutParams pagerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        pagerLp.topMargin = (int) (dp(96) + statusBarHeight());
        pagerLp.bottomMargin = (int) dp(40);
        root.addView(pager, pagerLp);

        // 分辨率适配: 测量真实顶栏高度后动态设置分页器上边距(避免不同屏幕遮挡/错位)
        topArea.post(() -> {
            if (pager.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pager.getLayoutParams();
                int h = topArea.getHeight() + topArea.getTop() + (int) dp(6);
                if (h > 0 && lp.topMargin != h) {
                    lp.topMargin = h;
                    pager.setLayoutParams(lp);
                }
            }
        });

        pager.setOnPageChangedListener((index, pos) -> {
            // 分页器是唯一动画来源: 胶囊/指示点 1:1 跟随, 不做二次弹簧(否则点击会"慢半拍")
            topTabs.setPosition(pos, true);
            dots.setPosition(pos);
        });

        // 底部指示器
        dots = new DotIndicator(this);
        dots.setCount(3);
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, (int) dp(12), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dotLp.bottomMargin = (int) (dp(12) + navBarHeight());
        root.addView(dots, dotLp);

        setContentView(root);

        // 存储权限检查
        checkStoragePermission();
        // 初始化引擎
        ensureEngine(null);
        // 自检(输出到 logcat, 便于验证): /sdcard 可写性
        worker.execute(() -> {
            try {
                File out = Binaries.defaultOutDir(this);
                out.mkdirs();
                File probe = new File(out, ".probe");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(probe);
                fos.write(1);
                fos.close();
                probe.delete();
                android.util.Log.i("DNA", "SDCARD_OK " + out.getAbsolutePath());
            } catch (Exception e) {
                android.util.Log.e("DNA", "SDCARD_FAIL " + e.getMessage());
            }
        });
    }

    private float statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : (int) dp(24);
    }

    /** 导航栏/手势条高度, 用于底部留白适配 */
    private int navBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : (int) dp(24);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    // ---------------- 解包页 ----------------

    private View buildUnpackPage() {
        LinearLayout col = pageColumn();
        col.addView(sectionTitle("解包镜像"));

        // 输入文件卡片
        GlassCard card1 = new GlassCard(this);
        card1.attachScene(scene);
        LinearLayout c1 = cardColumn(card1);
        c1.addView(fieldLabel("镜像文件"));
        inputPathEt = glassEdit();
        inputPathEt.setHint("点右侧按钮选择, 或直接输入路径(含 /sdcard/...)");
        c1.addView(inputPathEt);
        GlassButton browseBtn = new GlassButton(this, "浏览", GlassButton.STYLE_GLASS);
        browseBtn.setOnClickListener(v -> pickInputFile());
        c1.addView(browseBtn);
        typeLabel = new TextView(this);
        typeLabel.setText("未选择文件");
        typeLabel.setTextColor(0xFF6B7280);
        typeLabel.setTextSize(13);
        c1.addView(typeLabel);
        col.addView(card1, cardLp());

        // 输出目录卡片
        GlassCard card2 = new GlassCard(this);
        card2.attachScene(scene);
        LinearLayout c2 = cardColumn(card2);
        c2.addView(fieldLabel("解包输出目录"));
        outPathEt = glassEdit();
        outPathEt.setText(Binaries.defaultOutDir(this).getAbsolutePath());
        c2.addView(outPathEt);
        GlassButton dirBtn = new GlassButton(this, "选择目录", GlassButton.STYLE_GLASS);
        dirBtn.setOnClickListener(v -> pickOutputDir());
        c2.addView(dirBtn);
        col.addView(card2, cardLp());

        // 选项卡片
        GlassCard card3 = new GlassCard(this);
        card3.attachScene(scene);
        LinearLayout c3 = cardColumn(card3);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = new TextView(this);
        tv.setText("自动解包 payload/super 分区(耗时更长)");
        tv.setTextColor(0xFF14191C);
        tv.setTextSize(15);
        row.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        autoPartsSw = new GlassSwitch(this);
        autoPartsSw.setChecked(true);
        row.addView(autoPartsSw);
        c3.addView(row);
        col.addView(card3, cardLp());

        GlassButton runBtn = new GlassButton(this, "开始解包", GlassButton.STYLE_PRIMARY);
        runBtn.setOnClickListener(v -> startUnpack());
        col.addView(runBtn, btnLp());

        unpackLog = new LogView(this);
        unpackLog.setVisibility(View.GONE);
        col.addView(unpackLog, logLp());

        return scrollWrap(col);
    }

    // ---------------- 打包页 ----------------

    private View buildPackPage() {
        LinearLayout col = pageColumn();
        col.addView(sectionTitle("打包镜像"));

        GlassCard card1 = new GlassCard(this);
        card1.attachScene(scene);
        LinearLayout c1 = cardColumn(card1);
        c1.addView(fieldLabel("源目录(解包产物 / 要打包的文件夹)"));
        packSrcEt = glassEdit();
        packSrcEt.setHint("选择或输入目录路径");
        c1.addView(packSrcEt);
        GlassButton b1 = new GlassButton(this, "选择目录", GlassButton.STYLE_GLASS);
        b1.setOnClickListener(v -> pickPackSrc());
        c1.addView(b1);
        col.addView(card1, cardLp());

        GlassCard card2 = new GlassCard(this);
        card2.attachScene(scene);
        LinearLayout c2 = cardColumn(card2);
        c2.addView(fieldLabel("输出文件"));
        packOutEt = glassEdit();
        packOutEt.setHint("例如 /sdcard/DNA/out/system.img");
        c2.addView(packOutEt);
        GlassButton b2 = new GlassButton(this, "浏览保存位置", GlassButton.STYLE_GLASS);
        b2.setOnClickListener(v -> pickPackOut());
        c2.addView(b2);
        c2.addView(fieldLabel("ext4 卷标(可选)"));
        packLabelEt = glassEdit();
        packLabelEt.setHint("system / vendor ...");
        c2.addView(packLabelEt);
        col.addView(card2, cardLp());

        GlassCard card3 = new GlassCard(this);
        card3.attachScene(scene);
        LinearLayout c3 = cardColumn(card3);
        c3.addView(fieldLabel("输出格式"));
        formatSeg = new GlassSegmented(this, new String[]{"ext4 镜像", "sparse 镜像", "boot 镜像"});
        c3.addView(formatSeg, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) dp(44)));
        col.addView(card3, cardLp());

        GlassButton runBtn = new GlassButton(this, "开始打包", GlassButton.STYLE_PRIMARY);
        runBtn.setOnClickListener(v -> startPack());
        col.addView(runBtn, btnLp());

        packLog = new LogView(this);
        packLog.setVisibility(View.GONE);
        col.addView(packLog, logLp());

        return scrollWrap(col);
    }

    // ---------------- 关于页 ----------------

    private View buildAboutPage() {
        LinearLayout col = pageColumn();
        col.addView(sectionTitle("关于"));

        GlassCard card1 = new GlassCard(this);
        card1.attachScene(scene);
        LinearLayout c1 = cardColumn(card1);
        TextView title = new TextView(this);
        title.setText("DNA 固件解包助手");
        title.setTextColor(0xFF14191C);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        c1.addView(title);
        TextView ver = new TextView(this);
        ver.setText("版本 3.0.0  ·  包名 com.zhiyu.dna");
        ver.setTextColor(0xFF6B7280);
        ver.setTextSize(12);
        ver.setGravity(Gravity.CENTER);
        c1.addView(ver);
        TextView author = new TextView(this);
        author.setText("作者: Zhiyu · cuoxianxu");
        author.setTextColor(0xFF169AFF);
        author.setTextSize(13);
        author.setGravity(Gravity.CENTER);
        author.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        c1.addView(author);
        engineStatus = new TextView(this);
        engineStatus.setText("引擎: 初始化中 ...");
        engineStatus.setTextColor(0xFF169AFF);
        engineStatus.setTextSize(13);
        engineStatus.setGravity(Gravity.CENTER);
        c1.addView(engineStatus);
        GlassButton initBtn = new GlassButton(this, "初始化 / 修复引擎", GlassButton.STYLE_GLASS);
        initBtn.setOnClickListener(v -> ensureEngine(unpackLog != null ? unpackLog : packLog));
        c1.addView(initBtn);
        col.addView(card1, cardLp());

        GlassCard card2 = new GlassCard(this);
        card2.attachScene(scene);
        LinearLayout c2 = cardColumn(card2);
        c2.addView(fieldLabel("原理(移植自 D.N.A3)"));
        c2.addView(infoLine("· 魔数识别: 不靠后缀, 直接读文件头判断 zip / sparse / ext4 / super / payload / boot 等格式"));
        c2.addView(infoLine("· sparse→raw: 解析 Android sparse 块表(RAW/FILL/DONTCARE)展开为完整镜像"));
        c2.addView(infoLine("· super.img: 解析 liblp geometry + metadata 表, 按 extent 切出各分区"));
        c2.addView(infoLine("· ext4: 内置 debugfs 提取文件树, 不依赖 root"));
        c2.addView(infoLine("· new.dat: 解析 transfer list, 按块区间写回镜像; .br 用 brotli 解压"));
        c2.addView(infoLine("· payload.bin: 内置 payload-dumper-go 提取分区"));
        c2.addView(infoLine("· boot.img: 解析头部 + cpio/gzip 解包 ramdisk"));
        col.addView(card2, cardLp());

        GlassCard card3 = new GlassCard(this);
        card3.attachScene(scene);
        LinearLayout c3 = cardColumn(card3);
        c3.addView(fieldLabel("支持格式"));
        c3.addView(chipsRow(new String[]{"*.img", "*.sparse", "super.img", "payload.bin",
                "*.new.dat", "*.dat.br", "*.zip", "*.br", "boot.img", "*.gz"}));
        col.addView(card3, cardLp());

        GlassCard card4 = new GlassCard(this);
        card4.attachScene(scene);
        LinearLayout c4 = cardColumn(card4);
        c4.addView(infoLine("无需 root: 解包只需读取镜像文件, 只有\"从手机物理分区导出镜像\"才需要 root, 本应用不包含该功能。"));
        c4.addView(infoLine("输出默认保存到 /sdcard/DNA/out, 路径可自定义。"));
        c4.addView(infoLine("来源: github.com/ColdWindScholar/D.N.A3 (MIT)"));
        col.addView(card4, cardLp());

        return scrollWrap(col);
    }

    // ================= 页面构建工具 =================

    private LinearLayout pageColumn() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) dp(20), (int) dp(10), (int) dp(20), (int) dp(20));
        return col;
    }

    private LinearLayout cardColumn(GlassCard card) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding((int) dp(18), (int) dp(16), (int) dp(18), (int) dp(16));
        card.addView(c);
        return c;
    }

    /**
     * 自适应左右留边(px): 铺满整屏, 只按屏宽留一点点边。
     *  手机(<600dp): 20dp(与原观感一致)
     *  大屏: 屏宽的 4%, 上限 40dp —— 不再把内容挤成中间一条
     */
    private int adaptiveSidePadding(float dpWidth) {
        float marginDp;
        if (dpWidth < 600f) marginDp = 20f;
        else marginDp = Math.min(40f, dpWidth * 0.04f);
        return (int) dp(marginDp);
    }

    private View scrollWrap(View content) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        // 分辨率适配: 内容铺满整屏宽度(仅按屏宽比例留边), 不在大屏上居中限宽
        int screenW = getResources().getDisplayMetrics().widthPixels;
        float density = getResources().getDisplayMetrics().density;
        int sidePad = adaptiveSidePadding(screenW / density) - (int) dp(20);
        if (sidePad > 0) {
            FrameLayout wrap = new FrameLayout(this);
            wrap.setPadding(sidePad, 0, sidePad, 0);
            wrap.addView(content, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            sv.addView(wrap, new ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        } else {
            sv.addView(content, new ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        }
        return sv;
    }

    private FrameLayout.LayoutParams cardLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) dp(14);
        return lp;
    }

    private FrameLayout.LayoutParams btnLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, (int) dp(54));
        lp.bottomMargin = (int) dp(14);
        return lp;
    }

    private FrameLayout.LayoutParams logLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, (int) dp(220));
        lp.bottomMargin = (int) dp(14);
        return lp;
    }

    private View sectionTitle(String s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // 渐变强调条
        View bar = new View(this);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF64C6FF, 0xFF169AFF, 0xFF9C6BFF});
        gd.setCornerRadius(dp(4));
        bar.setBackground(gd);
        bar.setElevation(dp(2));
        row.addView(bar, new LinearLayout.LayoutParams((int) dp(7), (int) dp(26)));
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(0xFF14191C);
        tv.setTextSize(23);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setShadowLayer(dp(3), 0, dp(1), 0x33000000);
        tv.setPadding((int) dp(10), 0, 0, 0);
        row.addView(tv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) dp(14);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView fieldLabel(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(0xFF6B7280);
        tv.setTextSize(13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) dp(4);
        lp.bottomMargin = (int) dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView infoLine(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(0xFF2B3036);
        tv.setTextSize(14);
        tv.setLineSpacing(dp(2), 1.15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View chipsRow(String[] items) {
        // 流式布局: 标签自动换行, 不再散乱
        com.zhiyu.dna.ui.FlowLayout row = new com.zhiyu.dna.ui.FlowLayout(this);
        row.setGaps(8, 8);
        row.setPadding(0, 0, 0, 0);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        for (String it : items) {
            TextView chip = new TextView(this);
            chip.setText(it);
            chip.setTextColor(0xFF007FFF);
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            chip.setPadding((int) dp(14), (int) dp(7), (int) dp(14), (int) dp(7));
            chip.setBackground(roundedRect(0xFFEAF3FE, 0xFF7FBCFF, dp(18)));
            row.addView(chip, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        return row;
    }

    private android.graphics.drawable.Drawable roundedRect(int fill, int stroke, float radius) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(radius);
        gd.setStroke((int) dp(1.2f), stroke);
        return gd;
    }

    private EditText glassEdit() {
        EditText et = new EditText(this);
        et.setTextColor(0xFF14191C);
        et.setHintTextColor(0xFFA0A6AD);
        et.setTextSize(14);
        et.setSingleLine(true);
        et.setPadding((int) dp(12), (int) dp(10), (int) dp(12), (int) dp(10));
        et.setBackground(roundedRect(0x99FFFFFF, 0x77FFFFFF, dp(14)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) dp(8);
        et.setLayoutParams(lp);
        return et;
    }

    // ================= 存储权限 =================

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM_LEGACY && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED && permBanner != null) {
            permBanner.setVisibility(View.GONE);
        }
    }

    private void checkStoragePermission() {
        // 首次进入自动申请文件权限
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+: 先申请存储权限(targetSdk 27 走 legacy 通路), 再探测可写性
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM_LEGACY);
            }
            if (!canWriteSdcard() && !Environment.isExternalStorageManager()) {
                autoRequestAllFiles();   // 仍不可写才跳「所有文件访问」授权页
                showPermissionBanner();
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM_LEGACY);
                showPermissionBanner();
            }
        }
    }

    /** 实际探测 /sdcard 是否可写(不依赖 isExternalStorageManager, 兼容 Android 11 legacy 访问) */
    private boolean canWriteSdcard() {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "DNA");
            if (!dir.exists() && !dir.mkdirs()) return false;
            File probe = new File(dir, ".probe_rw");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(probe);
            fos.write(1);
            fos.close();
            boolean ok = probe.exists();
            probe.delete();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    /** 自动跳转系统「所有文件访问」授权页(首次进入时调用一次) */
    private void autoRequestAllFiles() {
        if (allFilesRequested) return;
        allFilesRequested = true;
        new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_ALL_FILES);
            } catch (Exception e) {
                try {
                    startActivityForResult(
                            new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_ALL_FILES);
                } catch (Exception e2) {
                    toast("请手动打开设置 → 应用 → DNA 解包助手 → 所有文件访问");
                }
            }
        }, 600);   // 稍作延迟, 等界面显示完再跳转
    }

    /** 应用内玻璃提示条: 点击去系统授权 */
    private void showPermissionBanner() {
        if (permBanner != null) {
            permBanner.setVisibility(View.VISIBLE);
            return;
        }
        GlassCard card = new GlassCard(this);
        card.attachScene(scene);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) dp(14), (int) dp(10), (int) dp(14), (int) dp(10));
        TextView tv = new TextView(this);
        tv.setText("需要「所有文件访问」权限才能读取/写入 /sdcard");
        tv.setTextColor(0xFF14191C);
        tv.setTextSize(13);
        row.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView btn = new TextView(this);
        btn.setText("去授权");
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(13);
        btn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding((int) dp(14), (int) dp(8), (int) dp(14), (int) dp(8));
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setCornerRadius(dp(16));
        gd.setColor(0xFF007FFF);
        btn.setBackground(gd);
        btn.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_ALL_FILES);
            } catch (Exception e) {
                try {
                    startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_ALL_FILES);
                } catch (Exception e2) {
                    toast("请手动打开设置 → 应用 → DNA 解包助手 → 所有文件访问");
                }
            }
        });
        row.addView(btn);
        card.addView(row);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        lp.topMargin = (int) (dp(8) + statusBarHeight());
        lp.leftMargin = (int) dp(12);
        lp.rightMargin = (int) dp(12);
        scene.addView(card, lp);
        permBanner = card;
    }

    // ================= 文件选择 =================

    /** 浏览: 弹选择方式(系统文件选择器 / 内置浏览器) */
    private void pickInputFile() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("选择文件")
                .setItems(new String[]{"系统文件选择器", "内置文件浏览器(推荐, 路径更准)"}, (d, w) -> {
                    if (w == 0) pickInputFileSystem();
                    else pickInputFileBuiltIn();
                })
                .show();
    }

    /** 系统文件选择器(SAF) */
    private void pickInputFileSystem() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        try {
            startActivityForResult(i, REQ_INPUT_FILE);
        } catch (Exception e) {
            toast("无法打开系统文件选择器");
        }
    }

    /** 内置文件浏览器 */
    private void pickInputFileBuiltIn() {
        showFileBrowser(new File(Environment.getExternalStorageDirectory(), "Download"),
                file -> {
                    if (file != null) {
                        inputPathEt.setText(file.getAbsolutePath());
                        updateTypeLabel(file.getAbsolutePath());
                    }
                });
    }

    /** 内置文件浏览器: 列出目录, 点文件即选中(返回真实路径)。 */
    private void showFileBrowser(File startDir, java.util.function.Consumer<File> onPick) {
        File dir = (startDir != null && startDir.isDirectory()) ? startDir
                : Environment.getExternalStorageDirectory();
        final File[] cur = {dir};
        final android.widget.LinearLayout list = new android.widget.LinearLayout(this);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        final android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(list);
        final android.widget.TextView title = new android.widget.TextView(this);
        title.setPadding((int) dp(16), (int) dp(12), (int) dp(16), (int) dp(8));
        title.setTextSize(14);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF14191C);
        final android.widget.LinearLayout wrap = new android.widget.LinearLayout(this);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        wrap.addView(title);
        wrap.addView(sv, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int) dp(360)));
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setView(wrap)
                .setNegativeButton("取消", null)
                .create();
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            list.removeAllViews();
            File d = cur[0];
            title.setText(d.getAbsolutePath());
            // 上一级
            File parent = d.getParentFile();
            if (parent != null) {
                android.widget.TextView up = browserRow("⬆  ..  上一级", 0xFF007FFF);
                up.setOnClickListener(v -> { cur[0] = parent; refresh[0].run(); });
                list.addView(up);
            }
            File[] items = d.listFiles();
            if (items == null) return;
            java.util.Arrays.sort(items, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            int dirs = 0;
            for (File f : items) {
                if (f.isDirectory()) { dirs++; if (dirs > 300) break; }
            }
            for (File f : items) {
                if (f.isDirectory()) {
                    android.widget.TextView tv = browserRow("📁  " + f.getName(), 0xFF14191C);
                    final File fd = f;
                    tv.setOnClickListener(v -> { cur[0] = fd; refresh[0].run(); });
                    list.addView(tv);
                }
            }
            for (File f : items) {
                if (f.isFile()) {
                    String n = f.getName();
                    boolean interesting = n.endsWith(".img") || n.endsWith(".bin")
                            || n.endsWith(".dat") || n.endsWith(".br") || n.endsWith(".zip")
                            || n.endsWith(".gz") || n.endsWith(".lz4") || n.endsWith(".raw");
                    android.widget.TextView tv = browserRow(
                            (interesting ? "📦  " : "📄  ") + n + "   " + (f.length() / 1048576) + "MB",
                            interesting ? 0xFF007FFF : 0xFF6B7280);
                    final File ff = f;
                    tv.setOnClickListener(v -> {
                        dlg.dismiss();
                        onPick.accept(ff);
                    });
                    list.addView(tv);
                }
            }
        };
        refresh[0].run();
        dlg.show();
    }

    private android.widget.TextView browserRow(String text, int color) {
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(text);
        tv.setTextSize(13.5f);
        tv.setTextColor(color);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        tv.setPadding((int) dp(16), (int) dp(11), (int) dp(16), (int) dp(11));
        tv.setBackgroundColor(0x08FFFFFF);
        return tv;
    }

    private void pickOutputDir() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("选择输出目录")
                .setItems(new String[]{"系统目录选择器", "内置目录浏览器(推荐)"}, (d, w) -> {
                    if (w == 0) {
                        try {
                            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                                    REQ_OUTPUT_DIR);
                        } catch (Exception e) { toast("无法打开目录选择器"); }
                    } else {
                        showDirBrowser(Environment.getExternalStorageDirectory(), dir -> {
                            if (dir != null) outPathEt.setText(dir.getAbsolutePath());
                        });
                    }
                })
                .show();
    }

    /** 内置目录浏览器: 只选目录。 */
    private void showDirBrowser(File startDir, java.util.function.Consumer<File> onPick) {
        File dir = (startDir != null && startDir.isDirectory()) ? startDir
                : Environment.getExternalStorageDirectory();
        final File[] cur = {dir};
        final android.widget.LinearLayout list = new android.widget.LinearLayout(this);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        final android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(list);
        final android.widget.TextView title = new android.widget.TextView(this);
        title.setPadding((int) dp(16), (int) dp(12), (int) dp(16), (int) dp(8));
        title.setTextSize(14);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF14191C);
        final android.widget.LinearLayout wrap = new android.widget.LinearLayout(this);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        wrap.addView(title);
        wrap.addView(sv, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int) dp(360)));
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setView(wrap)
                .setPositiveButton("选择此目录", (d, w) -> onPick.accept(cur[0]))
                .setNegativeButton("取消", null)
                .create();
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            list.removeAllViews();
            File d = cur[0];
            title.setText(d.getAbsolutePath());
            File parent = d.getParentFile();
            if (parent != null) {
                android.widget.TextView up = browserRow("⬆  ..  上一级", 0xFF007FFF);
                up.setOnClickListener(v -> { cur[0] = parent; refresh[0].run(); });
                list.addView(up);
            }
            File[] items = d.listFiles();
            if (items == null) return;
            java.util.Arrays.sort(items, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : items) {
                if (!f.isDirectory()) continue;
                android.widget.TextView tv = browserRow("📁  " + f.getName(), 0xFF14191C);
                final File fd = f;
                tv.setOnClickListener(v -> { cur[0] = fd; refresh[0].run(); });
                list.addView(tv);
            }
        };
        refresh[0].run();
        dlg.show();
    }

    private void pickPackSrc() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("选择源目录")
                .setItems(new String[]{"系统目录选择器", "内置目录浏览器(推荐)"}, (d, w) -> {
                    if (w == 0) {
                        try {
                            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                                    REQ_OUTPUT_DIR + 10);
                        } catch (Exception e) { toast("无法打开目录选择器"); }
                    } else {
                        showDirBrowser(Environment.getExternalStorageDirectory(), dir -> {
                            if (dir != null) packSrcEt.setText(dir.getAbsolutePath());
                        });
                    }
                })
                .show();
    }

    private void pickPackOut() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("选择输出文件")
                .setItems(new String[]{"系统保存对话框", "内置(选目录+输文件名)"}, (d, w) -> {
                    if (w == 0) pickPackOutLegacy();
                    else pickPackOutBuiltIn();
                })
                .show();
    }

    private void pickPackOutBuiltIn() {
        showDirBrowser(Environment.getExternalStorageDirectory(), dir -> {
            if (dir == null) return;
            final EditText et = glassEdit();
            et.setHint("文件名, 如 vendor_new.img");
            String cur = packOutEt.getText().toString().trim();
            if (cur.isEmpty()) et.setText("output.img");
            else et.setText(new File(cur).getName());
            new android.app.AlertDialog.Builder(this)
                    .setTitle("保存到: " + dir.getAbsolutePath())
                    .setView(et)
                    .setPositiveButton("确定", (d, w) -> {
                        String name = et.getText().toString().trim();
                        if (name.isEmpty()) name = "output.img";
                        packOutEt.setText(new File(dir, name).getAbsolutePath());
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }

    private void pickPackOutLegacy() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, "output.img");
        try {
            startActivityForResult(i, REQ_OUTPUT_DIR + 20);
        } catch (Exception e) {
            toast("无法打开保存选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 系统授权页返回时 resultCode 常为 CANCELED 且 data 为空, 需先处理授权结果
        if (requestCode == REQ_ALL_FILES) {
            boolean granted = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager();
            if (granted && permBanner != null) {
                permBanner.setVisibility(View.GONE);
                toast("已获得所有文件访问权限 ✓");
            }
            return;
        }
        if (requestCode == REQ_PERM_LEGACY) {
            if (Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED
                    && permBanner != null) {
                permBanner.setVisibility(View.GONE);
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null) return;
        try {
            if (requestCode == REQ_INPUT_FILE) {
                Uri uri = data.getData();
                if (uri == null && data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                    uri = data.getClipData().getItemAt(0).getUri();
                }
                if (uri != null) {
                    String path = resolvePath(uri);
                    String name = queryDisplayName(uri);
                    // 拿不到真实路径时, 输入框显示文件名(而不是空白/难看的 URI)
                    if (path != null && path.startsWith("content://")) {
                        inputPathEt.setText(path);
                        if (name != null) inputPathEt.setHint(name);
                    } else {
                        inputPathEt.setText(path != null ? path : "");
                    }
                    updateTypeLabel(path != null ? path : "");
                }
            } else if (requestCode == REQ_OUTPUT_DIR && data.getData() != null) {
                String path = resolveTreePath(data.getData());
                if (path != null) outPathEt.setText(path);
            } else if (requestCode == REQ_OUTPUT_DIR + 10 && data.getData() != null) {
                String path = resolveTreePath(data.getData());
                if (path != null) packSrcEt.setText(path);
            } else if (requestCode == REQ_OUTPUT_DIR + 20 && data.getData() != null) {
                String path = resolvePath(data.getData());
                if (path != null) packOutEt.setText(path);
            }
        } catch (Exception e) {
            toast("解析路径失败: " + e.getMessage());
        }
    }

    /** 查询 SAF 文件的显示名 */
    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 把 content:// 解析为真实路径(多级兜底)。 */
    private String resolvePath(Uri uri) {
        String path = null;
        // 1) 直接查 _data
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{"_data"}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex("_data");
                if (idx >= 0) path = c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        // 2) Downloads / external storage provider 映射
        if (path == null || !new File(path).exists()) {
            path = resolveByAuthority(uri);
        }
        if (path != null && new File(path).exists()) return path;
        // 3) 显示名兜底: /sdcard/Download/<文件名>
        String name = null;
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        if (name != null) {
            File guess = new File(Environment.getExternalStorageDirectory(), "Download/" + name);
            if (guess.exists()) return guess.getAbsolutePath();
        }
        return uri.toString();
    }

    /** 按 provider 类型解析常见路径 */
    private String resolveByAuthority(Uri uri) {
        try {
            String auth = uri.getAuthority();
            if (auth != null && auth.contains("downloads")) {
                String id = android.provider.DocumentsContract.getDocumentId(uri);
                if (id != null) {
                    Uri contentUri = android.content.ContentUris.withAppendedId(
                            Uri.parse("content://downloads/public_downloads"), Long.parseLong(id));
                    try (android.database.Cursor c = getContentResolver().query(contentUri,
                            new String[]{"_data"}, null, null, null)) {
                        if (c != null && c.moveToFirst()) {
                            int idx = c.getColumnIndex("_data");
                            if (idx >= 0) {
                                String p = c.getString(idx);
                                if (p != null && new File(p).exists()) return p;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            if (auth != null && auth.contains("external")) {
                String id = android.provider.DocumentsContract.getDocumentId(uri);
                if (id != null && id.startsWith("primary:")) {
                    String p = Environment.getExternalStorageDirectory() + "/" + id.substring(8);
                    if (new File(p).exists()) return p;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String resolveTreePath(Uri treeUri) {
        try (android.database.Cursor c = getContentResolver().query(treeUri,
                new String[]{"_data"}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex("_data");
                if (idx >= 0) {
                    String p = c.getString(idx);
                    if (p != null && new File(p).isDirectory()) return p;
                }
            }
        } catch (Exception ignored) {
        }
        String docId = treeUri.getLastPathSegment();
        if (docId != null && docId.startsWith("primary:")) {
            return Environment.getExternalStorageDirectory() + "/" + docId.substring(8);
        }
        return null;
    }

    private void updateTypeLabel(String path) {
        if (path.startsWith("content://")) {
            // SAF 文件: 直接读文件头识别类型, 不显示"文件不存在"
            try (java.io.InputStream is = getContentResolver().openInputStream(Uri.parse(path))) {
                byte[] head = new byte[4096];
                int n = is.read(head);
                com.zhiyu.dna.engine.ImgType.Type t = detectFromBytes(head, n);
                typeLabel.setText("已选择: " + com.zhiyu.dna.engine.ImgType.label(t)
                        + " (SAF 文件, 解包时自动复制)");
            } catch (Exception e) {
                typeLabel.setText("已选择 SAF 文件, 解包时自动复制处理");
            }
            typeLabel.setTextColor(0xFF007FFF);
            return;
        }
        File f = new File(path);
        if (!f.exists()) {
            typeLabel.setText("路径无效: 请选择文件或直接输入 /sdcard/ 开头的路径");
            typeLabel.setTextColor(0xFFE65100);
            return;
        }
        com.zhiyu.dna.engine.ImgType.Type t = com.zhiyu.dna.engine.ImgType.detect(f);
        typeLabel.setText("识别类型: " + com.zhiyu.dna.engine.ImgType.label(t)
                + "  ·  " + (f.length() / 1048576) + " MB");
        typeLabel.setTextColor(0xFF007FFF);
    }

    /** 从字节流头识别镜像类型(SAF 文件用) */
    private com.zhiyu.dna.engine.ImgType.Type detectFromBytes(byte[] b, int n) {
        if (n >= 4 && (b[0] & 0xFF) == 0x50 && (b[1] & 0xFF) == 0x4B) return com.zhiyu.dna.engine.ImgType.Type.ZIP;
        if (n >= 4 && (b[0] & 0xFF) == 0x3A && (b[1] & 0xFF) == 0xFF) return com.zhiyu.dna.engine.ImgType.Type.SPARSE;
        if (n >= 4 && (b[0] & 0xFF) == 0x43 && (b[1] & 0xFF) == 0x72) return com.zhiyu.dna.engine.ImgType.Type.PAYLOAD;
        if (n >= 8 && (b[0] & 0xFF) == 0x41 && (b[1] & 0xFF) == 0x4E) return com.zhiyu.dna.engine.ImgType.Type.BOOT;
        if (n >= 4 && (b[0] & 0xFF) == 0x67 && (b[1] & 0xFF) == 0x44) return com.zhiyu.dna.engine.ImgType.Type.SUPER;
        if (n >= 1082 && (b[1080] & 0xFF) == 0x53 && (b[1081] & 0xFF) == (byte) 0xEF) return com.zhiyu.dna.engine.ImgType.Type.EXT;
        if (n >= 2 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B) return com.zhiyu.dna.engine.ImgType.Type.GZIP;
        return com.zhiyu.dna.engine.ImgType.Type.UNKNOWN;
    }

    // ================= 引擎 =================

    private void ensureEngine(LogView log) {
        engineStatus = engineStatus != null ? engineStatus : new TextView(this);
        worker.execute(() -> {
            try {
                ToolPaths p = Binaries.ensure(this, s -> post(() -> {
                    if (log != null) log.append(s);
                    if (engineStatus != null) engineStatus.setText("引擎: " + s);
                }));
                tools = p;
                engineReady = true;
                post(() -> {
                    if (engineStatus != null) engineStatus.setText("引擎: 就绪 ✓ (" + tools.debugfs.getName() + ")");
                });
            } catch (Exception e) {
                engineReady = false;
                post(() -> {
                    if (engineStatus != null) engineStatus.setText("引擎: 初始化失败 - " + e.getMessage());
                });
            }
        });
    }

    private void startUnpack() {
        String input = inputPathEt.getText().toString().trim();
        String out = outPathEt.getText().toString().trim();
        if (input.isEmpty()) { toast("请先选择镜像文件"); return; }
        if (out.isEmpty()) { toast("请设置输出目录"); return; }
        unpackLog.setVisibility(View.VISIBLE);
        unpackLog.clear();
        final ToolPaths t = tools;
        final String inPath = input;
        worker.execute(() -> {
            try {
                if (t == null || !engineReady) {
                    ToolPaths p = Binaries.ensure(this, null);
                    tools = p;
                }
                final ToolPaths tt = tools;
                File inFile = new File(inPath);
                // content:// 拿不到真实路径时: 复制到缓存再解包
                if (inPath.startsWith("content://") || !inFile.exists()) {
                    post(() -> unpackLog.append("SAF 文件无法直接定位, 复制到缓存处理..."));
                    Uri uri = Uri.parse(inPath);
                    String dn = queryDisplayName(uri);
                    if (dn == null || dn.isEmpty()) dn = "input_" + System.currentTimeMillis();
                    File cache = new File(getCacheDir(), dn);
                    try (java.io.InputStream is = getContentResolver().openInputStream(uri);
                         java.io.FileOutputStream fos = new java.io.FileOutputStream(cache)) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                    inFile = cache;
                }
                post(() -> unpackLog.append("正在解包, 请稍候..."));
                File outDir = new File(out);
                // 输出目录无法创建时, 自动回退到默认目录 /sdcard/DNA/out
                if (!outDir.exists() && !outDir.mkdirs()) {
                    File fallback = Binaries.defaultOutDir(MainActivity.this);
                    if (fallback.mkdirs() || fallback.exists()) {
                        post(() -> unpackLog.append("⚠ 输出目录不可写, 已改用默认目录: " + fallback));
                        outDir = fallback;
                    }
                }
                DnaEngine.unpack(inFile, outDir, autoPartsSw.isChecked(), tt,
                        uiProgress(unpackLog, "解包"));
                final String finalOut = outDir.getAbsolutePath();
                post(() -> {
                    unpackLog.append("【解包完成】输出目录: " + finalOut);
                    toast("解包完成 ✓");
                });
            } catch (Exception e) {
                post(() -> unpackLog.append("[失败] " + e.getMessage()));
                try {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    File crash = new File(Binaries.defaultOutDir(MainActivity.this).getParentFile(), "unpack_error.log");
                    crash.getParentFile().mkdirs();
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(crash);
                    fos.write(sw.toString().getBytes());
                    fos.close();
                } catch (Exception ignore) {}
            }
        });
    }

    private void startPack() {
        String src = packSrcEt.getText().toString().trim();
        String out = packOutEt.getText().toString().trim();
        String label = packLabelEt.getText().toString().trim();
        if (src.isEmpty()) { toast("请选择源目录"); return; }
        if (out.isEmpty()) { toast("请设置输出文件"); return; }
        packLog.setVisibility(View.VISIBLE);
        packLog.clear();
        worker.execute(() -> {
            try {
                if (tools == null) tools = Binaries.ensure(this, null);
                int fmt = formatSeg.getSelectedIndex();
                Progress p = uiProgress(packLog, "打包");
                if (fmt == 0) {
                    DnaEngine.packExt4(new File(src), new File(out), label, tools, p);
                } else if (fmt == 1) {
                    DnaEngine.packSparse(new File(src), new File(out), p);
                } else {
                    DnaEngine.packBoot(new File(src), new File(out), tools, p);
                }
            } catch (Exception e) {
                post(() -> packLog.append("[失败] " + e.getMessage()));
            }
        });
    }

    private Progress uiProgress(LogView log, String tag) {
        return new Progress() {
            @Override
            public void log(String line) {
                post(() -> log.append(line));
            }

            @Override
            public void progress(int percent) {
                post(() -> log.setProgress(percent));
            }

            @Override
            public void done(boolean ok, String message) {
                post(() -> {
                    log.append(ok ? "✅ " + message : "❌ " + message);
                    toast(message);   // 同时弹提示, 明确告知完成
                });
            }
        };
    }

    private void post(Runnable r) {
        runOnUiThread(r);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show();
    }
}
