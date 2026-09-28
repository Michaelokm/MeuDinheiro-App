package com.michael.meudinheiro;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int VOICE_REQUEST = 4017;
    private static final int NOTIFICATION_REQUEST = 4018;
    private static final int BACKUP_EXPORT_REQUEST = 4019;
    private static final int BACKUP_IMPORT_REQUEST = 4020;
    private static final String CHANNEL_ID = "vencimentos";
    private static final String PREFS = "meu_dinheiro_reminders";
    private static final String PREF_JSON = "reminders_json";
    private static final String PREF_FIRED = "reminders_fired";

    private WebView webView;
    private TextToSpeech textToSpeech;
    private boolean ttsReady = false;
    private String pendingBackupJson = null;

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

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new VoiceBridge(), "AndroidVoice");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
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
                    startActivityForResult(intent, BACKUP_EXPORT_REQUEST);
                } catch (Exception e) {
                    sendBackupError("Não foi possível abrir o local para salvar o backup.");
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
                    startActivityForResult(intent, BACKUP_IMPORT_REQUEST);
                } catch (Exception e) {
                    sendBackupError("Não foi possível abrir o seletor de arquivos.");
                }
            });
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

            String key = intent.getStringExtra("key");
            String title = intent.getStringExtra("title");
            String message = intent.getStringExtra("message");

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
            markFired(context, key);
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
