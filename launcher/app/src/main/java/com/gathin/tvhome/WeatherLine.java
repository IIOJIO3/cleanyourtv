package com.gathin.tvhome;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import java.util.Locale;

/**
 * The weather line under the date: "+7° · Cloudy · Moscow". The launcher has no network of its own; the
 * weather add-on (addons/weather) fetches from Open-Meteo and answers a small content provider with the
 * reading it holds. Without the add-on, or before a city is chosen, the line stays hidden. Every failure here
 * only hides the line: Home must never go down because of the weather.
 */
final class WeatherLine {
    static final String AUTHORITY = "io.github.sumyabazar1.weather.now";
    private static final Uri NOW = Uri.parse("content://" + AUTHORITY + "/now");

    private final Context context;
    private final TextView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler bg;
    private ContentObserver observer;
    private final Runnable query = this::queryNow;

    WeatherLine(Context context, TextView view) {
        this.context = context.getApplicationContext();
        this.view = view;
    }

    /** Asks the add-on again; cheap, so it runs with the clock's minute tick and on every return to Home. */
    void refresh() {
        try {
            if (thread == null) {
                thread = new HandlerThread("weather");
                thread.start();
                bg = new Handler(thread.getLooper());
            }
            if (observer == null) {
                ContentObserver o = new ContentObserver(bg) {
                    @Override
                    public void onChange(boolean selfChange) {
                        queryNow();
                    }
                };
                try {
                    context.getContentResolver().registerContentObserver(NOW, false, o);
                    observer = o;
                } catch (Throwable ignored) {
                    // the add-on is not installed yet: the next refresh tries again
                }
            }
            bg.removeCallbacks(query);
            bg.post(query);
        } catch (Throwable t) {
            hide();
        }
    }

    void release() {
        try {
            if (observer != null) context.getContentResolver().unregisterContentObserver(observer);
        } catch (Throwable ignored) {
        }
        observer = null;
        if (thread != null) thread.quitSafely();
        thread = null;
        bg = null;
    }

    private void queryNow() {
        String text = null;
        try (Cursor c = context.getContentResolver().query(NOW, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int temp = c.getInt(c.getColumnIndexOrThrow("temp"));
                int code = c.getInt(c.getColumnIndexOrThrow("code"));
                boolean day = c.getInt(c.getColumnIndexOrThrow("day")) == 1;
                String place = c.getString(c.getColumnIndexOrThrow("place"));
                text = format(temp, code, day, place);
            }
        } catch (Throwable ignored) {
            text = null;
        }
        String shown = text;
        main.post(() -> {
            if (shown == null) {
                hide();
            } else {
                view.setText(shown);
                view.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hide() {
        main.post(() -> view.setVisibility(View.GONE));
    }

    static String format(int temp, int code, boolean day, String place) {
        boolean ru = "ru".equals(Locale.getDefault().getLanguage());
        String sign = temp > 0 ? "+" : temp < 0 ? "−" : "";
        StringBuilder out = new StringBuilder(sign).append(Math.abs(temp)).append('°');
        out.append("  ·  ").append(sky(code, day, ru));
        if (place != null && !place.isEmpty()) out.append("  ·  ").append(place);
        return out.toString();
    }

    /** WMO weather codes as Open-Meteo sends them, in a word or two. */
    static String sky(int code, boolean day, boolean ru) {
        if (code == 0) return ru ? (day ? "Ясно" : "Ясная ночь") : (day ? "Clear" : "Clear night");
        if (code == 1 || code == 2) return ru ? "Переменная облачность" : "Partly cloudy";
        if (code == 3) return ru ? "Облачно" : "Cloudy";
        if (code == 45 || code == 48) return ru ? "Туман" : "Fog";
        if (code >= 51 && code <= 57) return ru ? "Морось" : "Drizzle";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return ru ? "Дождь" : "Rain";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return ru ? "Снег" : "Snow";
        if (code >= 95) return ru ? "Гроза" : "Thunderstorm";
        return ru ? "Облачно" : "Cloudy";
    }
}
