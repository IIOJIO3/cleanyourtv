package io.github.sumyabazar1.weather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The weather now for the Clean Your TV launcher, which shows it under the date and has no network of its own.
 * Read-only: one row (temp in the chosen unit, WMO code, day, place) from the last reading. A query that finds
 * the reading older than {@link Forecast#REFRESH_MS} (or {@link Forecast#RETRY_MS} after a failure) starts one
 * fetch on a background thread and notifies the uri when it lands. No reading and no city: no row.
 */
public final class NowProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.sumyabazar1.weather.now";
    static final Uri NOW = Uri.parse("content://" + AUTHORITY + "/now");
    private static final String CACHE = "now";
    private static final AtomicBoolean FETCHING = new AtomicBoolean();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        Context context = getContext();
        if (context == null) return null;
        SharedPreferences place = context.getSharedPreferences("place", Context.MODE_PRIVATE);
        String name = place.getString("name", null);
        MatrixCursor out = new MatrixCursor(new String[] {"temp", "code", "day", "place", "updated"});
        if (name == null) return out;
        SharedPreferences cache = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE);
        boolean samePlace = name.equals(cache.getString("place", null));
        long updated = cache.getLong("updated", 0);
        long tried = cache.getLong("tried", 0);
        long now = System.currentTimeMillis();
        boolean stale = !samePlace || now - updated > Forecast.REFRESH_MS;
        if (stale && now - tried > Forecast.RETRY_MS) fetch(context, place);
        if (samePlace && cache.contains("tempC")) {
            boolean fahrenheit = "f".equalsIgnoreCase(context.getString(R.string.temperature_unit).trim());
            double tempC = Double.longBitsToDouble(cache.getLong("tempC", 0));
            out.addRow(new Object[] {Forecast.degrees(tempC, fahrenheit), cache.getInt("code", 3),
                    cache.getBoolean("day", true) ? 1 : 0, name, updated});
        }
        return out;
    }

    /** Keeps a reading, from this provider or from the weather page, and tells the launcher. */
    static void save(Context context, String place, Forecast.Report r) {
        context.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit()
                .putString("place", place)
                .putLong("tempC", Double.doubleToRawLongBits(r.tempC))
                .putInt("code", r.code)
                .putBoolean("day", r.day)
                .putLong("updated", System.currentTimeMillis())
                .apply();
        try {
            context.getContentResolver().notifyChange(NOW, null);
        } catch (Throwable ignored) {
        }
    }

    private static void fetch(Context context, SharedPreferences place) {
        if (!FETCHING.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        app.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit()
                .putLong("tried", System.currentTimeMillis()).apply();
        String name = place.getString("name", "");
        double lat = Double.longBitsToDouble(place.getLong("lat", 0));
        double lon = Double.longBitsToDouble(place.getLong("lon", 0));
        new Thread(() -> {
            try {
                save(app, name, Forecast.fetch(lat, lon, Forecast.MIN_DAYS));
            } catch (Throwable ignored) {
                // offline or the service failed: the launcher keeps the last reading, and asks again later
            } finally {
                FETCHING.set(false);
            }
        }, "weather-now").start();
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
