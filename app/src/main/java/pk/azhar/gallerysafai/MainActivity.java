package pk.azhar.gallerysafai;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gallery Safai v2 - offline gallery cleaner.
 * INTERNET permission nahi hai. Delete = Android Trash (30 din tak wapas la sakte hain).
 */
public class MainActivity extends Activity {

    static class Item {
        Uri uri; String name; String bucket; String path;
        long size; long dateMs; boolean video;
    }

    static class Category {
        String title; String hint; int color; List<Item> items = new ArrayList<>();
        Category(String t, String h, int c) { title = t; hint = h; color = c; }
        long bytes() { long b = 0; for (Item i : items) b += i.size; return b; }
    }

    private static final int REQ_PERM = 1, REQ_TRASH = 2;
    private static final long MB = 1024L * 1024;

    private final List<Item> all = new ArrayList<>();
    private final List<Category> junk = new ArrayList<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private LruCache<String, Bitmap> cache;
    private int screenW;
    private float dp;

    // kis screen pe hain: 0 dashboard, 1 grid, 2 swipe
    private int screen = 0;

    // grid state
    private List<Item> shown = new ArrayList<>();
    private final Set<Uri> selected = new HashSet<>();
    private GridAdapter adapter;
    private TextView gridInfo;
    private Button gridTrash;

