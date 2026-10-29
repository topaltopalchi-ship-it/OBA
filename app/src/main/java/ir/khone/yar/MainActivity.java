package ir.khone.yar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlarmManager;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.app.PendingIntent;
import android.os.Build;
import android.provider.Settings;
import android.content.Intent;
import android.net.Uri;
import android.media.MediaPlayer;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Calendar;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;
import org.json.JSONException;

public class MainActivity extends Activity {
    private LinearLayout root, cards;
    private TextView status, recent, remindersView, inventoryView;
    private EditText reminderDraftInput;
    private AlertDialog reminderDraftDialog;
    private SharedPreferences prefs;
    private MediaPlayer reminderPreviewPlayer;
    private SpeechRecognizer recognizer;
    private boolean continuousExpenseListening = false;
    private Button continuousExpenseButton;
    private TextView continuousTranscriptView;
    private TextView reminderTranscriptView;
    private Button commitContinuousButton;
    private final ArrayList<String> pendingVoiceExpenses = new ArrayList<>();
    private String unfinishedVoiceExpense = "";
    private boolean correctingNextVoicePhrase = false;
    private boolean awaitingVoiceConfirmation = false;
    private String correctionTargetItem = "";
    private String liveVoicePartial = "";
    private boolean resumeWakeAfterCommand = true;
    private boolean wakeTriggeredSession = false;
    private boolean quietVoiceSession = false;
    private TextToSpeech tts;
    private OfflineVoskSpeechEngine offlineEngine;
    private boolean downloadingVoskModel = false;
    private String pendingMode = "auto";
    private String pendingPurchaseCategory = "";
    private String pendingPurchaseSubgroup = "";
    private long incomeTotal = 0, expenseTotal = 0;
    private final ArrayList<String> entries = new ArrayList<>();
    private static final int AUDIO_PERMISSION = 44;
    private static final int BACKUP_CREATE = 71, BACKUP_RESTORE = 72;
    private static final int HOME_DOC_CAMERA = 73, HOME_DOC_GALLERY = 74, REMINDER_SOUND_PICKER = 75;
    private int dark = Color.rgb(32, 52, 105), green = Color.rgb(0, 157, 112), cream = Color.rgb(244, 248, 255);
    private int cardIndex = 0;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("khoneyar", MODE_PRIVATE);
        incomeTotal = prefs.getLong("income", 0);
        expenseTotal = prefs.getLong("expense", 0);
        loadEntries();
        tts = new TextToSpeech(this, result -> {
            if (tts == null || result != TextToSpeech.SUCCESS) return;
            Locale persian = new Locale("fa", "IR");
            // Prefer a locally installed Persian voice so speech can work without a network.
            // Android cannot provide a voice that is not installed on the device; if none is
            // available offline, keep the system Persian default as a compatibility fallback.
            try {
                android.speech.tts.Voice offlinePersian = null;
                android.speech.tts.Voice anyPersian = null;
                for (android.speech.tts.Voice voice : tts.getVoices()) {
                    Locale voiceLocale = voice.getLocale();
                    if (voiceLocale == null || !"fa".equalsIgnoreCase(voiceLocale.getLanguage())) continue;
                    if (anyPersian == null) anyPersian = voice;
                    if (!voice.isNetworkConnectionRequired()
                            && ("IR".equalsIgnoreCase(voiceLocale.getCountry()) || voiceLocale.getCountry().isEmpty())) {
                        offlinePersian = voice;
                        break;
                    }
                }
                if (offlinePersian != null) {
                    tts.setVoice(offlinePersian);
                } else {
                    int languageResult = tts.setLanguage(persian);
                    if (languageResult < TextToSpeech.LANG_AVAILABLE && anyPersian != null) {
                        tts.setVoice(anyPersian);
                    }
                }
            } catch (Exception ignored) {
                tts.setLanguage(persian);
            }
        });
        buildUi();
        // Restore scheduled text reminders with vibration notifications.
        ReminderReceiver.restore(this);
        // Voice wake-up by saying "OBA" is disabled; voice commands remain available from other controls.
        wakeTriggeredSession = false;
        quietVoiceSession = false;
        prefs.edit().putBoolean("wake_enabled", false).apply();
        try { stopService(new Intent(this, VoiceWakeService.class)); } catch (Exception ignored) {}
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.setText("تشخیص گفتار گوشی در دسترس نیست؛ مدل آفلاین فارسی اوبا جداگانه آماده می‌شود.");
        }
        // Start downloading the Persian Vosk model automatically on the first app launch.
        // This does not require microphone permission and does not enable wake-word listening.
        if (OfflineModelDownloader.getInstalledModelDirectory(this) == null) {
            if (status != null) status.setText("در حال آماده‌سازی و دانلود خودکار مدل گفتار فارسی…");
            if (root != null) root.postDelayed(this::downloadVoskModelAutomatically, 800);
            else downloadVoskModelAutomatically();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        // Re-register saved reminders when returning from Android's exact-alarm settings.
        if (prefs != null) ReminderReceiver.restore(this);
    }

    private void ensureExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (alarmManager == null || alarmManager.canScheduleExactAlarms()) return;
        new AlertDialog.Builder(this)
            .setTitle("فعال‌کردن آلارم دقیق")
            .setMessage("برای اینکه یادآوری‌ها سر ساعت و حتی هنگام بسته بودن برنامه اجرا شوند، اجازهٔ «آلارم‌ها و یادآوری‌ها» را در صفحهٔ بعد فعال کنید. بدون این اجازه ممکن است هشدار دیر اجرا شود.")
            .setNegativeButton("بعداً", (dialog, which) -> dialog.dismiss())
            .setPositiveButton("فعال‌کردن", (dialog, which) -> {
                try {
                    Intent settingsIntent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName()));
                    startActivity(settingsIntent);
                } catch (Exception ignored) {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))); } catch (Exception ignoredAgain) {}
                }
            }).show();
    }

    private void buildUi() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(cream);
        root.setPadding(dp(12), dp(10), dp(12), dp(8));
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        TextView brand = text("🏠 OBA", 27, dark, true); brand.setGravity(Gravity.CENTER);
        content.addView(brand, lp(-1, -2, 0, 4, 0, 1));
        TextView subtitle = text("مدیریت خانه، فقط با حرف زدن", 16, Color.DKGRAY, false); subtitle.setGravity(Gravity.CENTER);
        content.addView(subtitle, lp(-1, -2, 0, 0, 0, 8));

        // Primary dashboard control: explicit opt-in continuous listening for expense entries.
        continuousExpenseButton = new Button(this);
        continuousExpenseButton.setText("🎙  شروع ثبت");
        continuousExpenseButton.setTextSize(17);
        continuousExpenseButton.setTextColor(Color.WHITE);
        continuousExpenseButton.setAllCaps(false);
        continuousExpenseButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        continuousExpenseButton.setGravity(Gravity.CENTER);
        continuousExpenseButton.setPadding(dp(12), dp(14), dp(12), dp(14));
        GradientDrawable voiceFace = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{Color.rgb(8, 91, 91), Color.rgb(12, 116, 105), Color.rgb(17, 73, 75)}
        );
        voiceFace.setCornerRadius(dp(18));
        voiceFace.setStroke(dp(2), Color.rgb(211, 174, 92));
        continuousExpenseButton.setBackground(voiceFace);
        continuousExpenseButton.setElevation(dp(7));
        continuousExpenseButton.setMinHeight(dp(62));
        continuousExpenseButton.setOnClickListener(v -> { if (continuousExpenseListening) toggleContinuousExpenseListening(); else runFeature("voice_entry", this::toggleContinuousExpenseListening); });
        content.addView(continuousExpenseButton, lp(-1, -2, 0, 3, 0, 6));

        TextView transcriptLabel = text("متن شنیده‌شده و فهرست موقت هزینه‌ها", 15, dark, true);
        content.addView(transcriptLabel, lp(-1, -2, 0, 2, 0, 3));
        continuousTranscriptView = text("هنوز متنی دریافت نشده است. برای یادآوری، «ثبت صوتی یادآوری» را بزن و صحبت کن.", 16, dark, false);
        continuousTranscriptView.setGravity(Gravity.RIGHT | Gravity.TOP);
        continuousTranscriptView.setMinHeight(dp(104));
        continuousTranscriptView.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable transcriptBox = round(Color.WHITE, 12);
        transcriptBox.setStroke(dp(1), Color.rgb(210, 216, 220));
        continuousTranscriptView.setBackground(transcriptBox);
        continuousTranscriptView.setTextColor(dark);
        continuousTranscriptView.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        continuousTranscriptView.setLineSpacing(dp(3), 1.08f);
        continuousTranscriptView.setTextIsSelectable(true);
        content.addView(continuousTranscriptView, lp(-1, -2, 0, 0, 0, 5));

        // Reminder transcript is intentionally hidden: voice input is unified in the
        // main expense capture box and classified by date words versus amount/quantity.
        reminderTranscriptView = text("برای ثبت یادآوری، کلید «ثبت صوتی یادآوری» را بزن. متن این بخش جدا از هزینه‌ها ذخیره می‌شود؛ در پایان بگو «ذخیره کن».", 16, Color.WHITE, false);
        reminderTranscriptView.setGravity(Gravity.RIGHT | Gravity.TOP);
        reminderTranscriptView.setMinHeight(dp(84));
        reminderTranscriptView.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable reminderTranscriptBox = round(Color.rgb(45, 62, 150), 12);
        reminderTranscriptBox.setStroke(dp(1), Color.rgb(210, 216, 220));
        reminderTranscriptView.setBackground(reminderTranscriptBox);
        reminderTranscriptView.setTextColor(Color.WHITE);
        reminderTranscriptView.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        reminderTranscriptView.setLineSpacing(dp(3), 1.08f);
        reminderTranscriptView.setTextIsSelectable(true);
        reminderTranscriptView.setVisibility(View.GONE);
        content.addView(reminderTranscriptView, lp(-1, 0, 0, 0, 0, 0));

        LinearLayout voiceReviewRow = new LinearLayout(this);
        voiceReviewRow.setOrientation(LinearLayout.HORIZONTAL);
        commitContinuousButton = button("تأیید و ثبت همه", Color.WHITE, dark, 13);
        commitContinuousButton.setOnClickListener(v -> commitPendingVoiceExpenses());
        commitContinuousButton.setVisibility(View.GONE);
        Button clearContinuousButton = button("پاک‌کردن فهرست", Color.WHITE, dark, 13);
        clearContinuousButton.setOnClickListener(v -> clearPendingVoiceExpenses());
        voiceReviewRow.addView(commitContinuousButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        voiceReviewRow.addView(clearContinuousButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        content.addView(voiceReviewRow, lp(-1, -2, 0, 0, 0, 10));

        // Budget control: bright coral-pink button with cumulative budget tracking.
        Button budgetButton = button("💗  بودجه", Color.rgb(244, 91, 130), Color.WHITE, 18);
        budgetButton.setMinHeight(dp(56));
        budgetButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable budgetFace = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{Color.rgb(255, 105, 145), Color.rgb(226, 45, 103), Color.rgb(196, 35, 91)}
        );
        budgetFace.setCornerRadius(dp(18));
        budgetFace.setStroke(dp(2), Color.rgb(255, 190, 205));
        budgetButton.setBackground(budgetFace);
        budgetButton.setElevation(dp(5));
        budgetButton.setOnClickListener(v -> runFeature("budget", this::showBudgetDialog));
        content.addView(budgetButton, lp(-1, -2, 0, 2, 0, 8));

        Button shoppingListButton = button("🛒 لیست خرید", Color.rgb(221, 245, 232), dark, 17);
        shoppingListButton.setMinHeight(dp(50));
        shoppingListButton.setOnClickListener(v -> runFeature("shopping_list", this::showShoppingList));
        content.addView(shoppingListButton, lp(-1, -2, 0, 2, 0, 8));

        TextView categoryTitle = text("دسته‌بندی هزینه‌ها", 18, dark, true);
        content.addView(categoryTitle, lp(-1,-2,0,2,0,4));
        String[][] categoryButtons = {
            {"🏠 خانه", "خانه"}, {"🚗 ماشین", "ماشین"}, {"🏪 مغازه", "مغازه"},
            {"🏫 مدرسه", "مدرسه"}, {"🏥 سلامت", "سلامت"}, {"📦 سایر", "سایر"}
        };
        for (int i = 0; i < categoryButtons.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < Math.min(i + 2, categoryButtons.length); j++) {
                final String category = categoryButtons[j][1];
                Button categoryButton = button(categoryButtons[j][0], Color.WHITE, dark, 15);
                categoryButton.setMinHeight(dp(42));
                categoryButton.setOnClickListener(v -> showCategorySubgroups(category));
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1);
                p.setMargins(dp(3), dp(2), dp(3), dp(2));
                row.addView(categoryButton, p);
            }
            content.addView(row, lp(-1,-2,0,0,0,0));
        }

        status = text("مثلاً بگو: «هزینه ۸۰ هزار تومان برای نان»", 15, dark, false);
        status.setGravity(Gravity.CENTER); status.setPadding(dp(8),dp(10),dp(8),dp(10));
        content.addView(status, lp(-1,-2,0,0,0,12));

        LinearLayout toolsRow = new LinearLayout(this);
        toolsRow.setOrientation(LinearLayout.HORIZONTAL);
        Button docsBtn = button("📄 مدارک", Color.WHITE, dark, 11);
        docsBtn.setOnClickListener(v -> runFeature("documents", this::showHomeDocuments));
        Button backupBtn = button("💾 پشتیبان", Color.WHITE, dark, 11);
        backupBtn.setOnClickListener(v -> runFeature("backup", this::showBackupOptions));
        Button chartBtn = button("📊 هزینه‌ها", Color.WHITE, dark, 11);
        chartBtn.setOnClickListener(v -> runFeature("expense_charts", this::showExpenseCharts));
        // Distinct but coordinated dashboard colors for the three utility buttons.
        Button[] toolButtons = {docsBtn, backupBtn, chartBtn};
        int[][] toolGradients = {
            {Color.rgb(82, 133, 190), Color.rgb(39, 72, 125)},   // Documents: refined steel blue
            {Color.rgb(125, 105, 174), Color.rgb(69, 54, 119)},  // Backup: muted royal violet
            {Color.rgb(43, 158, 151), Color.rgb(20, 99, 105)}   // Expenses: deep teal
        };
        int[] toolBorders = {
            Color.rgb(190, 218, 247),
            Color.rgb(207, 195, 239),
            Color.rgb(174, 229, 221)
        };
        for (int i = 0; i < toolButtons.length; i++) {
            Button b = toolButtons[i];
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(50), 1);
            bp.setMargins(dp(3), dp(2), dp(3), dp(2));
            b.setMinHeight(dp(50));
            b.setPadding(dp(4), dp(2), dp(4), dp(2));
            b.setMaxLines(2);
            b.setTextColor(Color.WHITE);
            b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            GradientDrawable toolFace = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                toolGradients[i]
            );
            toolFace.setCornerRadius(dp(16));
            toolFace.setStroke(dp(2), toolBorders[i]);
            b.setBackground(toolFace);
            b.setElevation(dp(4));
            toolsRow.addView(b, bp);
        }
        content.addView(toolsRow, lp(-1, -2, 0, 1, 0, 2));
        Button calendarBtn = button("🗓️ تقویم و آرشیو خریدها", Color.rgb(224, 240, 255), dark, 15);
        calendarBtn.setMinHeight(dp(48));
        calendarBtn.setOnClickListener(v -> showPurchaseCenter());
        content.addView(calendarBtn, lp(-1, -2, 0, 2, 0, 6));
        Button smartBtn = button("🧠 تحلیل هوشمند هزینه‌ها و راهکار صرفه‌جویی", Color.rgb(255, 244, 220), dark, 14);
        smartBtn.setMinHeight(dp(44));
        smartBtn.setOnClickListener(v -> runFeature("smart_analysis", this::showSmartExpenseAnalysis));
        content.addView(smartBtn, lp(-1, -2, 0, 2, 0, 4));
        // Keep every home-screen action reachable on short displays by scrolling the content.
        ScrollView homeScroll = new ScrollView(this);
        homeScroll.setFillViewport(false);
        homeScroll.setClipToPadding(false);
        homeScroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(homeScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView foot = text("نسخهٔ اولیه • اطلاعات روی همین گوشی ذخیره می‌شود", 12, Color.GRAY, false); foot.setGravity(Gravity.CENTER);
        root.addView(foot, lp(-1,-2,0,8,0,0));
        setContentView(root);
        refresh();
    }




    private String featureKeyForCategory(String category) {
        if ("خانه".equals(category)) return "home_expense";
        if ("ماشین".equals(category)) return "vehicle_expense";
        if ("مغازه".equals(category)) return "shop_expense";
        if ("مدرسه".equals(category)) return "school_expense";
        if ("سلامت".equals(category)) return "health_expense";
        return "other_expense";
    }

    private void runFeature(String featureKey, Runnable action) {
        OBAQuota.consume(this, featureKey, (allowed, used, error) -> runOnUiThread(() -> {
            if (error != null) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                return;
            }
            if (allowed) {
                action.run();
            } else {
                showQuotaDialog(featureKey);
            }
        }));
    }

    private void showQuotaDialog(String featureKey) {
        String featureName;
        switch (featureKey) {
            case "voice_entry": featureName = "ثبت صوتی"; break;
            case "budget": featureName = "بودجه"; break;
            case "shopping_list": featureName = "لیست خرید"; break;
            case "home_expense": featureName = "هزینه‌های خانه"; break;
            case "vehicle_expense": featureName = "هزینه‌های ماشین"; break;
            case "shop_expense": featureName = "هزینه‌های مغازه"; break;
            case "school_expense": featureName = "هزینه‌های مدرسه"; break;
            case "health_expense": featureName = "هزینه‌های سلامت"; break;
            case "other_expense": featureName = "هزینه‌های سایر"; break;
            case "documents": featureName = "مدارک خانه"; break;
            case "backup": featureName = "پشتیبان‌گیری"; break;
            case "expense_charts": featureName = "نمودار هزینه‌ها"; break;
            case "purchase_center": featureName = "تقویم و آرشیو خریدها"; break;
            case "smart_analysis": featureName = "تحلیل هوشمند"; break;
            default: featureName = "این قابلیت"; break;
        }
        new AlertDialog.Builder(this)
            .setTitle("پایان سهمیهٔ رایگان")
            .setMessage("۱۰ استفادهٔ رایگان از «" + featureName + "» تمام شده است. برای ادامه، حساب بساز یا وارد حساب خودت شو و اشتراک سه‌ماهه را فعال کن. سهمیهٔ قابلیت‌های دیگر جداگانه محاسبه می‌شود.")
            .setNegativeButton("بعداً", (d, w) -> d.dismiss())
            .setNeutralButton("ساخت حساب / ورود", (d, w) -> {
                startActivity(new Intent(this, OBAAuthActivity.class));
            })
            .setPositiveButton("اشتراک سه‌ماهه", (d, w) -> new AlertDialog.Builder(this)
                .setTitle("اشتراک اوبا")
                .setMessage("برای فعال‌سازی پرداخت واقعی بازار، شناسهٔ محصول اشتراک سه‌ماهه باید در تنظیمات فروشگاه و برنامه ثبت و تأیید شود.")
                .setPositiveButton("ساخت حساب / ورود", (d2, w2) -> startActivity(new Intent(this, OBAAuthActivity.class)))
                .setNegativeButton("بستن", null)
                .show())
            .show();
    }

    private void showBudgetDialog() {
        long currentBudget = Math.max(0L, prefs.getLong("home_budget", 0L));
        long spent = Math.max(0L, expenseTotal);
        long remaining = currentBudget - spent;

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(4), dp(8), dp(4));

        TextView summary = text("بودجهٔ کل: " + fmt(currentBudget) + " تومان\nهزینه‌های ثبت‌شده: "
                + fmt(spent) + " تومان\nماندهٔ بودجه: " + fmt(remaining) + " تومان",
                17, dark, true);
        summary.setGravity(Gravity.RIGHT);
        summary.setPadding(dp(12), dp(12), dp(12), dp(12));
        summary.setBackground(round(Color.rgb(255, 239, 244), 12));
        panel.addView(summary, lp(-1, -2, 0, 0, 0, 10));

        EditText amountInput = new EditText(this);
        amountInput.setSingleLine(true);
        amountInput.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        amountInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        amountInput.setHint("مبلغ جدید به تومان");
        panel.addView(amountInput, lp(-1, -2, 0, 0, 0, 4));

        Button clearAmountButton = button("پاک‌کردن مبلغ برای اصلاح", Color.rgb(255, 224, 233), Color.rgb(150, 25, 65), 14);
        clearAmountButton.setMinHeight(dp(44));
        clearAmountButton.setOnClickListener(v -> {
            amountInput.setText("");
            amountInput.setError(null);
            amountInput.requestFocus();
        });
        panel.addView(clearAmountButton, lp(-1, -2, 0, 2, 0, 4));

        Button deleteBudgetButton = button("🗑 پاک‌کردن کامل بودجهٔ ثبت‌شده", Color.rgb(190, 25, 75), Color.WHITE, 14);
        deleteBudgetButton.setMinHeight(dp(46));
        deleteBudgetButton.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("حذف کامل بودجه")
            .setMessage("آیا مطمئنی می‌خواهی بودجهٔ قبلی به‌طور کامل پاک شود؟ این کار هزینه‌های ثبت‌شده را حذف نمی‌کند.")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("بله، پاک کن", (confirmDialog, which) -> {
                prefs.edit().putLong("home_budget", 0L).apply();
                status.setText("بودجهٔ ثبت‌شده پاک شد. هزینه‌های قبلی همچنان محفوظ هستند.");
                Toast.makeText(this, "بودجهٔ قبلی پاک شد.", Toast.LENGTH_LONG).show();
                dialogRefreshAfterBudgetDelete();
            }).show());
        panel.addView(deleteBudgetButton, lp(-1, -2, 0, 2, 0, 4));

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("💗 بودجه")
            .setMessage("مبلغی وارد کن تا به بودجهٔ فعلی اضافه شود؛ مجموع جدید نمایش داده می‌شود.")
            .setView(panel)
            .setNegativeButton("بستن", null)
            .setNeutralButton("تنظیم از ابتدا", null)
            .setPositiveButton("افزودن به بودجه", null)
            .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                long amount;
                try { amount = Long.parseLong(amountInput.getText().toString().replace(",", "").replace("٬", "").trim()); }
                catch (Exception ex) { amount = 0L; }
                long old = Math.max(0L, prefs.getLong("home_budget", 0L));
                if (amount <= 0L || old > Long.MAX_VALUE - amount) {
                    amountInput.setError("مبلغ معتبر وارد کن");
                    return;
                }
                long total = old + amount;
                prefs.edit().putLong("home_budget", total).apply();
                long balance = total - expenseTotal;
                String msg = "مبلغ " + fmt(amount) + " تومان به بودجه اضافه شد. بودجهٔ کل: "
                        + fmt(total) + " تومان؛ مانده: " + fmt(balance) + " تومان.";
                status.setText(msg);
                Toast.makeText(this, "بودجهٔ کل: " + fmt(total) + " تومان", Toast.LENGTH_LONG).show();
                dialog.dismiss();
                refresh();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                long amount;
                try { amount = Long.parseLong(amountInput.getText().toString().replace(",", "").replace("٬", "").trim()); }
                catch (Exception ex) { amount = 0L; }
                if (amount <= 0L) { amountInput.setError("مبلغ معتبر وارد کن"); return; }
                prefs.edit().putLong("home_budget", amount).apply();
                status.setText("بودجه از ابتدا روی " + fmt(amount) + " تومان تنظیم شد.");
                dialog.dismiss();
                refresh();
            });
        });
        dialog.show();
    }

    private void dialogRefreshAfterBudgetDelete() {
        refresh();
    }

    private void showShoppingList() {
        String savedList = prefs.getString("oba_shopping_list", "").replace("\\n", "\n").trim();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(4), dp(8), dp(4));

        TextView current = text(savedList.isEmpty()
                ? "هنوز چیزی در لیست خرید نیست."
                : "🛒 فهرست فعلی:\n" + savedList, 16, dark, false);
        current.setGravity(Gravity.RIGHT | Gravity.TOP);
        current.setPadding(dp(10), dp(10), dp(10), dp(10));
        current.setBackground(round(Color.rgb(245, 249, 246), 10));
        panel.addView(current, lp(-1, -2, 0, 0, 0, 8));

        if (!savedList.isEmpty()) {
            Button deleteOneButton = button("🗑 حذف یک قلم از لیست", Color.rgb(255, 224, 233), Color.rgb(150, 25, 65), 14);
            deleteOneButton.setMinHeight(dp(44));
            deleteOneButton.setOnClickListener(v -> {
                String normalizedList = prefs.getString("oba_shopping_list", "").replace("\\n", "\n").trim();
                String[] items = normalizedList.isEmpty() ? new String[0] : normalizedList.split("\n");
                ArrayList<String> nonEmptyItems = new ArrayList<>();
                for (String item : items) if (!item.trim().isEmpty()) nonEmptyItems.add(item.trim());
                if (nonEmptyItems.isEmpty()) {
                    prefs.edit().remove("oba_shopping_list").apply();
                    current.setText("هنوز چیزی در لیست خرید نیست.");
                    deleteOneButton.setVisibility(View.GONE);
                    Toast.makeText(this, "لیست خرید خالی شد.", Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] labels = nonEmptyItems.toArray(new String[0]);
                new AlertDialog.Builder(this)
                    .setTitle("کدام قلم حذف شود؟")
                    .setItems(labels, (deleteDialog, which) -> {
                        nonEmptyItems.remove(which);
                        String updatedList = android.text.TextUtils.join("\n", nonEmptyItems);
                        if (updatedList.trim().isEmpty()) prefs.edit().remove("oba_shopping_list").apply();
                        else prefs.edit().putString("oba_shopping_list", updatedList).apply();
                        Toast.makeText(this, "قلم انتخاب‌شده حذف شد.", Toast.LENGTH_SHORT).show();
                        current.setText(updatedList.isEmpty()
                            ? "هنوز چیزی در لیست خرید نیست."
                            : "🛒 فهرست فعلی:\n" + updatedList);
                        if (updatedList.isEmpty()) deleteOneButton.setVisibility(View.GONE);
                    })
                    .setNegativeButton("انصراف", null)
                    .show();
            });
            panel.addView(deleteOneButton, lp(-1, -2, 0, 0, 0, 8));
        }

        EditText itemInput = new EditText(this);
        itemInput.setHint("اقلام خرید را اینجا بنویسید");
        itemInput.setGravity(Gravity.RIGHT | Gravity.TOP);
        itemInput.setMinLines(2);
        itemInput.setMaxLines(5);
        itemInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        panel.addView(itemInput, lp(-1, -2, 0, 0, 0, 8));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("🛒 لیست خرید")
                .setView(panel)
                .setPositiveButton("ثبت متن", (d, which) -> {
                    String entered = itemInput.getText().toString().trim();
                    if (!entered.isEmpty()) {
                        String existing = prefs.getString("oba_shopping_list", "").trim();
                        String updated = existing.isEmpty() ? entered : existing.replace("\\n", "\n") + "\n" + entered;
                        prefs.edit().putString("oba_shopping_list", updated).apply();
                        Toast.makeText(this, "اقلام به لیست خرید اضافه شد.", Toast.LENGTH_SHORT).show();
                    }
                    showShoppingList();
                })
                .setNeutralButton("🗑 پاک کردن لیست", null)
                .setNegativeButton("برگشت", (d, which) -> d.dismiss())
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextSize(13);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextSize(13);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextSize(13);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("پاک کردن لیست خرید")
                        .setMessage("همه اقلام لیست خرید پاک شوند؟")
                        .setNegativeButton("لغو", (confirm, which) -> { })
                        .setPositiveButton("پاک کن", (confirm, which) -> {
                            prefs.edit().remove("oba_shopping_list").apply();
                            Toast.makeText(this, "لیست خرید پاک شد.", Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                            showShoppingList();
                        })
                        .show();
            });
        });
        dialog.show();
    }

    private void handleShoppingListVoice(String spoken) {
        String item = spoken == null ? "" : spoken.trim();
        item = item.replaceAll("^[،,؛;\\s]+|[،,؛;\\s]+$", "");
        String compact = normalize(item).replace("‌", "").replace(" ", "")
                .replace("ي", "ی").replace("ك", "ک");
        if (item.isEmpty() || compact.equals("لیستخرید") || compact.equals("خرید")) {
            status.setText("نام کالا را واضح بگو؛ مثلاً «نان، شیر و تخم‌مرغ».");
            showShoppingList();
            return;
        }
        String existing = prefs.getString("oba_shopping_list", "").trim();
        String updated = existing.isEmpty() ? item : existing + "\n" + item;
        prefs.edit().putString("oba_shopping_list", updated).apply();
        status.setText("به لیست خرید اضافه شد: " + item);
        Toast.makeText(this, "به لیست خرید اضافه شد.", Toast.LENGTH_SHORT).show();
        showShoppingList();
    }

    private static final int[] CHART_COLORS = new int[]{Color.rgb(52,152,219), Color.rgb(46,204,113), Color.rgb(243,156,18), Color.rgb(155,89,182), Color.rgb(231,76,60), Color.rgb(26,188,156), Color.rgb(241,196,15)};

    private void showSmartExpenseAnalysis() {
        java.util.LinkedHashMap<String, Long> categories = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, Long> subgroups = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, Long> items = new java.util.LinkedHashMap<>();
        String[] names = new String[]{"خانه", "مدرسه", "ماشین", "مغازه", "سلامت", "سایر"};
        long all = 0;
        for (String name : names) {
            long amount = prefs.getLong("oba_chart_cat_" + name, 0L);
            if (amount > 0) { categories.put(name, amount); all += amount; }
            for (String subgroup : subgroupNames(name)) {
                long sub = prefs.getLong("oba_chart_sub_" + name + "_" + subgroup, 0L);
                if (sub > 0) subgroups.put(name + " / " + subgroup, sub);
            }
        }
        // Use only explicitly described, categorized purchases for item-level insights.
        for (String name : names) {
            for (String subgroup : subgroupNames(name)) {
                String prefix = "oba_smart_item_" + name + "_" + subgroup + "_";
                java.util.Map<String, ?> allPrefs = prefs.getAll();
                for (String key : allPrefs.keySet()) {
                    if (!key.startsWith(prefix)) continue;
                    Object value = allPrefs.get(key);
                    if (value instanceof Long && ((Long)value) > 0) {
                        String label = key.substring(prefix.length()).replace('_', ' ').trim();
                        items.put(name + " / " + label, ((Long)value));
                    }
                }
            }
        }
        if (categories.isEmpty() && subgroups.isEmpty() && prefs.getString("oba_expense_history", "").isEmpty()) {
            new AlertDialog.Builder(this).setTitle("🧠 تحلیل هوشمند هزینه‌ها")
                .setMessage("برای تحلیل دقیق، هزینه‌ها را از دکمه‌های دسته‌بندی ثبت کن و در شرح خرید نام مورد را هم بنویس؛ مثلاً «نان» یا «سینما». هرچه ثبت‌ها کامل‌تر باشد، پیشنهادها مفیدتر می‌شوند.")
                .setPositiveButton("متوجه شدم", null).show();
            return;
        }
        StringBuilder report = new StringBuilder();
        report.append("🧠 گزارش هزینه‌ها\n\n");
        if (all > 0) report.append("مجموع هزینه‌های دسته‌بندی‌شده: ").append(fmt(all)).append(" تومان\n\n");
        java.util.ArrayList<java.util.Map.Entry<String, Long>> ranked = new java.util.ArrayList<>(subgroups.entrySet());
        java.util.Collections.sort(ranked, (a,b) -> Long.compare(b.getValue(), a.getValue()));
        if (!ranked.isEmpty()) {
            report.append("بیشترین محل‌های خرج ثبت‌شده:\n");
            int limit = Math.min(5, ranked.size());
            for (int i=0; i<limit; i++) {
                java.util.Map.Entry<String,Long> e = ranked.get(i);
                double pct = all > 0 ? e.getValue()*100.0/all : 0;
                report.append("• ").append(e.getKey()).append(" — ").append(fmt(e.getValue())).append(" تومان");
                if (all > 0) report.append(" ( ").append(String.format(Locale.getDefault(), "%.1f", pct)).append("٪ از کل )");
                report.append("\n");
            }
            report.append("\n");
        }
        java.util.ArrayList<java.util.Map.Entry<String, Long>> topItems = new java.util.ArrayList<>(items.entrySet());
        java.util.Collections.sort(topItems, (a,b) -> Long.compare(b.getValue(), a.getValue()));
        if (!topItems.isEmpty()) {
            report.append("خریدهای دارای شرح که بیشترین مبلغ را دارند:\n");
            for (int i=0; i<Math.min(5, topItems.size()); i++) {
                java.util.Map.Entry<String,Long> e=topItems.get(i);
                report.append("• ").append(e.getKey()).append(" — ").append(fmt(e.getValue())).append(" تومان\n");
            }
            report.append("\n");
        }
        // Compare the last 30 days with the previous 30 days using dated records collected from now on.
        long now = System.currentTimeMillis();
        long day30 = 30L * 24L * 60L * 60L * 1000L;
        long currentStart = now - day30;
        long previousStart = now - 2L * day30;
        java.util.LinkedHashMap<String, Long> currentItems = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, Long> previousItems = new java.util.LinkedHashMap<>();
        String history = prefs.getString("oba_expense_history", "");
        for (String line : history.split("\\n")) {
            String[] fields = line.split("\\t", -1);
            if (fields.length < 5) continue;
            try {
                long time = Long.parseLong(fields[0]);
                long amount = Long.parseLong(fields[4]);
                String item = fields[3].trim();
                if (item.isEmpty() || amount <= 0) continue;
                String key = item.toLowerCase(Locale.ROOT);
                if (time >= currentStart && time <= now) currentItems.put(key, currentItems.containsKey(key) ? currentItems.get(key) + amount : amount);
                else if (time >= previousStart && time < currentStart) previousItems.put(key, previousItems.containsKey(key) ? previousItems.get(key) + amount : amount);
            } catch (Exception ignored) {}
        }
        java.util.ArrayList<String> rising = new java.util.ArrayList<>();
        for (String item : currentItems.keySet()) {
            long currentAmount = currentItems.get(item);
            long previousAmount = previousItems.containsKey(item) ? previousItems.get(item) : 0L;
            if (previousAmount > 0 && currentAmount >= previousAmount * 1.2d) {
                double increase = (currentAmount - previousAmount) * 100.0 / previousAmount;
                rising.add(item + " — " + fmt(currentAmount) + " تومان (افزایش " + String.format(Locale.getDefault(), "%.0f", increase) + "٪)");
            }
        }
        if (!rising.isEmpty()) {
            report.append("⚠️ مواردی که در ۳۰ روز اخیر بیشتر خرج داشته‌اند (نسبت به ۳۰ روز قبل):\n");
            for (int i = 0; i < Math.min(5, rising.size()); i++) report.append("• ").append(rising.get(i)).append("\n");
            report.append("\n");
        } else if (!history.trim().isEmpty()) {
            report.append("📅 مقایسهٔ ۳۰ روزه: هنوز موردی با افزایش قابل‌مقایسه پیدا نشد؛ برای نتیجهٔ بهتر، ثبت هزینه‌ها را ادامه بده.\n\n");
        } else {
            report.append("📅 مقایسهٔ زمانی از امروز شروع می‌شود؛ هزینه‌های جدید با تاریخ ذخیره می‌شوند تا در آینده افزایش خرج قابل‌شناسایی باشد.\n\n");
        }
        report.append("💡 پیشنهادهای صرفه‌جویی:\n")
            .append("• برای نان و خوراکی‌ها، خرید روزانه را با نیاز واقعی خانواده هماهنگ کن تا دورریز کم شود.\n")
            .append("• برای سینما و تفریح، یک سقف ماهانه تعیین کن و پیش از خرید بلیت، هزینهٔ کل خانواده را بررسی کن.\n")
            .append("• هزینه‌های تکراری را هفته‌ای یک‌بار مرور کن و برای هر مورد سقف قابل‌قبول بگذار.\n\n")
            .append("توجه: این گزارش بر اساس مبالغ ثبت‌شده است؛ تا وقتی سابقهٔ زمانی کافی وجود نداشته باشد، اوبا نمی‌تواند با قطعیت بگوید خرجی نسبت به ماه قبل زیاد شده است. برای هشدار دقیق‌تر، نام خرید و مبلغ را در شرح هزینه ثبت کن.");
        new AlertDialog.Builder(this).setTitle("🧠 تحلیل هوشمند هزینه‌ها")
            .setMessage(report.toString()).setPositiveButton("باشه", null).show();
    }

    private void showExpenseCharts() {
        java.util.LinkedHashMap<String, Long> categories = new java.util.LinkedHashMap<>();
        String[] names = new String[]{"خانه", "مدرسه", "ماشین", "مغازه", "سلامت", "سایر"};
        for (String name : names) {
            long value = prefs.getLong("oba_chart_cat_" + name, 0L);
            if (value > 0) categories.put(name, value);
        }
        if (categories.isEmpty()) {
            // Older records predate chart totals; use visible saved entries as a best-effort starting point.
            for (String row : entries) {
                String[] parts = row.split("\\s*•\\s*");
                if (parts.length < 3) continue;
                String category = parts[0].trim();
                if (!java.util.Arrays.asList(names).contains(category)) continue;
                long amount = parseChartAmount(parts[parts.length - 1]);
                if (amount > 0) categories.put(category, categories.containsKey(category) ? categories.get(category) + amount : amount);
            }
        }
        if (categories.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("نمودار هزینه‌ها")
                .setMessage("هنوز هزینهٔ دسته‌بندی‌شده‌ای برای نمایش نمودار ثبت نشده است. از دکمه‌های دسته‌بندی خانه، مدرسه یا ماشین هزینه ثبت کن.")
                .setPositiveButton("باشه", null).show();
            return;
        }
        showPieDialog("سهم هر بخش از کل هزینه‌ها", categories, null);
    }

    private long parseChartAmount(String value) {
        String digits = value.replaceAll("[^0-9۰-۹٠-٩]", "").replace('۰','0').replace('۱','1').replace('۲','2').replace('۳','3').replace('۴','4').replace('۵','5').replace('۶','6').replace('۷','7').replace('۸','8').replace('۹','9').replace('٠','0').replace('١','1').replace('٢','2').replace('٣','3').replace('٤','4').replace('٥','5').replace('٦','6').replace('٧','7').replace('٨','8').replace('٩','9');
        try { return Long.parseLong(digits); } catch (Exception e) { return 0; }
    }

    private void showPieDialog(String title, java.util.LinkedHashMap<String, Long> values, String parentCategory) {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(12), dp(8), dp(12), dp(4));
        long total = 0; for (Long v : values.values()) total += v;
        TextView totalText = text("مجموع: " + fmt(total) + " تومان", 15, dark, true); totalText.setGravity(Gravity.CENTER);
        panel.addView(totalText, lp(-1, -2, 0, 0, 0, 4));
        PieChartView chart = new PieChartView(values, total, name -> {
            if (parentCategory != null) return;
            java.util.LinkedHashMap<String, Long> subs = new java.util.LinkedHashMap<>();
            String[] known = subgroupNames(name);
            for (String subgroup : known) {
                long amount = prefs.getLong("oba_chart_sub_" + name + "_" + subgroup, 0L);
                if (amount > 0) subs.put(subgroup, amount);
            }
            if (!subs.isEmpty()) showPieDialog("جزئیات «" + name + "»", subs, name);
            else Toast.makeText(this, "برای این بخش هنوز جزئیات زیرگروه ثبت‌شده‌ای نیست.", Toast.LENGTH_SHORT).show();
        });
        panel.addView(chart, new LinearLayout.LayoutParams(-1, dp(250)));
        for (java.util.Map.Entry<String, Long> e : values.entrySet()) {
            double pct = total == 0 ? 0 : e.getValue() * 100.0 / total;
            TextView legend = text("●  " + e.getKey() + "   " + String.format(Locale.getDefault(), "%.1f%%", pct) + "   •   " + fmt(e.getValue()) + " تومان", 14, dark, false);
            legend.setTextColor(CHART_COLORS[Math.abs(e.getKey().hashCode()) % CHART_COLORS.length]);
            panel.addView(legend, lp(-1, -2, 0, 2, 0, 2));
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(panel)
            .setNegativeButton(parentCategory == null ? "بستن" : "بازگشت به نمودار اصلی", (d, w) -> { if (parentCategory != null) showExpenseCharts(); }).create();
        dialog.show();
    }

    private String[] subgroupNames(String category) {
        // This bucket is also used for dashboard/voice expenses that have no explicit subgroup.
        // Keep it available in every category so dated calendar entries can be opened in the archive.
        if ("خانه".equals(category)) return new String[]{"مواد غذایی", "لوازم خانه", "قبض و خدمات", "اجاره و تعمیرات", "نظافت", "سایر هزینه‌های خانه", "خریدهای روزانه"};
        if ("ماشین".equals(category)) return new String[]{"سوخت", "روغن و فیلتر", "تسمه تایم و تعمیرات", "بیمه", "لاستیک", "پارکینگ و عوارض", "سایر هزینه‌های ماشین", "خریدهای روزانه"};
        if ("مدرسه".equals(category)) return new String[]{"شهریه", "کتاب و لوازم‌التحریر", "رفت‌وآمد", "لباس فرم", "فعالیت‌ها", "سایر هزینه‌های مدرسه", "خریدهای روزانه"};
        if ("مغازه".equals(category)) return new String[]{"خرید کالا", "اجاره مغازه", "قبض و خدمات", "حقوق", "تعمیرات", "سایر هزینه‌های مغازه", "خریدهای روزانه"};
        if ("سلامت".equals(category)) return new String[]{"دکتر", "دارو", "آزمایش", "بیمه درمانی", "دندان‌پزشکی", "سایر هزینه‌های سلامت", "خریدهای روزانه"};
        return new String[]{"خرید متفرقه", "هدیه", "سفر", "سایر", "خریدهای روزانه"};
    }

    private class PieChartView extends View {
        private final java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        private final java.util.ArrayList<Long> amounts = new java.util.ArrayList<>();
        private final long total;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final java.util.function.Consumer<String> onSlice;
        private final android.graphics.RectF oval = new android.graphics.RectF();
        private float radius, cx, cy;
        PieChartView(java.util.LinkedHashMap<String, Long> data, long sum, java.util.function.Consumer<String> click) {
            super(MainActivity.this); total = sum; onSlice = click;
            for (java.util.Map.Entry<String, Long> e : data.entrySet()) { labels.add(e.getKey()); amounts.add(e.getValue()); }
        }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas); if (total <= 0 || amounts.isEmpty()) return;
            radius = Math.min(getWidth() * 0.38f, getHeight() * 0.42f); cx = getWidth() / 2f; cy = getHeight() / 2f;
            oval.set(cx-radius, cy-radius, cx+radius, cy+radius);
            float start = -90f;
            for (int i=0; i<amounts.size(); i++) {
                float sweep = (float)(amounts.get(i) * 360.0 / total);
                paint.setColor(CHART_COLORS[i % CHART_COLORS.length]); paint.setStyle(Paint.Style.FILL);
                canvas.drawArc(oval, start, sweep, true, paint);
                if (sweep >= 18) {
                    double mid = Math.toRadians(start + sweep/2f); float tx = cx + (float)Math.cos(mid)*radius*0.62f; float ty = cy + (float)Math.sin(mid)*radius*0.62f;
                    paint.setColor(Color.WHITE); paint.setTextSize(dp(12)); paint.setTypeface(Typeface.DEFAULT_BOLD); paint.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText(String.format(Locale.getDefault(), "%.0f%%", amounts.get(i)*100.0/total), tx, ty, paint);
                }
                start += sweep;
            }
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent event) {
            if (event.getAction() != android.view.MotionEvent.ACTION_UP || radius <= 0) return true;
            float dx=event.getX()-cx, dy=event.getY()-cy; if (Math.sqrt(dx*dx+dy*dy)>radius) return true;
            double angle = Math.toDegrees(Math.atan2(dy,dx)) + 90; if (angle < 0) angle += 360;
            double cursor=0; for (int i=0;i<amounts.size();i++) { cursor += amounts.get(i)*360.0/total; if (angle <= cursor) { onSlice.accept(labels.get(i)); break; } }
            return true;
        }
    }

    private void showBackupOptions() {
        new AlertDialog.Builder(this).setTitle("پشتیبان‌گیری")
            .setItems(new String[]{"ساخت فایل پشتیبان", "بازیابی فایل پشتیبان"}, (d, which) -> {
                if (which == 0) createBackup(); else chooseBackup();
            }).setNegativeButton("بستن", null).show();
    }

    // Convert Gregorian dates to the Jalali (Solar Hijri) calendar for Persian display.
    private int[] gregorianToJalali(int gy, int gm, int gd) {
        int[] gdm = {0,31,((gy%4==0 && gy%100!=0)||gy%400==0)?29:28,31,30,31,30,31,31,30,31,30,31};
        int jy = (gy <= 1600) ? 0 : 979;
        gy -= (gy <= 1600) ? 621 : 1600;
        int days = 365 * gy + ((gy + 3) / 4) - ((gy + 99) / 100) + ((gy + 399) / 400);
        for (int i = 0; i < gm; i++) days += gdm[i];
        days += gd - 1;
        int jdn = days - 79;
        int jnp = jdn / 12053;
        jdn %= 12053;
        jy += 33 * jnp + 4 * (jdn / 1461);
        jdn %= 1461;
        if (jdn >= 366) {
            jy += (jdn - 1) / 365;
            jdn = (jdn - 1) % 365;
        }
        int jm, jd;
        if (jdn < 186) { jm = 1 + jdn / 31; jd = 1 + jdn % 31; }
        else { jm = 7 + (jdn - 186) / 30; jd = 1 + (jdn - 186) % 30; }
        return new int[]{jy, jm, jd};
    }

    private int jalaliMonthLength(int jy, int jm) {
        if (jm <= 6) return 31;
        if (jm <= 11) return 30;
        // Leap-year check in the 33-year Jalali cycle, adequate for calendar navigation.
        int r = jy % 33;
        return (r == 1 || r == 5 || r == 9 || r == 13 || r == 17 || r == 22 || r == 26 || r == 30) ? 30 : 29;
    }

    private String jalaliDateText(int gy, int gm, int gd) {
        int[] j = gregorianToJalali(gy, gm, gd);
        String[] months = {"فروردین","اردیبهشت","خرداد","تیر","مرداد","شهریور","مهر","آبان","آذر","دی","بهمن","اسفند"};
        return j[2] + " " + months[j[1]-1] + " " + j[0];
    }

    private String jalaliDatePartsText(int jy, int jm, int jd) {
        String[] months = {"فروردین","اردیبهشت","خرداد","تیر","مرداد","شهریور","مهر","آبان","آذر","دی","بهمن","اسفند"};
        return jd + " " + months[jm - 1] + " " + jy;
    }

    private void showExpenseCalendar() {
        Calendar today = Calendar.getInstance();
        int[] jToday = gregorianToJalali(today.get(Calendar.YEAR), today.get(Calendar.MONTH)+1, today.get(Calendar.DAY_OF_MONTH));
        String[] months = {"فروردین","اردیبهشت","خرداد","تیر","مرداد","شهریور","مهر","آبان","آذر","دی","بهمن","اسفند"};
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(12), dp(18), dp(12));
        TextView prompt = new TextView(this);
        prompt.setText("تاریخ شمسی را انتخاب کن");
        prompt.setTextSize(16);
        prompt.setGravity(Gravity.CENTER);
        panel.addView(prompt, lp(-1, -2, 0, 0, 0, 10));
        android.widget.Spinner monthSpinner = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> monthAdapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, months);
        monthSpinner.setAdapter(monthAdapter);
        monthSpinner.setSelection(jToday[1]-1);
        panel.addView(monthSpinner, lp(-1, -2, 0, 0, 0, 8));
        android.widget.EditText yearInput = new android.widget.EditText(this);
        yearInput.setSingleLine(true);
        yearInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        yearInput.setHint("سال شمسی، مثل " + jToday[0]);
        yearInput.setText(String.valueOf(jToday[0]));
        panel.addView(yearInput, lp(-1, -2, 0, 0, 0, 8));
        android.widget.Spinner daySpinner = new android.widget.Spinner(this);
        panel.addView(daySpinner, lp(-1, -2, 0, 0, 0, 8));
        Runnable refreshDays = () -> {
            int year;
            try { year = Integer.parseInt(yearInput.getText().toString().trim()); } catch (Exception e) { year = jToday[0]; }
            int month = monthSpinner.getSelectedItemPosition() + 1;
            int count = jalaliMonthLength(year, month);
            String[] days = new String[count];
            for (int i=0; i<count; i++) days[i] = String.valueOf(i+1);
            android.widget.ArrayAdapter<String> dayAdapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, days);
            daySpinner.setAdapter(dayAdapter);
            if (year == jToday[0] && month == jToday[1]) daySpinner.setSelection(Math.min(jToday[2]-1, count-1));
        };
        monthSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { refreshDays.run(); }
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        yearInput.setOnFocusChangeListener((v, hasFocus) -> { if (!hasFocus) refreshDays.run(); });
        refreshDays.run();
        new AlertDialog.Builder(this).setTitle("تقویم شمسی مخارج")
            .setView(panel)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("نمایش مخارج", (dialog, which) -> {
                int jy;
                try { jy = Integer.parseInt(yearInput.getText().toString().trim()); } catch (Exception e) { jy = jToday[0]; }
                int jm = monthSpinner.getSelectedItemPosition()+1;
                int jd = daySpinner.getSelectedItemPosition()+1;
                // Convert Jalali selection to a Gregorian day by searching nearby dates.
                Calendar probe = Calendar.getInstance();
                probe.set(jy+621, Math.max(0, jm-1), jd, 0, 0, 0);
                probe.set(Calendar.MILLISECOND, 0);
                Calendar found = null;
                for (int offset=-220; offset<=220; offset++) {
                    Calendar candidate = (Calendar) probe.clone();
                    candidate.add(Calendar.DAY_OF_MONTH, offset);
                    int[] j = gregorianToJalali(candidate.get(Calendar.YEAR), candidate.get(Calendar.MONTH)+1, candidate.get(Calendar.DAY_OF_MONTH));
                    if (j[0]==jy && j[1]==jm && j[2]==jd) { found = candidate; break; }
                }
                if (found == null) { Toast.makeText(this, "تاریخ شمسی معتبر نیست", Toast.LENGTH_SHORT).show(); return; }
                Calendar selected = (Calendar) found.clone();
                selected.set(Calendar.HOUR_OF_DAY, 0); selected.set(Calendar.MINUTE, 0); selected.set(Calendar.SECOND, 0); selected.set(Calendar.MILLISECOND, 0);
                long start = selected.getTimeInMillis();
                selected.add(Calendar.DAY_OF_MONTH, 1);
                long end = selected.getTimeInMillis();
                String history = prefs.getString("oba_expense_history", "");
                java.util.LinkedHashMap<String, Long> totalsByCategory = new java.util.LinkedHashMap<>();
                java.util.LinkedHashMap<String, java.util.List<String>> detailsByCategory = new java.util.LinkedHashMap<>();
                String[] categories = {"خانه", "مدرسه", "ماشین", "مغازه", "سلامت", "سایر"};
                for (String category : categories) { totalsByCategory.put(category, 0L); detailsByCategory.put(category, new java.util.ArrayList<>()); }
                long dayTotal = 0L;
                if (!history.trim().isEmpty()) for (String row : history.split("\\n")) {
                    String[] parts = row.split("\\t", -1);
                    if (parts.length < 5) continue;
                    try {
                        long timestamp = Long.parseLong(parts[0]);
                        if (timestamp < start || timestamp >= end) continue;
                        String category = parts[1].trim();
                        if (!totalsByCategory.containsKey(category)) category = "سایر";
                        long amount = Long.parseLong(parts[4]);
                        totalsByCategory.put(category, totalsByCategory.get(category) + amount); dayTotal += amount;
                        String subgroup = parts[2].trim(), item = parts[3].trim();
                        detailsByCategory.get(category).add((subgroup.isEmpty() ? "" : subgroup + " — ") + item + " : " + fmt(amount) + " تومان");
                    } catch (Exception ignored) {}
                }
                LinearLayout reportCards = new LinearLayout(this);
                reportCards.setOrientation(LinearLayout.VERTICAL);
                reportCards.setPadding(16, 8, 16, 8);
                TextView totalCard = new TextView(this);
                totalCard.setText("مجموع مخارج: " + fmt(dayTotal) + " تومان");
                totalCard.setTextSize(20);
                totalCard.setTypeface(null, Typeface.BOLD);
                totalCard.setTextColor(Color.WHITE);
                totalCard.setGravity(Gravity.CENTER);
                totalCard.setPadding(18, 18, 18, 18);
                GradientDrawable totalBg = new GradientDrawable();
                totalBg.setColor(Color.rgb(25, 55, 110));
                totalBg.setCornerRadius(18);
                totalCard.setBackground(totalBg);
                LinearLayout.LayoutParams totalParams = new LinearLayout.LayoutParams(-1, -2);
                totalParams.setMargins(0, 0, 0, 12);
                reportCards.addView(totalCard, totalParams);
                for (String category : categories) {
                    LinearLayout categoryCard = new LinearLayout(this);
                    categoryCard.setOrientation(LinearLayout.VERTICAL);
                    categoryCard.setPadding(16, 12, 16, 12);
                    GradientDrawable cardBg = new GradientDrawable();
                    cardBg.setColor(Color.rgb(245, 248, 255));
                    cardBg.setCornerRadius(14);
                    cardBg.setStroke(1, Color.rgb(205, 215, 235));
                    categoryCard.setBackground(cardBg);
                    TextView categoryTitle = new TextView(this);
                    categoryTitle.setText(category + "  •  " + fmt(totalsByCategory.get(category)) + " تومان");
                    categoryTitle.setTextSize(18);
                    categoryTitle.setTypeface(null, Typeface.BOLD);
                    categoryTitle.setTextColor(Color.rgb(25, 55, 110));
                    categoryTitle.setGravity(Gravity.RIGHT);
                    categoryCard.addView(categoryTitle);
                    for (String detail : detailsByCategory.get(category)) {
                        TextView detailView = new TextView(this);
                        detailView.setText("• " + detail);
                        detailView.setTextSize(16);
                        detailView.setTextColor(Color.DKGRAY);
                        detailView.setGravity(Gravity.RIGHT);
                        detailView.setPadding(0, 8, 0, 4);
                        categoryCard.addView(detailView);
                    }
                    LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
                    cardParams.setMargins(0, 0, 0, 10);
                    reportCards.addView(categoryCard, cardParams);
                }
                if (dayTotal == 0) {
                    TextView empty = new TextView(this);
                    empty.setText("برای این روز هزینه‌ای ثبت نشده است.");
                    empty.setTextSize(16);
                    empty.setGravity(Gravity.CENTER);
                    reportCards.addView(empty);
                }

                // Daily reminders live inside the selected calendar day.
                TextView reminderHeading = new TextView(this);
                reminderHeading.setText("🔔 یادآوری‌های این روز");
                reminderHeading.setTextSize(19);
                reminderHeading.setTypeface(null, Typeface.BOLD);
                reminderHeading.setTextColor(Color.rgb(25, 55, 110));
                reminderHeading.setPadding(0, dp(12), 0, dp(6));
                reportCards.addView(reminderHeading);
                java.util.ArrayList<String> dayReminderRows = new java.util.ArrayList<>();
                for (String key : prefs.getAll().keySet()) {
                    if (!key.startsWith("reminder_note_")) continue;
                    try {
                        String value = prefs.getString(key, "");
                        String[] noteParts = value.split("\\n", 2);
                        if (noteParts.length < 2) continue;
                        long noteWhen = Long.parseLong(noteParts[0]);
                        if (noteWhen >= start && noteWhen < end)
                            dayReminderRows.add(formatPersianDateTime(new Date(noteWhen)) + " — " + noteParts[1].trim());
                    } catch (Exception ignored) {}
                }
                if (dayReminderRows.isEmpty()) {
                    TextView noReminder = new TextView(this);
                    noReminder.setText("برای این روز یادآوری ثبت نشده است.");
                    noReminder.setTextSize(15);
                    noReminder.setTextColor(Color.DKGRAY);
                    reportCards.addView(noReminder);
                } else {
                    for (String reminderText : dayReminderRows) {
                        TextView reminderLine = new TextView(this);
                        reminderLine.setText("• " + reminderText);
                        reminderLine.setTextSize(15);
                        reminderLine.setTextColor(Color.DKGRAY);
                        reminderLine.setPadding(0, dp(5), 0, dp(5));
                        reportCards.addView(reminderLine);
                    }
                }
                final int selectedJalaliYear = jy;
                final int selectedJalaliMonth = jm;
                final int selectedJalaliDay = jd;
                final long selectedDayStart = start;
                Button addReminderButton = button("＋ افزودن یادآوری برای این روز", Color.rgb(255, 239, 205), dark, 14);
                addReminderButton.setOnClickListener(v -> runFeature("reminder", () -> showAddCalendarReminder(selectedJalaliYear, selectedJalaliMonth, selectedJalaliDay, selectedDayStart)));
                reportCards.addView(addReminderButton, lp(-1, dp(48), 0, dp(8), 0, dp(8)));
                Button reminderArchiveButton = button("🗂️ آرشیو یادآوری‌ها", Color.rgb(224, 240, 255), dark, 13);
                reminderArchiveButton.setOnClickListener(v -> runFeature("reminder_archive", this::showReminderArchive));
                reportCards.addView(reminderArchiveButton, lp(-1, dp(44), 0, 0, 0, dp(8)));

                ScrollView reportScroll = new ScrollView(this);
                reportScroll.addView(reportCards);
                new AlertDialog.Builder(this)
                    .setTitle("مخارج " + jalaliDatePartsText(jy, jm, jd))
                    .setView(reportScroll)
                    .setPositiveButton("باشه", null)
                    .setNeutralButton("روز دیگر", (d,w) -> showExpenseCalendar())
                    .show();
            }).show();
    }

    private void showAddCalendarReminder(int jy, int jm, int jd, long dayStart) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(8), dp(18), dp(4));
        android.widget.EditText noteInput = new android.widget.EditText(this);
        noteInput.setHint("متن یادآوری");
        noteInput.setMinLines(2);
        form.addView(noteInput, lp(-1, -2, 0, 0, 0, dp(8)));
        android.widget.EditText timeInput = new android.widget.EditText(this);
        timeInput.setHint("ساعت (00:00)");
        timeInput.setSingleLine(true);
        timeInput.setInputType(android.text.InputType.TYPE_CLASS_DATETIME);
        form.addView(timeInput, lp(-1, -2, 0, 0, 0, dp(8)));

        new AlertDialog.Builder(this).setTitle("یادآوری " + jalaliDatePartsText(jy, jm, jd))
            .setView(form).setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره", (d,w) -> {
                String note = noteInput.getText().toString().trim();
                if (note.isEmpty()) {
                    Toast.makeText(this, "متن یادآوری را وارد کن.", Toast.LENGTH_SHORT).show();
                    return;
                }
                Calendar due = Calendar.getInstance();
                due.setTimeInMillis(dayStart);
                String time = timeInput.getText().toString().trim()
                    .replace('۰','0').replace('۱','1').replace('۲','2').replace('۳','3')
                    .replace('۴','4').replace('۵','5').replace('۶','6').replace('۷','7')
                    .replace('۸','8').replace('۹','9');
                if (!time.isEmpty()) {
                    try {
                        String[] hm;
                        if (time.matches("[0-9]{4}")) {
                            hm = new String[]{time.substring(0, 2), time.substring(2, 4)};
                        } else {
                            hm = time.split(":");
                        }
                        if (hm.length != 2 || hm[0].trim().length() < 1 || hm[0].trim().length() > 2
                                || hm[1].trim().length() < 1 || hm[1].trim().length() > 2) throw new IllegalArgumentException();
                        int hour = Integer.parseInt(hm[0].trim());
                        int minute = Integer.parseInt(hm[1].trim());
                        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) throw new IllegalArgumentException();
                        due.set(Calendar.HOUR_OF_DAY, hour);
                        due.set(Calendar.MINUTE, minute);
                    } catch (Exception ex) {
                        Toast.makeText(this, "ساعت را به شکل 00:00 وارد کن؛ مثلاً 20:30 یا 2030.", Toast.LENGTH_LONG).show();
                        return;
                    }
                } else {
                    due.set(Calendar.HOUR_OF_DAY, 9);
                    due.set(Calendar.MINUTE, 0);
                }
                due.set(Calendar.SECOND, 0);
                due.set(Calendar.MILLISECOND, 0);
                long when = due.getTimeInMillis();
                if (when <= System.currentTimeMillis()) {
                    Toast.makeText(this, "زمان یادآوری باید در آینده باشد؛ ساعت را اصلاح کن.", Toast.LENGTH_LONG).show();
                    return;
                }
                String title = note.replaceAll("\\s+", " ").trim();
                int reminderId = (int) (System.currentTimeMillis() & 0x7fffffff);
                if (reminderId == 0) reminderId = 1;
                while (prefs.contains("reminder_" + reminderId)) {
                    reminderId = reminderId == Integer.MAX_VALUE ? 1 : reminderId + 1;
                }
                String savedReminder = when + "\nonce\n" + title + "\n-1";
                // Keep the archive entry and scheduled alarm in sync.
                prefs.edit()
                    .putString("reminder_" + reminderId, savedReminder)
                    .putString("reminder_note_" + when + "_" + reminderId, when + "\n" + note)
                    .apply();
                ReminderReceiver.schedule(this, reminderId, when, title, false);
                ensureExactAlarmAccess();
                if (Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 45);
                }
                refreshReminders();
                Toast.makeText(this, "یادآوری ذخیره شد؛ آلارم یک ساعت قبل پخش می‌شود.", Toast.LENGTH_LONG).show();
                showExpenseCalendar();
            }).show();
    }

    private void confirmClearArchive(String title, String message, Runnable clearAction) {
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("پاک کن", (dialog, which) -> {
                clearAction.run();
                Toast.makeText(this, "آرشیو پاک شد.", Toast.LENGTH_LONG).show();
            })
            .setNegativeButton("انصراف", null)
            .show();
    }

    private void clearPurchaseArchives() {
        prefs.edit()
            .remove("oba_expense_history")
            .remove("entries")
            .apply();
        entries.clear();
        refresh();
    }

    private void clearReminderArchive() {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("reminder_note_")) editor.remove(key);
        }
        editor.apply();
        refreshReminders();
    }

    private void showExpenseArchive() {
        String all = prefs.getString("entries", "");
        if (all.trim().isEmpty()) {
            new AlertDialog.Builder(this).setTitle("آرشیو هزینه‌ها")
                .setMessage("هنوز موردی ثبت نشده است. از دسته‌بندی‌ها هزینه ثبت کن.")
                .setPositiveButton("باشه", null).show();
            return;
        }
        String[] rows = all.split("\\n");
        String[] display = new String[rows.length];
        for (int i = 0; i < rows.length; i++) display[i] = rows[i];
        new AlertDialog.Builder(this).setTitle("آرشیو ثبت‌ها")
            .setItems(display, (d, which) -> new AlertDialog.Builder(this)
                .setTitle("جزئیات ثبت").setMessage(display[which])
                .setPositiveButton("باشه", null).show())
            .setNegativeButton("بستن", null).show();
    }

    private void showAudioArchive() {
        File folder = new File(getFilesDir(), "audio_records");
        if (!folder.exists()) folder.mkdirs();
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".3gp"));
        if (files == null || files.length == 0) {
            new AlertDialog.Builder(this).setTitle("آرشیو صداها")
                .setMessage("هنوز صدایی ضبط نشده است. برای ضبط، دکمهٔ زیر را بزن.")
                .setPositiveButton("ضبط صدای جدید", (d, w) -> startAudioRecording())
                .setNegativeButton("بستن", null).show();
            return;
        }
        String[] labels = new String[files.length + 1];
        labels[0] = "🎙 ضبط صدای جدید";
        for (int i = 0; i < files.length; i++) labels[i + 1] = formatPersianDateTime(new Date(files[i].lastModified())) + " • " + files[i].getName();
        new AlertDialog.Builder(this).setTitle("آرشیو صداهای ذخیره‌شده")
            .setItems(labels, (d, which) -> {
                if (which == 0) { startAudioRecording(); return; }
                File chosen = files[which - 1];
                new AlertDialog.Builder(this).setTitle("پخش فایل صوتی")
                    .setMessage(labels[which]).setPositiveButton("پخش", (dd, ww) -> playAudioFile(chosen))
                    .setNegativeButton("حذف", (dd, ww) -> {
                        if (chosen.delete()) Toast.makeText(this, "فایل صوتی حذف شد.", Toast.LENGTH_SHORT).show();
                        else Toast.makeText(this, "حذف فایل ممکن نشد.", Toast.LENGTH_SHORT).show();
                    }).setNeutralButton("بستن", null).show();
            }).setNegativeButton("بستن", null).show();
    }

    private android.media.MediaRecorder recorder;
    private File currentAudioFile;
    private void startAudioRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
            Toast.makeText(this, "پس از اجازهٔ میکروفون، دوباره آرشیو صداها را باز کن.", Toast.LENGTH_LONG).show();
            return;
        }
        if (recorder != null) {
            Toast.makeText(this, "ضبط دیگری در حال اجراست.", Toast.LENGTH_SHORT).show(); return;
        }
        File folder = new File(getFilesDir(), "audio_records");
        if (!folder.exists()) folder.mkdirs();
        currentAudioFile = new File(folder, "OBA_audio_" + System.currentTimeMillis() + ".3gp");
        try {
            recorder = Build.VERSION.SDK_INT >= 31 ? new android.media.MediaRecorder(this) : new android.media.MediaRecorder();
            recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.THREE_GPP);
            recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AMR_NB);
            recorder.setOutputFile(currentAudioFile.getAbsolutePath());
            recorder.prepare(); recorder.start();
            new AlertDialog.Builder(this).setTitle("ضبط صدا")
                .setMessage("ضبط فعال است. برای پایان ضبط، «پایان و ذخیره» را بزن.")
                .setCancelable(false)
                .setPositiveButton("پایان و ذخیره", (d, w) -> stopAudioRecording())
                .setNegativeButton("لغو ضبط", (d, w) -> { stopAudioRecording(); if (currentAudioFile != null) currentAudioFile.delete(); })
                .show();
        } catch (Exception e) {
            releaseRecorder();
            Toast.makeText(this, "شروع ضبط صدا ممکن نشد؛ مجوز میکروفون را بررسی کن.", Toast.LENGTH_LONG).show();
        }
    }
    private void stopAudioRecording() {
        try {
            if (recorder != null) { recorder.stop(); recorder.reset(); recorder.release(); recorder = null; }
            if (currentAudioFile != null && currentAudioFile.isFile() && currentAudioFile.length() > 0)
                Toast.makeText(this, "صدا در آرشیو خصوصی برنامه ذخیره شد.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            if (currentAudioFile != null) currentAudioFile.delete();
            Toast.makeText(this, "ضبط خیلی کوتاه بود یا ذخیره صدا انجام نشد.", Toast.LENGTH_LONG).show();
        } finally { releaseRecorder(); }
    }
    private void releaseRecorder() {
        if (recorder != null) { try { recorder.reset(); } catch (Exception ignored) {} try { recorder.release(); } catch (Exception ignored) {} recorder = null; }
    }
    private void playAudioFile(File file) {
        try {
            android.media.MediaPlayer player = new android.media.MediaPlayer();
            player.setDataSource(file.getAbsolutePath());
            player.setOnCompletionListener(p -> p.release());
            player.setOnErrorListener((p, what, extra) -> { p.release(); Toast.makeText(this, "پخش فایل ممکن نشد.", Toast.LENGTH_SHORT).show(); return true; });
            player.prepare(); player.start();
        } catch (Exception e) { Toast.makeText(this, "پخش فایل صوتی ممکن نشد.", Toast.LENGTH_LONG).show(); }
    }

    private void showHomeDocuments() {
        String[] options = new String[]{"📷 عکس گرفتن از مدرک", "🖼 انتخاب تصویر از گالری", "📂 مشاهده مدارک ذخیره‌شده"};
        new AlertDialog.Builder(this).setTitle("اسکن مدارک خانه")
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    try {
                        Intent camera = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                        if (camera.resolveActivity(getPackageManager()) != null) startActivityForResult(camera, HOME_DOC_CAMERA);
                        else Toast.makeText(this, "دوربین در دسترس نیست.", Toast.LENGTH_LONG).show();
                    } catch (Exception e) { Toast.makeText(this, "باز کردن دوربین ممکن نشد.", Toast.LENGTH_LONG).show(); }
                } else if (which == 1) {
                    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    pick.setType("image/*");
                    startActivityForResult(pick, HOME_DOC_GALLERY);
                } else showSavedHomeDocuments();
            }).setNegativeButton("بستن", null).show();
    }

    private void saveHomeDocument(Bitmap bitmap, String defaultName) {
        try {
            File folder = new File(getFilesDir(), "home_documents");
            if (!folder.exists() && !folder.mkdirs()) throw new java.io.IOException("Cannot create folder");
            String fileName = "home_doc_" + System.currentTimeMillis() + ".jpg";
            File file = new File(folder, fileName);
            try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out); }
            EditText nameInput = new EditText(this);
            nameInput.setText(defaultName);
            nameInput.setHint("مثلاً کارت ملی، شناسنامه، سند خانه");
            new AlertDialog.Builder(this).setTitle("نام مدرک")
                .setMessage("تصویر در حافظهٔ خصوصی برنامه ذخیره می‌شود.")
                .setView(nameInput).setNegativeButton("لغو", (d,w) -> appendHomeDocument(fileName, defaultName))
                .setPositiveButton("ذخیره", (d,w) -> {
                    String name = nameInput.getText().toString().trim();
                    appendHomeDocument(fileName, name.isEmpty() ? defaultName : name);
                }).show();
        } catch (Exception e) { Toast.makeText(this, "ذخیره تصویر مدرک انجام نشد.", Toast.LENGTH_LONG).show(); }
    }

    private void appendHomeDocument(String fileName, String name) {
        String saved = prefs.getString("home_documents_v1", "");
        String row = fileName + "|" + name.replace("|", " ").replace("\n", " ");
        prefs.edit().putString("home_documents_v1", saved.isEmpty() ? row : saved + "\n" + row).apply();
        Toast.makeText(this, "مدرک در آرشیو مدارک خانه ذخیره شد.", Toast.LENGTH_LONG).show();
    }

    private void showSavedHomeDocuments() {
        String saved = prefs.getString("home_documents_v1", "");
        if (saved.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("مدارک خانه").setMessage("هنوز مدرکی ذخیره نشده است.").setPositiveButton("باشه", null).show();
            return;
        }
        String[] rows = saved.split("\n");
        String[] labels = new String[rows.length];
        for (int i=0;i<rows.length;i++) {
            String[] p = rows[i].split("\\|",2);
            labels[i] = p.length > 1 ? p[1] : p[0];
        }
        new AlertDialog.Builder(this).setTitle("مدارک ذخیره‌شده").setItems(labels, (d,which) -> {
            String[] p = rows[which].split("\\|",2);
            File imageFile = new File(new File(getFilesDir(),"home_documents"), p[0]);
            if (!imageFile.isFile()) { Toast.makeText(this, "فایل تصویر پیدا نشد.", Toast.LENGTH_LONG).show(); return; }
            android.widget.ImageView preview = new android.widget.ImageView(this);
            preview.setImageBitmap(BitmapFactory.decodeFile(imageFile.getAbsolutePath()));
            preview.setAdjustViewBounds(true);
            ScrollView scroll = new ScrollView(this); scroll.addView(preview);
            new AlertDialog.Builder(this).setTitle(labels[which]).setView(scroll).setPositiveButton("بستن", null).show();
        }).setNegativeButton("بستن", null).show();
    }

    private void createBackup() {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "OBA-backup.json");
            startActivityForResult(intent, BACKUP_CREATE);
        } catch (Exception e) { Toast.makeText(this,"ذخیره فایل پشتیبان ممکن نشد.",Toast.LENGTH_LONG).show(); }
    }

    private void chooseBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, BACKUP_RESTORE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if (requestCode == REMINDER_SOUND_PICKER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri selected = data.getData();
                try { getContentResolver().takePersistableUriPermission(selected, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                prefs.edit().putString("reminder_alarm_sound_uri", selected.toString()).apply();
                showReminderSoundPreview(selected);
            }
            return;
        }
        if (requestCode == HOME_DOC_CAMERA) {
            if (resultCode == RESULT_OK && data != null && data.getExtras() != null) {
                Object image = data.getExtras().get("data");
                if (image instanceof Bitmap) saveHomeDocument((Bitmap) image, "مدرک خانه");
            }
            return;
        }
        if (requestCode == HOME_DOC_GALLERY) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                    Bitmap bitmap = BitmapFactory.decodeStream(in);
                    if (bitmap != null) saveHomeDocument(bitmap, "مدرک خانه");
                    else Toast.makeText(this, "خواندن تصویر ممکن نشد.", Toast.LENGTH_LONG).show();
                } catch (Exception e) { Toast.makeText(this, "باز کردن تصویر ممکن نشد.", Toast.LENGTH_LONG).show(); }
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            if (requestCode == BACKUP_CREATE) {
                JSONObject json = new JSONObject();
                json.put("app","OBA");
                json.put("version",1);
                JSONObject values = new JSONObject();
                for (java.util.Map.Entry<String,?> entry : prefs.getAll().entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();
                    // Service state is transient and must never be restored from a backup file.
                    if ("wake_enabled".equals(key)) continue;
                    if (isValidBackupPreference(key, value)) values.put(key, value);
                }
                json.put("preferences",values);
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    if (out == null) throw new java.io.IOException("No output stream");
                    out.write(json.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                Toast.makeText(this,"نسخه پشتیبان در محل انتخابی گوشی ذخیره شد.",Toast.LENGTH_LONG).show();
            } else if (requestCode == BACKUP_RESTORE) {
                StringBuilder raw = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(data.getData()), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line=reader.readLine())!=null) {
                        if (raw.length() + line.length() > 1000000) {
                            Toast.makeText(this,"فایل پشتیبان بیش از حد بزرگ است.",Toast.LENGTH_LONG).show(); return;
                        }
                        raw.append(line);
                    }
                }
                JSONObject json = new JSONObject(raw.toString());
                if (!"OBA".equals(json.optString("app")) || json.optInt("version", -1) != 1 || !json.has("preferences") || !(json.opt("preferences") instanceof JSONObject)) {
                    Toast.makeText(this,"این فایل، نسخه پشتیبان معتبر OBA نیست.",Toast.LENGTH_LONG).show(); return;
                }
                JSONObject checkedValues = json.getJSONObject("preferences");
                java.util.Iterator<String> checkedKeys = checkedValues.keys();
                while (checkedKeys.hasNext()) {
                    String key = checkedKeys.next();
                    if (!isValidBackupPreference(key, checkedValues.opt(key))) {
                        Toast.makeText(this,"فایل پشتیبان دارای دادهٔ نامعتبر یا پشتیبانی‌نشده است؛ اطلاعات تغییر نکرد.",Toast.LENGTH_LONG).show(); return;
                    }
                }
                new AlertDialog.Builder(this).setTitle("بازیابی اطلاعات")
                    .setMessage("اطلاعات فعلی با اطلاعات داخل نسخه پشتیبان جایگزین می‌شوند. ادامه می‌دهی؟")
                    .setNegativeButton("انصراف",null).setPositiveButton("بازیابی", (dialog,which) -> {
                        try {
                            JSONObject values = json.getJSONObject("preferences");
                            SharedPreferences.Editor editor = prefs.edit().clear();
                            java.util.Iterator<String> keys = values.keys();
                            while(keys.hasNext()) {
                                String key=keys.next(); Object value=values.get(key);
                                if(value instanceof String) editor.putString(key,(String)value);
                                else if ("income".equals(key) || "expense".equals(key) || "home_budget".equals(key)) editor.putLong(key,((Number)value).longValue());
                                else if (key.startsWith("stock_")) editor.putInt(key,((Number)value).intValue());
                            }
                            editor.apply();
                            incomeTotal=prefs.getLong("income",0); expenseTotal=prefs.getLong("expense",0);
                            entries.clear(); loadEntries(); refresh();
                            Toast.makeText(this,"اطلاعات بازیابی شد. برای بازنشانی یادآوری‌ها برنامه را یک‌بار باز نگه دار.",Toast.LENGTH_LONG).show();
                        } catch(Exception ex) { Toast.makeText(this,"بازیابی انجام نشد: فایل را بررسی کن.",Toast.LENGTH_LONG).show(); }
                    }).show();
            }
        } catch(Exception e) { Toast.makeText(this,"خطا در فایل پشتیبان؛ دوباره تلاش کن.",Toast.LENGTH_LONG).show(); }
    }

    private boolean isValidBackupPreference(String key, Object value) {
        if (key == null || value == null) return false;
        if ("income".equals(key) || "expense".equals(key) || "home_budget".equals(key)) {
            if (!(value instanceof Number)) return false;
            double number = ((Number) value).doubleValue();
            return Double.isFinite(number) && number >= 0 && number <= Long.MAX_VALUE && number == Math.rint(number);
        }
        if ("entries".equals(key) || "vehicleRecords".equals(key) || "home_documents_v1".equals(key)) {
            return value instanceof String && ((String) value).length() <= 20000;
        }
        if (key.matches("reminder_[0-9]{1,10}")) {
            return value instanceof String && ((String) value).length() <= 2000;
        }
        if (key.startsWith("stock_") && key.length() > 6 && key.length() <= 106) {
            if (!(value instanceof Number)) return false;
            double number = ((Number) value).doubleValue();
            return Double.isFinite(number) && number >= 0 && number <= Integer.MAX_VALUE && number == Math.rint(number);
        }
        // Unknown keys and runtime-only flags are deliberately not imported.
        return false;
    }

    private void showCategorySubgroups(String category) {
        String[] groups;
        if ("خانه".equals(category)) groups = new String[]{"مواد غذایی", "لوازم خانه", "قبض و خدمات", "اجاره و تعمیرات", "نظافت", "سایر هزینه‌های خانه"};
        else if ("ماشین".equals(category)) groups = new String[]{"سوخت", "روغن و فیلتر", "تسمه تایم و تعمیرات", "بیمه", "لاستیک", "پارکینگ و عوارض", "سایر هزینه‌های ماشین"};
        else if ("مغازه".equals(category)) groups = new String[]{"خرید کالا", "اجاره مغازه", "قبض و خدمات", "حقوق", "تعمیرات", "سایر هزینه‌های مغازه"};
        else if ("مدرسه".equals(category)) groups = new String[]{"شهریه", "کتاب و لوازم‌التحریر", "رفت‌وآمد", "لباس فرم", "فعالیت‌ها", "سایر هزینه‌های مدرسه"};
        else if ("سلامت".equals(category)) groups = new String[]{"دکتر", "دارو", "آزمایش", "بیمه درمانی", "دندان‌پزشکی", "سایر هزینه‌های سلامت"};
        else groups = new String[]{"خرید متفرقه", "هدیه", "سفر", "سایر"};
        if ("خانه".equals(category)) groups = new String[]{"مواد غذایی", "لوازم خانه", "قبض و خدمات", "اجاره و تعمیرات", "نظافت", "📄 اسکن مدارک خانه", "سایر هزینه‌های خانه"};

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(8), dp(12), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(list);
        // A clear in-page back button makes it easier to return to the dashboard.
        Button backButton = button("⬅ برگشت به صفحه قبل", Color.rgb(232, 238, 250), Color.rgb(24, 37, 105), 15);
        backButton.setAllCaps(false);
        backButton.setMinHeight(dp(48));
        list.addView(backButton, lp(-1, -2, 0, 2, 0, 8));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("زیرگروه «" + category + "»")
            .setView(scroll).setNegativeButton("بستن", null).create();
        backButton.setOnClickListener(v -> dialog.dismiss());
        for (String option : groups) {
            Button item = button(option, Color.WHITE, Color.rgb(24, 37, 105), 16);
            item.setAllCaps(false);
            item.setMinHeight(dp(54));
            GradientDrawable pinkFace = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(244, 155, 195), Color.rgb(222, 104, 158)});
            pinkFace.setCornerRadius(dp(14));
            pinkFace.setStroke(dp(3), Color.rgb(24, 37, 105));
            item.setBackground(pinkFace);
            item.setTextColor(Color.rgb(24, 37, 105));
            item.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
            item.setElevation(dp(6));
            item.setPadding(dp(10), dp(4), dp(10), dp(4));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(54));
            params.setMargins(dp(2), dp(4), dp(2), dp(4));
            list.addView(item, params);
            item.setOnClickListener(v -> {
                dialog.dismiss();
                if ("خانه".equals(category) && option.contains("اسکن مدارک خانه")) {
                    runFeature("documents", this::showHomeDocuments);
                } else if (!"ماشین".equals(category)) {
                    showNestedSubgroups(category, option);
                } else {
                    showEntryMethodDialog(category, option);
                }
            });
        }
        dialog.show();
    }


    private void showNestedSubgroups(String category, String parent) {
        String[] children;
        switch (parent) {
            case "مواد غذایی": children = new String[]{"لبنیات", "حبوبات", "گوشت و مرغ", "ماهی", "غلات و برنج", "نان و شیرینی", "میوه‌ها و سبزیجات", "خشکبار و آجیل", "روغن", "چاشنی و ادویه", "نوشیدنی‌ها", "مواد غذایی دیگر"}; break;
            case "لوازم خانه": children = new String[]{"ظروف و لوازم آشپزخانه", "وسایل برقی", "مبلمان و دکور", "لوازم خواب و حمام", "ابزار و یراق", "لوازم خانه دیگر"}; break;
            case "قبض و خدمات": children = new String[]{"برق", "آب", "گاز", "تلفن و اینترنت", "شارژ ساختمان", "خدمات دیگر"}; break;
            case "اجاره و تعمیرات": children = new String[]{"اجاره", "تعمیرات لوله‌کشی", "تعمیرات برق", "تعمیرات وسایل", "نقاشی و بازسازی", "هزینه‌های دیگر"}; break;
            case "نظافت": children = new String[]{"مواد شوینده", "دستمال و کیسه زباله", "لوازم نظافت", "خدمات نظافت", "سایر لوازم نظافت"}; break;
            case "خرید کالا": children = new String[]{"کالای اصلی", "بسته‌بندی", "لوازم مصرفی", "حمل کالا", "سایر خریدها"}; break;
            case "اجاره مغازه": children = new String[]{"اجاره ماهانه", "ودیعه و تمدید", "هزینه‌های مرتبط"}; break;
            case "حقوق": children = new String[]{"حقوق کارکنان", "اضافه‌کاری", "پاداش و مزایا", "بیمه کارکنان"}; break;
            case "تعمیرات": children = new String[]{"تعمیر تجهیزات", "تعمیرات ساختمان", "تعمیرات برق", "سایر تعمیرات"}; break;
            case "شهریه": children = new String[]{"شهریه اصلی", "ثبت‌نام", "کلاس فوق‌برنامه", "هزینه‌های دیگر"}; break;
            case "کتاب و لوازم‌التحریر": children = new String[]{"کتاب درسی", "دفتر و کاغذ", "نوشت‌افزار", "کیف و جامدادی", "لوازم دیگر"}; break;
            case "رفت‌وآمد": children = new String[]{"سرویس", "اتوبوس و مترو", "تاکسی", "هزینه‌های دیگر"}; break;
            case "لباس فرم": children = new String[]{"لباس", "کفش", "دوخت و تعمیر", "سایر موارد"}; break;
            case "فعالیت‌ها": children = new String[]{"اردو", "ورزش", "برنامه‌های مدرسه", "سایر فعالیت‌ها"}; break;
            case "دکتر": children = new String[]{"ویزیت عمومی", "متخصص", "اورژانس", "سایر ویزیت‌ها"}; break;
            case "دارو": children = new String[]{"داروی نسخه‌ای", "داروی بدون نسخه", "لوازم پزشکی", "سایر داروها"}; break;
            case "آزمایش": children = new String[]{"آزمایش خون", "تصویربرداری", "آزمایش‌های دیگر"}; break;
            case "بیمه درمانی": children = new String[]{"حق بیمه", "فرانشیز", "هزینه‌های دیگر"}; break;
            case "دندان‌پزشکی": children = new String[]{"معاینه", "ترمیم", "جرم‌گیری", "سایر خدمات"}; break;
            default: children = new String[]{"خرید روزمره", "هزینه خدمات", "تعمیر و نگهداری", "سایر موارد"};
        }
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(8), dp(12), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(list);
        // Back from nested items to the category's previous subgroup page.
        Button backButton = button("⬅ برگشت به صفحه قبل", Color.rgb(232, 238, 250), Color.rgb(24, 37, 105), 15);
        backButton.setAllCaps(false);
        backButton.setMinHeight(dp(48));
        list.addView(backButton, lp(-1, -2, 0, 2, 0, 8));
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("زیرمجموعهٔ «" + parent + "»")
            .setView(scroll).setNegativeButton("بستن", null).create();
        backButton.setOnClickListener(v -> {
            dialog.dismiss();
            showCategorySubgroups(category);
        });
        for (String child : children) {
            Button item = button(child, Color.WHITE, Color.rgb(24, 37, 105), 16);
            item.setAllCaps(false);
            item.setMinHeight(dp(54));
            GradientDrawable face = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(184, 216, 255), Color.rgb(142, 188, 245)});
            face.setCornerRadius(dp(14));
            face.setStroke(dp(3), Color.rgb(24, 37, 105));
            item.setBackground(face);
            item.setTextColor(Color.rgb(24, 37, 105));
            item.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
            item.setElevation(dp(6));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(54));
            params.setMargins(dp(2), dp(4), dp(2), dp(4));
            list.addView(item, params);
            item.setOnClickListener(v -> {
                dialog.dismiss();
                showEntryMethodDialog(category, parent + " / " + child);
            });
        }
        dialog.show();
    }

    private void showEntryMethodDialog(String category, String subgroup) {
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(dp(12), dp(8), dp(12), dp(8));
        Button add = button("➕ ثبت خرید در این زیرمجموعه", Color.rgb(224, 240, 255), dark, 16);
        add.setMinHeight(dp(52));
        add.setOnClickListener(v -> showCategoryExpenseForm(category, subgroup));
        Button archive = button("🗂️ آرشیو خریدهای همین زیرمجموعه", Color.WHITE, dark, 16);
        archive.setMinHeight(dp(52));
        archive.setOnClickListener(v -> runFeature("subgroup_archive", () -> showSubgroupPurchaseArchive(category, subgroup)));
        actions.addView(add, lp(-1, -2, 0, 0, 0, 8));
        actions.addView(archive, lp(-1, -2, 0, 0, 0, 0));
        new AlertDialog.Builder(this)
            .setTitle(category + " ← " + subgroup)
            .setView(actions)
            .setNegativeButton("بستن", null)
            .show();
    }

    private void showPurchaseCenter() {
        String[] labels = {
            "🗓️  تقویم مخارج روزانه",
            "🗂️  آرشیو خریدهای روزانه",
            "📚  آرشیو خریدهای دسته‌بندی‌شده",
            "🔎  گزارش ماهانهٔ کالا (مثلاً گوشت)",
            "➕  ثبت خرید با انتخاب دسته"
        };
        int[][] gradients = {
            {Color.rgb(57, 126, 190), Color.rgb(35, 76, 139)},
            {Color.rgb(40, 157, 151), Color.rgb(20, 104, 112)},
            {Color.rgb(123, 105, 176), Color.rgb(75, 58, 129)},
            {Color.rgb(213, 151, 67), Color.rgb(158, 99, 34)},
            {Color.rgb(75, 143, 113), Color.rgb(38, 101, 79)}
        };
        int[] borders = {
            Color.rgb(185, 220, 250),
            Color.rgb(175, 235, 226),
            Color.rgb(218, 204, 246),
            Color.rgb(249, 220, 164),
            Color.rgb(188, 230, 207)
        };
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(16), dp(8), dp(16), dp(6));
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("🛒 خریدها و تقویم هزینه‌ها")
            .setView(menu)
            .setNegativeButton("بستن", null)
            .create();
        for (int i = 0; i < labels.length; i++) {
            final int selected = i;
            Button item = button(labels[i], Color.WHITE, Color.WHITE, 16);
            item.setAllCaps(false);
            item.setGravity(Gravity.CENTER);
            item.setMinHeight(dp(54));
            item.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            item.setTextColor(Color.WHITE);
            GradientDrawable face = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, gradients[i]);
            face.setCornerRadius(dp(15));
            face.setStroke(dp(2), borders[i]);
            item.setBackground(face);
            item.setElevation(dp(3));
            item.setPadding(dp(12), dp(8), dp(12), dp(8));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(56));
            params.setMargins(0, dp(5), 0, dp(5));
            menu.addView(item, params);
            item.setOnClickListener(v -> {
                dialog.dismiss();
                if (selected == 0) runFeature("expense_calendar", this::showExpenseCalendar);
                else if (selected == 1) runFeature("daily_purchase_archive", this::showDailyPurchaseArchive);
                else if (selected == 2) runFeature("category_purchase_archive", this::showPurchaseCategoryPicker);
                else if (selected == 3) runFeature("monthly_item_report", this::showMonthlyItemReport);
                else showPurchaseCategoryPicker();
            });
        }
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.rgb(28, 133, 119));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    }

    private void showMonthlyItemReport() {
        int[] today = gregorianToJalali(Calendar.getInstance().get(Calendar.YEAR),
            Calendar.getInstance().get(Calendar.MONTH) + 1,
            Calendar.getInstance().get(Calendar.DAY_OF_MONTH));
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(4));

        EditText itemInput = new EditText(this);
        itemInput.setHint("نام کالا؛ مثلاً گوشت");
        itemInput.setSingleLine(true);
        itemInput.setTextDirection(View.TEXT_DIRECTION_RTL);
        form.addView(itemInput, lp(-1, -2, 0, 0, 0, 8));

        EditText yearInput = new EditText(this);
        yearInput.setHint("سال شمسی؛ مثلاً " + today[0]);
        yearInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        yearInput.setText(String.valueOf(today[0]));
        form.addView(yearInput, lp(-1, -2, 0, 0, 0, 8));

        String[] months = {"فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"};
        android.widget.Spinner monthSpinner = new android.widget.Spinner(this);
        monthSpinner.setAdapter(new android.widget.ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, months));
        monthSpinner.setSelection(Math.max(0, Math.min(11, today[1] - 1)));
        form.addView(monthSpinner, lp(-1, -2, 0, 0, 0, 8));

        String[] categories = {"همهٔ بخش‌ها", "خانه", "مدرسه", "ماشین", "مغازه", "سلامت", "سایر"};
        android.widget.Spinner categorySpinner = new android.widget.Spinner(this);
        categorySpinner.setAdapter(new android.widget.ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, categories));
        form.addView(categorySpinner, lp(-1, -2, 0, 0, 0, 4));

        new AlertDialog.Builder(this).setTitle("🔎 جست‌وجوی کالا در یک ماه")
            .setMessage("نام کالا، ماه و سال را انتخاب کن. برنامه تعداد دفعات و مجموع مبلغ را محاسبه می‌کند.")
            .setView(form).setNegativeButton("انصراف", null)
            .setPositiveButton("نمایش گزارش", (dialog, which) -> {
                String query = itemInput.getText().toString().trim().replace('ي', 'ی').replace('ك', 'ک');
                if (query.isEmpty()) {
                    Toast.makeText(this, "نام کالا را وارد کن؛ مثلاً گوشت.", Toast.LENGTH_LONG).show();
                    return;
                }
                int year;
                try { year = Integer.parseInt(yearInput.getText().toString().trim()); }
                catch (Exception e) {
                    Toast.makeText(this, "سال شمسی معتبر وارد کن.", Toast.LENGTH_LONG).show();
                    return;
                }
                int month = monthSpinner.getSelectedItemPosition() + 1;
                String categoryFilter = categories[categorySpinner.getSelectedItemPosition()];
                showMonthlyItemReportResults(query, year, month, categoryFilter);
            }).show();
    }

    private void showMonthlyItemReportResults(String query, int jalaliYear, int jalaliMonth, String categoryFilter) {
        String history = prefs.getString("oba_expense_history", "");
        ArrayList<String> details = new ArrayList<>();
        long total = 0L;
        int count = 0;
        String normalizedQuery = query.replace('ي', 'ی').replace('ك', 'ک').toLowerCase(Locale.ROOT);
        if (!history.trim().isEmpty()) {
            for (String row : history.split("\\n")) {
                String[] parts = row.split("\\t", -1);
                if (parts.length < 5) continue;
                try {
                    long timestamp = Long.parseLong(parts[0]);
                    String category = parts[1].trim();
                    String item = parts[3].trim();
                    String normalizedItem = item.replace('ي', 'ی').replace('ك', 'ک').toLowerCase(Locale.ROOT);
                    if (!normalizedItem.contains(normalizedQuery)) continue;
                    if (!"همهٔ بخش‌ها".equals(categoryFilter) && !categoryFilter.equals(category)) continue;
                    Calendar date = Calendar.getInstance();
                    date.setTimeInMillis(timestamp);
                    int[] jalali = gregorianToJalali(date.get(Calendar.YEAR), date.get(Calendar.MONTH) + 1, date.get(Calendar.DAY_OF_MONTH));
                    if (jalali[0] != jalaliYear || jalali[1] != jalaliMonth) continue;
                    long amount = Long.parseLong(parts[4]);
                    total += amount;
                    count++;
                    details.add(formatPersianDate(new Date(timestamp)) + " • " + category + " • " + item + " • " + fmt(amount) + " تومان");
                } catch (Exception ignored) {}
            }
        }
        String[] monthNames = {"فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"};
        String title = "گزارش " + query + " — " + monthNames[jalaliMonth - 1] + " " + jalaliYear;
        if (details.isEmpty()) {
            new AlertDialog.Builder(this).setTitle(title)
                .setMessage("در این ماه خریدی با این نام در سوابق ثبت‌شده پیدا نشد. اگر کالا با نام دیگری ثبت شده، همان نام را هم امتحان کن.")
                .setPositiveButton("جست‌وجوی دیگر", (d, w) -> showMonthlyItemReport())
                .setNegativeButton("بستن", null).show();
            return;
        }
        StringBuilder report = new StringBuilder();
        report.append("تعداد دفعات خرید: ").append(count).append("\n");
        report.append("مجموع هزینه: ").append(fmt(total)).append(" تومان\n\n");
        report.append("ریز خریدها:\n");
        for (String detail : details) report.append("• ").append(detail).append("\n");
        new AlertDialog.Builder(this).setTitle(title).setMessage(report.toString())
            .setPositiveButton("باشه", null)
            .setNeutralButton("جست‌وجوی دیگر", (d, w) -> showMonthlyItemReport())
            .show();
    }

    private void showPurchaseCategoryPicker() {
        String[] categories = {"خانه", "مدرسه", "ماشین", "مغازه", "سلامت", "سایر"};
        new AlertDialog.Builder(this)
            .setTitle("خرید را برای کدام بخش ثبت می‌کنی؟")
            .setItems(categories, (dialog, which) -> showCategorySubgroups(categories[which]))
            .setNegativeButton("بازگشت", null)
            .show();
    }

    private void showDailyPurchaseArchive() {
        String history = prefs.getString("oba_expense_history", "");
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<String> details = new ArrayList<>();
        long total = 0L;
        if (!history.trim().isEmpty()) {
            for (String row : history.split("\\n")) {
                String[] parts = row.split("\\t", -1);
                if (parts.length < 5 || !"خریدهای روزانه".equals(parts[2].trim())) continue;
                try {
                    long timestamp = Long.parseLong(parts[0]);
                    long amount = Long.parseLong(parts[4]);
                    String date = formatPersianDate(new Date(timestamp));
                    labels.add(date + " • " + parts[3].trim() + " • " + fmt(amount) + " تومان");
                    details.add("بخش: " + parts[1].trim() + "\nتاریخ: " + date + "\nشرح: " + parts[3].trim() + "\nمبلغ: " + fmt(amount) + " تومان");
                    total += amount;
                } catch (Exception ignored) {}
            }
        }
        if (labels.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("🗂️ خریدهای روزانه")
                .setMessage("هنوز خریدی در بخش خریدهای روزانه ثبت نشده است. برای ثبت، از «ثبت خرید با انتخاب دسته» استفاده کن.")
                .setPositiveButton("ثبت خرید", (d, w) -> showPurchaseCategoryPicker())
                .setNegativeButton("بستن", null).show();
            return;
        }
        labels.add(0, "مجموع خریدهای روزانه: " + fmt(total) + " تومان");
        String[] items = labels.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("🗂️ آرشیو خریدهای روزانه")
            .setItems(items, (d, which) -> {
                if (which == 0) return;
                int index = which - 1;
                new AlertDialog.Builder(this).setTitle("جزئیات خرید روزانه").setMessage(details.get(index))
                    .setPositiveButton("باشه", null).show();
            }).setNeutralButton("پاک کن", (d, w) -> confirmClearArchive(
                "پاک کردن آرشیو خریدها",
                "تمام خریدهای ذخیره‌شده در آرشیو خریدها پاک شوند؟ این کار قابل بازگشت نیست.",
                this::clearPurchaseArchives))
              .setNegativeButton("بستن", null).show();
    }

    private void showSubgroupPurchaseArchive(String category, String subgroup) {
        String history = prefs.getString("oba_expense_history", "");
        ArrayList<String> matches = new ArrayList<>();
        if (!history.trim().isEmpty()) {
            for (String row : history.split("\\n")) {
                String[] parts = row.split("\\t", -1);
                if (parts.length < 5 || !category.equals(parts[1].trim()) || !subgroup.equals(parts[2].trim())) continue;
                try {
                    String date = formatPersianDate(new Date(Long.parseLong(parts[0])));
                    matches.add(date + " • " + parts[3].trim() + " • " + fmt(Long.parseLong(parts[4])) + " تومان");
                } catch (Exception ignored) {}
            }
        }
        if (matches.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("آرشیو خریدهای «" + subgroup + "»").setMessage("هنوز خریدی برای این زیرمجموعه ثبت نشده است.").setPositiveButton("باشه", null).show();
            return;
        }
        String[] items = matches.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("آرشیو خریدهای «" + subgroup + "»").setItems(items, (d, which) -> new AlertDialog.Builder(this).setTitle("جزئیات خرید").setMessage(items[which]).setPositiveButton("باشه", null).show()).setNegativeButton("بستن", null).show();
    }

    private void showCategoryExpenseForm(String category, String subgroup) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(4));
        EditText amountInput = new EditText(this);
        amountInput.setHint("مبلغ به تومان (مثلاً ۱۵۰۰۰۰)");
        amountInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        form.addView(amountInput, lp(-1,-2,0,0,0,8));
        EditText descriptionInput = new EditText(this);
        descriptionInput.setHint("نام کالا (مثلاً گوشت یا پنیر)");
        descriptionInput.setSingleLine(true);
        form.addView(descriptionInput, lp(-1,-2,0,0,0,4));
        EditText quantityInput = new EditText(this);
        quantityInput.setHint("مقدار یا وزن (مثلاً ۲ کیلو یا ۳ عدد)");
        quantityInput.setSingleLine(true);
        form.addView(quantityInput, lp(-1,-2,0,0,0,4));
        new AlertDialog.Builder(this).setTitle(category + " ← " + subgroup)
            .setView(form).setNegativeButton("لغو", null)
            .setPositiveButton("ثبت هزینه", (dialog, which) -> {
                String rawAmount = amountInput.getText().toString().trim()
                    .replace("۰","0").replace("۱","1").replace("۲","2").replace("۳","3")
                    .replace("۴","4").replace("۵","5").replace("۶","6").replace("۷","7")
                    .replace("۸","8").replace("۹","9").replace("٬","").replace(",","");
                long amount;
                try { amount = Long.parseLong(rawAmount); }
                catch (Exception e) { Toast.makeText(this, "مبلغ معتبر وارد کن.", Toast.LENGTH_LONG).show(); return; }
                if (amount <= 0) { Toast.makeText(this, "مبلغ باید بیشتر از صفر باشد.", Toast.LENGTH_LONG).show(); return; }
                if (amount > Long.MAX_VALUE - expenseTotal) { Toast.makeText(this, "مبلغ بیش از حد مجاز است.", Toast.LENGTH_LONG).show(); return; }
                String description = descriptionInput.getText().toString().trim();
                String quantity = quantityInput.getText().toString().trim();
                if (description.isEmpty()) description = subgroup;
                if (!quantity.isEmpty()) description = description + " — مقدار: " + quantity;
                final String savedDescription = description;
                runFeature(featureKeyForCategory(category), () -> {
                    expenseTotal += amount;
                    prefs.edit().putLong("expense", expenseTotal).apply();
                    String entryTitle = subgroup + (savedDescription.isEmpty() ? "" : " — " + savedDescription);
                    addEntry(category, entryTitle, amount);
                    refresh();
                    String confirmation = "هزینه " + fmt(amount) + " تومان در «" + category + " / " + subgroup + "» ثبت شد.";
                    status.setText(confirmation);
                    say(confirmation);
                    Toast.makeText(this, confirmation, Toast.LENGTH_LONG).show();
                });
            }).show();
    }

    private void addVoiceCard(String title, String desc, String mode) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(14),dp(12),dp(14),dp(12));
        card.setBackground(round(Color.WHITE,16));
        TextView t = text(title,18,dark,true); card.addView(t);
        TextView d = text(desc,13,Color.DKGRAY,false); card.addView(d, lp(-1,-2,0,4,0,0));
        card.setOnClickListener(v -> startVoice(mode));
        LinearLayout.LayoutParams p = lp(-1,-2,0,3,0,5); cards.addView(card,p);
    }

    private void toggleContinuousExpenseListening() {
        if (continuousExpenseListening) {
            continuousExpenseListening = false;
            if (offlineEngine != null) { offlineEngine.stop(); offlineEngine = null; }
            if (recognizer != null) { try { recognizer.cancel(); } catch (Exception ignored) {} }
            awaitingVoiceConfirmation = true;
            if (continuousExpenseButton != null) continuousExpenseButton.setText("🎙  شروع ثبت");
            if (status != null) status.setText("فهرست را بررسی کن. با گفتن «بله» همه ثبت می‌شوند؛ با گفتن «اصلاح پنیر» مورد پنیر را اصلاح کن.");
            updateContinuousTranscript();
            startVoice("continuous_expense_confirmation");
            return;
        }
        awaitingVoiceConfirmation = false;
        continuousExpenseListening = true;
        if (continuousExpenseButton != null) continuousExpenseButton.setText("⏹  پایان ثبت");
        if (status != null) status.setText("ثبت صوتی فعال است؛ هر مورد را با مبلغ و کلمهٔ «تومان» تمام کن.");
        startVoice("continuous_expense");
    }

    private void handleContinuousExpense(String raw) {
        String normalized = normalize(raw).trim();
        if (normalized.isEmpty()) return;

        String saveCommand = normalized.replace("‌", "").replace(" ", "");
        if (saveCommand.contains("ذخیرهکن") || saveCommand.contains("ثبتکن") ||
            saveCommand.contains("تمامشدذخیرهکن") || saveCommand.contains("هزینههاتمامشد")) {
            continuousExpenseListening = false;
            if (offlineEngine != null) { offlineEngine.stop(); offlineEngine = null; }
            if (recognizer != null) { try { recognizer.cancel(); } catch (Exception ignored) {} }
            awaitingVoiceConfirmation = false;
            if (continuousExpenseButton != null) continuousExpenseButton.setText("🎙  شروع ثبت");
            if (!unfinishedVoiceExpense.trim().isEmpty()) {
                if (status != null) status.setText("آخرین مورد هنوز با «تومان» کامل نشده؛ لطفاً مبلغ آن را همراه کلمهٔ تومان بگو، سپس «ذخیره کن» را تکرار کن.");
                return;
            }
            commitPendingVoiceExpenses();
            return;
        }

        if (normalized.startsWith("اصلاح")) {
            String target = normalized.replaceFirst("^اصلاح( مورد)?( کن)?\\s*", "").trim();
            if (!target.isEmpty() && parseAmount(target) > 0) {
                replacePendingVoiceExpense(target);
            } else {
                correctionTargetItem = target;
                correctingNextVoicePhrase = true;
                if (status != null) status.setText(target.isEmpty()
                    ? "حالت اصلاح فعال شد؛ متن کامل مورد را دوباره بخوان و در پایان «تومان» بگو."
                    : "مورد «" + target + "» انتخاب شد؛ حالا متن درست آن را کامل بخوان و در پایان «تومان» بگو.");
                updateContinuousTranscript();
            }
            return;
        }

        liveVoicePartial = "";
        if (correctingNextVoicePhrase) {
            correctingNextVoicePhrase = false;
            replacePendingVoiceExpense(normalized);
            return;
        }

        unfinishedVoiceExpense = (unfinishedVoiceExpense + " " + normalized).trim();
        boolean added = false;
        int marker;
        while ((marker = unfinishedVoiceExpense.indexOf("تومان")) >= 0) {
            String complete = unfinishedVoiceExpense.substring(0, marker + "تومان".length()).trim();
            unfinishedVoiceExpense = unfinishedVoiceExpense.substring(marker + "تومان".length()).trim();
            if (!complete.isEmpty()) {
                pendingVoiceExpenses.add(complete);
                added = true;
            }
        }
        updateContinuousTranscript();
        if (status != null) {
            if (added) status.setText("مورد به فهرست موقت اضافه شد؛ برای مورد بعدی ادامه بده. برای اصلاح مورد آخر بگو «اصلاح».");
            else status.setText("متن دریافت شد؛ برای جدا شدن هر هزینه، مبلغ را با کلمهٔ «تومان» تمام کن.");
        }
    }

    private void replacePendingVoiceExpense(String correctedRaw) {
        String corrected = correctedRaw.trim();
        if (!corrected.contains("تومان")) corrected += " تومان";
        String[] parsedCorrection = parseContinuousExpense(corrected);
        if (parsedCorrection == null) {
            correctingNextVoicePhrase = false;
            correctionTargetItem = "";
            if (status != null) status.setText("مبلغ یا شرح مورد اصلاح‌شده واضح نبود؛ دوباره «اصلاح» بگو و متن را کامل بخوان.");
            updateContinuousTranscript();
            return;
        }
        String target = correctionTargetItem.trim();
        if (target.isEmpty()) target = parsedCorrection[0].trim();
        int found = -1;
        for (int i = 0; i < pendingVoiceExpenses.size(); i++) {
            String[] existing = parseContinuousExpense(pendingVoiceExpenses.get(i));
            if (existing != null && (existing[0].contains(target) || target.contains(existing[0]))) {
                found = i;
                break;
            }
        }
        correctingNextVoicePhrase = false;
        correctionTargetItem = "";
        unfinishedVoiceExpense = "";
        if (found >= 0) {
            pendingVoiceExpenses.set(found, corrected);
            if (status != null) status.setText("مورد «" + parsedCorrection[0] + "» در ردیف " + (found + 1) + " اصلاح شد. برای پایان ضبط دکمه را بزن.");
        } else {
            if (status != null) status.setText("مورد «" + target + "» در فهرست پیدا نشد؛ هیچ ردیفی تغییر نکرد. نام مورد را دقیق‌تر بگو.");
        }
        updateContinuousTranscript();
    }

    private void handleVoiceConfirmation(String raw) {
        String answer = normalize(raw).trim();
        if (answer.isEmpty()) {
            if (status != null) status.setText("لطفاً واضح بگو «بله» یا «اصلاح».");
            return;
        }
        if (answer.contains("بله") || answer.contains("آره") || answer.contains("تایید") || answer.contains("تأیید") || answer.contains("درسته") || answer.contains("درست است")) {
            awaitingVoiceConfirmation = false;
            commitPendingVoiceExpenses();
            if (continuousExpenseButton != null) continuousExpenseButton.setText("🎙  شروع ثبت");
            return;
        }
        if (answer.startsWith("اصلاح")) {
            String target = answer.replaceFirst("^اصلاح( مورد)?( کن)?\\s*", "").trim();
            awaitingVoiceConfirmation = false;
            correctingNextVoicePhrase = true;
            correctionTargetItem = target;
            continuousExpenseListening = true;
            if (continuousExpenseButton != null) continuousExpenseButton.setText("⏹  پایان ثبت");
            if (status != null) status.setText(target.isEmpty()
                ? "اصلاح فعال شد؛ متن کامل مورد را دوباره بخوان و در پایان «تومان» بگو."
                : "مورد «" + target + "» انتخاب شد؛ متن درست آن را بخوان و در پایان «تومان» بگو.");
            updateContinuousTranscript();
            startVoice("continuous_expense");
            return;
        }
        if (status != null) status.setText("پاسخ را متوجه نشدم؛ بگو «بله» برای ثبت یا «اصلاح پنیر» برای اصلاح همان مورد.");
        startVoice("continuous_expense_confirmation");
    }

    private String inferVoiceExpenseCategory(String item) {
        String value = normalize(item == null ? "" : item).toLowerCase(Locale.ROOT);
        if (contains(value, "ماشین", "خودرو", "بنزین", "سوخت", "روغن موتور", "لاستیک", "پارکینگ", "تعمیر خودرو", "بیمه خودرو")) return "ماشین";
        if (contains(value, "مدرسه", "شهریه", "معلم", "کتاب درسی", "لوازم التحریر", "لوازم‌التحریر", "کلاس", "دانشگاه")) return "مدرسه";
        if (contains(value, "مغازه", "فروشگاه", "جنس برای فروش", "خرید کالا", "اجاره مغازه", "حقوق کارمند", "تعمیر مغازه")) return "مغازه";
        if (contains(value, "دکتر", "دارو", "درمان", "بیمارستان", "آزمایش", "دندان", "پزشک", "داروخانه", "بیمه درمانی")) return "سلامت";
        if (contains(value, "اجاره خانه", "قبض", "برق", "آب", "گاز", "نان", "برنج", "غذا", "میوه", "سبزی", "لبنیات", "شیر", "گوشت", "مرغ", "نظافت", "لوازم خانه", "خرید خانه")) return "خانه";
        return "سایر";
    }

    private String[] parseContinuousExpense(String raw) {
        String normalized = normalize(raw == null ? "" : raw).trim();
        if (normalized.isEmpty()) return null;

        // Extract the amount ONLY from a phrase explicitly tied to currency.
        // Example: "پنج کیلو برنج ۱۰ میلیون تومن" -> amount phrase "۱۰ میلیون تومن".
        // Do not let the amount matcher consume item words (e.g. "برنج ده میلیون تومان").
        // The previous broad Persian-letter alternative could match "برنج ده میلیون" as the number,
        // leaving an empty item description and causing the whole purchase to be rejected.
        String numberWords = "(?:یک|یه|دو|سه|چهار|پنج|شش|شیش|هفت|هشت|نه|ده|یازده|دوازده|سیزده|چهارده|پانزده|شانزده|هفده|هجده|هیجده|نوزده|بیست|سی|چهل|پنجاه|شصت|هفتاد|هشتاد|نود|صد|یکصد|دویست|سیصد|چهارصد|پانصد|ششصد|هفتصد|هشتصد|نهصد|و)";
        java.util.regex.Pattern currencyPattern = java.util.regex.Pattern.compile(
            "([0-9][0-9,٬.]*|" + numberWords + "(?:\\s+" + numberWords + "){0,3})\\s*(میلیارد|میلیون|هزار)?\\s*(تومان|تومن|ریال)"
        );
        java.util.regex.Matcher matcher = currencyPattern.matcher(normalized);
        String amountPhrase = null;
        int amountStart = -1;
        int amountEnd = -1;
        while (matcher.find()) {
            amountPhrase = matcher.group();
            amountStart = matcher.start();
            amountEnd = matcher.end();
        }
        if (amountPhrase == null) return null;

        long amount = parseAmount(amountPhrase);
        if (amount <= 0) return null;

        // Preserve the item description and its quantity; remove only the currency phrase.
        String item = (normalized.substring(0, amountStart) + " " + normalized.substring(amountEnd))
            .replaceAll("\\s+", " ")
            .replaceAll("(?<!\\S)(هزینه|خرج|برای|ثبت|کن|ذخیره)(?!\\S)", " ")
            .replaceAll("\\s+", " ").trim();
        if (item.isEmpty()) return null;
        return new String[]{item, String.valueOf(amount)};
    }

    private void updateContinuousTranscript() {
        if (continuousTranscriptView == null) return;
        StringBuilder display = new StringBuilder();
        if (pendingVoiceExpenses.isEmpty() && unfinishedVoiceExpense.isEmpty()) {
            display.append("هنوز متنی دریافت نشده است.");
        }
        for (int i = 0; i < pendingVoiceExpenses.size(); i++) {
            display.append(i + 1).append(") ").append(pendingVoiceExpenses.get(i)).append("\n");
        }
        if (!unfinishedVoiceExpense.isEmpty()) display.append("در حال دریافت: ").append(unfinishedVoiceExpense).append("\n");
        if (!liveVoicePartial.isEmpty()) display.append("در حال شنیدن: ").append(liveVoicePartial).append("\n");
        if (correctingNextVoicePhrase) display.append("🎙 منتظر متن اصلاح‌شده…");
        display.append("\nراهنما: اگر مقدار یا اسم چیزی اشتباه خوانده شد، مثلاً ۳ کیلو پنیر، بگو «اصلاح پنیر» و بعد جملهٔ درست پنیر را دوباره بخوان. برای ذخیره هزینه‌ها بگو: «ذخیره کن».");
        continuousTranscriptView.setText(display.toString());
    }

    private void clearPendingVoiceExpenses() {
        pendingVoiceExpenses.clear();
        unfinishedVoiceExpense = "";
        correctingNextVoicePhrase = false;
        liveVoicePartial = "";
        updateContinuousTranscript();
        if (status != null) status.setText("فهرست موقت پاک شد؛ چیزی از این فهرست ثبت نشد.");
    }

    private void commitPendingVoiceExpenses() {
        if (!unfinishedVoiceExpense.trim().isEmpty()) {
            if (status != null) status.setText("مورد آخر هنوز به «تومان» نرسیده؛ مبلغ را کامل بگو یا ضبط را متوقف کن و فهرست را بررسی کن.");
            return;
        }
        int saved = 0;
        StringBuilder rejected = new StringBuilder();
        ArrayList<String> failed = new ArrayList<>();
        for (String raw : new ArrayList<>(pendingVoiceExpenses)) {
            String[] parsed = parseContinuousExpense(raw);
            if (parsed == null) {
                rejected.append("• ").append(raw).append("\n");
                failed.add(raw);
                continue;
            }
            long amount;
            try { amount = Long.parseLong(parsed[1]); }
            catch (Exception ex) {
                rejected.append("• ").append(raw).append("\n");
                failed.add(raw);
                continue;
            }
            if (amount <= 0 || parsed[0].trim().isEmpty()) {
                rejected.append("• ").append(raw).append("\n");
                failed.add(raw);
                continue;
            }
            expenseTotal += amount;
            prefs.edit().putLong("expense", expenseTotal).commit();
            // Voice and manual expense registration share addEntry() and the same dated history
            // consumed by the calendar. Infer the category from the spoken item instead of
            // forcing every dashboard voice expense into "خانه".
            addEntry(inferVoiceExpenseCategory(parsed[0]), parsed[0], amount);
            saved++;
        }
        // Never discard a phrase that could not be parsed: leave it visible for correction.
        pendingVoiceExpenses.clear();
        pendingVoiceExpenses.addAll(failed);
        correctingNextVoicePhrase = false;
        liveVoicePartial = "";
        updateContinuousTranscript();
        refresh();
        if (status != null) status.setText(saved + " مورد ثبت شد." + (rejected.length() > 0 ? " این موارد ثبت نشدند و در فهرست باقی ماندند؛ متن را اصلاح کن: " + rejected : ""));
    }

    private void startVoice(String mode) {
        pendingMode = mode;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
            return;
        }

        // The wake-word service and command recognizer must never own the microphone together.
        // Pause wake detection for this one command; the service is restarted afterwards.
        try { stopService(new Intent(this, VoiceWakeService.class)); } catch (Exception ignored) {}
        prefs.edit().putBoolean("wake_enabled", true).apply();
        resumeWakeAfterCommand = true;
        if (!quietVoiceSession && status != null) status.setText("در حال بررسی فایل‌های تشخیص گفتار؛ در صورت نیاز دانلود آغاز می‌شود…");
        if (!quietVoiceSession) Toast.makeText(this, "آماده‌سازی تشخیص گفتار فارسی…", Toast.LENGTH_SHORT).show();

        // Give Android a moment to release the microphone from the wake-word service.
        if (root != null) {
            root.postDelayed(() -> ensureVoskModelAndStart(mode), 500);
        } else {
            ensureVoskModelAndStart(mode);
        }
    }

    private void startPlatformVoice(String mode) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.setText("تشخیص گفتار گوشی در دسترس نیست. سرویس گفتار فارسی را در تنظیمات گوشی فعال کن.");
            Toast.makeText(this, "سرویس تشخیص گفتار در دسترس نیست.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            if (recognizer != null) {
                try { recognizer.cancel(); } catch (Exception ignored) {}
                recognizer.destroy();
                recognizer = null;
            }
            if (offlineEngine != null) {
                offlineEngine.stop();
                offlineEngine = null;
            }
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new android.speech.RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    if (status != null && !isFinishing()) status.setText("🎙 اوبا گوش می‌دهد؛ صحبت کن و بعد مکث کن.");
                }
                @Override public void onBeginningOfSpeech() {
                    if (status != null && !isFinishing()) status.setText("صدایت دریافت شد…");
                }
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {
                    if (status != null && !isFinishing()) status.setText("در حال پردازش گفتار…");
                }
                @Override public void onError(int error) {
                    if (isFinishing()) return;
                    String message;
                    switch (error) {
                        case SpeechRecognizer.ERROR_NO_MATCH:
                        case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                            message = "گفتاری تشخیص داده نشد؛ دوباره بزن و واضح صحبت کن.";
                            break;
                        case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                            message = "اجازهٔ میکروفون داده نشده است.";
                            break;
                        case SpeechRecognizer.ERROR_NETWORK:
                        case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                            message = "سرویس گفتار گوشی به اینترنت نیاز دارد یا اتصال در دسترس نیست. بستهٔ فارسی آفلاین را در تنظیمات گفتار نصب کن.";
                            break;
                        default:
                            message = "تشخیص گفتار شروع نشد (کد " + error + "). دوباره تلاش کن.";
                    }
                    if (status != null) status.setText(message);
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
                @Override public void onResults(Bundle results) {
                    if (isFinishing() || results == null) return;
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches == null || matches.isEmpty() || matches.get(0).trim().isEmpty()) {
                        if (status != null) status.setText("گفتاری تشخیص داده نشد؛ دوباره تلاش کن.");
                        return;
                    }
                    String spoken = matches.get(0).trim();
                    if (status != null) status.setText("شنیدم: «" + spoken + "»");
                    handleVoice(spoken);
                }
                @Override public void onPartialResults(Bundle partialResults) {
                    if (status == null || isFinishing() || partialResults == null) return;
                    ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String partial = matches.get(0).trim();
                        status.setText("شنیدم: " + partial);
                        String compact = normalize(partial).replace("‌", "").replace(" ", "")
                            .replace("ي", "ی").replace("ك", "ک")
                            .replaceAll("[،,؛;.!؟?]+", "");
                        boolean saveCommand = compact.contains("ذخیرهکن")
                            || compact.contains("ذخیرهیادآوری")
                            || compact.contains("ثبتکن");
                        // The recognizer can change modes or deliver the save phrase
                        // while the expense panel is active. If a reminder draft exists,
                        // honor the spoken save command regardless of the current panel mode.
                        String savedReminderDraft = prefs.getString("oba_reminder_draft", "").trim();
                        String visibleReminderDraft = reminderTranscriptView == null ? ""
                            : cleanReminderTranscript(reminderTranscriptView.getText().toString());
                        if (saveCommand && (!savedReminderDraft.isEmpty() || !visibleReminderDraft.isEmpty())
                                && !"saving_reminder".equals(pendingMode)) {
                            pendingMode = "saving_reminder";
                            saveReminderDraft();
                        }
                    }
                }
                @Override public void onEvent(int eventType, Bundle params) {}
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR");
            intent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false);
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            recognizer.startListening(intent);
            if (status != null) status.setText("در حال بازکردن میکروفون…");
        } catch (Throwable e) {
            if (status != null) status.setText("شروع میکروفون ممکن نشد؛ سرویس گفتار گوشی را بررسی کن.");
            Toast.makeText(this, "شروع تشخیص گفتار ممکن نشد.", Toast.LENGTH_LONG).show();
            if (recognizer != null) {
                try { recognizer.destroy(); } catch (Exception ignored) {}
                recognizer = null;
            }
        }
    }

    private void downloadVoskModelAutomatically() {
        if (isFinishing() || OfflineModelDownloader.getInstalledModelDirectory(this) != null || downloadingVoskModel) return;
        downloadingVoskModel = true;
        if (status != null) status.setText("در حال دریافت بستهٔ تشخیص گفتار فارسی…");
        OfflineModelDownloader.downloadModel(this, new OfflineModelDownloader.Callback() {
            @Override public void onProgress(long downloadedBytes, long totalBytes) {
                if (status == null || isFinishing()) return;
                if (totalBytes > 0) {
                    int percent = (int) Math.min(100L, downloadedBytes * 100L / totalBytes);
                    status.setText("دریافت بستهٔ گفتار فارسی: " + percent + "%");
                } else {
                    status.setText("در حال دریافت بستهٔ گفتار فارسی…");
                }
            }
            @Override public void onInstalled(File installedDirectory) {
                downloadingVoskModel = false;
                if (!isFinishing() && status != null) {
                    status.setText("بستهٔ تشخیص گفتار فارسی آماده شد؛ تشخیص گفتار آفلاین فعال است.");
                }
            }
            @Override public void onError(String message) {
                downloadingVoskModel = false;
                if (isFinishing() || isDestroyed()) return;
                if (status != null) status.setText("دریافت بستهٔ گفتار قطع شد؛ اوبا دوباره تلاش می‌کند. علت: " + message);
                // Retry automatically after network failures. Do not require the user to
                // press the microphone button again; the download starts again while the
                // activity is alive, with a capped delay to avoid hammering the server.
                final long retryDelayMs = 15000L;
                if (root != null) {
                    root.postDelayed(() -> {
                        if (!isFinishing() && !isDestroyed()
                                && OfflineModelDownloader.getInstalledModelDirectory(MainActivity.this) == null
                                && !downloadingVoskModel) {
                            downloadVoskModelAutomatically();
                        }
                    }, retryDelayMs);
                }
            }
        });
    }

    private void ensureVoskModelAndStart(String mode) {
        File modelDirectory = OfflineModelDownloader.getInstalledModelDirectory(this);
        if (modelDirectory != null) {
            startOfflineVoice(mode, modelDirectory);
            return;
        }
        // Do not leave reminder recording apparently inactive while the offline model downloads.
        // Use Android's speech service immediately as a fallback for this explicit button.
        if ("reminder_draft".equals(mode)) {
            startPlatformVoice(mode);
            return;
        }
        if (downloadingVoskModel) {
            if (status != null) status.setText("دریافت بستهٔ گفتار در حال انجام است؛ کمی صبر کن…");
            return;
        }
        downloadingVoskModel = true;
        if (status != null) status.setText("در حال دریافت بستهٔ تشخیص گفتار فارسی…");
        OfflineModelDownloader.downloadModel(this, new OfflineModelDownloader.Callback() {
            @Override public void onProgress(long downloadedBytes, long totalBytes) {
                if (status == null || isFinishing()) return;
                if (totalBytes > 0) {
                    int percent = (int) Math.min(100L, downloadedBytes * 100L / totalBytes);
                    status.setText("دریافت بستهٔ گفتار فارسی: " + percent + "%");
                } else {
                    status.setText("در حال دریافت بستهٔ گفتار فارسی…");
                }
            }
            @Override public void onInstalled(File installedDirectory) {
                downloadingVoskModel = false;
                if (isFinishing()) return;
                if (status != null) status.setText("بستهٔ تشخیص گفتار فارسی آماده شد؛ میکروفون را فعال می‌کنم…");
                startOfflineVoice(mode, installedDirectory);
            }
            @Override public void onError(String message) {
                downloadingVoskModel = false;
                if (isFinishing()) return;
                if (status != null) status.setText("دریافت بستهٔ تشخیص گفتار ناموفق بود: " + message);
                Toast.makeText(MainActivity.this, "دریافت بستهٔ تشخیص گفتار ناموفق بود: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void startOfflineVoice(String mode, File modelDirectory) {
        try {
            if (recognizer != null) { recognizer.destroy(); recognizer = null; }
            if (offlineEngine != null) { offlineEngine.stop(); offlineEngine = null; }
            // Keep a stable reference to this recognition session. Vosk stops its
            // SpeechService after a finalized utterance, but the field used to keep
            // pointing at the stopped engine; that prevented the next session starting.
            OfflineVoskSpeechEngine activeEngine = new OfflineVoskSpeechEngine();
            offlineEngine = activeEngine;
            activeEngine.start(this, modelDirectory, new OfflineVoskSpeechEngine.Listener() {
                @Override public void onStatus(String message) {
                    if (status != null && !isFinishing()) status.setText(message);
                }
                @Override public void onText(String text) {
                    if (continuousExpenseListening) {
                        if (status != null && !isFinishing()) status.setText("شنیدم: «" + text + "»");
                        handleContinuousExpense(text);
                    } else {
                        if (offlineEngine == activeEngine) offlineEngine = null;
                        if (status != null && !isFinishing()) status.setText("شنیدم: «" + text + "»");
                        if (awaitingVoiceConfirmation) handleVoiceConfirmation(text);
                        else handleVoice(text);
                    }
                }
                @Override public void onPartialText(String text) {
                    if (isFinishing()) return;
                    if (continuousExpenseListening) {
                        liveVoicePartial = text;
                        updateContinuousTranscript();
                        if (status != null) status.setText("در حال شنیدن…");
                    } else if ("reminder_draft".equals(mode) && reminderTranscriptView != null) {
                        String recognized = cleanReminderTranscript(text);
                        String compact = normalize(recognized).replace("‌", "").replace(" ", "")
                            .replace("ي", "ی").replace("ك", "ک")
                            .replaceAll("[،,؛;.!؟?]+", "");
                        boolean saveCommand = compact.contains("ذخیرهکن")
                            || compact.contains("ذخیرهیادآوری")
                            || compact.contains("ثبتکن")
                            || compact.contains("ثبتیادآوری")
                            || compact.contains("تمامشد")
                            || compact.contains("تمومشد");
                        // Keep reminder speech exclusively in the lower reminder panel.
                        // Never overwrite the saved draft with the final "ذخیره کن" utterance.
                        if (!recognized.isEmpty() && !saveCommand) {
                            prefs.edit().putString("oba_reminder_draft", recognized).apply();
                        }
                        String visibleDraft = prefs.getString("oba_reminder_draft", "").trim();
                        reminderTranscriptView.setText((visibleDraft.isEmpty() ? "" : visibleDraft)
                            + "\n\n🎙 در حال شنیدن… در پایان بگو «ذخیره کن».");
                    }
                }
                @Override public void onError(String message) {
                    if (offlineEngine == activeEngine) offlineEngine = null;
                    if (status != null && !isFinishing()) status.setText(message);
                    if ("reminder_draft".equals(mode) && !isFinishing()) {
                        startPlatformVoice(mode);
                        return;
                    }
                    if (continuousExpenseListening) {
                        continuousExpenseListening = false;
                        if (continuousExpenseButton != null) continuousExpenseButton.setText("🎙  شروع ثبت");
                    }
                }
            }, continuousExpenseListening);
        } catch (Exception e) {
            status.setText("شروع گفتار آفلاین ممکن نشد؛ دوباره دکمهٔ صوتی را بزن.");
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    private void ensureWakeServiceEnabled() {
        // Intentionally disabled: OBA no longer starts listening for its wake word.
        try { stopService(new Intent(this, VoiceWakeService.class)); } catch (Exception ignored) {}
        prefs.edit().putBoolean("wake_enabled", false).apply();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == AUDIO_PERMISSION && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startVoice(pendingMode);
        else { status.setText("برای فرمان صوتی باید اجازهٔ میکروفون بدهی."); say("برای استفاده صوتی اجازه میکروفون لازم است"); }
    }

    private void startWakeService() {
        // Intentionally disabled: voice wake-up by saying "OBA" is no longer supported.
        try { stopService(new Intent(this, VoiceWakeService.class)); } catch (Exception ignored) {}
        prefs.edit().putBoolean("wake_enabled", false).apply();
    }

    private boolean tryRegisterCategorizedExpense(String raw, String normalized) {
        String category = null;
        String subgroup = null;
        String[][] known = {
            {"خانه", "مواد غذایی", "لوازم خانه", "قبض و خدمات", "اجاره و تعمیرات", "نظافت", "سایر هزینه‌های خانه"},
            {"ماشین", "سوخت", "روغن و فیلتر", "تسمه تایم و تعمیرات", "بیمه", "لاستیک", "پارکینگ و عوارض", "سایر هزینه‌های ماشین"},
            {"مغازه", "خرید کالا", "اجاره مغازه", "قبض و خدمات", "حقوق", "تعمیرات", "سایر هزینه‌های مغازه"},
            {"مدرسه", "شهریه", "کتاب و لوازم‌التحریر", "رفت‌وآمد", "لباس فرم", "فعالیت‌ها", "سایر هزینه‌های مدرسه"},
            {"سلامت", "دکتر", "دارو", "آزمایش", "بیمه درمانی", "دندان‌پزشکی", "سایر هزینه‌های سلامت"},
            {"سایر", "خرید متفرقه", "هدیه", "سفر", "سایر"}
        };
        String[][] aliases = {
            {"خانه", "مواد غذایی", "لوازم خانه", "قبض و خدمات", "اجاره و تعمیرات", "نظافت", "سایر هزینه های خانه"},
            {"ماشین", "سوخت", "روغن و فیلتر", "تسمه تایم و تعمیرات", "بیمه", "لاستیک", "پارکینگ و عوارض", "سایر هزینه های ماشین"},
            {"مغازه", "خرید کالا", "اجاره مغازه", "قبض و خدمات", "حقوق", "تعمیرات", "سایر هزینه های مغازه"},
            {"مدرسه", "شهریه", "کتاب و لوازم التحریر", "رفت و آمد", "لباس فرم", "فعالیت ها", "سایر هزینه های مدرسه"},
            {"سلامت", "دکتر", "دارو", "آزمایش", "بیمه درمانی", "دندان پزشکی", "سایر هزینه های سلامت"},
            {"سایر", "خرید متفرقه", "هدیه", "سفر", "سایر"}
        };
        String[][] aliasesByCategory = {
            {"خانه","مواد غذایی","لوازم خانه","قبض و خدمات","اجاره و تعمیرات","نظافت","سایر هزینه های خانه"},
            {"ماشین","سوخت","روغن و فیلتر","تسمه تایم و تعمیرات","بیمه","لاستیک","پارکینگ و عوارض","سایر هزینه های ماشین"},
            {"مغازه","خرید کالا","اجاره مغازه","قبض و خدمات","حقوق","تعمیرات","سایر هزینه های مغازه"},
            {"مدرسه","شهریه","کتاب و لوازم التحریر","رفت و آمد","لباس فرم","فعالیت ها","سایر هزینه های مدرسه"},
            {"سلامت","دکتر","دارو","آزمایش","بیمه درمانی","دندان پزشکی","سایر هزینه های سلامت"},
            {"سایر","خرید متفرقه","هدیه","سفر","سایر"}
        };
        int catIndex = -1, subIndex = -1;
        for (int i=0; i<known.length; i++) {
            String cat = normalize(known[i][0]);
            if (normalized.contains(cat)) { catIndex=i; break; }
        }
        if (catIndex < 0) return false;
        for (int j=1; j<known[catIndex].length; j++) {
            String candidate = normalize(known[catIndex][j]);
            if (normalized.contains(candidate)) { subIndex=j; break; }
        }
        if (subIndex < 0 && catIndex == 0 && (normalized.contains("لوازم خانگی") || normalized.contains("وسایل خانه"))) {
            subIndex = 2; // Common spoken names for the "لوازم خانه" subgroup.
        }
        if (subIndex < 0) {
            for (int j=1; j<aliasesByCategory[catIndex].length; j++) {
                String candidate = normalize(aliasesByCategory[catIndex][j]);
                if (!candidate.isEmpty() && normalized.contains(candidate)) { subIndex=j; break; }
            }
        }
        if (subIndex < 0) return false;
        long amount = parseAmount(normalized);
        if (amount <= 0) return false;
        // Preserve spoken quantity/unit in the archive title (e.g. "۳ کیلو لوبیا").
        java.util.regex.Matcher quantityMatcher = java.util.regex.Pattern
            .compile("([0-9۰-۹]+(?:[.,٫][0-9۰-۹]+)?\\s*(?:کیلوگرم|کیلو|گرم|عدد|بسته|لیتر))")
            .matcher(raw);
        String quantityText = quantityMatcher.find() ? quantityMatcher.group(1).trim() : "";
        String title = raw;
        String[] remove = {"اوبا","اوبآ","خانه","ماشین","مغازه","مدرسه","سلامت","سایر",
            known[catIndex][subIndex], "تومان","تومن","هزار","میلیون","میلیارد","هزینه","خرج","خرید کردم","خریدم","خریداری کردم","به مبلغ","به قیمت","قیمت"};
        for (String word : remove) if (word != null && !word.isEmpty()) title = title.replace(word, " ");
        title = title.replaceAll("[0-9۰-۹,،٬]+", " ").replaceAll("\\s+", " ").trim();
        if (!quantityText.isEmpty()) {
            String unit = quantityText.replaceAll("[0-9۰-۹]+", "").trim();
            title = title.replace(unit, "").replaceAll("\\s+", " ").trim();
            title = quantityText + (title.isEmpty() ? "" : " " + title);
        }
        if (title.isEmpty()) title = "هزینه " + known[catIndex][subIndex];
        expenseTotal += amount;
        prefs.edit().putLong("expense", expenseTotal).apply();
        addEntry("هزینه • " + known[catIndex][0] + " • " + known[catIndex][subIndex], title, amount);
        String message = "ثبت شد؛ " + known[catIndex][0] + "، " + known[catIndex][subIndex] + "، " + fmt(amount) + " تومان، " + title;
        status.setText(message);
        say(message);
        refresh();
        return true;
    }

    private boolean awaitingArchiveCorrection = false;
    private String archiveCorrectionTarget = "";

    private void handleArchiveVoiceCommand(String raw, String normalized) {
        String compact = normalized.replace("‌", "").replace(" ", "");
        if (awaitingArchiveCorrection) {
            String corrected = raw.trim();
            String oldAll = prefs.getString("entries", "");
            String[] rows = oldAll.isEmpty() ? new String[0] : oldAll.split("\\n");
            java.util.ArrayList<Integer> matches = new java.util.ArrayList<>();
            for (int i = 0; i < rows.length; i++) {
                if (rows[i].toLowerCase(Locale.ROOT).contains(archiveCorrectionTarget.toLowerCase(Locale.ROOT))) matches.add(i);
            }
            awaitingArchiveCorrection = false;
            if (matches.size() == 1) {
                rows[matches.get(0)] = corrected;
                StringBuilder saved = new StringBuilder();
                for (String row : rows) { if (saved.length() > 0) saved.append("\n"); saved.append(row); }
                prefs.edit().putString("entries", saved.toString()).apply();
                entries.clear();
                for (String row : rows) if (!row.trim().isEmpty()) entries.add(row);
                refresh();
                status.setText("شرح مورد در آرشیو اصلاح شد. جمع مبالغ و تاریخچه تحلیلی هنوز جداگانه به‌روزرسانی نشده‌اند.");
            } else if (matches.size() > 1) {
                status.setText("چند مورد با نام «" + archiveCorrectionTarget + "» پیدا شد؛ برای جلوگیری از اصلاح اشتباه، فعلاً چیزی تغییر نکرد. نام مورد را همراه تاریخ یا مبلغ دقیق‌تر بگو.");
            } else status.setText("موردی با آن نام در آرشیو پیدا نشد؛ چیزی تغییر نکرد.");
            archiveCorrectionTarget = "";
            return;
        }
        if (compact.startsWith("اصلاح")) {
            String target = normalized.replaceFirst("^اصلاح(\\s+کن)?\\s*", "").trim();
            if (target.isEmpty()) {
                status.setText("بعد از «اصلاح» نام مورد را هم بگو؛ مثلاً «اصلاح پنیر، ۴ مهر».");
                return;
            }
            archiveCorrectionTarget = target;
            awaitingArchiveCorrection = true;
            status.setText("حالا شرح درست مورد «" + target + "» را کامل بگو. اگر چند مورد هم‌نام باشد، برای جلوگیری از اصلاح اشتباه چیزی تغییر نمی‌کند.");
            return;
        }
        if (compact.startsWith("پاککن") || compact.startsWith("حذفکن")) {
            String target = normalized.replaceFirst("^(پاک\\s*کن|حذف\\s*کن)\\s*", "").trim();
            if (target.isEmpty()) {
                status.setText("برای حذف دقیق، نام کالا و در صورت امکان تاریخ را بگو؛ مثلاً «پاک کن پنیر، ۴ مهر».");
                return;
            }
            // Never delete by a broad item-name match. Require one exact archive row and
            // a confirmation dialog. If a date is spoken, it must be present in that row.
            java.util.regex.Matcher dm = java.util.regex.Pattern
                .compile("((?:[0-3]?[0-9])\\s*(?:مهر|آبان|آذر|دی|بهمن|اسفند|فروردین|اردیبهشت|خرداد|تیر|مرداد|شهریور))")
                .matcher(target);
            String requestedDate = "";
            if (dm.find()) {
                requestedDate = dm.group(1).replaceAll("\\s+", " ").trim();
                target = (target.substring(0, dm.start()) + " " + target.substring(dm.end())).replaceAll("[،,]", " ").trim();
            }
            final String itemTarget = target;
            final String dateTarget = requestedDate;
            if (itemTarget.isEmpty()) {
                status.setText("نام کالا مشخص نیست؛ مثلاً «پاک کن پنیر، ۴ مهر».");
                return;
            }
            String oldAll = prefs.getString("entries", "");
            String[] rows = oldAll.isEmpty() ? new String[0] : oldAll.split("\\n");
            java.util.ArrayList<Integer> matches = new java.util.ArrayList<>();
            for (int i = 0; i < rows.length; i++) {
                String lower = rows[i].toLowerCase(Locale.ROOT);
                if (!lower.contains(itemTarget.toLowerCase(Locale.ROOT))) continue;
                if (!dateTarget.isEmpty() && !lower.contains(dateTarget.toLowerCase(Locale.ROOT))) continue;
                matches.add(i);
            }
            if (matches.isEmpty()) {
                if (!dateTarget.isEmpty()) {
                    status.setText("هیچ ردیف آرشیوی با نام «" + itemTarget + "» و تاریخ «" + dateTarget + "» پیدا نشد. برای جلوگیری از حذف پنیرِ روز دیگر، چیزی حذف نشد؛ آرشیو فعلی تاریخ هر خرید را در متن ردیف ذخیره نمی‌کند.");
                } else status.setText("موردی با نام «" + itemTarget + "» پیدا نشد؛ چیزی حذف نشد.");
                return;
            }
            final String[] choices = new String[matches.size()];
            for (int i = 0; i < matches.size(); i++) choices[i] = rows[matches.get(i)];
            final int[] selected = new int[]{-1};
            AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("انتخاب دقیق مورد برای حذف")
                .setSingleChoiceItems(choices, -1, (d, which) -> selected[0] = which)
                .setNegativeButton("انصراف", (d, w) -> status.setText("حذف لغو شد؛ آرشیو تغییری نکرد."))
                .setPositiveButton("حذف مورد انتخاب‌شده", (d, w) -> {
                    if (selected[0] < 0) {
                        status.setText("هیچ موردی انتخاب نشد؛ چیزی حذف نشد.");
                        return;
                    }
                    int rowIndex = matches.get(selected[0]);
                    StringBuilder kept = new StringBuilder();
                    for (int i = 0; i < rows.length; i++) {
                        if (i == rowIndex) continue;
                        if (kept.length() > 0) kept.append("\n");
                        kept.append(rows[i]);
                    }
                    prefs.edit().putString("entries", kept.toString()).apply();
                    entries.clear();
                    for (String row : kept.toString().split("\\n")) if (!row.trim().isEmpty()) entries.add(row);
                    refresh();
                    status.setText("فقط همین ردیف از آرشیو نمایشی حذف شد. جمع هزینه‌ها و تاریخچه تحلیلی هنوز به‌روزرسانی نشده‌اند.");
                });
            dialog.show();
        }
    }

    private void handleVoice(String raw) {
        String s = normalize(raw);
        status.setText("شنیدم: «" + raw + "»");

        // Route only messages that START with a date/day/time phrase to reminders.
        // Messages starting with a number continue to the expense/calendar parser.
        String dateRoute = s.replace("‌", " ").trim().replaceAll("\\s+", " ");
        boolean startsWithReminderDate = dateRoute.matches(
            "^(?:(?:پس\\s*فردا|فردا|امروز|پریروز|دیروز|"
            + "شنبه|یکشنبه|دوشنبه|سه\\s*شنبه|چهارشنبه|پنجشنبه|جمعه|"
            + "شنبه\\s*آینده|یکشنبه\\s*آینده|دوشنبه\\s*آینده|سه\\s*شنبه\\s*آینده|"
            + "چهارشنبه\\s*آینده|پنجشنبه\\s*آینده|جمعه\\s*آینده|"
            + "هفته\\s*آینده|ماه\\s*آینده|ساعت)\\b.*)$");
        if (startsWithReminderDate && !dateRoute.contains("ذخیره کن")) {
            String item = raw.trim();
            if (!item.isEmpty()) {
                long now = System.currentTimeMillis();
                prefs.edit().putString("reminder_note_" + now + "_0", now + "\n" + item).apply();
                if (reminderTranscriptView != null) reminderTranscriptView.setText(item);
                refreshReminders();
                status.setText("یادآوری در آرشیو یادآوری‌ها ثبت شد.");
                say("یادآوری ذخیره شد");
            }
            return;
        }
        String compactCommand = s.replace("‌", "").replace(" ", "")
            .replace("ي", "ی").replace("ك", "ک")
            .replaceAll("[،,؛;.!؟?]+", "");
        // Handle the save command before expense/category routing. Some recognizers
        // return extra leading words ("پیام صوتی ذخیره کن"), and mode can change
        // between final callbacks, so prefer the persisted reminder draft as source.
        String existingReminderDraft = prefs.getString("oba_reminder_draft", "").trim();
        // The blue transcript can receive live speech before the final recognition callback
        // persists it. Treat that visible transcript as a draft fallback for the save command.
        if (existingReminderDraft.isEmpty() && reminderTranscriptView != null) {
            existingReminderDraft = cleanReminderTranscript(reminderTranscriptView.getText().toString());
        }
        boolean hasReminderDraft = !existingReminderDraft.isEmpty();
        boolean explicitTextSaveCommand = compactCommand.contains("پیامذخیرهکن")
            || compactCommand.contains("پیامذخیره")
            || compactCommand.equals("ذخیرهپیام");
        if (explicitTextSaveCommand) {
            String typedText = reminderDraftInput != null
                ? reminderDraftInput.getText().toString()
                : prefs.getString("oba_reminder_text_draft", "");
            if (saveReminderTextToArchive(typedText)) {
                if (reminderDraftDialog != null && reminderDraftDialog.isShowing()) reminderDraftDialog.dismiss();
                reminderDraftDialog = null;
                reminderDraftInput = null;
                status.setText("پیام در آرشیو یادآوری‌ها ذخیره شد.");
                Toast.makeText(this, "پیام در آرشیو یادآوری‌ها ذخیره شد.", Toast.LENGTH_LONG).show();
            } else {
                status.setText("متنی برای ذخیره پیدا نشد؛ اول متن پیام را بنویس.");
                say("اول متن پیام را بنویس");
            }
            return;
        }
        boolean reminderSaveShortcut = hasReminderDraft
            && (compactCommand.contains("ذخیرهیادآوری")
                || compactCommand.contains("ذخیرهیادآوریها")
                || compactCommand.contains("ذخیره")
                || compactCommand.contains("ثبتکن")
                || compactCommand.contains("ثبتیادآوری")
                || compactCommand.equals("ثبت")
                || compactCommand.contains("تمامشد")
                || compactCommand.contains("تمومشد"));
        if (reminderSaveShortcut && !pendingMode.equals("saving_reminder")) {
            saveReminderDraft();
            return;
        }
        if (awaitingArchiveCorrection || compactCommand.startsWith("پاککن") || compactCommand.startsWith("حذفکن") || compactCommand.startsWith("اصلاح")) {
            handleArchiveVoiceCommand(raw, s);
            return;
        }

        // Accept a complete spoken expense command, including category and subgroup,
        // in one utterance: "خانه، لوازم خانه، پنج میلیون، خرید ماشین ظرف‌شویی".
        if (tryRegisterCategorizedExpense(raw, s)) return;

        String mode = pendingMode;
        if (mode.equals("shopping_list_voice")) {
            pendingMode = "auto";
            handleShoppingListVoice(raw);
            return;
        }
        if (mode.equals("reminder_draft")) {
            String reminderCommand = normalize(raw).replace("‌", "").replace(" ", "")
                .replace("ي", "ی").replace("ك", "ک")
                .replaceAll("[،,؛;.!؟?]+", "");
            boolean saveCommand = reminderCommand.contains("ذخیرهیادآوری")
                || reminderCommand.contains("ذخیره")
                || reminderCommand.contains("ثبتکن")
                || reminderCommand.contains("ثبتیادآوری")
                || reminderCommand.equals("ثبت")
                || reminderCommand.contains("تمامشد")
                || reminderCommand.contains("تمومشد");
            if (saveCommand) {
                // Switch mode immediately so a late speech callback cannot append the command
                // itself to the reminder draft or restart microphone capture.
                pendingMode = "saving_reminder";
                saveReminderDraft();
            } else {
                String draft = prefs.getString("oba_reminder_draft", "").trim();
                String phrase = raw.trim();
                String compact = normalize(phrase).replace("‌", "").replace(" ", "")
                    .replace("ي", "ی").replace("ك", "ک").replaceAll("[،,؛;.!؟?]+", "");
                if (!phrase.isEmpty() && !compact.equals("ذخیرهکن") && !compact.equals("ذخیرهیادآوری")) {
                    String next = draft.isEmpty() ? phrase : draft + "\n" + phrase;
                    prefs.edit().putString("oba_reminder_draft", next).apply();
                    if (reminderTranscriptView != null) reminderTranscriptView.setText(next + "\n\n🎙 متن یادآوری دریافت شد؛ برای ذخیره در آرشیو بگو «ذخیره کن».");
                }
                status.setText("متن یادآوری دریافت شد؛ برای ذخیره در آرشیو و پایان ضبط، بگو «ذخیره یادآوری».");
                pendingMode = "reminder_draft";
                if (root != null) root.postDelayed(() -> {
                    if ("reminder_draft".equals(pendingMode) && !isFinishing()) startVoice("reminder_draft");
                }, 350);
            }
            return;
        }
        if (mode.equals("subgroup_purchase")) {
            long amount = parseAmount(s);
            if (amount <= 0) { status.setText("مبلغ را هم بگو؛ مثلاً ۲ کیلو گوشت ۱۲۰۰۰۰۰ تومان."); say("لطفاً مبلغ خرید را هم بگو"); return; }
            // Preserve quantity/weight (especially kilograms) in the archive even when
            // the numeric amount is removed from the spoken sentence.
            java.util.regex.Matcher quantityMatcher = java.util.regex.Pattern
                .compile("([0-9۰-۹]+(?:[.,٫][0-9۰-۹]+)?\\s*(?:کیلوگرم|کیلو|گرم|عدد|بسته|لیتر))")
                .matcher(raw);
            String quantityText = quantityMatcher.find() ? quantityMatcher.group(1).trim() : "";
            String title = raw.replace("تومان", "").replace("تومن", "").trim();
            title = title.replaceAll("[0-9۰-۹,٬]+", " ").replaceAll("\\s+", " ").trim();
            if (!quantityText.isEmpty()) {
                String unitText = quantityText.replaceAll("[0-9۰-۹]+", "").trim();
                title = title.replace(unitText, "").replaceAll("\\s+", " ").trim();
                title = quantityText + (title.isEmpty() ? "" : " " + title);
            }
            if (title.isEmpty()) title = pendingPurchaseSubgroup;
            expenseTotal += amount;
            prefs.edit().putLong("expense", expenseTotal).apply();
            addEntry(pendingPurchaseCategory, pendingPurchaseSubgroup + " — " + title, amount);
            String message = "خرید در «" + pendingPurchaseSubgroup + "» به مبلغ " + fmt(amount) + " تومان در آرشیو و تقویم هزینه ثبت شد.";
            status.setText(message); say(message); refresh();
            pendingMode = "auto"; pendingPurchaseCategory = ""; pendingPurchaseSubgroup = "";
            return;
        }
        if (mode.equals("auto")) {
            if (contains(s,"بودجه خانه","بودجه ماهانه","تنظیم بودجه")) mode="budget";
            else if (contains(s,"موجودی خانه","چی داریم","چه چیزهایی داریم")) { refreshInventory(); status.setText(inventoryView.getText()); say("موجودی خانه روی صفحه نمایش داده شد"); return; }
            else if (contains(s,"مانده","خلاصه حساب","گزارش مالی","جمع هزینه","جمع درآمد")) {
                String summary="درآمد کل "+fmt(incomeTotal)+" تومان. هزینه کل "+fmt(expenseTotal)+" تومان. مانده "+fmt(incomeTotal-expenseTotal)+" تومان.";
                status.setText(summary); say(summary); return;
            } else if (contains(s,"سلامت","دکتر","پزشک","دارو","درمان","آزمایش","بیمارستان")) mode="health";
            else if (contains(s,"ماشین","خودرو","تعویض روغن","روغن موتور","کیلومتر","لنت ترمز","باتری ماشین","سرویس خودرو")) mode="vehicle";
            else if (contains(s,"یادآوری","یادم بنداز","یادآور")) mode="reminder";
            else if (contains(s,"درآمد","حقوق","فروختم","دریافت کردم")) mode="income";
            else if (contains(s,"ماشین حساب","حساب کن","جمع","ضرب","منهای","تقسیم")) mode="calc";
            else if (contains(s,"خریدم","خرید کردم","خریداری کردم","خرید به قیمت","خرید به مبلغ")) mode="shopping";
            else if (contains(s,"هزینه","خرج","پرداخت کردم","قیمت خرید")) mode="expense";
            else if (contains(s,"خرید","فهرست")) mode="shopping";
            else if (contains(s,"کار خانه","وظیفه","تمیزکاری")) mode="task";
            else mode="expense";
        }
        if (mode.equals("expense") || mode.equals("income")) {
            long amount = parseAmount(s);
            if (amount <= 0) { status.setText("مبلغ را هم بگو؛ مثلاً «هزینه پنجاه هزار تومان برای نان»"); say("لطفاً مبلغ را هم بگو"); return; }
            String title = cleanDescription(s, mode);
            if (mode.equals("income")) { incomeTotal += amount; prefs.edit().putLong("income",incomeTotal).apply(); addEntry("درآمد", title, amount); say("درآمد به مبلغ " + fmt(amount) + " تومان ثبت شد"); }
            else { expenseTotal += amount; prefs.edit().putLong("expense",expenseTotal).apply(); addEntry("هزینه", title, amount); say("هزینه " + fmt(amount) + " تومان ثبت شد"); }
            refresh(); return;
        }
        if (mode.equals("calc")) {
            try {
                double result = VoiceCalculator.evaluate(s);
                String resultText = new DecimalFormat("#,##0.####").format(result);
                String answer = "نتیجه محاسبه: " + resultText;
                status.setText(answer);
                say(answer);
            } catch (ArithmeticException ex) {
                String message = ex.getMessage() == null ? "این محاسبه ممکن نیست." : ex.getMessage();
                status.setText(message);
                say(message);
            } catch (IllegalArgumentException ex) {
                String message = "عبارت را واضح‌تر بگو؛ مثلاً «ماشین حساب ۲۰۰ جمع ۳۰ ضربدر ۲».";
                status.setText(message);
                say(message);
            }
            return;
        }
        if (mode.equals("health")) {
            long amount = parseAmount(s);
            if (amount <= 0) {
                status.setText("برای ثبت هزینه سلامت، مبلغ را هم بگو؛ مثلاً «هزینه ۳۰۰ هزار تومان دکتر».");
                say("مبلغ هزینه سلامت را هم بگو");
                return;
            }
            String title = raw.replace("تومان", "").replace("تومن", "").replace("هزار", "").replaceAll("[0-9۰-۹]+", " ").replaceAll("\\s+", " ").trim();
            if (title.isEmpty()) title = "هزینه سلامت";
            expenseTotal += amount;
            prefs.edit().putLong("expense", expenseTotal).apply();
            addEntry("سلامت", title, amount);
            String message = "هزینه سلامت به مبلغ " + fmt(amount) + " تومان ثبت شد.";
            status.setText(message); say(message); refresh(); return;
        }
        if (mode.equals("vehicle")) { handleVehicleVoice(raw, s); return; }
        if (mode.equals("shopping")) { handlePurchaseVoice(raw, s); return; }
        if (mode.equals("budget")) { handleBudgetVoice(raw, s); return; }
        if (mode.equals("stock_use")) { handleStockUse(raw); return; }
        if (mode.equals("task")) { addEntry("کار خانه", raw, 0); say("کار خانه ثبت شد"); refresh(); return; }
        if (mode.equals("reminder")) { handleReminderVoice(raw, s); return; }
        status.setText("فرمان دریافت شد: " + raw);
    }





    private void handleBudgetVoice(String raw, String s) {
        long amount = parseAmount(s);
        if (amount <= 0) {
            status.setText("مبلغ بودجه را بگو؛ مثلاً «بودجه ماهانه خانه پنج میلیون تومان»");
            say("مبلغ بودجه را بگو"); return;
        }
        long oldBudget = Math.max(0L, prefs.getLong("home_budget", 0L));
        if (oldBudget > Long.MAX_VALUE - amount) {
            status.setText("مبلغ بودجه بیش از حد بزرگ است.");
            say("مبلغ بیش از حد بزرگ است"); return;
        }
        long totalBudget = oldBudget + amount;
        prefs.edit().putLong("home_budget", totalBudget).apply();
        long remaining = totalBudget - expenseTotal;
        String msg = "مبلغ " + fmt(amount) + " تومان به بودجه اضافه شد. بودجه کل " + fmt(totalBudget) + " تومان است؛ مانده بودجه " + fmt(remaining) + " تومان است.";
        status.setText(msg); say(msg); refresh();
    }

    private void handleStockUse(String raw) {
        String item = raw.replace("مصرف کردم", "").replace("تمام شده است", "").replace("تمام شده", "").replace("تمام شد", "").replace("از موجودی کم کن", "").trim();
        String key = "stock_" + item;
        int qty = prefs.getInt(key, 0);
        if (item.isEmpty() || qty <= 0) {
            String msg = "این مورد در موجودی پیدا نشد. نام کالا را مثل زمان خرید بگو.";
            status.setText(msg); say(msg); return;
        }
        prefs.edit().putInt(key, Math.max(0, qty - 1)).apply();
        String msg = "یک قلم " + item + " از موجودی کم شد.";
        status.setText(msg); say(msg); refresh();
    }

    private void handlePurchaseVoice(String raw, String s) {
        long amount = parseAmount(s);
        if (amount <= 0) {
            addEntry("فهرست خرید", raw, 0);
            status.setText("مورد خرید به فهرست اضافه شد؛ برای ثبت هزینه بگو «خریدم شیر به قیمت ۸۰ هزار تومان».");
            say("به فهرست خرید اضافه شد. برای کم شدن از بودجه، قیمت را هم بگو.");
            refresh(); return;
        }
        java.util.regex.Matcher quantityMatcher = java.util.regex.Pattern
            .compile("([0-9۰-۹]+(?:[.,٫][0-9۰-۹]+)?\\s*(?:کیلوگرم|کیلو|گرم|عدد|بسته|لیتر))")
            .matcher(raw);
        String quantityText = quantityMatcher.find() ? quantityMatcher.group(1).trim() : "";
        String item = raw.replace("خریدم", "").replace("خرید کردم", "").replace("خریداری کردم", "")
            .replace("به قیمت", " ").replace("به مبلغ", " ").replace("قیمت", " ")
            .replace("تومان", " ").replace("تومن", " ").replace("هزار", " ").replace("میلیون", " ").replace("میلیارد", " ")
            .replaceAll("[0-9۰-۹,،٬]+", " ").replaceAll("\\s+", " ").trim();
        if (!quantityText.isEmpty()) {
            String unit = quantityText.replaceAll("[0-9۰-۹]+", "").trim();
            item = item.replace(unit, "").replaceAll("\\s+", " ").trim();
            item = quantityText + (item.isEmpty() ? "" : " " + item);
        }
        if (item.isEmpty()) item = "خرید خانه";
        expenseTotal += amount;
        prefs.edit().putLong("expense", expenseTotal).apply();
        String key = "stock_" + item;
        int qty = prefs.getInt(key, 0);
        prefs.edit().putInt(key, qty + 1).apply();
        addEntry("خرید: " + item, raw, amount);
        long budget = prefs.getLong("home_budget", 0);
        String msg = item + " به مبلغ " + fmt(amount) + " تومان ثبت شد؛ یک قلم به موجودی اضافه شد و هزینه از حساب خانه کم شد.";
        if (budget > 0) msg += " مانده بودجه " + fmt(budget - expenseTotal) + " تومان است.";
        status.setText(msg); say(msg); refresh();
    }

    private void refreshInventory() {
        if (inventoryView == null) return;
        StringBuilder out = new StringBuilder();
        for (String key : prefs.getAll().keySet()) {
            if (!key.startsWith("stock_")) continue;
            try {
                int qty = prefs.getInt(key, 0);
                if (qty <= 0) continue;
                if (out.length() > 0) out.append("\\n");
                out.append("• ").append(key.substring(6)).append(" — ").append(qty).append(" قلم");
            } catch (Exception ignored) {}
        }
        inventoryView.setText(out.length() == 0 ? "هنوز خریدی در موجودی ثبت نشده است. بگو: «خریدم شیر به قیمت ۸۰ هزار تومان»." : out.toString());
    }

    private void showReminderList() {
        new AlertDialog.Builder(this)
            .setTitle("🔔 یادآوری‌ها")
            .setItems(new String[]{"✍️ ثبت متنی یادآوری + انتخاب آلارم", "🗂️ آرشیو یادآوری‌ها", "🎵 انتخاب صدای آلارم"}, (dialog, which) -> {
                if (which == 0) showReminderTextEntry();
                else if (which == 1) showReminderArchive();
                else pickReminderAlarmSound();
            })
            .setNegativeButton("بستن", null)
            .show();
    }

    private boolean saveReminderTextToArchive(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String[] lines = text.trim().split("\\n");
        long now = System.currentTimeMillis();
        int saved = 0;
        android.content.SharedPreferences.Editor editor = prefs.edit();
        for (String line : lines) {
            String item = line.trim();
            if (item.isEmpty()) continue;
            editor.putString("reminder_note_" + now + "_" + saved, now + "\n" + item);
            saved++;
        }
        if (saved == 0) return false;
        editor.remove("oba_reminder_text_draft");
        editor.apply();
        refreshReminders();
        return true;
    }

    private void showReminderTextEntry() {
        reminderDraftInput = new EditText(this);
        reminderDraftInput.setHint("متن یادآوری را بنویس…");
        reminderDraftInput.setTextDirection(View.TEXT_DIRECTION_RTL);
        reminderDraftInput.setGravity(Gravity.RIGHT | Gravity.TOP);
        reminderDraftInput.setMinLines(4);
        reminderDraftInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        reminderDraftInput.setText(prefs.getString("oba_reminder_text_draft", ""));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(reminderDraftInput, new ScrollView.LayoutParams(-1, -2));
        reminderDraftDialog = new AlertDialog.Builder(this)
            .setTitle("ثبت متنی یادآوری و آلارم")
            .setMessage("متن را بنویس؛ سپس حتماً تاریخ و ساعت آلارم را انتخاب کن.")
            .setView(scroll)
            .setPositiveButton("انتخاب تاریخ و ساعت آلارم", null)
            .setNegativeButton("انصراف", (dialog, which) -> {
                String text = reminderDraftInput == null ? "" : reminderDraftInput.getText().toString();
                prefs.edit().putString("oba_reminder_text_draft", text).apply();
            })
            .create();
        reminderDraftDialog.setOnShowListener(dialog -> reminderDraftDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = reminderDraftInput == null ? "" : reminderDraftInput.getText().toString().trim();
            if (text.isEmpty()) {
                reminderDraftInput.setError("اول متن یادآوری را بنویس");
                return;
            }
            Calendar initial = Calendar.getInstance();
            initial.add(Calendar.DAY_OF_MONTH, 1);
            DatePickerDialog datePicker = new DatePickerDialog(this, (view, year, month, day) -> {
                Calendar selected = Calendar.getInstance();
                selected.set(Calendar.YEAR, year);
                selected.set(Calendar.MONTH, month);
                selected.set(Calendar.DAY_OF_MONTH, day);
                TimePickerDialog timePicker = new TimePickerDialog(this, (timeView, hour, minute) -> {
                    selected.set(Calendar.HOUR_OF_DAY, hour);
                    selected.set(Calendar.MINUTE, minute);
                    selected.set(Calendar.SECOND, 0);
                    selected.set(Calendar.MILLISECOND, 0);
                    long when = selected.getTimeInMillis();
                    if (when <= System.currentTimeMillis()) {
                        Toast.makeText(this, "زمان آلارم باید در آینده باشد؛ دوباره تاریخ و ساعت را انتخاب کن.", Toast.LENGTH_LONG).show();
                        showReminderTextEntry();
                        return;
                    }
                    saveTypedReminderWithAlarm(text, when);
                }, 9, 0, false);
                timePicker.setTitle("ساعت آلارم را انتخاب کن");
                timePicker.show();
            }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH));
            datePicker.setTitle("تاریخ آلارم را انتخاب کن");
            datePicker.getDatePicker().setMinDate(System.currentTimeMillis() - 1000);
            datePicker.show();
        }));
        reminderDraftDialog.show();
    }

    private void saveTypedReminderWithAlarm(String text, long when) {
        String title = text.replaceAll("\\s+", " ").trim();
        if (title.isEmpty()) {
            Toast.makeText(this, "متن یادآوری خالی است.", Toast.LENGTH_LONG).show();
            return;
        }
        int id = (int) (System.currentTimeMillis() & 0x7fffffff);
        if (id == 0) id = 1;
        String saved = when + "\nonce\n" + title + "\n-1";
        String archiveKey = "reminder_note_" + when + "_" + id;
        prefs.edit()
            .putString("reminder_" + id, saved)
            .putString(archiveKey, when + "\n" + text.trim())
            .remove("oba_reminder_text_draft")
            .apply();
        ReminderReceiver.schedule(this, id, when, title, false);
        ensureExactAlarmAccess();
        if (reminderDraftDialog != null && reminderDraftDialog.isShowing()) reminderDraftDialog.dismiss();
        reminderDraftDialog = null;
        reminderDraftInput = null;
        refreshReminders();
        String confirmation = "یادآوری متنی در آرشیو ذخیره شد و آلارم برای " + formatPersianDateTime(new Date(when)) + " تنظیم شد.";
        status.setText(confirmation);
        Toast.makeText(this, confirmation, Toast.LENGTH_LONG).show();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 45);
        }
        // Open the archive so the newly saved reminder is immediately visible.
        showReminderArchive();
    }

    private void showReminderArchive() {
        remindersView = new TextView(this);
        remindersView.setTextSize(16);
        remindersView.setTextColor(dark);
        remindersView.setGravity(Gravity.RIGHT | Gravity.TOP);
        remindersView.setTextDirection(View.TEXT_DIRECTION_RTL);
        remindersView.setPadding(dp(18), dp(12), dp(18), dp(12));
        refreshReminders();
        ScrollView scroll = new ScrollView(this);
        scroll.addView(remindersView, new ScrollView.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
            .setTitle("🗂️ آرشیو یادآوری‌ها")
            .setView(scroll)
            .setNegativeButton("بستن", null)
            .setPositiveButton("پاک کن", (dialog, which) -> confirmClearArchive(
                "پاک کردن آرشیو یادآوری‌ها",
                "تمام یادآوری‌های ذخیره‌شده پاک شوند؟ این کار قابل بازگشت نیست.",
                this::clearReminderArchive))
            .show();
    }

    private void pickReminderAlarmSound() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("audio/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(picker, REMINDER_SOUND_PICKER); }
        catch (Exception e) { Toast.makeText(this, "انتخاب فایل صوتی در گوشی در دسترس نیست.", Toast.LENGTH_LONG).show(); }
    }

    private void showReminderSoundPreview(Uri soundUri) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(48, 24, 48, 12);

        Button playButton = new Button(this);
        playButton.setText("▶ پخش آهنگ");
        playButton.setOnClickListener(v -> playReminderSoundPreview(soundUri));
        panel.addView(playButton, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button stopButton = new Button(this);
        stopButton.setText("■ قطع آهنگ");
        stopButton.setOnClickListener(v -> {
            stopReminderSoundPreview();
            Toast.makeText(this, "پخش آهنگ متوقف شد.", Toast.LENGTH_SHORT).show();
        });
        panel.addView(stopButton, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button chooseButton = new Button(this);
        chooseButton.setText("انتخاب آهنگ دیگر");
        chooseButton.setOnClickListener(v -> {
            stopReminderSoundPreview();
            pickReminderAlarmSound();
        });
        panel.addView(chooseButton, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
            .setTitle("🎵 پیش‌شنوی آهنگ آلارم")
            .setMessage("برای شنیدن آهنگ انتخاب‌شده، پخش را بزن؛ هر زمان خواستی می‌توانی قطعش کنی.")
            .setView(panel)
            .setNegativeButton("تمام", (dialog, which) -> {
                stopReminderSoundPreview();
                Toast.makeText(this, "آهنگ آلارم ذخیره شد.", Toast.LENGTH_SHORT).show();
            })
            .show();
    }

    private void playReminderSoundPreview(Uri soundUri) {
        stopReminderSoundPreview();
        try {
            reminderPreviewPlayer = new MediaPlayer();
            reminderPreviewPlayer.setDataSource(this, soundUri);
            reminderPreviewPlayer.setOnPreparedListener(player -> player.start());
            reminderPreviewPlayer.setOnCompletionListener(player -> stopReminderSoundPreview());
            reminderPreviewPlayer.setOnErrorListener((player, what, extra) -> {
                stopReminderSoundPreview();
                Toast.makeText(this, "پخش این فایل ممکن نیست؛ یک فایل صوتی دیگر انتخاب کن.", Toast.LENGTH_LONG).show();
                return true;
            });
            reminderPreviewPlayer.prepareAsync();
        } catch (Exception e) {
            stopReminderSoundPreview();
            Toast.makeText(this, "پخش آهنگ انجام نشد؛ دسترسی فایل صوتی را بررسی کن.", Toast.LENGTH_LONG).show();
        }
    }

    private void stopReminderSoundPreview() {
        if (reminderPreviewPlayer != null) {
            try { if (reminderPreviewPlayer.isPlaying()) reminderPreviewPlayer.stop(); } catch (Exception ignored) {}
            try { reminderPreviewPlayer.release(); } catch (Exception ignored) {}
            reminderPreviewPlayer = null;
        }
    }

    private void refreshReminders() {
        java.util.ArrayList<String> rows = new java.util.ArrayList<>();
        for (String key : prefs.getAll().keySet()) {
            if (!key.startsWith("reminder_")) continue;
            try {
                String value = prefs.getString(key, "");
                if (key.startsWith("reminder_note_")) {
                    String[] noteParts = value.split("\\n", 2);
                    if (noteParts.length >= 2 && !noteParts[1].trim().isEmpty()) {
                        long noteWhen = Long.parseLong(noteParts[0]);
                        rows.add(noteWhen + "|" + "📝 " + noteParts[1].trim() + " — " + formatPersianDateTime(new Date(noteWhen)));
                    }
                    continue;
                }
                String[] parts = value.split("\\n", 4);
                if (parts.length < 3) continue;
                long when = Long.parseLong(parts[0]);
                boolean monthly = "monthly".equals(parts[1]);
                String title = parts[2];
                String date = formatPersianDateTime(new Date(when));
                rows.add(when + "|" + "⏰ " + title + " — " + date + (monthly ? " (هر ماه)" : ""));
            } catch (Exception ignored) {}
        }
        java.util.Collections.sort(rows, (a,b) -> Long.compare(Long.parseLong(a.substring(0,a.indexOf("|"))), Long.parseLong(b.substring(0,b.indexOf("|")))));
        StringBuilder out = new StringBuilder();
        for (String row : rows) {
            if (out.length() > 0) out.append("\n");
            out.append(row.substring(row.indexOf("|")+1));
        }
        String reminderText = out.length() == 0
                ? "هنوز یادآوری زمان‌بندی‌شده‌ای ثبت نشده است. با صدا بگو: «یادآوری قسط فردا ساعت ۹ صبح»."
                : out.toString();
        // Saving can call refreshReminders before the archive dialog/view exists.
        if (remindersView != null) remindersView.setText(reminderText);
    }

    private void startReminderDraft() {
        // Voice reminders use the same blue transcript box visible on the home screen.
        String existing = prefs.getString("oba_reminder_draft", "").trim();
        if (reminderTranscriptView != null) {
            reminderTranscriptView.setText(existing.isEmpty()
                ? "🎙 در حال ثبت یادآوری… صحبت کن. در پایان بگو «ذخیره کن»."
                : existing + "\n\nادامه بده؛ در پایان بگو «ذخیره کن».");
            reminderTranscriptView.setTextColor(Color.WHITE);
            reminderTranscriptView.setVisibility(View.VISIBLE);
            View parent = (View) reminderTranscriptView.getParent();
            while (parent != null && !(parent instanceof ScrollView)) parent = (View) parent.getParent();
            if (parent instanceof ScrollView) {
                final ScrollView scroll = (ScrollView) parent;
                scroll.post(() -> scroll.smoothScrollTo(0, reminderTranscriptView.getTop()));
            }
        }
        pendingMode = "reminder_draft";
        status.setText("🎙 میکروفون در حال فعال‌شدن است؛ اگر درخواست اجازه آمد، اجازه بده. صحبت کن و در پایان بگو «ذخیره کن».");
        startVoice("reminder_draft");
    }

    private String cleanReminderTranscript(String transcript) {
        if (transcript == null) return "";
        StringBuilder clean = new StringBuilder();
        for (String line : transcript.split("\\n")) {
            String item = line.trim();
            if (item.isEmpty()) continue;
            if (item.contains("در حال شنیدن") || item.contains("در پایان بگو")
                    || item.contains("متن دریافت شد") || item.contains("ادامه بده")
                    || item.contains("در حال ثبت یادآوری") || item.contains("برای ذخیره در آرشیو")) continue;
            if (item.startsWith("🎙")) continue;
            if (clean.length() > 0) clean.append("\n");
            clean.append(item);
        }
        return clean.toString().trim();
    }

    private void saveReminderDraft() {
        String draft = prefs.getString("oba_reminder_draft", "").trim();
        // Fallback to the actual blue transcript text if recognition updated the UI
        // but its final callback did not persist the text into SharedPreferences.
        if (reminderTranscriptView != null) {
            String visibleDraft = cleanReminderTranscript(reminderTranscriptView.getText().toString());
            if (!visibleDraft.isEmpty() && (draft.isEmpty() || visibleDraft.length() > draft.length())) {
                draft = visibleDraft;
            }
        }
        if (draft.isEmpty()) {
            status.setText("هنوز متنی برای یادآوری گفته نشده است.");
            say("اول متن یادآوری را بگو");
            pendingMode = "reminder_draft";
            startVoice("reminder_draft");
            return;
        }
        // Stop both speech backends before writing to the archive; this prevents a late
        // recognizer callback from appending more text after the user said "ذخیره کن".
        pendingMode = "saving_reminder";
        if (offlineEngine != null) {
            try { offlineEngine.stop(); } catch (Exception ignored) {}
            offlineEngine = null;
        }
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
            try { recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }

        // Persist the recovered transcript before parsing so the archive save path
        // is independent of whether the speech engine delivered a final callback.
        prefs.edit().putString("oba_reminder_draft", draft).apply();
        String[] lines = draft.split("\\n");
        int savedCount = 0;
        for (String line : lines) {
            String item = line.trim();
            if (item.isEmpty()) continue;
            item = item.replace("یادآوری اول", "").replace("یادآوری دوم", "").replace("یادآوری سوم", "").replace("یادآوری چهارم", "").trim();
            if (item.isEmpty()) continue;

            // Keep every spoken reminder as readable archive text. If it contains a
            // recognizable time and task, also register it as a scheduled reminder.
            java.util.regex.Matcher tm = java.util.regex.Pattern.compile("ساعت\\s*([0-9۰-۹]{1,2})(?:[:٫.]([0-9۰-۹]{2}))?").matcher(item);
            if (tm.find()) {
                String timePhrase = tm.group();
                String datePhrase = item.substring(0, tm.start()).replaceAll("[،,؛;\\s]+$", "").trim();
                String task = item.substring(tm.end()).replaceFirst("^[،,؛;\\s]+", "").trim();
                if (!task.isEmpty()) {
                    String command = "یادآوری " + task + " " + datePhrase + " " + timePhrase;
                    handleReminderVoice(command, normalize(command));
                }
            }
            long now = System.currentTimeMillis();
            prefs.edit().putString("reminder_note_" + now + "_" + savedCount, now + "\n" + item).apply();
            savedCount++;
        }
        prefs.edit().remove("oba_reminder_draft").apply();
        if (reminderTranscriptView != null) reminderTranscriptView.setText(
            savedCount > 0
                ? "✅ ذخیره کن انجام شد؛ " + savedCount + " یادآوری در آرشیو ذخیره شد."
                : "⚠️ متن یادآوری برای ذخیره پیدا نشد. دوباره صحبت کن و در پایان بگو «ذخیره کن»."
        );
        if (reminderDraftDialog != null && reminderDraftDialog.isShowing()) reminderDraftDialog.dismiss();
        reminderDraftDialog = null;
        reminderDraftInput = null;
        pendingMode = "auto";
        String msg = savedCount > 0 ? savedCount + " یادآوری در آرشیو ذخیره شد." : "یادآوری ذخیره نشد.";
        status.setText(msg);
        say(msg);
        refreshReminders();
    }

    private int countReminderRecords() {
        int count = 0;
        for (String key : prefs.getAll().keySet()) if (key.startsWith("reminder_")) count++;
        return count;
    }

    private void handleReminderVoice(String raw, String s) {
        if (contains(s, "لیست یادآوری", "یادآوری های من", "یادآوری‌های من", "یادآوری ها")) {
            refreshReminders();
            status.setText(remindersView.getText());
            say("یادآوری‌های فعال در بخش یادآوری‌های من نمایش داده شده‌اند");
            return;
        }
        if (contains(s, "لغو یادآوری", "حذف یادآوری", "پاک کن یادآوری")) {
            String target = raw.replace("لغو یادآوری", "").replace("حذف یادآوری", "").replace("پاک کن یادآوری", "").trim();
            int removed = 0;
            for (String key : new java.util.ArrayList<>(prefs.getAll().keySet())) {
                if (!key.startsWith("reminder_")) continue;
                try {
                    String[] parts = prefs.getString(key, "").split("\\n", 4);
                    if (parts.length >= 3 && (target.isEmpty() || parts[2].contains(target))) {
                        int rid = Integer.parseInt(key.substring("reminder_".length()));
                        ReminderReceiver.cancel(this, rid);
                        removed++;
                    }
                } catch (Exception ignored) {}
            }
            refresh();
            String msg = removed == 0 ? "یادآوری مطابق این نام پیدا نشد." : removed + " یادآوری لغو شد.";
            status.setText(msg); say(msg); return;
        }
        boolean monthly = contains(s, "هر ماه", "ماهانه");
        Calendar now = Calendar.getInstance();
        Calendar due = (Calendar) now.clone();
        int monthlyDay = -1;
        int hour = -1, minute = 0;
        java.util.regex.Matcher tm = java.util.regex.Pattern.compile("ساعت\\s*([0-9]{1,2})(?:[:٫.]([0-9]{2}))?").matcher(s);
        if (tm.find()) {
            hour = Integer.parseInt(tm.group(1));
            if (tm.group(2) != null) minute = Integer.parseInt(tm.group(2));
            if (contains(s, "شب", "بعدازظهر", "بعد از ظهر") && hour < 12) hour += 12;
            if (contains(s, "صبح") && hour == 12) hour = 0;
            if (hour > 23 || minute > 59) hour = -1;
        }
        if (hour < 0) {
            status.setText("برای زمان‌بندی دقیق، ساعت را هم بگو؛ مثلاً «یادآوری قسط فردا ساعت ۹ صبح».");
            say("لطفاً ساعت یادآوری را هم بگو");
            return;
        }
        due.set(Calendar.HOUR_OF_DAY, hour);
        due.set(Calendar.MINUTE, minute);
        due.set(Calendar.SECOND, 0);
        due.set(Calendar.MILLISECOND, 0);

        if (monthly) {
            int day = extractReminderDay(s);
            monthlyDay = day;
            if (day < 1 || day > 31) {
                status.setText("برای یادآوری ماهانه، روز ماه را مشخص کن؛ مثلاً «یادآوری قسط روز ۲۰ هر ماه ساعت ۹ صبح».");
                say("روز ماه را هم بگو");
                return;
            }
            due.set(Calendar.DAY_OF_MONTH, Math.min(day, due.getActualMaximum(Calendar.DAY_OF_MONTH)));
            if (!due.after(now)) {
                due.add(Calendar.MONTH, 1);
                due.set(Calendar.DAY_OF_MONTH, Math.min(day, due.getActualMaximum(Calendar.DAY_OF_MONTH)));
            }
        } else if (contains(s, "پس فردا", "پس‌فردا")) {
            due.add(Calendar.DAY_OF_MONTH, 2);
        } else if (contains(s, "فردا")) {
            due.add(Calendar.DAY_OF_MONTH, 1);
        } else if (contains(s, "امروز", "امشب")) {
            if (!due.after(now)) due.add(Calendar.DAY_OF_MONTH, 1);
        } else {
            status.setText("تاریخ را هم مشخص کن؛ مثلاً «یادآوری قسط فردا ساعت ۹ صبح» یا «روز ۲۰ هر ماه ساعت ۹».");
            say("لطفاً روز یا تاریخ یادآوری را هم مشخص کن");
            return;
        }

        String title = raw.replace("یادم بنداز", "").replace("یادآوری", "").replace("یادآور", "").trim();
        title = title.replace("هر ماه", "").replace("ماهانه", "").replace("فردا", "").replace("پس فردا", "").replace("پس‌فردا", "").replace("امروز", "").replace("امشب", "").replace("صبح", "").replace("شب", "").replace("بعدازظهر", "").replace("بعد از ظهر", "");
        title = title.replaceAll("ساعت\\s*[0-9]{1,2}(?:[:٫.][0-9]{2})?", "").replaceAll("[0-9۰-۹]+", " ").replaceAll("\\s+", " ").trim();
        if (title.isEmpty()) title = "یادآوری خانه";
        int id = (int)(System.currentTimeMillis() & 0x7fffffff);
        long when = due.getTimeInMillis();
        String saved = when + "\n" + (monthly ? "monthly" : "once") + "\n" + title + "\n" + monthlyDay;
        prefs.edit().putString("reminder_" + id, saved).apply();
        ReminderReceiver.schedule(this, id, when, title, monthly);
        ensureExactAlarmAccess();
        String whenText = formatPersianDateTime(due.getTime());
        String confirmation = "یادآوری «" + title + "» برای " + whenText + (monthly ? " و تکرار ماهانه" : "") + " ثبت شد.";
        addEntry("یادآوری", confirmation, 0);
        status.setText(confirmation);
        say(confirmation);
        refresh();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 45);
        }
    }

    private int extractReminderDay(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:روز\\s*)?([0-9]{1,2})\\s*(?:ام)?\\s*(?:هر ماه|ماهانه)").matcher(s);
        if (m.find()) try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        m = java.util.regex.Pattern.compile("روز\\s*([0-9]{1,2})").matcher(s);
        if (m.find()) try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        String[] ordinals = {"سی و یکم","سی‌ویکم","سی‌ام","سی ام","بیست و نهم","بیست‌ونهم","بیست و هشتم","بیست‌وهشتم","بیست و هفتم","بیست‌وهفتم","بیست و ششم","بیست‌وششم","بیست و پنجم","بیست‌وپنجم","بیست و چهارم","بیست‌وچهارم","بیست و سوم","بیست‌وسوم","بیست و دوم","بیست‌ودوم","بیستم","نوزدهم","هجدهم","هفدهم","شانزدهم","پانزدهم","چهاردهم","سیزدهم","دوازدهم","یازدهم","دهم","نهم","هشتم","هفتم","ششم","پنجم","چهارم","سوم","دوم","اول"};
        int[] vals = {31,31,30,30,29,29,28,28,27,27,26,26,25,25,24,24,23,23,22,22,20,19,18,17,16,15,14,13,12,11,10,9,8,7,6,5,4,3,2,1};
        for (int i=0;i<ordinals.length;i++) if(s.contains(ordinals[i])) return vals[i];
        return -1;
    }

    private void handleVehicleVoice(String raw, String s) {
        boolean oil = contains(s, "روغن", "تعویض روغن");
        boolean service = oil || contains(s, "سرویس", "تعمیر", "لنت", "باتری", "لاستیک", "فیلتر", "شمع", "تسمه");
        if (!service) {
            status.setText("برای ثبت خودرو بگو چه سرویسی انجام شد؛ مثلاً تعویض روغن در کیلومتر ۸۵ هزار.");
            say("نام سرویس خودرو را بگو");
            return;
        }

        long currentKm = extractMileage(s);
        long nextKm = extractNextMileage(s);
        String date = formatPersianDate(new Date());
        String kind = oil ? "تعویض روغن" : "سرویس/تعمیر خودرو";
        StringBuilder detail = new StringBuilder(kind).append(" • ").append(date);
        if (currentKm > 0) detail.append(" • کیلومتر فعلی: ").append(fmt(currentKm));
        if (nextKm > 0) detail.append(" • سرویس بعدی: ").append(fmt(nextKm)).append(" کیلومتر");

        String all = prefs.getString("vehicleRecords", "");
        String record = detail.toString();
        all = record + (all.isEmpty() ? "" : "\n" + all);
        String[] lines = all.split("\n");
        StringBuilder saved = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 30); i++) {
            if (i > 0) saved.append("\n");
            saved.append(lines[i]);
        }
        prefs.edit().putString("vehicleRecords", saved.toString()).apply();
        addEntry("ماشین خانه", record, 0);
        refresh();

        if (currentKm <= 0) {
            status.setText("نوع سرویس ثبت شد؛ برای کامل شدن سابقه، کیلومتر فعلی را هم بگو. مثلاً «تعویض روغن در کیلومتر ۸۵ هزار».");
            say("سرویس ثبت شد. برای کامل شدن سابقه، کیلومتر فعلی خودرو را هم بگو.");
        } else if (nextKm <= 0) {
            status.setText("سرویس در کیلومتر " + fmt(currentKm) + " ثبت شد. کیلومتر سرویس بعدی هنوز مشخص نشده است.");
            say(kind + " در کیلومتر " + fmt(currentKm) + " ثبت شد. برای یادآوری بر اساس کیلومتر، عدد سرویس بعدی را هم بگو.");
        } else {
            status.setText("ثبت شد: " + kind + " در کیلومتر " + fmt(currentKm) + "؛ سرویس بعدی در " + fmt(nextKm) + " کیلومتر.");
            say("ثبت شد. " + kind + " در کیلومتر " + fmt(currentKm) + " و سرویس بعدی در کیلومتر " + fmt(nextKm));
        }
    }

    private long extractMileage(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:کیلومتر\\s*)([0-9]+)\\s*هزار").matcher(s);
        if (m.find()) try { return Long.parseLong(m.group(1)) * 1000L; } catch (Exception ignored) {}
        m = java.util.regex.Pattern.compile("([0-9]+)\\s*هزار\\s*کیلومتر").matcher(s);
        if (m.find()) try { return Long.parseLong(m.group(1)) * 1000L; } catch (Exception ignored) {}
        m = java.util.regex.Pattern.compile("کیلومتر\\s*([0-9]+)").matcher(s);
        if (m.find()) try { return Long.parseLong(m.group(1)); } catch (Exception ignored) {}
        m = java.util.regex.Pattern.compile("([0-9]+)\\s*کیلومتر").matcher(s);
        if (m.find()) try { return Long.parseLong(m.group(1)); } catch (Exception ignored) {}
        return 0;
    }

    private long extractNextMileage(String s) {
        int idx = s.indexOf("بعدی");
        if (idx < 0) idx = s.indexOf("بعد");
        if (idx < 0 || idx >= s.length() - 1) return 0;
        return extractMileage(s.substring(idx));
    }

    private String cleanDescription(String s, String mode) {
        String x = s.replace("تومان","").replace("ریال","").replace("هزینه","").replace("خرج","").replace("مخارج","").replace("درآمد","").replace("حقوق","").trim();
        x = x.replaceAll("[0-9۰-۹]+"," ").replaceAll("\\s+"," ").trim();
        if (x.isEmpty()) return mode.equals("income") ? "درآمد" : "هزینه خانه";
        return x;
    }

    private long parseAmount(String s) {
        // Normalize Persian/Arabic digits before parsing.
        String normalized = normalize(s == null ? "" : s);
        // Speech recognition may split a Persian compound amount into a word multiplier
        // and digits, e.g. "شش ۱۰۰۰۰۰ تومان". Recombine that before the ordinary parser.
        java.util.regex.Matcher spokenMultiplier = java.util.regex.Pattern
            .compile("([\\u0600-\\u06FF]+(?:\\s+[\\u0600-\\u06FF]+){0,2})\\s+([0-9]{4,})\\s*(?:تومان|تومن|ریال)")
            .matcher(normalized);
        long combinedAmount = -1;
        while (spokenMultiplier.find()) {
            long multiplier = wordNumber(spokenMultiplier.group(1).trim());
            try {
                long base = Long.parseLong(spokenMultiplier.group(2));
                if (multiplier > 0 && base > 0 && multiplier <= Long.MAX_VALUE / base) {
                    combinedAmount = multiplier * base;
                }
            } catch (Exception ignored) {}
        }
        if (combinedAmount > 0) return combinedAmount;

        // Prefer the number immediately associated with the currency amount, not an
        // earlier quantity such as "3 کیلو". Search each amount marker independently.
        java.util.regex.Pattern currencyPattern = java.util.regex.Pattern.compile(
            "([0-9]+(?:[,٬.][0-9]{3})*|[\\u0600-\\u06FF]+(?:\\s+[\\u0600-\\u06FF]+){0,3})\\s*(میلیارد|میلیون|هزار)?\\s*(?:تومان|تومن|ریال)"
        );
        java.util.regex.Matcher currency = currencyPattern.matcher(normalized);
        long currencyAmount = -1;
        while (currency.find()) {
            String numberPart = currency.group(1).replace(",", "").replace("٬", "").replace(".", "").trim();
            long n;
            try { n = Long.parseLong(numberPart); }
            catch (Exception ex) { n = wordNumber(numberPart); }
            String scale = currency.group(2) == null ? "" : currency.group(2);
            if (n > 0) {
                if ("میلیارد".equals(scale)) n *= 1000000000L;
                else if ("میلیون".equals(scale)) n *= 1000000L;
                else if ("هزار".equals(scale)) n *= 1000L;
                currencyAmount = n;
            }
        }
        if (currencyAmount > 0) return currencyAmount;

        // Fallback: explicit scaled amount phrases. Pick the number immediately before
        // the scale word, rather than the first number in the whole utterance.
        java.util.regex.Matcher scaled = java.util.regex.Pattern
            .compile("([0-9]+)\\s*(میلیارد|میلیون|هزار)").matcher(normalized);
        long scaledAmount = -1;
        while (scaled.find()) {
            try {
                long n = Long.parseLong(scaled.group(1));
                String scale = scaled.group(2);
                if ("میلیارد".equals(scale)) n *= 1000000000L;
                else if ("میلیون".equals(scale)) n *= 1000000L;
                else n *= 1000L;
                scaledAmount = n;
            } catch (Exception ignored) {}
        }
        if (scaledAmount > 0) return scaledAmount;

        // Plain numeric amounts: the last number is generally the amount when a
        // quantity/count appeared earlier in the spoken sentence.
        java.util.regex.Matcher numbers = java.util.regex.Pattern.compile("[0-9]+").matcher(normalized);
        long lastNumber = -1;
        while (numbers.find()) {
            try { lastNumber = Long.parseLong(numbers.group()); } catch (Exception ignored) {}
        }
        if (lastNumber > 0) return lastNumber;
        return wordNumber(normalized);
    }

    private long[] extractNumbers(String s) {
        java.util.ArrayList<Long> found = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[0-9]+").matcher(s);
        while(m.find()) try { found.add(Long.parseLong(m.group())); } catch(Exception ignored) {}
        if(found.isEmpty()) {
            String[] words=s.split("\\s+");
            for(String w:words) { long n=wordValue(w); if(n>0) found.add(n); }
        }
        long[] out=new long[found.size()]; for(int i=0;i<out.length;i++) out[i]=found.get(i); return out;
    }

    private long wordNumber(String s) {
        long total=0, current=0;
        for(String w:s.split("\\s+")) {
            if(w.equals("هزار")) { total += Math.max(1,current)*1000; current=0; }
            else if(w.equals("میلیون")) { total += Math.max(1,current)*1000000; current=0; }
            else { long v=wordValue(w); if(v>0) current += v; }
        }
        return total+current;
    }

    private long wordValue(String w) {
        switch(w) {
            case "یک": case "یه": case "اول": return 1;
            case "دو": case "دوتا": return 2;
            case "سه": return 3; case "چهار": return 4; case "پنج": return 5;
            case "شش": case "شیش": return 6; case "هفت": return 7; case "هشت": return 8; case "نه": return 9;
            case "ده": return 10; case "یازده": return 11; case "دوازده": return 12; case "سیزده": return 13;
            case "چهارده": return 14; case "پانزده": return 15; case "شانزده": return 16; case "هفده": return 17;
            case "هجده": return 18; case "نوزده": return 19; case "بیست": return 20; case "سی": return 30;
            case "چهل": return 40; case "پنجاه": return 50; case "شصت": return 60; case "هفتاد": return 70;
            case "هشتاد": return 80; case "نود": return 90; case "صد": case "یکصد": return 100; case "دویست": return 200;
            case "سیصد": return 300; case "چهارصد": return 400; case "پانصد": return 500;
            case "ششصد": return 600; case "هفتصد": return 700; case "هشتصد": return 800; case "نهصد": return 900;
            case "هزار": return 1000;
            case "میلیون": return 1000000; default: return 0;
        }
    }

    private String normalize(String s) {
        return s.replace('۰','0').replace('۱','1').replace('۲','2').replace('۳','3').replace('۴','4').replace('۵','5').replace('۶','6').replace('۷','7').replace('۸','8').replace('۹','9')
                .replace('٠','0').replace('١','1').replace('٢','2').replace('٣','3').replace('٤','4').replace('٥','5').replace('٦','6').replace('٧','7').replace('٨','8').replace('٩','9')
                .replace("،"," ").replace("؟"," ").replace("رو"," ").replace("برای"," ").replace("تومن","تومان").trim();
    }
    private boolean contains(String s, String... words) { for(String w:words) if(s.contains(w)) return true; return false; }
    // Convert a Gregorian date to the Persian Solar Hijri calendar (works on minSdk 23).
    private String formatPersianDate(Date date) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTime(date);
        int gy = calendar.get(java.util.Calendar.YEAR);
        int gm = calendar.get(java.util.Calendar.MONTH) + 1;
        int gd = calendar.get(java.util.Calendar.DAY_OF_MONTH);
        int[] gDays = {0,31,59,90,120,151,181,212,243,273,304,334};
        int gy2 = gm > 2 ? gy + 1 : gy;
        int days = 355666 + 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 + gd + gDays[gm - 1];
        int jy = -1595 + 33 * (days / 12053);
        days %= 12053;
        jy += 4 * (days / 1461);
        days %= 1461;
        if (days > 365) {
            jy += (days - 1) / 365;
            days = (days - 1) % 365;
        }
        int jm, jd;
        if (days < 186) {
            jm = 1 + days / 31;
            jd = 1 + days % 31;
        } else {
            jm = 7 + (days - 186) / 30;
            jd = 1 + (days - 186) % 30;
        }
        return String.format(java.util.Locale.ROOT, "%04d/%02d/%02d", jy, jm, jd)
            .replace('0','۰').replace('1','۱').replace('2','۲').replace('3','۳')
            .replace('4','۴').replace('5','۵').replace('6','۶').replace('7','۷')
            .replace('8','۸').replace('9','۹');
    }

    private String formatPersianDateTime(Date date) {
        java.text.SimpleDateFormat timeFormat = new java.text.SimpleDateFormat("HH:mm", Locale.getDefault());
        return formatPersianDate(date) + "  " + timeFormat.format(date);
    }

    private void addEntry(String type, String title, long amount) {
        if (amount > 0 && !"درآمد".equals(type) && !"یادآوری".equals(type)) recordExpenseHistory(type, title, amount);
        if (amount > 0 && java.util.Arrays.asList("خانه", "ماشین", "مغازه", "مدرسه", "سلامت", "سایر").contains(type)) {
            prefs.edit().putLong("oba_chart_cat_" + type, prefs.getLong("oba_chart_cat_" + type, 0L) + amount).apply();
            String subgroup = title.split(" — ", 2)[0].trim();
            if (!subgroup.isEmpty()) prefs.edit().putLong("oba_chart_sub_" + type + "_" + subgroup, prefs.getLong("oba_chart_sub_" + type + "_" + subgroup, 0L) + amount).apply();
            String[] titleParts = title.split(" — ", 2);
            if (titleParts.length > 1) {
                String item = titleParts[1].trim();
                if (!item.isEmpty()) {
                    String safeItem = item.replaceAll("[^\\p{L}\\p{N} ]", "").trim().replace(' ', '_');
                    if (!safeItem.isEmpty()) {
                        String itemKey = "oba_smart_item_" + type + "_" + subgroup + "_" + safeItem;
                        prefs.edit().putLong(itemKey, prefs.getLong(itemKey, 0L) + amount).apply();
                    }
                }
            }
        }
        String row = type + " • " + title + (amount > 0 ? " • " + formatPersianDate(new Date()) + " • " + fmt(amount) + " تومان" : "");
        entries.add(0,row); while(entries.size()>12) entries.remove(entries.size()-1);
        StringBuilder b=new StringBuilder(); for(String e:entries)b.append(e).append("\n");
        prefs.edit().putString("entries",b.toString()).apply();
    }
    private void recordExpenseHistory(String type, String title, long amount) {
        String archiveCategory = type == null ? "سایر" : type.trim();
        String archiveSubgroup = "";
        // Some category/subgroup screens pass a combined label such as
        // "هزینه • خانه • لبنیات". Normalize it so calendar and subgroup archive
        // use the same category and subgroup keys.
        if (archiveCategory.startsWith("هزینه • ")) {
            String[] typeParts = archiveCategory.split(" • ");
            if (typeParts.length >= 3) {
                archiveCategory = typeParts[1].trim();
                archiveSubgroup = typeParts[2].trim();
            }
        }
        String[] parts = (title == null ? "" : title).split(" — ", 2);
        // Every calendar expense is stored in a subgroup archive with its timestamp.
        // Preserve an explicitly selected subgroup; use a daily-purchases bucket when
        // an expense was recorded without a subgroup.
        String subgroup = !archiveSubgroup.isEmpty() ? archiveSubgroup
            : (parts.length > 1 ? parts[0].trim() : "خریدهای روزانه");
        String item = !archiveSubgroup.isEmpty() ? (title == null ? "" : title).trim()
            : (parts.length > 1 ? parts[1] : parts[0]).trim();
        String[] validCategories = {"خانه", "مدرسه", "ماشین", "مغازه", "سلامت", "سایر"};
        if (!java.util.Arrays.asList(validCategories).contains(archiveCategory)) archiveCategory = "سایر";
        long timestamp = System.currentTimeMillis();
        String row = timestamp + "\t" + archiveCategory.replace('\t', ' ') + "\t" + subgroup.replace('\t', ' ') + "\t" + item.replace('\t', ' ') + "\t" + amount;
        String old = prefs.getString("oba_expense_history", "");
        String combined = row + (old.isEmpty() ? "" : "\n" + old);
        String[] rows = combined.split("\n");
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < Math.min(rows.length, 1000); i++) {
            if (i > 0) kept.append("\n");
            kept.append(rows[i]);
        }
        prefs.edit().putString("oba_expense_history", kept.toString()).apply();
    }

    private void loadEntries() {
        String all=prefs.getString("entries","");
        if(!all.isEmpty()) for(String line:all.split("\n")) if(!line.trim().isEmpty()) entries.add(line);
    }
    private void refresh() {
        if(recent!=null) { StringBuilder b=new StringBuilder(); for(String e:entries) b.append("• ").append(e).append("\n"); recent.setText(b.length()==0?"هنوز چیزی ثبت نشده است":b.toString().trim()); }
        if(remindersView!=null) refreshReminders();
        if(inventoryView!=null) refreshInventory();
    }
    private String fmt(long n) { return new DecimalFormat("#,###").format(n); }
    private void say(String s) {
        // Wake-triggered command sessions stay silent; the only spoken prompt is "می‌شنوم".
        if (quietVoiceSession) return;
        speakWithSystemTts(s);
    }
    private void speakWithSystemTts(String s) {
        if (tts != null) tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "khoneyar");
    }
    private TextView text(String s,int size,int color,boolean bold) {
        TextView v=new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(color); v.setGravity(Gravity.RIGHT); v.setTextDirection(View.TEXT_DIRECTION_RTL);
        if(bold) v.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return v;
    }
    private Button button(String s,int bg,int fg,int size) {
        Button b=new Button(this);
        b.setText(s);
        b.setTextSize(size);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        // Outer colored rim follows the button silhouette; the inset face creates a double-border 3D look.
        GradientDrawable rim = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.rgb(151, 184, 255), Color.rgb(50, 86, 214), Color.rgb(20, 34, 104)}
        );
        rim.setCornerRadius(dp(20));
        rim.setStroke(dp(1), Color.rgb(178, 204, 255));

        GradientDrawable face = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.rgb(83, 117, 239), Color.rgb(43, 63, 166), Color.rgb(24, 35, 104)}
        );
        face.setCornerRadius(dp(16));
        face.setStroke(dp(1), Color.rgb(112, 143, 255));

        android.graphics.drawable.LayerDrawable layered =
            new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{rim, face});
        layered.setLayerInset(1, dp(3), dp(3), dp(3), dp(3));
        b.setBackground(layered);
        b.setElevation(dp(5));
        b.setStateListAnimator(null);
        b.setMinHeight(dp(48));
        return b;
    }
    private GradientDrawable round(int color,int radius) {
        GradientDrawable d=new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.rgb(58, 83, 190), color == Color.WHITE ? Color.rgb(31, 45, 126) : color}
        );
        d.setCornerRadius(dp(radius));
        d.setStroke(dp(1), Color.rgb(91, 121, 230));
        return d;
    }
    private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h)); p.setMargins(dp(l),dp(t),dp(r),dp(b)); return p;
    }
    private int dp(float v) { return (int)(v*getResources().getDisplayMetrics().density+0.5f); }
    @Override protected void onDestroy() {
        stopReminderSoundPreview();
        if(recognizer!=null) recognizer.destroy();
        if(offlineEngine!=null) offlineEngine.stop();
        if(tts!=null) tts.shutdown();
        super.onDestroy();
    }
}