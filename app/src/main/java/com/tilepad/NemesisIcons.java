package com.tilepad;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Icons for apps whose APK has none (so Android would draw the generic robot), taken from the
 * NEMESIS package that installed them: descriptor 1.2's {@code favicon=}, the same file NEMESIS
 * Manager shows in its package lists. Installed packages and their favicons are world-readable.
 */
final class NemesisIcons {
    private static final File APPS = new File("/data/nemesis/apps");
    // NEMESIS refuses a favicon over 256 KiB; Manager decodes down to about this edge.
    private static final long MAX_BYTES = 256 * 1024;
    private static final int EDGE = 192;

    /** Decoded favicons by path and modification time, so an update's new icon shows. */
    private static final Map<String, Bitmap> decoded = new HashMap<>();

    /** The package's favicon as a drawable, or null when no installed NEMESIS package names one. */
    static Drawable forPackage(Context context, String packageName) {
        return drawable(context, faviconsByPackage().get(packageName));
    }

    static Drawable drawable(Context context, File favicon) {
        Bitmap bitmap = favicon == null ? null : decode(favicon);
        return bitmap == null ? null : new BitmapDrawable(context.getResources(), bitmap);
    }

    /** Android package to favicon file, over every installed NEMESIS package. Blocking file reads. */
    static Map<String, File> faviconsByPackage() {
        Map<String, File> byPackage = new HashMap<>();
        File[] dirs = APPS.listFiles();
        if (dirs == null) return byPackage;
        for (File dir : dirs) {
            String text = read(new File(dir, "NEMESIS.data"));
            if (text == null) continue;
            NemesisDescriptor d = NemesisDescriptor.parse(text);
            if (d.favicon == null) continue;
            File file = new File(dir, d.favicon);
            if (!file.isFile() || file.length() > MAX_BYTES) continue;
            for (String pkg : d.packages) byPackage.put(pkg, file);
        }
        return byPackage;
    }

    private static synchronized Bitmap decode(File file) {
        String key = file.getPath() + "@" + file.lastModified();
        if (decoded.containsKey(key)) return decoded.get(key);
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= EDGE && o.outHeight / (sample * 2) >= EDGE) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            bitmap = BitmapFactory.decodeFile(file.getPath(), o);
        } catch (Throwable ignored) { }
        decoded.put(key, bitmap);
        return bitmap;
    }

    private static String read(File file) {
        if (!file.isFile() || file.length() > 64 * 1024) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int n = 0;
            for (int r; n < buffer.length && (r = in.read(buffer, n, buffer.length - n)) > 0; ) n += r;
            return new String(buffer, 0, n, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private NemesisIcons() { }
}