    // swipe state
    private List<Item> swipeList = new ArrayList<>();
    private int swipeIdx = 0;
    private final List<Item> swipeDelete = new ArrayList<>();
    private final List<Boolean> swipeHistory = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 6);
        cache = new LruCache<String, Bitmap>(maxKb) {
            @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount() / 1024; }
        };
        screenW = getResources().getDisplayMetrics().widthPixels;
        dp = getResources().getDisplayMetrics().density;
        if (hasPermission()) load(); else { showMessage("Permission ka intezar..."); askPermission(); }
    }

    // ================= helpers =================
    private int px(int v) { return (int) (v * dp); }

    private static String size(long b) {
        if (b >= 1024L * MB) return String.format(Locale.US, "%.2f GB", b / (1024.0 * MB));
        return String.format(Locale.US, "%.1f MB", b / (double) MB);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, int bg) {
        Button bt = new Button(this);
        bt.setText(s); bt.setAllCaps(false); bt.setTextColor(Color.WHITE);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg); d.setCornerRadius(px(10));
        bt.setBackground(d);
        return bt;
    }

    private GradientDrawable card(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(px(16));
        return d;
    }

    private void showMessage(String m) {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER);
        l.setBackgroundColor(0xFFF4F6FA);
        l.addView(text(m, 18, 0xFF333333, false));
        setContentView(l);
    }

    // ================= permission =================
    private String[] perms() {
        if (Build.VERSION.SDK_INT >= 33)
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO};
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private boolean hasPermission() {
        for (String p : perms())
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private void askPermission() { requestPermissions(perms(), REQ_PERM); }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code != REQ_PERM) return;
        if (hasPermission()) load();
        else showMessage("Photos/videos ki permission chahiye.\nSettings > Apps > Gallery Safai > Permissions");
    }

    // ================= load + analyse =================
    private void load() {
        showMessage("Gallery scan ho rahi hai...");
        pool.execute(() -> {
            List<Item> list = new ArrayList<>();
            query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), false, list);
            query(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, list);
            list.sort((a, c) -> Long.compare(c.dateMs, a.dateMs));
            List<Category> cats = analyse(list);
            main.post(() -> {
                all.clear(); all.addAll(list);
                junk.clear(); junk.addAll(cats);
                showDashboard();
            });
        });
    }

    private void query(Uri base, boolean video, List<Item> out) {
        String[] cols = {
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH
        };
        try (Cursor c = getContentResolver().query(base, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                Item it = new Item();
                it.uri = ContentUris.withAppendedId(base, c.getLong(0));
                it.name = nz(c.getString(1));
                it.size = c.getLong(2);
                long taken = c.isNull(4) ? 0 : c.getLong(4);
                it.dateMs = taken > 0 ? taken : c.getLong(3) * 1000L;
                it.bucket = nz(c.getString(5));
                it.path = nz(c.getString(6));
                it.video = video;
                out.add(it);
            }
        } catch (Exception ignored) { }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private List<Category> analyse(List<Item> list) {
        Category dup = new Category("Duplicates", "Bilkul same copies. Har ek ki 1 copy safe rakhi gayi hai.", 0xFFE53935);
        Category waImg = new Category("WhatsApp forwards / images", "Doosron ki bheji hui photos, memes, good morning waghera.", 0xFF2E7D32);
        Category waVid = new Category("WhatsApp videos", "Forward videos jo bohat jagah leti hain.", 0xFF1B5E20);
        Category shots = new Category("Screenshots / screen recordings", "Aksar kaam ke baad faltu ho jate hain.", 0xFF6A1B9A);
        Category bigVid = new Category("Bari videos (100MB+)", "Sab se zyada jagah inhi ki hai. Zaroori ho to computer pe backup karein.", 0xFFEF6C00);
        Category tiny = new Category("Chhoti / faltu tasveerain", "50KB se chhoti files: stickers, icons, thumbnails.", 0xFF546E7A);
        Category oldVid = new Category("1 saal se purani videos", "Purani videos dekh kar faisla karein.", 0xFF00838F);

        long yearAgo = System.currentTimeMillis() - 365L * 24 * 3600 * 1000;
        Map<String, List<Item>> bySize = new HashMap<>();
        for (Item it : list) {
            String p = (it.path + " " + it.bucket).toLowerCase(Locale.US);
            boolean wa = p.contains("whatsapp");
            boolean sent = p.contains("/sent");
            if (wa && !sent) { if (it.video) waVid.items.add(it); else waImg.items.add(it); }
            if (p.contains("screenshot") || p.contains("screen record") || p.contains("screenrecord")) shots.items.add(it);
            if (it.video && it.size >= 100 * MB) bigVid.items.add(it);
            if (!it.video && it.size > 0 && it.size < 50 * 1024) tiny.items.add(it);
            if (it.video && it.dateMs < yearAgo && it.size >= 20 * MB) oldVid.items.add(it);
            if (it.size > 0) {
                String k = (it.video ? "v" : "i") + it.size;
                List<Item> l = bySize.get(k);
                if (l == null) { l = new ArrayList<>(); bySize.put(k, l); }
                l.add(it);
            }
        }
        for (List<Item> l : bySize.values()) {
            if (l.size() < 2) continue;
            l.sort((a, c) -> Long.compare(a.dateMs, c.dateMs));
            dup.items.addAll(l.subList(1, l.size()));
        }
        bigVid.items.sort((a, c) -> Long.compare(c.size, a.size));
        oldVid.items.sort((a, c) -> Long.compare(c.size, a.size));

        List<Category> out = new ArrayList<>();
        Category[] arr = {dup, waImg, waVid, bigVid, shots, oldVid, tiny};
        for (Category c : arr) if (!c.items.isEmpty()) out.add(c);
        out.sort((a, c) -> Long.compare(c.bytes(), a.bytes()));
        return out;
    }

    // ================= DASHBOARD =================
    private void showDashboard() {
        screen = 0;
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFFF4F6FA);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(16), px(16), px(16), px(24));
        sv.addView(root);

        // Hero card
        long junkBytes = 0;
        Set<Uri> seen = new HashSet<>();
        for (Category c : junk) for (Item i : c.items) if (seen.add(i.uri)) junkBytes += i.size;
        long totalMedia = 0;
        for (Item i : all) totalMedia += i.size;

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(px(20), px(20), px(20), px(20));
        hero.setBackground(card(0xFF1565C0));
        hero.addView(text("Aap free kar sakte hain", 15, 0xFFBBDEFB, false));
        hero.addView(text(size(junkBytes), 40, Color.WHITE, true));
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long free = fs.getAvailableBytes(), tot = fs.getTotalBytes();
        hero.addView(text("Phone storage: " + size(tot - free) + " bhari / " + size(tot)
                + "\nKhali: " + size(free) + "\nPhotos + videos: " + all.size() + " files (" + size(totalMedia) + ")",
                14, Color.WHITE, false));
        root.addView(hero, lp(-1, -2, 0, 0, 0, px(16)));

        // Swipe all
        Button swipeAll = button("Sari gallery Swipe se saaf karein  →", 0xFF00897B);
        swipeAll.setOnClickListener(v -> startSwipe(all));
        root.addView(swipeAll, lp(-1, px(52), 0, 0, 0, px(16)));

        root.addView(text("Kachra (sab se zyada jagah wala upar)", 17, 0xFF222222, true), lp(-1, -2, 0, 0, 0, px(8)));

        if (junk.isEmpty()) root.addView(text("Mubarak ho! Koi kachra nahi mila.", 15, 0xFF555555, false));

        for (Category c : junk) {
            LinearLayout cd = new LinearLayout(this);
            cd.setOrientation(LinearLayout.VERTICAL);
            cd.setPadding(px(16), px(14), px(16), px(14));
            cd.setBackground(card(Color.WHITE));

            LinearLayout head = new LinearLayout(this);
            TextView t = text(c.title, 16, c.color, true);
            head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            head.addView(text(size(c.bytes()), 18, 0xFF222222, true));
            cd.addView(head);
            cd.addView(text(c.items.size() + " files • " + c.hint, 13, 0xFF666666, false));

            LinearLayout btns = new LinearLayout(this);
            Button see = button("Dekhein", 0xFF78909C);
            see.setOnClickListener(v -> showGrid(c.title, c.items));
            Button sw = button("Swipe", 0xFF00897B);
            sw.setOnClickListener(v -> startSwipe(c.items));
            Button clean = button("Sab saaf", c.color);
            clean.setOnClickListener(v -> trash(c.items));
            btns.addView(see, lp(0, px(44), 1f, 0, px(6)));
            btns.addView(sw, lp(0, px(44), 1f, px(3), px(3)));
            btns.addView(clean, lp(0, px(44), 1f, px(6), 0));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
            bl.topMargin = px(10);
            cd.addView(btns, bl);

            root.addView(cd, lp(-1, -2, 0, 0, 0, px(12)));
        }

        // Browse by month / folder
        root.addView(text("Gallery dekhein", 17, 0xFF222222, true), lp(-1, -2, 0, px(8), 0, px(8)));
        LinkedHashMap<String, List<Item>> months = new LinkedHashMap<>();
        SimpleDateFormat fmt = new SimpleDateFormat("MMMM yyyy", Locale.US);
        for (Item i : all) {
            String m = fmt.format(new Date(i.dateMs));
            List<Item> l = months.get(m);
            if (l == null) { l = new ArrayList<>(); months.put(m, l); }
            l.add(i);
        }
        for (Map.Entry<String, List<Item>> e : months.entrySet()) {
            long b = 0; for (Item i : e.getValue()) b += i.size;
            Button mb = new Button(this);
            mb.setAllCaps(false);
            mb.setText(e.getKey() + "   •   " + e.getValue().size() + " files   •   " + size(b));
            final String title = e.getKey();
            final List<Item> items = e.getValue();
            mb.setOnClickListener(v -> showGrid(title, items));
            root.addView(mb, lp(-1, -2, 0, 0, 0, px(4)));
        }

        root.addView(text("\n🔒 Ye app internet use nahi karti. Delete hui files 30 din tak Trash mein rehti hain.",
                12, 0xFF888888, false));
        setContentView(sv);
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight, int l, int r) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.leftMargin = l; p.rightMargin = r;
        return p;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    // ================= GRID =================
    private void showGrid(String title, List<Item> items) {
        screen = 1;
        shown = new ArrayList<>(items);
        selected.clear();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout top = new LinearLayout(this);
        top.setPadding(px(8), px(8), px(8), px(4));
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("←", 0xFF78909C);
        back.setOnClickListener(v -> showDashboard());
        top.addView(back, new LinearLayout.LayoutParams(px(48), px(44)));
        TextView tt = text("  " + title, 17, 0xFF222222, true);
        top.addView(tt, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(top);

        gridInfo = text("", 13, 0xFF555555, false);
        gridInfo.setPadding(px(12), 0, px(12), px(4));
        root.addView(gridInfo);

        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(px(8), 0, px(8), px(6));
        Button all2 = button("Sab select", 0xFF546E7A);
        all2.setOnClickListener(v -> {
            if (selected.size() == shown.size()) selected.clear();
            else for (Item i : shown) selected.add(i.uri);
            refreshGrid();
        });
        Button sw = button("Swipe", 0xFF00897B);
        sw.setOnClickListener(v -> startSwipe(shown));
        gridTrash = button("Delete (0)", 0xFFE53935);
        gridTrash.setOnClickListener(v -> {
            List<Item> sel = new ArrayList<>();
            for (Item i : shown) if (selected.contains(i.uri)) sel.add(i);
            trash(sel);
        });
        bar.addView(all2, lp(0, px(44), 1f, 0, px(4)));
        bar.addView(sw, lp(0, px(44), 1f, px(2), px(2)));
        bar.addView(gridTrash, lp(0, px(44), 1f, px(4), 0));
        root.addView(bar);

        GridView grid = new GridView(this);
        grid.setNumColumns(3);
        adapter = new GridAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            Uri u = shown.get(pos).uri;
            if (!selected.remove(u)) selected.add(u);
            refreshGrid();
        });
        grid.setOnItemLongClickListener((p, v, pos, id) -> { openItem(shown.get(pos)); return true; });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        refreshGrid();
    }

    private void refreshGrid() {
        long sb = 0, tb = 0;
        for (Item i : shown) { tb += i.size; if (selected.contains(i.uri)) sb += i.size; }
        gridInfo.setText(shown.size() + " files (" + size(tb) + ")  •  Selected: " + selected.size()
                + " (" + size(sb) + ")\nTap = select, lamba dabayen = kholein");
        gridTrash.setText("Delete (" + selected.size() + ")");
        adapter.notifyDataSetChanged();
    }

    private class GridAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            FrameLayout cell;
            ImageView img;
            TextView tag;
            int cellPx = screenW / 3;
            if (convert == null) {
                cell = new FrameLayout(MainActivity.this);
                cell.setLayoutParams(new GridView.LayoutParams(cellPx, cellPx));
                img = new ImageView(MainActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cell.addView(img, new FrameLayout.LayoutParams(-1, -1));
                tag = new TextView(MainActivity.this);
                tag.setTextColor(Color.WHITE);
                tag.setBackgroundColor(0x99000000);
                tag.setPadding(8, 2, 8, 2);
                cell.addView(tag, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
            } else {
                cell = (FrameLayout) convert;
                img = (ImageView) cell.getChildAt(0);
                tag = (TextView) cell.getChildAt(1);
            }
            Item it = shown.get(pos);
            boolean sel = selected.contains(it.uri);
            int pad = sel ? 10 : 2;
            cell.setPadding(pad, pad, pad, pad);
            cell.setBackgroundColor(sel ? 0xFFE53935 : Color.WHITE);
            img.setAlpha(sel ? 0.55f : 1f);
            tag.setText((sel ? "✓ " : "") + (it.video ? "▶ " : "") + size(it.size));
            loadInto(img, it, 256);
            return cell;
        }
    }

    private void loadInto(ImageView img, Item it, int sizePx) {
        String key = it.uri + "#" + sizePx;
        img.setTag(key);
        Bitmap bm = cache.get(key);
        if (bm != null) { img.setImageBitmap(bm); return; }
        img.setImageDrawable(null);
        final Uri u = it.uri;
        pool.execute(() -> {
            try {
                Bitmap t = getContentResolver().loadThumbnail(u, new Size(sizePx, sizePx), null);
                cache.put(key, t);
                main.post(() -> { if (key.equals(img.getTag())) img.setImageBitmap(t); });
            } catch (Exception ignored) { }
        });
    }

    // ================= SWIPE =================
    private TextView swCounter, swInfo;
    private ImageView swImage;
    private FrameLayout swFrame;
    private float downX;

    private void startSwipe(List<Item> items) {
        if (items.isEmpty()) { Toast.makeText(this, "Is mein kuch nahi", Toast.LENGTH_SHORT).show(); return; }
        screen = 2;
        swipeList = new ArrayList<>(items);
        swipeIdx = 0;
        swipeDelete.clear();
        swipeHistory.clear();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111111);

        LinearLayout top = new LinearLayout(this);
        top.setPadding(px(8), px(8), px(8), px(8));
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button done = button("Khatam", 0xFF455A64);
        done.setOnClickListener(v -> finishSwipe());
        top.addView(done, new LinearLayout.LayoutParams(-2, px(44)));
        swCounter = text("", 15, Color.WHITE, true);
        swCounter.setGravity(Gravity.END);
        top.addView(swCounter, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(top);

        swFrame = new FrameLayout(this);
        swImage = new ImageView(this);
        swImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        swFrame.addView(swImage, new FrameLayout.LayoutParams(-1, -1));
        swInfo = text("", 14, Color.WHITE, false);
        swInfo.setBackgroundColor(0x99000000);
        swInfo.setPadding(px(10), px(6), px(10), px(6));
        swFrame.addView(swInfo, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        swFrame.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN: downX = e.getRawX(); return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX;
                    swImage.setTranslationX(dx);
                    swImage.setRotation(dx / 40f);
                    swFrame.setBackgroundColor(dx < -px(40) ? 0x55E53935 : dx > px(40) ? 0x5543A047 : 0);
                    return true;
                case MotionEvent.ACTION_UP:
                    float d = e.getRawX() - downX;
                    resetCard();
                    if (d < -px(100)) decide(true);
                    else if (d > px(100)) decide(false);
                    else if (Math.abs(d) < px(10)) openItem(swipeList.get(swipeIdx));
                    return true;
                case MotionEvent.ACTION_CANCEL: resetCard(); return true;
            }
            return false;
        });
        root.addView(swFrame, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(px(10), px(10), px(10), px(14));
        Button del = button("✕  Delete", 0xFFE53935);
        del.setOnClickListener(v -> decide(true));
        Button undo = button("↶", 0xFF546E7A);
        undo.setOnClickListener(v -> undo());
        Button keep = button("Rakhein  ✓", 0xFF43A047);
        keep.setOnClickListener(v -> decide(false));
        bar.addView(del, lp(0, px(56), 2f, 0, px(6)));
        bar.addView(undo, lp(0, px(56), 1f, px(3), px(3)));
        bar.addView(keep, lp(0, px(56), 2f, px(6), 0));
        root.addView(bar);

        setContentView(root);
        showSwipeItem();
    }

    private void resetCard() {
        swImage.setTranslationX(0);
        swImage.setRotation(0);
        swFrame.setBackgroundColor(0);
    }

    private void showSwipeItem() {
        if (swipeIdx >= swipeList.size()) { finishSwipe(); return; }
        Item it = swipeList.get(swipeIdx);
        long delBytes = 0; for (Item i : swipeDelete) delBytes += i.size;
        swCounter.setText((swipeIdx + 1) + " / " + swipeList.size() + "   •   Delete: "
                + swipeDelete.size() + " (" + size(delBytes) + ")");
        SimpleDateFormat f = new SimpleDateFormat("d MMM yyyy", Locale.US);
        swInfo.setText((it.video ? "▶ Video (tap = chalayen) • " : "") + size(it.size) + " • "
                + f.format(new Date(it.dateMs)) + " • " + it.bucket
                + "\n← Delete        Rakhein →");
        loadInto(swImage, it, 1080);
        // agli 2 pehle se load
        for (int k = 1; k <= 2 && swipeIdx + k < swipeList.size(); k++) {
            Item n = swipeList.get(swipeIdx + k);
            String key = n.uri + "#1080";
            if (cache.get(key) == null) {
                pool.execute(() -> {
                    try { cache.put(key, getContentResolver().loadThumbnail(n.uri, new Size(1080, 1080), null)); }
                    catch (Exception ignored) { }
                });
            }
        }
    }

    private void decide(boolean delete) {
        if (swipeIdx >= swipeList.size()) return;
        if (delete) swipeDelete.add(swipeList.get(swipeIdx));
        swipeHistory.add(delete);
        swipeIdx++;
        showSwipeItem();
    }

    private void undo() {
        if (swipeIdx == 0 || swipeHistory.isEmpty()) return;
        swipeIdx--;
        boolean wasDel = swipeHistory.remove(swipeHistory.size() - 1);
        if (wasDel) swipeDelete.remove(swipeDelete.size() - 1);
        showSwipeItem();
    }

    private void finishSwipe() {
        if (swipeDelete.isEmpty()) { showDashboard(); return; }
        trash(new ArrayList<>(swipeDelete));
    }

    // ================= actions =================
    private void openItem(Item it) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(it.uri, it.video ? "video/*" : "image/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Open nahi ho saka", Toast.LENGTH_SHORT).show();
        }
    }

    private long pendingBytes = 0;

    private void trash(List<Item> items) {
        if (items.isEmpty()) { Toast.makeText(this, "Pehle kuch select karein", Toast.LENGTH_SHORT).show(); return; }
        List<Uri> uris = new ArrayList<>();
        Set<Uri> seen = new HashSet<>();
        pendingBytes = 0;
        for (Item i : items) if (seen.add(i.uri)) { uris.add(i.uri); pendingBytes += i.size; }
        try {
            // Android khud confirm poochega. Files Trash mein jati hain.
            PendingIntent pi = MediaStore.createTrashRequest(getContentResolver(), uris, true);
            startIntentSenderForResult(pi.getIntentSender(), REQ_TRASH, null, 0, 0, 0);
        } catch (Exception e) {
            Toast.makeText(this, "Delete fail: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_TRASH) return;
        if (result == RESULT_OK) {
            Toast.makeText(this, size(pendingBytes) + " saaf ho gaya! (Trash mein 30 din)", Toast.LENGTH_LONG).show();
            load();
        } else if (screen == 2) {
            showDashboard();
        }
    }

    @Override
    public void onBackPressed() {
        if (screen != 0 && !all.isEmpty()) {
            if (screen == 2 && !swipeDelete.isEmpty()) { finishSwipe(); return; }
            showDashboard();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }
}
