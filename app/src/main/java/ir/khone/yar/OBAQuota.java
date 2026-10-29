package ir.khone.yar;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class OBAQuota {
    private static final String API_URL = "https://sxyaujvliummbvzznejq.supabase.co";
    private static final String API_KEY = "sb_publishable_AQdI9Ehm-lf7p1Rs7bu4BA_m60TTlen";
    private static final int FREE_LIMIT = 10;

    interface Callback { void complete(boolean allowed, int used, String error); }
    private OBAQuota() {}

    static void consume(Context context, String featureKey, Callback callback) {
        SharedPreferences auth = context.getSharedPreferences("oba_auth", Context.MODE_PRIVATE);
        if (!"account".equals(auth.getString("mode", "guest"))) {
            SharedPreferences local = context.getSharedPreferences("oba_quota", Context.MODE_PRIVATE);
            int used = local.getInt(featureKey, 0);
            if (used >= FREE_LIMIT) {
                callback.complete(false, used, null);
            } else {
                used++;
                local.edit().putInt(featureKey, used).apply();
                callback.complete(true, used, null);
            }
            return;
        }
        new Thread(() -> {
            try {
                String token = validAccessToken(context);
                JSONObject body = new JSONObject();
                body.put("p_feature_key", featureKey);
                HttpURLConnection c = open("/rest/v1/rpc/oba_consume_feature_use", token);
                c.setDoOutput(true);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
                int status = c.getResponseCode();
                String text = readResponse(c, status);
                if (status < 200 || status >= 300) throw new Exception("امکان بررسی سهمیه نیست. اینترنت را بررسی کن.");
                JSONArray rows = new JSONArray(text);
                JSONObject row = rows.length() > 0 ? rows.getJSONObject(0) : new JSONObject();
                callback.complete(row.optBoolean("allowed", false), row.optInt("used_count", 0), null);
            } catch (Exception e) {
                callback.complete(false, 0, e.getMessage() == null ? "اتصال به سرور برقرار نشد." : e.getMessage());
            }
        }).start();
    }

    static boolean syncGuestUsage(Context context, String accessToken) {
        try {
            SharedPreferences local = context.getSharedPreferences("oba_quota", Context.MODE_PRIVATE);
            JSONObject usage = new JSONObject();
            for (String key : local.getAll().keySet()) {
                Object value = local.getAll().get(key);
                if (value instanceof Integer) usage.put(key, Math.min(FREE_LIMIT, Math.max(0, (Integer) value)));
            }
            JSONObject body = new JSONObject();
            body.put("p_usage", usage);
            HttpURLConnection c = open("/rest/v1/rpc/oba_sync_guest_usage", accessToken);
            c.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            int status = c.getResponseCode();
            readResponse(c, status);
            return status >= 200 && status < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private static String validAccessToken(Context context) throws Exception {
        SharedPreferences auth = context.getSharedPreferences("oba_auth", Context.MODE_PRIVATE);
        String token = auth.getString("access_token", "");
        if (!token.isEmpty() && auth.getLong("expires_at", 0) > System.currentTimeMillis() + 60000) return token;
        String refresh = auth.getString("refresh_token", "");
        if (refresh.isEmpty()) throw new Exception("نشست حساب منقضی شده است؛ دوباره وارد شو.");
        JSONObject body = new JSONObject();
        body.put("refresh_token", refresh);
        HttpURLConnection c = (HttpURLConnection) new URL(API_URL + "/auth/v1/token?grant_type=refresh_token").openConnection();
        if (c == null) throw new Exception("نوسازی ورود انجام نشد.");
        c.setRequestMethod("POST");
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("apikey", API_KEY);
        try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int status = c.getResponseCode();
        String text = readResponse(c, status);
        if (status < 200 || status >= 300) throw new Exception("نشست حساب منقضی شده است؛ دوباره وارد شو.");
        JSONObject session = new JSONObject(text);
        token = session.getString("access_token");
        String newRefresh = session.optString("refresh_token", refresh);
        auth.edit().putString("access_token", token).putString("refresh_token", newRefresh)
            .putLong("expires_at", System.currentTimeMillis() + session.optLong("expires_in", 3600) * 1000L).apply();
        return token;
    }

    private static HttpURLConnection open(String path, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API_URL + path).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("apikey", API_KEY);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    private static String readResponse(HttpURLConnection c, int status) throws Exception {
        java.io.InputStream stream = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
        StringBuilder text = new StringBuilder();
        if (stream != null) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) text.append(line);
            }
        }
        c.disconnect();
        return text.toString();
    }
}
