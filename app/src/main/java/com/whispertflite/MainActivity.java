package com.whispertflite;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.utils.HapticFeedback;
import com.whispertflite.utils.InputLang;
import com.whispertflite.utils.LanguagePairAdapter;
import com.whispertflite.utils.ThemeUtils;
import com.whispertflite.overlay_mode.FloatingOverlayService;
import com.whispertflite.overlay_mode.TextInjectorService;
import com.whispertflite.overlay_mode.WhitelistAppsActivity;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.whispertflite.caption.CaptionActivity;

import org.woheller69.freeDroidWarn.FreeDroidWarn;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private Context mContext;
    private static final String TAG = "MainActivity";

    // whisper-small.tflite works well for multi-lingual
    public static final String MULTI_LINGUAL_EU_MODEL_FAST = "whisper-base.EUROPEAN_UNION.tflite";
    public static final String MULTI_LINGUAL_TOP_WORLD_FAST = "whisper-base.TOP_WORLD.tflite";
    public static final String MULTI_LINGUAL_TOP_WORLD_SLOW = "whisper-small.TOP_WORLD.tflite";
    public static final String MULTI_LINGUAL_MODEL_FAST = "whisper-base.tflite";
    public static final String MULTI_LINGUAL_MODEL_SLOW = "whisper-small.tflite";
    public static final String ENGLISH_ONLY_MODEL = "whisper-tiny.en.tflite";
    // English only model ends with extension ".en.tflite"
    public static final String ENGLISH_ONLY_MODEL_EXTENSION = ".en.tflite";
    public static final String ENGLISH_ONLY_VOCAB_FILE = "filters_vocab_en.bin";
    public static final String MULTILINGUAL_VOCAB_FILE = "filters_vocab_multilingual.bin";


    private TextView tvStatus;
    private TextView tvResult;
    private LinearProgressIndicator processingBar;
    private FloatingActionButton fabRecord;
    private View cardStatus;
    private MaterialSwitch append;
    private MaterialSwitch translate;
    private View btnFloatingMode;
    private TextView tvFloatingMode;
    private ImageButton btnGitHub;
    private boolean isRecording = false;
    private boolean isFloatingModeActive = false;

    private Recorder mRecorder = null;
    private Whisper mWhisper = null;

    private File sdcardDataFolder = null;
    private File selectedTfliteFile = null;
    private SharedPreferences sp = null;
    private TextView tvSelectedModel;
    private TextView tvSelectedLanguage;
    private View btnSelectModel;
    private View btnSelectLanguage;
    private CountDownTimer countDownTimer;
    private int langToken = -1;
    private long startTime = 0;

    @Override
    protected void onDestroy() {
        deinitModel();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        stopProcessing();
        super.onPause();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        checkPermissions();
        mContext = this;
        setContentView(R.layout.activity_main);
        ThemeUtils.setStatusBarAppearance(this);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        // checkInputMethodEnabled();
        processingBar = findViewById(R.id.processing_bar);
        tvStatus = findViewById(R.id.tvStatus);
        tvResult = findViewById(R.id.tvResult);
        cardStatus = findViewById(R.id.cardStatus);

        sp = PreferenceManager.getDefaultSharedPreferences(this);
        selectedTfliteFile = new File(getExternalFilesDir(null), sp.getString("modelName", MULTI_LINGUAL_TOP_WORLD_SLOW));
        append = findViewById(R.id.mode_append);
        translate = findViewById(R.id.mode_translate);

        // Call the method to copy specific file types from assets to data folder
        sdcardDataFolder = this.getExternalFilesDir(null);

        ArrayList<File> tfliteFiles = getFilesWithExtension(sdcardDataFolder, ".tflite");

        // Initialize default model to use
        initModel();

        btnGitHub = findViewById(R.id.btnGitHub);
        btnGitHub.setOnClickListener(view -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/adithyakrish0/whisper-voice"))));

        // Floating Mode Button
        btnFloatingMode = findViewById(R.id.btnFloatingMode);
        tvFloatingMode = findViewById(R.id.tvFloatingMode);
        updateFloatingModeButton();
        findViewById(R.id.btnFloatingModeLayout).setOnClickListener(v -> toggleFloatingMode());
        btnFloatingMode.setOnClickListener(v -> toggleFloatingMode());
        
        // Whitelist Apps Button
        View btnWhitelist = findViewById(R.id.btnWhitelist);
        findViewById(R.id.btnWhitelistLayout).setOnClickListener(v -> {
            startActivity(new Intent(this, WhitelistAppsActivity.class));
        });
        btnWhitelist.setOnClickListener(v -> {
            startActivity(new Intent(this, WhitelistAppsActivity.class));
        });
        
        // Video Caption Button
        View btnVideoCaption = findViewById(R.id.btnVideoCaption);
        btnVideoCaption.setOnClickListener(v -> {
            // Stop floating mode to free memory before starting video captioning
            if (isFloatingModeActive) {
                stopFloatingMode();
            }
            startActivity(new Intent(this, CaptionActivity.class));
        });
        

        
        // Auto-start floating mode on app init
        // autoStartFloatingMode();

        tvSelectedLanguage = findViewById(R.id.tvSelectedLanguage);
        btnSelectLanguage = findViewById(R.id.btnSelectLanguage);
        
        List<Pair<String, String>> languagePairs = LanguagePairAdapter.getLanguagePairs(this);
        List<String> languageNames = new ArrayList<>();
        for (Pair<String, String> pair : languagePairs) {
            languageNames.add(pair.second);
        }

        btnSelectLanguage.setOnClickListener(v -> showLanguageSelector(languagePairs, languageNames));



        tvSelectedModel = findViewById(R.id.tvSelectedModel);
        btnSelectModel = findViewById(R.id.btnSelectModel);

        List<String> tfliteFileNames = new ArrayList<>();
        for (File f : tfliteFiles) {
            String name = f.getName();
            String displayName;
            
            if (name.equals(MULTI_LINGUAL_MODEL_SLOW)) displayName = getString(R.string.model_whisper_small);
            else if (name.equals(MULTI_LINGUAL_TOP_WORLD_SLOW)) displayName = getString(R.string.model_whisper_small_world);
            else if (name.equals(ENGLISH_ONLY_MODEL)) displayName = getString(R.string.model_whisper_tiny_en);
            else if (name.equals(MULTI_LINGUAL_MODEL_FAST)) displayName = getString(R.string.model_whisper_base);
            else if (name.equals(MULTI_LINGUAL_EU_MODEL_FAST)) displayName = getString(R.string.model_whisper_base_eu);
            else if (name.equals(MULTI_LINGUAL_TOP_WORLD_FAST)) displayName = getString(R.string.model_whisper_base_world);
            else displayName = name.substring(0, name.length() - ".tflite".length()); // Fallback
            
            tfliteFileNames.add(displayName);
        }

        btnSelectModel.setOnClickListener(v -> showModelSelector(tfliteFiles, tfliteFileNames, languagePairs, languageNames));
        
        // Find current selection index
        int modelPosition = 0;
        for (int i = 0; i < tfliteFiles.size(); i++) {
            if (tfliteFiles.get(i).equals(selectedTfliteFile)) {
                modelPosition = i;
                break;
            }
        }
        // Initialize with saved values
        tvSelectedModel.setText(tfliteFileNames.get(modelPosition));

        boolean needsLanguageStatus = selectedTfliteFile.getName().equals(MULTI_LINGUAL_EU_MODEL_FAST) || 
                                    selectedTfliteFile.getName().equals(MULTI_LINGUAL_TOP_WORLD_FAST) || 
                                    selectedTfliteFile.getName().equals(MULTI_LINGUAL_TOP_WORLD_SLOW);

        if (needsLanguageStatus) {
            btnSelectLanguage.setEnabled(true);
            btnSelectLanguage.setAlpha(1.0f);
            String langCode = sp.getString("language", "auto");
            int langIdx = 0;
            for(int i=0; i<languagePairs.size(); i++) if(languagePairs.get(i).first.equals(langCode)) { langIdx = i; break; }
            tvSelectedLanguage.setText(languageNames.get(langIdx));
        } else {
            tvSelectedLanguage.setText(languageNames.get(0));
            btnSelectLanguage.setEnabled(false);
            btnSelectLanguage.setAlpha(0.5f);
        }




        // Implementation of record button functionality
        fabRecord = findViewById(R.id.fabRecord);
        fabRecord.setOnClickListener(v -> {
            if (isRecording) {
                stopRecording();
            } else {
                if (mWhisper.isInProgress()) {
                    Toast.makeText(this, getString(R.string.please_wait), Toast.LENGTH_SHORT).show();
                    return;
                }
                HapticFeedback.vibrate(this);
                startRecording();
                isRecording = true;
                fabRecord.setImageResource(R.drawable.ic_stop_white);
                cardStatus.setVisibility(View.VISIBLE);
                processingBar.setVisibility(View.VISIBLE);
                processingBar.setIndeterminate(false);
                processingBar.setProgress(100);
                
                countDownTimer = new CountDownTimer(30000, 1000) {
                    @Override
                    public void onTick(long l) {
                        runOnUiThread(() -> processingBar.setProgress((int) (l / 300)));
                    }
                    @Override
                    public void onFinish() {
                        if (isRecording) {
                            stopRecording();
                        }
                    }
                };
                countDownTimer.start();
            }
        });

        // Initial status update
        tvStatus.setText(getString(R.string.tap_to_record));


        // Audio recording functionality
        mRecorder = new Recorder(this);
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                Log.d(TAG, "Update is received, Message: " + message);
                if (message.equals(Recorder.MSG_RECORDING)) {
                    runOnUiThread(() -> tvStatus.setText(getString(R.string.recording) + "…"));
                    if (!append.isChecked()) runOnUiThread(() -> tvResult.setText(""));
                } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                    HapticFeedback.vibrate(mContext);
                    if (isRecording) {
                        runOnUiThread(() -> {
                            isRecording = false;
                            fabRecord.setImageResource(R.drawable.ic_mic_white);
                        });
                    }
                    if (translate.isChecked()) startProcessing(Whisper.ACTION_TRANSLATE);
                    else startProcessing(Whisper.ACTION_TRANSCRIBE);
                } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                    HapticFeedback.vibrate(mContext);
                    if (countDownTimer!=null) { countDownTimer.cancel();}
                    runOnUiThread(() -> {
                        isRecording = false;
                        fabRecord.setImageResource(R.drawable.ic_mic_white);
                        processingBar.setProgress(0);
                        tvStatus.setText(getString(R.string.error_no_input));
                    });
                }
            }

        });
        FreeDroidWarn.showWarningOnUpgrade(this, BuildConfig.VERSION_CODE);
        if (GithubStar.shouldShowStarDialog(this)) GithubStar.starDialog(this, "https://github.com/woheller69/whisperIME");
        // Assume this Activity is the current activity, check record permission
        checkPermissions();

    }

    private void checkInputMethodEnabled() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        List<InputMethodInfo> enabledInputMethodList = imm.getEnabledInputMethodList();

        String myInputMethodId = getPackageName() + "/" + WhisperInputMethodService.class.getName();
        boolean inputMethodEnabled = false;
        for (InputMethodInfo imi : enabledInputMethodList) {
            if (imi.getId().equals(myInputMethodId)) {
                inputMethodEnabled = true;
                break;
            }
        }
        if (!inputMethodEnabled) {
            Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
            startActivity(intent);
        }
    }

    // Model initialization
    private void initModel() {
        new Thread(() -> {
            boolean isMultilingualModel = !(selectedTfliteFile.getName().endsWith(ENGLISH_ONLY_MODEL_EXTENSION));
            String vocabFileName = isMultilingualModel ? MULTILINGUAL_VOCAB_FILE : ENGLISH_ONLY_VOCAB_FILE;
            File vocabFile = new File(sdcardDataFolder, vocabFileName);

            mWhisper = new Whisper(this);
            mWhisper.loadModel(selectedTfliteFile, vocabFile, isMultilingualModel);
            Log.d(TAG, "Initialized: " + selectedTfliteFile.getName());
            mWhisper.setListener(new Whisper.WhisperListener() {
                @Override
                public void onUpdateReceived(String message) {
                    Log.d(TAG, "Update is received, Message: " + message);

                    if (message.equals(Whisper.MSG_PROCESSING)) {
                        runOnUiThread(() -> {
                            tvStatus.setText(getString(R.string.processing));
                            processingBar.setVisibility(View.VISIBLE);
                            processingBar.setIndeterminate(true);
                            btnSelectModel.setEnabled(false);
                            btnSelectModel.setAlpha(0.5f);
                        });
                        startTime = System.currentTimeMillis();
                    }
                }

                @Override
                public void onResultReceived(WhisperResult whisperResult) {
                    long timeTaken = System.currentTimeMillis() - startTime;
                    runOnUiThread(() -> tvStatus.setText(getString(R.string.processing_done) + timeTaken + "\u2009ms" + "\n"+ getString(R.string.language) + " " + new Locale(whisperResult.getLanguage()).getDisplayLanguage() + " " + (whisperResult.getTask() == Whisper.Action.TRANSCRIBE ? getString(R.string.mode_transcription) : getString(R.string.mode_translation))));
                    runOnUiThread(() -> processingBar.setIndeterminate(false));
                    Log.d(TAG, "Result: " + whisperResult.getResult() + " " + whisperResult.getLanguage() + " " + (whisperResult.getTask() == Whisper.Action.TRANSCRIBE ? "transcribing" : "translating"));
                    
                    final String resultText = whisperResult.getResult();
                    runOnUiThread(() -> tvResult.append(resultText));
                    
                    runOnUiThread(() -> {
                        btnSelectModel.setEnabled(true);
                        btnSelectModel.setAlpha(1.0f);
                        processingBar.setVisibility(View.INVISIBLE);
                        processingBar.setIndeterminate(false);
                    });
                }
            });
        }).start();
    }

    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.unloadModel();
            mWhisper = null;
        }
    }


    private @NonNull ArrayAdapter<File> getFileArrayAdapter(ArrayList<File> tfliteFiles) {
        ArrayAdapter<File> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, tfliteFiles) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                 View view = super.getView(position, convertView, parent);
                 TextView textView = view.findViewById(android.R.id.text1);
                 updateTextView(textView, getItem(position));
                 return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                 View view = super.getDropDownView(position, convertView, parent);
                 TextView textView = view.findViewById(android.R.id.text1);
                 updateTextView(textView, getItem(position));
                 return view;
            }
            
            private void updateTextView(TextView textView, File file) {
                 String name = file.getName();
                 if (name.equals(MULTI_LINGUAL_MODEL_SLOW)) textView.setText(R.string.model_whisper_small);
                 else if (name.equals(MULTI_LINGUAL_TOP_WORLD_SLOW)) textView.setText(R.string.model_whisper_small_world);
                 else if (name.equals(ENGLISH_ONLY_MODEL)) textView.setText(R.string.model_whisper_tiny_en);
                 else if (name.equals(MULTI_LINGUAL_MODEL_FAST)) textView.setText(R.string.model_whisper_base);
                 else if (name.equals(MULTI_LINGUAL_EU_MODEL_FAST)) textView.setText(R.string.model_whisper_base_eu);
                 else if (name.equals(MULTI_LINGUAL_TOP_WORLD_FAST)) textView.setText(R.string.model_whisper_base_world);
                 else textView.setText(name.substring(0, name.length() - ".tflite".length()));
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private void checkPermissions() {
        List<String> perms = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO);
            Toast.makeText(this, getString(R.string.need_record_audio_permission), Toast.LENGTH_SHORT).show();
        }
        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) && (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)){
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!perms.isEmpty()) {
            requestPermissions(perms.toArray(new String[] {}), 0);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Record permission is granted");
        } else {
            Log.d(TAG, "Record permission is not granted");
        }
    }

    // Recording calls
    private void startRecording() {
        checkPermissions();
        mRecorder.start();
    }

    private void stopRecording() {
        mRecorder.stop();
        isRecording = false;
        runOnUiThread(() -> fabRecord.setImageResource(R.drawable.ic_mic_white));
    }

    // Transcription calls
    private void startProcessing(Whisper.Action action) {
        if (countDownTimer!=null) { countDownTimer.cancel();}
        runOnUiThread(() -> {
            processingBar.setProgress(0);
            processingBar.setIndeterminate(true);
        });
        mWhisper.setAction(action);
        mWhisper.setLanguage(langToken);
        mWhisper.start();
        runOnUiThread(() -> tvStatus.setText(getString(R.string.processing)));
    }

    private void stopProcessing() {
        processingBar.setIndeterminate(false);
        if (mWhisper != null && mWhisper.isInProgress()) mWhisper.stop();
    }

    public ArrayList<File> getFilesWithExtension(File directory, String extension) {
        ArrayList<File> filteredFiles = new ArrayList<>();

        // Check if the directory is accessible
        if (directory != null && directory.exists()) {
            File[] files = directory.listFiles();

            // Filter files by the provided extension
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(extension)) {
                        filteredFiles.add(file);
                    }
                }
            }
        }

        return filteredFiles;
    }

    // ============== Floating Mode Methods ==============

    private void toggleFloatingMode() {
        if (isFloatingModeActive) {
            stopFloatingMode();
        } else {
            startFloatingMode();
        }
    }

    private void startFloatingMode() {
        // Check for overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            // Request overlay permission
            Toast.makeText(this, getString(R.string.enable_overlay_permission), Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, 1234);
            return;
        }
        
        // Check for accessibility service
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Please enable Whisper Voice accessibility service for text injection", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivityForResult(intent, 1235);
            return;
        }

        // Start the floating overlay service
        Intent serviceIntent = new Intent(this, FloatingOverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        isFloatingModeActive = true;
        updateFloatingModeButton();
        Toast.makeText(this, "Floating mode started", Toast.LENGTH_SHORT).show();
    }
    
    private boolean isAccessibilityServiceEnabled() {
        String serviceId = getPackageName() + "/" + TextInjectorService.class.getCanonicalName();
        try {
            int enabled = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
            if (enabled == 1) {
                String enabledServices = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (enabledServices != null) {
                    return enabledServices.contains(serviceId);
                }
            }
        } catch (Settings.SettingNotFoundException e) {
            // Accessibility settings not found
        }
        return false;
    }

    private void stopFloatingMode() {
        Intent serviceIntent = new Intent(this, FloatingOverlayService.class);
        stopService(serviceIntent);

        isFloatingModeActive = false;
        updateFloatingModeButton();
        Toast.makeText(this, "Floating mode stopped", Toast.LENGTH_SHORT).show();
    }

    private void updateFloatingModeButton() {
        if (tvFloatingMode != null) {
            tvFloatingMode.setText(isFloatingModeActive ? 
                    getString(R.string.stop_floating_mode) : 
                    getString(R.string.start_floating_mode));
        }
    }
    
    private void autoStartFloatingMode() {
        // Auto-start floating mode if overlay permission is already granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            // Small delay to ensure UI is ready
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!isFloatingModeActive) {
                    startFloatingMode();
                }
            }, 500);
        }
    }
    
    private void setupSizeButtons() {
        /*
        // Button btnSmall = findViewById(R.id.btnSizeSmall);
        // Button btnMedium = findViewById(R.id.btnSizeMedium);
        // Button btnLarge = findViewById(R.id.btnSizeLarge);
        ...
        */
    }
    
    private void restartFloatingModeIfActive() {
        if (isFloatingModeActive) {
            stopFloatingMode();
            // Small delay to ensure service is stopped before restarting
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                startFloatingMode();
            }, 300);
        }
    }
    
    private void updateSizeButtonColors(Button small, Button medium, Button large, int selectedIndex) {
        /*
        int activeColor = 0xFFFFFFFF; // White
        int inactiveColor = 0xFFAAAAAA; // Gray
        
        small.setTextColor(selectedIndex == 0 ? activeColor : inactiveColor);
        medium.setTextColor(selectedIndex == 1 ? activeColor : inactiveColor);
        large.setTextColor(selectedIndex == 2 ? activeColor : inactiveColor);
        */
    }

    private void showModelSelector(ArrayList<File> tfliteFiles, List<String> displayNames, List<Pair<String, String>> languagePairs, List<String> languageNames) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Select Recognition Model")
                .setItems(displayNames.toArray(new String[0]), (dialog, which) -> {
                    deinitModel();
                    selectedTfliteFile = tfliteFiles.get(which);
                    SharedPreferences.Editor editor = sp.edit();
                    editor.putString("modelName", selectedTfliteFile.getName());
                    editor.apply();
                    
                    tvSelectedModel.setText(displayNames.get(which));
                    initModel();

                    boolean needsLanguage = selectedTfliteFile.getName().equals(MULTI_LINGUAL_EU_MODEL_FAST) || 
                                          selectedTfliteFile.getName().equals(MULTI_LINGUAL_TOP_WORLD_FAST) || 
                                          selectedTfliteFile.getName().equals(MULTI_LINGUAL_TOP_WORLD_SLOW);

                    if (needsLanguage) {
                        btnSelectLanguage.setEnabled(true);
                        btnSelectLanguage.setAlpha(1.0f);
                    } else {
                        tvSelectedLanguage.setText(languageNames.get(0));
                        btnSelectLanguage.setEnabled(false);
                        btnSelectLanguage.setAlpha(0.5f);
                    }
                })
                .show();
    }

    private void showLanguageSelector(List<Pair<String, String>> languagePairs, List<String> languageNames) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Select Source Language")
                .setItems(languageNames.toArray(new String[0]), (dialog, which) -> {
                    langToken = InputLang.getIdForLanguage(InputLang.getLangList(), languagePairs.get(which).first);
                    SharedPreferences.Editor editor = sp.edit();
                    editor.putString("language", languagePairs.get(which).first);
                    editor.apply();
                    tvSelectedLanguage.setText(languageNames.get(which));
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1234) {
            // Check if overlay permission was granted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startFloatingMode();
            } else {
                Toast.makeText(this, getString(R.string.overlay_permission_required), Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == 1235) {
            // Check if accessibility service was enabled
            if (isAccessibilityServiceEnabled()) {
                startFloatingMode();
            } else {
                Toast.makeText(this, "Accessibility service not enabled", Toast.LENGTH_SHORT).show();
            }
        }
    }

}