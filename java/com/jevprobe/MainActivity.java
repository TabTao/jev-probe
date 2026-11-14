package com.jevprobe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 探针：把「别人递过来的 intent」原样接住，落到 Download/jev-probe/ 下。
 * 目的不是功能，是取证：
 *  - PROCESS_TEXT 到底会不会出现（微信文本菜单里）
 *  - 多选信封走的是 ACTION_SEND 还是 mailto
 *  - 导出的正文是 text/plain 还是 text/html
 *  - 有没有 READONLY / 来源包名
 */
public class MainActivity extends Activity {

    private static final String TAG = "JevProbe";
    private static final String DIR = "Download/jev-probe";

    private TextView metaView;
    private TextView payloadView;
    private String lastMeta = "";
    private String lastPayload = "";
    private String lastSavedPath = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Jev Probe");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        metaView = new TextView(this);
        metaView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        metaView.setTypeface(Typeface.MONOSPACE);
        metaView.setTextIsSelectable(true);
        root.addView(metaView, lp(0));

        payloadView = new TextView(this);
        payloadView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        payloadView.setTypeface(Typeface.MONOSPACE);
        payloadView.setTextIsSelectable(true);
        root.addView(payloadView, lp(dp(12)));

        root.addView(button("复制正文", new View.OnClickListener() {
            @Override public void onClick(View v) { copyToClipboard(lastPayload); }
        }), lp(0));

