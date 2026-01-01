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
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Pair;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.utils.HapticFeedback;
import com.whispertflite.utils.InputLang;
import com.whispertflite.utils.LanguagePairAdapter;
import com.whispertflite.utils.ThemeUtils;
import com.whispertflite.overlay_mode.FloatingOverlayService;
import com.whispertflite.overlay_mode.WhitelistAppsActivity;

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
    private ImageButton fabCopy;
    private ImageButton btnRecord;
    private LinearLayout layoutModeChinese;
    private LinearLayout layoutTTS;
    private androidx.appcompat.widget.SwitchCompat append;
    private androidx.appcompat.widget.SwitchCompat translate;
    private CheckBox modeSimpleChinese;
    private CheckBox modeTTS;
    private ProgressBar processingBar;
    private ImageButton btnInfo;
    private Button btnFloatingMode;
    private boolean isFloatingModeActive = false;

    private Recorder mRecorder = null;
    private Whisper mWhisper = null;

    private File sdcardDataFolder = null;
    private File selectedTfliteFile = null;
    private SharedPreferences sp = null;
    private Spinner spinnerTflite;
    private CountDownTimer countDownTimer;
    private Spinner spinnerLanguage;
    private int langToken = -1;
    private long startTime = 0;
    private TextToSpeech tts;

    @Override
    protected void onDestroy() {
        deinitModel();
        deinitTTS();
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
        mContext = this;
        setContentView(R.layout.activity_main);
        ThemeUtils.setStatusBarAppearance(this);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        checkInputMethodEnabled();
        processingBar = findViewById(R.id.processing_bar);
        sp = PreferenceManager.getDefaultSharedPreferences(this);
        append = findViewById(R.id.mode_append);

        layoutTTS = findViewById(R.id.layout_tts);
        modeTTS = findViewById(R.id.mode_tts);
        modeTTS.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (isChecked) {
                tts = new TextToSpeech(mContext, status -> {
                    if (status == TextToSpeech.SUCCESS) {
                        int result = tts.setLanguage(Locale.US);
                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            runOnUiThread(() -> {
                                Toast.makeText(mContext, mContext.getString(R.string.tts_language_not_supported),Toast.LENGTH_SHORT).show();
                                modeTTS.setChecked(false);
                            });

                        }
                    } else {
                        runOnUiThread(() -> Toast.makeText(mContext, mContext.getString(R.string.tts_initialization_failed),Toast.LENGTH_SHORT).show());
                    }
                });
            } else {
                deinitTTS();
            }
        });

        translate = findViewById(R.id.mode_translate);
        translate.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            layoutTTS.setVisibility(isChecked ? View.VISIBLE:View.GONE);
            if (layoutTTS.getVisibility() == View.GONE) modeTTS.setChecked(false);
        });

        // Call the method to copy specific file types from assets to data folder
        sdcardDataFolder = this.getExternalFilesDir(null);

        ArrayList<File> tfliteFiles = getFilesWithExtension(sdcardDataFolder, ".tflite");

        // Initialize default model to use
        initModel();

        btnInfo = findViewById(R.id.btnInfo);
        btnInfo.setOnClickListener(view -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/adithyakrish0/whisper-voice"))));

        // Floating Mode Button
        btnFloatingMode = findViewById(R.id.btnFloatingMode);
        updateFloatingModeButton();
        btnFloatingMode.setOnClickListener(v -> toggleFloatingMode());
        
        // Whitelist Apps Button
        Button btnWhitelist = findViewById(R.id.btnWhitelist);
        btnWhitelist.setOnClickListener(v -> {
            startActivity(new Intent(this, WhitelistAppsActivity.class));
        });
        
        // Floating overlay size buttons
        setupSizeButtons();
        
        // Auto-start floating mode on app init
        autoStartFloatingMode();

        spinnerLanguage = findViewById(R.id.spnrLanguage);
        List<Pair<String, String>> languagePairs = LanguagePairAdapter.getLanguagePairs(this);
        LanguagePairAdapter languagePairAdapter = new LanguagePairAdapter(this, android.R.layout.simple_spinner_item, languagePairs);
        languagePairAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLanguage.setAdapter(languagePairAdapter);

        spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                langToken = InputLang.getIdForLanguage(InputLang.getLangList(),languagePairs.get(i).first);
                SharedPreferences.Editor editor = sp.edit();
                editor.putString("language",languagePairs.get(i).first);
                editor.apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });

        selectedTfliteFile = new File(sdcardDataFolder, sp.getString("modelName", MULTI_LINGUAL_TOP_WORLD_SLOW));
        ArrayAdapter<File> tfliteAdapter = getFileArrayAdapter(tfliteFiles);
        int position = tfliteAdapter.getPosition(selectedTfliteFile);
        spinnerTflite = findViewById(R.id.spnrTfliteFiles);
        spinnerTflite.setAdapter(tfliteAdapter);
        spinnerTflite.setSelection(position,false);
        if (selectedTfliteFile.getName().equals(MULTI_LINGUAL_EU_MODEL_FAST) || selectedTfliteFile.getName().equals(MULTI_LINGUAL_TOP_WORLD_FAST) || selectedTfliteFile.getName().equals(MULTI_LINGUAL_TOP_WORLD_SLOW)){
            spinnerLanguage.setEnabled(true);
            String langCode = sp.getString("language", "auto");
            spinnerLanguage.setSelection(languagePairAdapter.getIndexByCode(langCode));
        } else {
            spinnerLanguage.setSelection(0);
            spinnerLanguage.setEnabled(false);
        }
        spinnerTflite.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                deinitModel();
                selectedTfliteFile = (File) parent.getItemAtPosition(position);
                SharedPreferences.Editor editor = sp.edit();
                editor.putString("modelName",selectedTfliteFile.getName());
                editor.apply();
                initModel();
                if (selectedTfliteFile.getName().equals(MULTI_LINGUAL_EU_MODEL_FAST) || selectedTfliteFile.getName().equals(MULTI_LINGUAL_TOP_WORLD_FAST) || selectedTfliteFile.getName().equals(MULTI_LINGUAL_TOP_WORLD_SLOW)){
                    spinnerLanguage.setEnabled(true);
                    String langCode = sp.getString("language", "auto");
                    spinnerLanguage.setSelection(languagePairAdapter.getIndexByCode(langCode));
                } else {
                    spinnerLanguage.setSelection(0);
                    spinnerLanguage.setEnabled(false);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Handle case when nothing is selected, if needed
            }
        });


        // Implementation of record button functionality
        btnRecord = findViewById(R.id.btnRecord);

        btnRecord.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                // Pressed
                runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                Log.d(TAG, "Start recording...");
                if (!mWhisper.isInProgress()) {
                    HapticFeedback.vibrate(this);
                    startRecording();
                    runOnUiThread(() -> processingBar.setProgress(100));
                    countDownTimer = new CountDownTimer(30000, 1000) {
                        @Override
                        public void onTick(long l) {
                            runOnUiThread(() -> processingBar.setProgress((int) (l / 300)));
                        }
                        @Override
                        public void onFinish() {}
                    };
                    countDownTimer.start();
                } else (Toast.makeText(this,getString(R.string.please_wait),Toast.LENGTH_SHORT)).show();

            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                // Released
                runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));
                if (mRecorder != null && mRecorder.isInProgress()) {
                    Log.d(TAG, "Recording is in progress... stopping...");
                    stopRecording();
                }
            }
            return true;
        });

        layoutModeChinese = findViewById(R.id.layout_mode_chinese);
        modeSimpleChinese = findViewById(R.id.mode_simple_chinese);
        modeSimpleChinese.setChecked(sp.getBoolean("simpleChinese",false));  //default to traditional Chinese
        modeSimpleChinese.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            SharedPreferences.Editor editor = sp.edit();
            editor.putBoolean("simpleChinese", isChecked);
            editor.apply();
            tvResult.setText("");
        });

        tvStatus = findViewById(R.id.tvStatus);
        tvResult = findViewById(R.id.tvResult);
        fabCopy = findViewById(R.id.fabCopy);
        fabCopy.setOnClickListener(v -> {
            // Get the text from tvResult
            String textToCopy = tvResult.getText().toString().trim();

            // Copy the text to the clipboard
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(getString(R.string.model_output), textToCopy);
            clipboard.setPrimaryClip(clip);
        });

        // Audio recording functionality
        mRecorder = new Recorder(this);
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                Log.d(TAG, "Update is received, Message: " + message);
                if (message.equals(Recorder.MSG_RECORDING)) {
                    runOnUiThread(() -> tvStatus.setText(getString(R.string.record_button) +"…"));
                    if (!append.isChecked()) runOnUiThread(() -> tvResult.setText(""));
                    runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                    HapticFeedback.vibrate(mContext);
                    runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));

                    if (translate.isChecked()) startProcessing(Whisper.ACTION_TRANSLATE);
                    else startProcessing(Whisper.ACTION_TRANSCRIBE);
                } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                    HapticFeedback.vibrate(mContext);
                    if (countDownTimer!=null) { countDownTimer.cancel();}
                    runOnUiThread(() -> {
                        btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
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
        File modelFile = new File(sdcardDataFolder, sp.getString("modelName", MULTI_LINGUAL_TOP_WORLD_SLOW));
        boolean isMultilingualModel = !(modelFile.getName().endsWith(ENGLISH_ONLY_MODEL_EXTENSION));
        String vocabFileName = isMultilingualModel ? MULTILINGUAL_VOCAB_FILE : ENGLISH_ONLY_VOCAB_FILE;
        File vocabFile = new File(sdcardDataFolder, vocabFileName);

        mWhisper = new Whisper(this);
        mWhisper.loadModel(modelFile, vocabFile, isMultilingualModel);
        Log.d(TAG, "Initialized: " + modelFile.getName());
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                Log.d(TAG, "Update is received, Message: " + message);

                if (message.equals(Whisper.MSG_PROCESSING)) {
                    runOnUiThread(() -> tvStatus.setText(getString(R.string.processing)));
                    startTime = System.currentTimeMillis();
                    runOnUiThread(() -> spinnerTflite.setEnabled(false));
                }
            }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                long timeTaken = System.currentTimeMillis() - startTime;
                runOnUiThread(() -> tvStatus.setText(getString(R.string.processing_done) + timeTaken + "\u2009ms" + "\n"+ getString(R.string.language) + " " + new Locale(whisperResult.getLanguage()).getDisplayLanguage() + " " + (whisperResult.getTask() == Whisper.Action.TRANSCRIBE ? getString(R.string.mode_transcription) : getString(R.string.mode_translation))));
                runOnUiThread(() -> processingBar.setIndeterminate(false));
                Log.d(TAG, "Result: " + whisperResult.getResult() + " " + whisperResult.getLanguage() + " " + (whisperResult.getTask() == Whisper.Action.TRANSCRIBE ? "transcribing" : "translating"));
                if ((whisperResult.getLanguage().equals("zh")) && (whisperResult.getTask() == Whisper.Action.TRANSCRIBE)){
                    runOnUiThread(() -> layoutModeChinese.setVisibility(View.VISIBLE));
                    boolean simpleChinese = sp.getBoolean("simpleChinese",false);  //convert to desired Chinese mode
                    String result = simpleChinese ? ZhConverterUtil.toSimple(whisperResult.getResult()) : ZhConverterUtil.toTraditional(whisperResult.getResult());
                    runOnUiThread(() -> tvResult.append(result));
                } else {
                    runOnUiThread(() -> layoutModeChinese.setVisibility(View.GONE));
                    runOnUiThread(() -> tvResult.append(whisperResult.getResult()));
                }
                runOnUiThread(() -> spinnerTflite.setEnabled(true));
                if (modeTTS.isChecked()){
                    tts.speak(whisperResult.getResult(), TextToSpeech.QUEUE_FLUSH, null, null);
                }
            }
        });
    }

    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.unloadModel();
            mWhisper = null;
        }
    }

    private void deinitTTS(){
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    private @NonNull ArrayAdapter<File> getFileArrayAdapter(ArrayList<File> tfliteFiles) {
        ArrayAdapter<File> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, tfliteFiles) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView textView = view.findViewById(android.R.id.text1);
                if ((getItem(position).getName()).equals(MULTI_LINGUAL_MODEL_SLOW))
                    textView.setText(R.string.multi_lingual_slow);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_TOP_WORLD_SLOW))
                    textView.setText(R.string.multi_lingual_slow);
                else if ((getItem(position).getName()).equals(ENGLISH_ONLY_MODEL))
                    textView.setText(R.string.english_only_fast);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_MODEL_FAST))
                    textView.setText(R.string.multi_lingual_fast);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_EU_MODEL_FAST))
                    textView.setText(R.string.multi_lingual_fast);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_TOP_WORLD_FAST))
                    textView.setText(R.string.multi_lingual_fast);
                else
                    textView.setText(getItem(position).getName().substring(0, getItem(position).getName().length() - ".tflite".length()));

                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                TextView textView = view.findViewById(android.R.id.text1);
                if ((getItem(position).getName()).equals(MULTI_LINGUAL_MODEL_SLOW))
                    textView.setText(R.string.multi_lingual_slow);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_TOP_WORLD_SLOW))
                    textView.setText(R.string.multi_lingual_slow);
                else if ((getItem(position).getName()).equals(ENGLISH_ONLY_MODEL))
                    textView.setText(R.string.english_only_fast);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_MODEL_FAST))
                    textView.setText(R.string.multi_lingual_fast);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_EU_MODEL_FAST))
                    textView.setText(R.string.multi_lingual_fast);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_TOP_WORLD_FAST))
                    textView.setText(R.string.multi_lingual_fast);
                else
                    textView.setText(getItem(position).getName().substring(0, getItem(position).getName().length() - ".tflite".length()));

                return view;
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

    private void stopFloatingMode() {
        Intent serviceIntent = new Intent(this, FloatingOverlayService.class);
        stopService(serviceIntent);

        isFloatingModeActive = false;
        updateFloatingModeButton();
        Toast.makeText(this, "Floating mode stopped", Toast.LENGTH_SHORT).show();
    }

    private void updateFloatingModeButton() {
        if (btnFloatingMode != null) {
            btnFloatingMode.setText(isFloatingModeActive ? 
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
        Button btnSmall = findViewById(R.id.btnSizeSmall);
        Button btnMedium = findViewById(R.id.btnSizeMedium);
        Button btnLarge = findViewById(R.id.btnSizeLarge);
        
        // Read current preference
        int currentSize = sp.getInt(FloatingOverlayService.PREF_OVERLAY_SIZE, 1);
        updateSizeButtonColors(btnSmall, btnMedium, btnLarge, currentSize);
        
        btnSmall.setOnClickListener(v -> {
            sp.edit().putInt(FloatingOverlayService.PREF_OVERLAY_SIZE, 0).apply();
            updateSizeButtonColors(btnSmall, btnMedium, btnLarge, 0);
            restartFloatingModeIfActive();
        });
        
        btnMedium.setOnClickListener(v -> {
            sp.edit().putInt(FloatingOverlayService.PREF_OVERLAY_SIZE, 1).apply();
            updateSizeButtonColors(btnSmall, btnMedium, btnLarge, 1);
            restartFloatingModeIfActive();
        });
        
        btnLarge.setOnClickListener(v -> {
            sp.edit().putInt(FloatingOverlayService.PREF_OVERLAY_SIZE, 2).apply();
            updateSizeButtonColors(btnSmall, btnMedium, btnLarge, 2);
            restartFloatingModeIfActive();
        });
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
        int activeColor = 0xFFFFFFFF; // White
        int inactiveColor = 0xFFAAAAAA; // Gray
        
        small.setTextColor(selectedIndex == 0 ? activeColor : inactiveColor);
        medium.setTextColor(selectedIndex == 1 ? activeColor : inactiveColor);
        large.setTextColor(selectedIndex == 2 ? activeColor : inactiveColor);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1234) {
            // Check if permission was granted after returning from settings
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startFloatingMode();
            } else {
                Toast.makeText(this, getString(R.string.overlay_permission_required), Toast.LENGTH_SHORT).show();
            }
        }
    }

}