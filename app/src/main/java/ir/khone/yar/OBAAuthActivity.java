package ir.khone.yar;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class OBAAuthActivity extends Activity {
    private static final String API_URL = "https://sxyaujvliummbvzznejq.supabase.co";
    private static final String API_KEY = "sb_publishable_AQdI9Ehm-lf7p1Rs7bu4BA_m60TTlen";
    private final int navy = Color.rgb(32, 52, 105);
    private final int teal = Color.rgb(0, 137, 120);
    private LinearLayout page;
    private EditText emailInput, usernameInput, passwordInput, identifierInput;
    private boolean registerMode = true;
    private Button submitButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        showWelcome();
    }

    private void showWelcome() {
        page = basePage();
        TextView title = text("🏠 OBA", 30, navy, true);
        title.setGravity(Gravity.CENTER);
        page.addView(title, params(-1, -2, 0, 0, 0, 8));
        TextView subtitle = text("دستیار صوتی مدیریت خانه", 17, Color.DKGRAY, false);
        subtitle.setGravity(Gravity.CENTER);
        page.addView(subtitle, params(-1, -2, 0, 0, 0, 24));
        TextView description = text("روش ورودت را انتخاب کن", 18, navy, true);
        description.setGravity(Gravity.CENTER);
        page.addView(description, params(-1, -2, 0, 0, 0, 16));
        Button free = button("🎁  ورود و استفادهٔ رایگان", teal, Color.WHITE);
        free.setOnClickListener(v -> {
            getSharedPreferences("oba_auth", MODE_PRIVATE).edit().putString("mode", "guest").apply();
            openApp();
        });
        page.addView(free, params(-1, dp(58), 0, 0, 0, 12));
        Button account = button("👤  ساخت حساب / ورود به حساب", navy, Color.WHITE);
        account.setOnClickListener(v -> showAccountForm(true));
        page.addView(account, params(-1, dp(58), 0, 0, 0, 12));
        TextView note = text("در حالت رایگان، سهمیهٔ هر قابلیت جداگانه محاسبه می‌شود. برای بازیابی حساب در گوشی دیگر، حساب بساز.", 14, Color.DKGRAY, false);
        note.setGravity(Gravity.CENTER);
        page.addView(note, params(-1, -2, 0, 12, 0, 0));
        setContentView(page);
    }

    private void showAccountForm(boolean signup) {
        registerMode = signup;
        page = basePage();
        TextView title = text(signup ? "ساخت حساب اوبا" : "ورود به حساب اوبا", 24, navy, true);
        title.setGravity(Gravity.CENTER);
        page.addView(title, params(-1, -2, 0, 0, 0, 16));

        if (signup) {
            emailInput = edit("جیمیل (example@gmail.com)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            page.addView(emailInput, params(-1, dp(54), 0, 0, 0, 10));
            usernameInput = edit("نام کاربری انگلیسی؛ ۳ تا ۲۴ حرف", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            page.addView(usernameInput, params(-1, dp(54), 0, 0, 0, 10));
        } else {
            identifierInput = edit("جیمیل یا نام کاربری", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            page.addView(identifierInput, params(-1, dp(54), 0, 0, 0, 10));
        }

        passwordInput = edit("رمز عبور (حداقل ۸ کاراکتر)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        page.addView(passwordInput, params(-1, dp(54), 0, 0, 0, 12));

        submitButton = button(signup ? "ساخت حساب" : "ورود", teal, Color.WHITE);
        submitButton.setOnClickListener(v -> submit());
        page.addView(submitButton, params(-1, dp(54), 0, 0, 0, 10));

        Button switchMode = button(signup ? "قبلاً حساب دارم؛ ورود" : "حساب ندارم؛ ساخت حساب", Color.rgb(232, 238, 249), navy);
        switchMode.setOnClickListener(v -> showAccountForm(!registerMode));
        page.addView(switchMode, params(-1, dp(50), 0, 0, 0, 10));
        Button back = button("بازگشت به انتخاب مسیر", Color.WHITE, navy);
        back.setOnClickListener(v -> showWelcome());
        page.addView(back, params(-1, dp(48), 0, 0, 0, 0));
        setContentView(page);
    }

    private void submit() {
        final String email = registerMode && emailInput != null ? emailInput.getText().toString().trim().toLowerCase() : "";
        final String username = registerMode && usernameInput != null ? usernameInput.getText().toString().trim().toLowerCase() : "";
        final String identifier = !registerMode && identifierInput != null ? identifierInput.getText().toString().trim().toLowerCase() : "";
        final String password = passwordInput == null ? "" : passwordInput.getText().toString();
        if (registerMode && !email.matches("^[^\\s@]+@gmail\\.com$")) {
            emailInput.setError("جیمیل معتبر با دامنه gmail.com وارد کن");
            return;
        }
        if (registerMode && !username.matches("^[a-z0-9_]{3,24}$")) {
            usernameInput.setError("فقط حروف انگلیسی کوچک، عدد و زیرخط؛ ۳ تا ۲۴ کاراکتر");
            return;
        }
        if (!registerMode && identifier.isEmpty()) {
            identifierInput.setError("جیمیل یا نام کاربری را وارد کن");
            return;
        }
        if (password.length() < 8 || password.length() > 72) {
            passwordInput.setError("رمز عبور باید ۸ تا ۷۲ کاراکتر باشد");
            return;
        }
        submitButton.setEnabled(false);
        submitButton.setText("لطفاً صبر کن…");
        new Thread(() -> {
            try {
                String loginIdentifier = identifier;
                if (registerMode) {
                    JSONObject body = new JSONObject();
                    body.put("email", email);
                    body.put("username", username);
                    body.put("password", password);
                    JSONObject created = request("/functions/v1/register-username", body);
                    if (!created.optBoolean("ok", false)) throw new Exception(created.optString("error", "ثبت‌نام انجام نشد."));
                    loginIdentifier = email;
                }

                JSONObject loginBody = new JSONObject();
                loginBody.put("identifier", loginIdentifier);
                loginBody.put("password", password);
                JSONObject session = request("/functions/v1/login-username", loginBody);
                String access = session.optString("access_token", "");
                String refresh = session.optString("refresh_token", "");
                if (access.isEmpty() || refresh.isEmpty()) throw new Exception(session.optString("error", "ورود انجام نشد."));
                if (!OBAQuota.syncGuestUsage(this, access)) {
                    throw new Exception("اتصال برقرار شد، اما انتقال سهمیهٔ رایگان کامل نشد. اینترنت را بررسی کن و دوباره وارد شو.");
                }
                JSONObject user = session.optJSONObject("user");
                SharedPreferences auth = getSharedPreferences("oba_auth", MODE_PRIVATE);
                auth.edit()
                    .putString("mode", "account")
                    .putString("access_token", access)
                    .putString("refresh_token", refresh)
                    .putString("user_id", user == null ? "" : user.optString("id", ""))
                    .putString("username", user == null ? loginIdentifier : user.optString("username", loginIdentifier))
                    .putLong("expires_at", System.currentTimeMillis() + session.optLong("expires_in", 3600) * 1000L)
                    .apply();
                runOnUiThread(() -> {
                    Toast.makeText(this, registerMode ? "حساب ساخته شد و وارد اوبا شدی." : "ورود موفق بود.", Toast.LENGTH_LONG).show();
                    openApp();
                });
            } catch (Exception e) {
                final String message = e.getMessage() == null ? "ارتباط با سرور برقرار نشد." : e.getMessage();
                runOnUiThread(() -> {
                    submitButton.setEnabled(true);
                    submitButton.setText(registerMode ? "ساخت حساب" : "ورود");
                    new AlertDialog.Builder(this).setTitle("خطا").setMessage(message).setPositiveButton("باشه", null).show();
                });
            }
        }).start();
    }

    private JSONObject request(String path, JSONObject payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API_URL + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("apikey", API_KEY);
        connection.setRequestProperty("Accept", "application/json");
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
        int status = connection.getResponseCode();
        java.io.InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder response = new StringBuilder();
        if (stream != null) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
            }
        }
        connection.disconnect();
        JSONObject result;
        try { result = new JSONObject(response.toString()); }
        catch (Exception ex) { throw new Exception("پاسخ سرور قابل خواندن نیست."); }
        if (status < 200 || status >= 300) throw new Exception(result.optString("error", result.optString("msg", "درخواست انجام نشد.")));
        return result;
    }

    private void openApp() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private LinearLayout basePage() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(24), dp(20), dp(24));
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setBackgroundColor(Color.rgb(244, 248, 255));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(layout);
        page = layout;
        // The ScrollView wrapper is applied by setPage below so the controls remain scrollable.
        return layout;
    }

    private EditText edit(String hint, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(16);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        e.setPadding(dp(12), dp(6), dp(12), dp(6));
        e.setBackground(round(Color.WHITE, 12));
        return e;
    }

    private Button button(String label, int background, int foreground) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(foreground);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(round(background, 14));
        return b;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private LinearLayout.LayoutParams params(int width, int height, int l, int top, int r, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height));
        p.setMargins(dp(l), dp(top), dp(r), dp(bottom));
        return p;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
