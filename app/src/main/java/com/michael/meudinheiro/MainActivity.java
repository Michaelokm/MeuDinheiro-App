package com.michael.meudinheiro;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.net.Uri;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.os.ParcelFileDescriptor;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int VOICE_REQUEST = 4017;
    private static final int NOTIFICATION_REQUEST = 4018;
    private static final int BACKUP_EXPORT_REQUEST = 4019;
    private static final int BACKUP_IMPORT_REQUEST = 4020;
    private static final int RECEIPT_PICK_REQUEST = 4021;
    private boolean suppressNextRelock = false;
    private static final String CHANNEL_ID = "vencimentos";
    private static final String PREFS = "meu_dinheiro_reminders";
    private static final String PREF_DAILY_TIME = "daily_reminder_time";
    private static final String DAILY_ACTION = "com.michael.meudinheiro.DAILY_REMINDER";
    private static final String PREF_JSON = "reminders_json";
    private static final String PREF_FIRED = "reminders_fired";

    private WebView webView;
    private TextToSpeech textToSpeech;
    private boolean ttsReady = false;
    private String pendingBackupJson = null;
    private boolean pageReady = false;
    private Intent pendingShareIntent = null;
    private TextRecognizer receiptRecognizer = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(0xFF0D1016);
        getWindow().setNavigationBarColor(0xFF0D1016);
        getWindow().getDecorView().setSystemUiVisibility(0);

        ensureNotificationChannel(this);

        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = textToSpeech.setLanguage(new Locale("pt", "BR"));
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED;
                textToSpeech.setSpeechRate(1.0f);
            }
        });

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF0D1016);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageReady = true;
                if (pendingShareIntent != null) {
                    Intent shared = pendingShareIntent;
                    pendingShareIntent = null;
                    handleIncomingIntent(shared);
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new VoiceBridge(), "AndroidVoice");
        setContentView(webView);
        if (isShareIntent(getIntent())) pendingShareIntent = getIntent();
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!isShareIntent(intent)) return;
        if (pageReady) handleIncomingIntent(intent);
        else pendingShareIntent = intent;
    }

    public class VoiceBridge {
        @JavascriptInterface
        public void startListening() {
            runOnUiThread(() -> {
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR");
                intent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false);
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale um lançamento ou uma pergunta");
                try {
                    suppressNextRelock = true;
                    startActivityForResult(intent, VOICE_REQUEST);
                } catch (ActivityNotFoundException e) {
                    sendVoiceError("O reconhecimento de voz do Android não está disponível.");
                }
            });
        }

        @JavascriptInterface
        public void speak(String text) {
            if (text == null || text.trim().isEmpty()) return;
            runOnUiThread(() -> {
                if (ttsReady && textToSpeech != null) {
                    textToSpeech.stop();
                    textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "meu-dinheiro-resposta");
                }
            });
        }

        @JavascriptInterface
        public void syncReminders(String json) {
            if (json == null) return;
            runOnUiThread(() -> MainActivity.syncReminders(MainActivity.this, json));
        }

        @JavascriptInterface
        public void requestNotificationPermission() {
            if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_REQUEST
                ));
            }
        }

        @JavascriptInterface
        public void exportBackup(String json) {
            if (json == null || json.trim().isEmpty()) return;
            pendingBackupJson = json;
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, "MeuDinheiro-backup-" + LocalDate.now() + ".json");
                try {
                    suppressNextRelock = true;
                    startActivityForResult(intent, BACKUP_EXPORT_REQUEST);
                } catch (Exception e) {
                    sendBackupError("Não foi possível abrir o local para salvar o backup.");
                }
            });
        }

        @JavascriptInterface
        public void showBiometricPrompt() {
            runOnUiThread(() -> MainActivity.this.showBiometricPrompt());
        }

        @JavascriptInterface
        public void setDailyReminder(String time) {
            if (time == null || !time.matches("\\d{1,2}:\\d{2}")) return;
            runOnUiThread(() -> MainActivity.scheduleDailyReminder(MainActivity.this, time));
        }

        @JavascriptInterface
        public void cancelDailyReminder() {
            runOnUiThread(() -> MainActivity.cancelDailyReminder(MainActivity.this));
        }

        @JavascriptInterface
        public void pickReceipt() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
                try {
                    suppressNextRelock = true;
                    startActivityForResult(intent, RECEIPT_PICK_REQUEST);
                } catch (Exception e) {
                    sendReceiptError("Não foi possível abrir o seletor de arquivos.");
                }
            });
        }

        @JavascriptInterface
        public void importBackup() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                try {
                    suppressNextRelock = true;
                    startActivityForResult(intent, BACKUP_IMPORT_REQUEST);
                } catch (Exception e) {
                    sendBackupError("Não foi possível abrir o seletor de arquivos.");
                }
            });
        }
    }

    private void showBiometricPrompt() {
        if (Build.VERSION.SDK_INT < 28) return;
        try {
            BiometricPrompt.Builder builder = new BiometricPrompt.Builder(this)
                    .setTitle("Meu Dinheiro")
                    .setSubtitle("Use sua digital para entrar")
                    .setNegativeButton("Usar senha", getMainExecutor(),
                            (DialogInterface dialog, int which) -> dialog.dismiss());
            BiometricPrompt prompt = builder.build();
            CancellationSignal cancelSignal = new CancellationSignal();
            prompt.authenticate(cancelSignal, getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    runOnUiThread(() -> {
                        if (webView != null) {
                            webView.evaluateJavascript("if(window.unlockFromBiometric)unlockFromBiometric();", null);
                        }
                    });
                }

                @Override
                public void onAuthenticationError(int errorCode, CharSequence errString) {
                    // Falhou, cancelou ou não tem digital cadastrada: a senha continua disponível na tela.
                }

                @Override
                public void onAuthenticationFailed() {
                    // Digital não reconhecida: o próprio sistema já mostra um aviso e permite tentar de novo.
                }
            });
        } catch (Exception ignored) {
            // Aparelho sem sensor de digital compatível: a senha continua disponível na tela.
        }
    }

    private static void ensureNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Vencimentos",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Avisos de contas e faturas que vencem em 2 dias");
            nm.createNotificationChannel(channel);
        }
    }

    private static int reminderCode(String key) {
        return key == null ? 1 : (key.hashCode() & 0x7fffffff);
    }

    private static PendingIntent reminderPendingIntent(Context context, JSONObject obj) {
        String key = obj.optString("key", String.valueOf(System.currentTimeMillis()));
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.setAction("com.michael.meudinheiro.REMINDER." + key);
        intent.putExtra("key", key);
        intent.putExtra("title", obj.optString("title", "Meu Dinheiro"));
        intent.putExtra("message", obj.optString("message", "Você tem um vencimento próximo."));
        return PendingIntent.getBroadcast(
                context,
                reminderCode(key),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent dailyReminderPendingIntent(Context context) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.setAction(DAILY_ACTION);
        intent.putExtra("daily", true);
        return PendingIntent.getBroadcast(
                context,
                reminderCode("daily-reminder-fixed-key"),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static void scheduleDailyReminder(Context context, String time) {
        try {
            String[] parts = time.split(":");
            int hh = Integer.parseInt(parts[0]), mm = Integer.parseInt(parts[1]);
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime next = now.withHour(hh).withMinute(mm).withSecond(0).withNano(0);
            if (!next.isAfter(now)) next = next.plusDays(1);
            long trigger = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit().putString(PREF_DAILY_TIME, time).apply();

            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = dailyReminderPendingIntent(context);
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, trigger, pi);
            }
        } catch (Exception ignored) {
            // horário inválido: não agenda nada
        }
    }

    private static void cancelDailyReminder(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.cancel(dailyReminderPendingIntent(context));
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().remove(PREF_DAILY_TIME).apply();
    }

    private static void cancelJson(Context context, String json) {
        try {
            JSONArray arr = new JSONArray(json == null ? "[]" : json);
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                am.cancel(reminderPendingIntent(context, obj));
            }
        } catch (Exception ignored) {
        }
    }

    private static void scheduleJson(Context context, String json) {
        JSONArray arr;
        try {
            arr = new JSONArray(json == null ? "[]" : json);
        } catch (Exception e) {
            return;
        }

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> fired = prefs.getStringSet(PREF_FIRED, new HashSet<String>());
        long nowMs = System.currentTimeMillis();
        LocalDate today = LocalDate.now();

        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;

                String dueText = obj.optString("dueDate", "");
                if (dueText.isEmpty()) continue;

                LocalDate dueDate = LocalDate.parse(dueText);
                if (dueDate.isBefore(today)) continue;

                LocalDate reminderDate = dueDate.minusDays(2);
                LocalDateTime localDateTime = LocalDateTime.of(reminderDate, LocalTime.of(9, 0));
                long trigger = localDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

                if (trigger <= nowMs) {
                    // O horário do aviso já passou (a conta vence em até 2 dias).
                    // Avisa uma única vez, e não toda vez que o app abre ou salva algo.
                    if (fired.contains(obj.optString("key", ""))) continue;
                    trigger = nowMs + 5000L;
                }

                PendingIntent pi = reminderPendingIntent(context, obj);
                if (Build.VERSION.SDK_INT >= 23) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, trigger, pi);
                }
            } catch (Exception ignored) {
                // um lembrete com problema não impede os demais
            }
        }
    }

    private static void markFired(Context context, String key) {
        if (key == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> updated = new HashSet<String>(prefs.getStringSet(PREF_FIRED, new HashSet<String>()));
        updated.add(key);
        prefs.edit().putStringSet(PREF_FIRED, updated).apply();
    }

    private static void pruneFired(SharedPreferences prefs, String json) {
        try {
            Set<String> fired = prefs.getStringSet(PREF_FIRED, null);
            if (fired == null || fired.isEmpty()) return;
            Set<String> keep = new HashSet<String>();
            JSONArray arr = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String key = obj.optString("key", "");
                if (fired.contains(key)) keep.add(key);
            }
            prefs.edit().putStringSet(PREF_FIRED, keep).apply();
        } catch (Exception ignored) {
        }
    }

    private static void syncReminders(Context context, String json) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String oldJson = prefs.getString(PREF_JSON, "[]");
        cancelJson(context, oldJson);
        pruneFired(prefs, json);
        prefs.edit().putString(PREF_JSON, json).apply();
        scheduleJson(context, json);
    }

    private static void rescheduleStored(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        scheduleJson(context, prefs.getString(PREF_JSON, "[]"));
        String dailyTime = prefs.getString(PREF_DAILY_TIME, "");
        if (!dailyTime.isEmpty()) scheduleDailyReminder(context, dailyTime);
    }

    public static class ReminderReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
                rescheduleStored(context);
                return;
            }

            if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            ensureNotificationChannel(context);

            boolean isDaily = DAILY_ACTION.equals(action) || intent.getBooleanExtra("daily", false);
            String key = isDaily ? "daily-reminder-fixed-key" : intent.getStringExtra("key");
            String title = isDaily ? "Meu Dinheiro" : intent.getStringExtra("title");
            String message = isDaily ? "Não esqueça de registrar os gastos de hoje 💰" : intent.getStringExtra("message");

            Intent openApp = new Intent(context, MainActivity.class);
            openApp.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent contentIntent = PendingIntent.getActivity(
                    context,
                    reminderCode(key),
                    openApp,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(context, CHANNEL_ID)
                    : new Notification.Builder(context);

            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title == null ? "Meu Dinheiro" : title)
                    .setContentText(message == null ? "Você tem um vencimento próximo." : message)
                    .setStyle(new Notification.BigTextStyle().bigText(message))
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent)
                    .setWhen(System.currentTimeMillis());

            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(reminderCode(key), builder.build());

            if (isDaily) {
                SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                String storedTime = prefs.getString(PREF_DAILY_TIME, "");
                if (!storedTime.isEmpty()) scheduleDailyReminder(context, storedTime);
            } else {
                markFired(context, key);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (suppressNextRelock) {
            suppressNextRelock = false;
            return;
        }
        if (webView != null) {
            webView.evaluateJavascript("if(window.relock)relock();", null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == BACKUP_EXPORT_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingBackupJson != null) {
                Uri uri = data.getData();
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new Exception("Sem acesso ao arquivo");
                    out.write(pendingBackupJson.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    sendBackupExported();
                } catch (Exception e) {
                    sendBackupError("Não foi possível salvar o backup.");
                } finally {
                    pendingBackupJson = null;
                }
            } else {
                pendingBackupJson = null;
            }
            return;
        }

        if (requestCode == BACKUP_IMPORT_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                try (InputStream in = getContentResolver().openInputStream(uri);
                     ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                    if (in == null) throw new Exception("Sem acesso ao arquivo");
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) != -1) buffer.write(chunk, 0, n);
                    String json = buffer.toString(StandardCharsets.UTF_8.name());
                    sendBackupImported(json);
                } catch (Exception e) {
                    sendBackupError("Não foi possível ler este backup.");
                }
            }
            return;
        }

        if (requestCode == RECEIPT_PICK_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                processReceiptUri(data.getData(), data.getType());
            }
            return;
        }

        if (requestCode != VOICE_REQUEST) return;

        if (resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                sendVoiceResult(results.get(0));
                return;
            }
        }
        sendVoiceError("Não consegui ouvir. Toque no microfone e tente novamente.");
    }

    private void sendBackupExported() {
        if (webView == null) return;
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.onNativeBackupExported && window.onNativeBackupExported();", null));
    }

    private void sendBackupImported(String json) {
        if (webView == null) return;
        String js = "window.onNativeBackupImported && window.onNativeBackupImported(" +
                JSONObject.quote(json) + ");";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private void sendBackupError(String message) {
        if (webView == null) return;
        String js = "window.onNativeBackupError && window.onNativeBackupError(" +
                JSONObject.quote(message) + ");";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private void sendVoiceResult(String text) {
        String js = "window.onNativeVoiceResult && window.onNativeVoiceResult(" + JSONObject.quote(text) + ");";
        webView.evaluateJavascript(js, null);
    }

    private void sendVoiceError(String message) {
        String js = "window.onNativeVoiceError && window.onNativeVoiceError(" + JSONObject.quote(message) + ");";
        webView.evaluateJavascript(js, null);
    }


    // ===== Ler comprovante (imagem ou PDF) com reconhecimento de texto no próprio aparelho =====
    private static boolean isShareIntent(Intent intent) {
        return intent != null && Intent.ACTION_SEND.equals(intent.getAction());
    }

    private void handleIncomingIntent(Intent intent) {
        if (!isShareIntent(intent)) return;
        Uri uri;
        if (Build.VERSION.SDK_INT >= 33) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        } else {
            uri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        String type = intent.getType();
        intent.setAction(Intent.ACTION_MAIN);
        if (uri == null) {
            sendReceiptError("Não recebi nenhum arquivo para ler.");
            return;
        }
        processReceiptUri(uri, type);
    }

    private void processReceiptUri(final Uri uri, final String mimeHint) {
        sendReceiptReading();
        new Thread(() -> {
            try {
                String mime = mimeHint;
                if (mime == null || mime.isEmpty() || mime.contains("*")) mime = getContentResolver().getType(uri);
                String lowerUri = String.valueOf(uri).toLowerCase(Locale.ROOT);
                boolean isPdf = (mime != null && mime.toLowerCase(Locale.ROOT).contains("pdf")) || lowerUri.endsWith(".pdf");
                Bitmap bitmap;
                int rotation = 0;
                if (isPdf) {
                    bitmap = renderPdfFirstPage(uri);
                } else {
                    bitmap = decodeReceiptBitmap(uri);
                    rotation = readExifRotation(uri);
                }
                if (bitmap == null) {
                    sendReceiptError("Não consegui abrir esse arquivo.");
                    return;
                }
                recognizeReceipt(bitmap, rotation);
            } catch (SecurityException e) {
                sendReceiptError("Esse PDF está protegido por senha.");
            } catch (OutOfMemoryError e) {
                sendReceiptError("A imagem é grande demais para ler.");
            } catch (Exception e) {
                sendReceiptError("Não consegui abrir esse arquivo.");
            }
        }).start();
    }

    private Bitmap decodeReceiptBitmap(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream first = getContentResolver().openInputStream(uri);
        if (first == null) return null;
        try {
            BitmapFactory.decodeStream(first, null, bounds);
        } finally {
            first.close();
        }
        int width = bounds.outWidth;
        int height = bounds.outHeight;
        if (width <= 0 || height <= 0) return null;
        int sample = 1;
        while ((long) (width / sample) * (long) (height / sample) > 10000000L) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        InputStream second = getContentResolver().openInputStream(uri);
        if (second == null) return null;
        try {
            return BitmapFactory.decodeStream(second, null, options);
        } finally {
            second.close();
        }
    }

    private int readExifRotation(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) return 0;
            try {
                ExifInterface exif = new ExifInterface(in);
                int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90) return 90;
                if (orientation == ExifInterface.ORIENTATION_ROTATE_180) return 180;
                if (orientation == ExifInterface.ORIENTATION_ROTATE_270) return 270;
            } finally {
                in.close();
            }
        } catch (Exception ignored) {
            // sem informação de rotação: segue com a imagem como está
        }
        return 0;
    }

    private Bitmap renderPdfFirstPage(Uri uri) throws Exception {
        File temp = new File(getCacheDir(), "comprovante.pdf");
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) return null;
        try {
            FileOutputStream out = new FileOutputStream(temp);
            try {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) != -1) out.write(chunk, 0, n);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer renderer = null;
        try {
            renderer = new PdfRenderer(descriptor);
            if (renderer.getPageCount() <= 0) return null;
            PdfRenderer.Page page = renderer.openPage(0);
            try {
                float scale = Math.max(2f, 1800f / Math.max(1, page.getWidth()));
                int width = Math.round(page.getWidth() * scale);
                int height = Math.round(page.getHeight() * scale);
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bitmap;
            } finally {
                page.close();
            }
        } finally {
            if (renderer != null) renderer.close();
            descriptor.close();
            temp.delete();
        }
    }

    private void recognizeReceipt(final Bitmap bitmap, final int rotation) {
        runOnUiThread(() -> {
            try {
                if (receiptRecognizer == null) {
                    receiptRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                }
                InputImage image = InputImage.fromBitmap(bitmap, rotation);
                receiptRecognizer.process(image)
                        .addOnSuccessListener(visionText -> {
                            String text = visionText.getText();
                            if (text == null || text.trim().isEmpty()) {
                                sendReceiptError("Não encontrei texto nessa imagem.");
                            } else {
                                sendReceiptText(text);
                            }
                        })
                        .addOnFailureListener(e -> sendReceiptError("Não consegui ler o texto desse comprovante."));
            } catch (Exception e) {
                sendReceiptError("Não consegui ler o texto desse comprovante.");
            }
        });
    }

    private void sendReceiptReading() {
        if (webView == null) return;
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.onReceiptReading && window.onReceiptReading();", null));
    }

    private void sendReceiptText(String text) {
        if (webView == null) return;
        final String js = "window.onReceiptText && window.onReceiptText(" + JSONObject.quote(text) + ");";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private void sendReceiptError(String message) {
        if (webView == null) return;
        final String js = "window.onReceiptError && window.onReceiptError(" + JSONObject.quote(message) + ");";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        // Voltar: 1) fecha a janela aberta, 2) volta para o Início, 3) só então sai do app.
        String js = "(function(){var o=document.querySelector('.overlay.show');"
                + "if(o){closeSheets();return 'handled';}"
                + "var p=document.querySelector('.page.active');"
                + "if(p&&p.id!=='home'){go('home');return 'handled';}"
                + "return 'exit';})()";
        webView.evaluateJavascript(js, value -> {
            if (value == null || !value.contains("handled")) {
                finish();
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (receiptRecognizer != null) {
            try {
                receiptRecognizer.close();
            } catch (Exception ignored) {
                // nada a fazer ao fechar o leitor
            }
            receiptRecognizer = null;
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidVoice");
            webView.destroy();
        }
        super.onDestroy();
    }
}
