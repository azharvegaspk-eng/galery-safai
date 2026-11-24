package pk.azhar.gallerysafai;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
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
 * Gallery Safai - offline gallery sorter.
 * Koi internet nahi, koi data bahar nahi jata. Delete = Android Trash (30 din mein wapas aa sakta hai).
 */
public class MainActivity extends Activity {

    static class Item {
        Uri uri; String name; String bucket; String path;
        long size; long dateMs; boolean video;
    }

    private static final int REQ_PERM = 1;
    private static final int REQ_TRASH = 2;
    private static final long BIG_VIDEO = 50L * 1024 * 1024;

    private final List<Item> all = new ArrayList<>();
    private final LinkedHashMap<String, List<Item>> groups = new LinkedHashMap<>();
    private final List<String> groupKeys = new ArrayList<>();
    private List<Item> shown = new ArrayList<>();
    private final Set<Uri> selected = new HashSet<>();

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private LruCache<String, Bitmap> cache;

    private Spinner spinner;
    private TextView info;
    private Button trashBtn;
    private GridView grid;
    private GridAdapter adapter;
    private int cellPx;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        cache = new LruCache<String, Bitmap>(maxKb) {
            @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount() / 1024; }
        };
        cellPx = getResources().getDisplayMetrics().widthPixels / 3;
        buildUi();
        if (hasPermission()) load(); else askPermission();
    }

    // ---------- UI ----------
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        spinner = new Spinner(this);
        root.addView(spinner, new LinearLayout.LayoutParams(-1, -2));

        info = new TextView(this);
        info.setPadding(24, 8, 24, 8);
        root.addView(info, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bar = new LinearLayout(this);
        Button selAll = new Button(this);
        selAll.setText("Sab select");
        selAll.setOnClickListener(v -> toggleAll());
        Button clear = new Button(this);
        clear.setText("Clear");
        clear.setOnClickListener(v -> { selected.clear(); refreshUi(); });
        trashBtn = new Button(this);
        trashBtn.setText("Delete (0)");
        trashBtn.setOnClickListener(v -> trashSelected());
        LinearLayout.LayoutParams w = new LinearLayout.LayoutParams(0, -2, 1f);
        bar.addView(selAll, w);
        bar.addView(clear, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(trashBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        grid = new GridView(this);
        grid.setNumColumns(3);
        adapter = new GridAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            Uri u = shown.get(pos).uri;
            if (!selected.remove(u)) selected.add(u);
            refreshUi();
        });
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            openItem(shown.get(pos));
            return true;
        });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        info.setText("Tap = select, lamba dabayen = dekhein");
    }

    private void refreshUi() {
        long bytes = 0; int n = 0;
        for (Item it : all) if (selected.contains(it.uri)) { bytes += it.size; n++; }
        trashBtn.setText("Delete (" + n + ")");
        long groupBytes = 0;
        for (Item it : shown) groupBytes += it.size;
        info.setText(shown.size() + " files, " + mb(groupBytes) + "   |   Selected: " + mb(bytes)
                + "\nTap = select, lamba dabayen = dekhein");
        adapter.notifyDataSetChanged();
    }

    private void toggleAll() {
        boolean allSel = true;
        for (Item it : shown) if (!selected.contains(it.uri)) { allSel = false; break; }
        for (Item it : shown) { if (allSel) selected.remove(it.uri); else selected.add(it.uri); }
        refreshUi();
    }

    private static String mb(long b) {
        if (b >= 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
        return String.format(Locale.US, "%.1f MB", b / 1048576.0);
    }

    // ---------- Permission ----------
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
        else info.setText("Photos/videos ki permission chahiye. App band karke dobara kholein aur Allow karein.");
    }

    // ---------- Load media ----------
    private void load() {
        info.setText("Gallery scan ho rahi hai...");
        pool.execute(() -> {
            List<Item> list = new ArrayList<>();
            query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), false, list);
            query(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, list);
            list.sort((a, c) -> Long.compare(c.dateMs, a.dateMs));
            main.post(() -> {
                all.clear(); all.addAll(list);
                selected.clear();
                buildGroups();
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

    private void buildGroups() {
        groups.clear();
        List<Item> shots = new ArrayList<>(), wa = new ArrayList<>(), bigVid = new ArrayList<>(),
                videos = new ArrayList<>(), photos = new ArrayList<>();
        for (Item it : all) {
            String p = (it.path + " " + it.bucket).toLowerCase(Locale.US);
            if (p.contains("screenshot") || p.contains("screen record")) shots.add(it);
            if (p.contains("whatsapp")) wa.add(it);
            if (it.video) { videos.add(it); if (it.size >= BIG_VIDEO) bigVid.add(it); }
            else photos.add(it);
        }
        bigVid.sort((a, c) -> Long.compare(c.size, a.size));

        // Duplicates: bilkul same size + same type. Har group ki sab se purani copy rakh li jati hai.
        Map<String, List<Item>> bySize = new HashMap<>();
        for (Item it : all) {
            if (it.size <= 0) continue;
            String k = (it.video ? "v" : "i") + it.size;
            List<Item> l = bySize.get(k);
            if (l == null) { l = new ArrayList<>(); bySize.put(k, l); }
            l.add(it);
        }
        List<Item> dups = new ArrayList<>();
        for (List<Item> l : bySize.values()) {
            if (l.size() < 2) continue;
            l.sort((a, c) -> Long.compare(a.dateMs, c.dateMs));
            dups.addAll(l.subList(1, l.size()));
        }

        groups.put("Sab kuch", new ArrayList<>(all));
        groups.put("Duplicates (1 copy safe rakhi)", dups);
        groups.put("Screenshots", shots);
        groups.put("WhatsApp media", wa);
        groups.put("Bari videos (50MB+)", bigVid);
        groups.put("Sirf videos", videos);
        groups.put("Sirf photos", photos);

        // Mahinay ke hisaab se
        SimpleDateFormat fmt = new SimpleDateFormat("MMM yyyy", Locale.US);
        LinkedHashMap<String, List<Item>> months = new LinkedHashMap<>();
        for (Item it : all) {
            String m = "Mahina: " + fmt.format(new Date(it.dateMs));
            List<Item> l = months.get(m);
            if (l == null) { l = new ArrayList<>(); months.put(m, l); }
            l.add(it);
        }
        groups.putAll(months);

        // Folders (Camera, Downloads, Instagram waghera)
        LinkedHashMap<String, List<Item>> folders = new LinkedHashMap<>();
        for (Item it : all) {
            String f = "Folder: " + (it.bucket.isEmpty() ? "Other" : it.bucket);
            List<Item> l = folders.get(f);
            if (l == null) { l = new ArrayList<>(); folders.put(f, l); }
            l.add(it);
        }
        groups.putAll(folders);

        groupKeys.clear();
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, List<Item>> e : groups.entrySet()) {
            long b = 0;
            for (Item it : e.getValue()) b += it.size;
            groupKeys.add(e.getKey());
            labels.add(e.getKey() + "  (" + e.getValue().size() + " • " + mb(b) + ")");
        }
        int prev = spinner.getSelectedItemPosition();
        ArrayAdapter<String> sa = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(sa);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                shown = groups.get(groupKeys.get(pos));
                refreshUi();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        if (prev >= 0 && prev < labels.size()) spinner.setSelection(prev);
        shown = groups.get(groupKeys.get(Math.max(0, Math.min(prev, groupKeys.size() - 1))));
        refreshUi();
    }

    // ---------- Actions ----------
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

    private void trashSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "Pehle kuch select karein", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // Android khud confirm poochega. Files Trash mein jati hain (30 din tak wapas la sakte hain).
            PendingIntent pi = MediaStore.createTrashRequest(getContentResolver(), new ArrayList<>(selected), true);
            startIntentSenderForResult(pi.getIntentSender(), REQ_TRASH, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException | RuntimeException e) {
            Toast.makeText(this, "Delete request fail: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_TRASH && result == RESULT_OK) {
            Toast.makeText(this, "Trash mein chali gayi", Toast.LENGTH_SHORT).show();
            load();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }

    // ---------- Grid ----------
    private class GridAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            FrameLayout cell;
            ImageView img;
            TextView tag;
            if (convert == null) {
                cell = new FrameLayout(MainActivity.this);
                cell.setLayoutParams(new GridView.LayoutParams(cellPx, cellPx));
                img = new ImageView(MainActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cell.addView(img, new FrameLayout.LayoutParams(-1, -1));
                tag = new TextView(MainActivity.this);
                tag.setTextColor(Color.WHITE);
                tag.setBackgroundColor(0x88000000);
                tag.setPadding(8, 2, 8, 2);
                cell.addView(tag, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
            } else {
                cell = (FrameLayout) convert;
                img = (ImageView) cell.getChildAt(0);
                tag = (TextView) cell.getChildAt(1);
            }
            Item it = shown.get(pos);
            boolean sel = selected.contains(it.uri);
            cell.setPadding(sel ? 10 : 2, sel ? 10 : 2, sel ? 10 : 2, sel ? 10 : 2);
            cell.setBackgroundColor(sel ? Color.RED : Color.WHITE);
            img.setAlpha(sel ? 0.6f : 1f);
            tag.setText((sel ? "✓ " : "") + (it.video ? "▶ " : "") + mb(it.size));

            String key = it.uri.toString();
            img.setTag(key);
            Bitmap bm = cache.get(key);
            if (bm != null) {
                img.setImageBitmap(bm);
            } else {
                img.setImageDrawable(null);
                final Uri u = it.uri;
                pool.execute(() -> {
                    try {
                        Bitmap t = getContentResolver().loadThumbnail(u, new Size(256, 256), null);
                        cache.put(key, t);
                        main.post(() -> { if (key.equals(img.getTag())) img.setImageBitmap(t); });
                    } catch (Exception ignored) { }
                });
            }
            return cell;
        }
    }
}