        root.addView(button("再存一份", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (lastMeta.isEmpty() && lastPayload.isEmpty()) return;
                lastSavedPath = saveText(lastAction() + "-manual", lastMeta + "\n\n" + lastPayload);
                toast("已保存: " + lastSavedPath);
            }
        }), lp(0));

        root.addView(button("分享这段正文", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, lastPayload);
                startActivity(Intent.createChooser(i, "分享"));
            }
        }), lp(0));

        setContentView(scroll);
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams lp(int topMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = topMargin;
        return p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction() == null ? "(none)" : intent.getAction();

        StringBuilder meta = new StringBuilder();
        meta.append("action   : ").append(action).append('\n');
        meta.append("type     : ").append(intent.getType()).append('\n');
        meta.append("data     : ").append(intent.getData()).append('\n');
        meta.append("flags    : 0x").append(Integer.toHexString(intent.getFlags())).append('\n');
        meta.append("callingPkg: ").append(callingPackage()).append('\n');
        meta.append("referrer : ").append(intent.getReferrer() == null ? "(none)" : intent.getReferrer().toString()).append('\n');
        meta.append("device   : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / SDK ").append(Build.VERSION.SDK_INT).append('\n');
        meta.append("time     : ").append(now()).append('\n');

        String[] cats = intent.getCategories() == null ? new String[0]
                : intent.getCategories().toArray(new String[0]);
        meta.append("categories: ").append(TextUtils.join(", ", cats)).append('\n');

        Bundle ex = intent.getExtras();
        if (ex != null) {
            meta.append("----- extras -----\n");
            for (String k : ex.keySet()) {
                Object v = ex.get(k);
                String shown;
                try {
                    shown = describeValue(v);
                } catch (Throwable t) {
                    shown = "<" + t.getClass().getSimpleName() + ">";
                }
                meta.append(k).append(" = ").append(shown).append('\n');
            }
        }

        List<String> texts = new ArrayList<>();
        Object pt = intent.getExtras() == null ? null : intent.getExtras().get(Intent.EXTRA_PROCESS_TEXT);
        if (pt != null) texts.add(stringOf(pt));
        Object et = intent.getExtras() == null ? null : intent.getExtras().get(Intent.EXTRA_TEXT);
        if (et != null) texts.add(stringOf(et));

        ClipData clip = intent.getClipData();
        if (clip != null) {
            meta.append("----- clip -----\n");
            meta.append("itemCount: ").append(clip.getItemCount()).append('\n');
            for (int i = 0; i < clip.getItemCount(); i++) {
                ClipData.Item item = clip.getItemAt(i);
                meta.append("[").append(i).append("] text=").append(item.getText())
                        .append(" uri=").append(item.getUri()).append('\n');
                if (item.getText() != null) texts.add(item.getText().toString());
            }
        }

        boolean readonly = intent.getExtras() != null
                && intent.getExtras().getBoolean(Intent.EXTRA_PROCESS_TEXT_READONLY, false);
        meta.append("PROCESS_TEXT_READONLY = ").append(readonly).append('\n');

        String payload = TextUtils.join("\n\n===8<===\n\n", texts);

        lastMeta = meta.toString();
        lastPayload = payload;
        metaView.setText(lastMeta);
        payloadView.setText(payload.isEmpty() ? "(没有文本，只有 uri)" : payload);

        lastSavedPath = saveText(action, lastMeta + "\n\n===== PAYLOAD =====\n" + payload);
        copyStreams(intent, clip);
        Log.i(TAG, "received " + action + " type=" + intent.getType() + " len=" + payload.length());
        toast("已收到并保存: " + lastSavedPath);
    }

    private String lastAction() {
        Intent i = getIntent();
        String a = i == null ? "intent" : String.valueOf(i.getAction());
        return a.substring(a.lastIndexOf('.') + 1);
    }

    private String callingPackage() {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                String p = getCallingPackage();
                return p == null ? "(null)" : p;
            } catch (Throwable t) {
                return "(err)";
            }
        }
        return "(pre-34)";
    }

    private String describeValue(Object v) {
        if (v == null) return "null";
        if (v instanceof CharSequence) {
            CharSequence cs = (CharSequence) v;
            String extra = cs instanceof Spanned ? " [Spanned]" : "";
            String s = cs.toString();
            return "(" + s.length() + " chars)" + extra + " " + head(s, 300);
        }
        if (v instanceof Bundle) return "(Bundle)";
        if (v instanceof Uri) return v.toString();
        if (v instanceof int[]) return "(int[])";
        return "(" + v.getClass().getSimpleName() + ") " + head(String.valueOf(v), 120);
    }

    private String stringOf(Object o) {
        if (o == null) return "";
        if (o instanceof Spanned) return "<!-- SPANNED -->\n" + o.toString();
        return o.toString();
    }

    private String head(String s, int n) {
        if (s == null) return "";
        s = s.replace("\n", "\\n");
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private String saveText(String tag, String content) {
        String safeTag = tag == null ? "intent" : tag.replaceAll("[^A-Za-z0-9_-]", "_");
        String name = "probe-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + "-" + safeTag + ".txt";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
        cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        cv.put(MediaStore.Downloads.RELATIVE_PATH, DIR);
        cv.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return "(insert failed)";
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                os.write(content.getBytes(StandardCharsets.UTF_8));
            }
            cv.clear();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, cv, null, null);
            return DIR + "/" + name;
        } catch (Throwable t) {
            Log.e(TAG, "saveText failed", t);
            return "(保存失败: " + t.getMessage() + ")";
        }
    }

    private void copyStreams(Intent intent, ClipData clip) {
        List<Uri> uris = new ArrayList<>();
        Uri single = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (single != null) uris.add(single);
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        }
        for (Uri u : uris) {
            try {
                String type = getContentResolver().getType(u);
                String ext = guessExt(type, u.toString());
                String name = "img-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                        + "-" + Math.abs(u.hashCode() % 10000) + ext;
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, type == null ? "application/octet-stream" : type);
                cv.put(MediaStore.Downloads.RELATIVE_PATH, DIR);
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri out = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (out == null) continue;
                long total = 0;
                try (InputStream in = getContentResolver().openInputStream(u);
                     OutputStream os = getContentResolver().openOutputStream(out)) {
                    if (in == null || os == null) continue;
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        total += n;
                    }
                }
                cv.clear();
                cv.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(out, cv, null, null);
                Log.i(TAG, "saved stream " + name + " bytes=" + total);
            } catch (Throwable t) {
                Log.e(TAG, "copyStream failed: " + u, t);
            }
        }
    }

    private String guessExt(String mime, String uri) {
        if (mime != null) {
            if (mime.contains("png")) return ".png";
            if (mime.contains("jpeg") || mime.contains("jpg")) return ".jpg";
            if (mime.contains("webp")) return ".webp";
            if (mime.contains("gif")) return ".gif";
            if (mime.contains("html")) return ".html";
        }
        int dot = uri.lastIndexOf('.');
        if (dot > 0 && dot > uri.length() - 6) return uri.substring(dot);
        return ".bin";
    }

    private void copyToClipboard(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("jev-probe", s));
        toast("已复制 " + s.length() + " 字符");
    }

    private String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }
}
