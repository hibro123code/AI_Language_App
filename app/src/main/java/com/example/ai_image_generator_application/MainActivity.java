package com.example.ai_image_generator_application;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.Manifest;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.ai.client.generativeai.GenerativeModel;
import com.google.ai.client.generativeai.java.GenerativeModelFutures;
import com.google.ai.client.generativeai.type.Content;
import com.google.ai.client.generativeai.type.GenerateContentResponse;
import com.google.ai.client.generativeai.type.InvalidStateException;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import com.example.ai_image_generator_application.databinding.ActivityMainBinding;
import com.example.ai_image_generator_application.BuildConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static class LanguageItem {
        private final String name;
        private final String code;
        private final String recognizerTag;

        LanguageItem(String name, String code, String recognizerTag) {
            this.name = name;
            this.code = code;
            this.recognizerTag = recognizerTag;
        }

        public String getName() {
            return name;
        }

        public String getCode() {
            return code;
        }

        public String getRecognizerTag() {
            return recognizerTag;
        } // Quan trọng cho Intent

        public Locale getLocale() {
            return new Locale(code);
        }

        @NonNull
        @Override
        public String toString() {
            return name;
        }
    }

    private enum Mode {TRANSLATE, PARAPHRASE}

    private enum ParaphraseTone {FRIENDLY, FORMAL, PROFESSIONAL}

    private ActivityMainBinding binding;
    private GenerativeModelFutures generativeModel;
    private Executor mainExecutor;
    private List<LanguageItem> languageList;
    private Mode currentMode = Mode.TRANSLATE;
    private ParaphraseTone currentParaphraseTone = ParaphraseTone.FRIENDLY;

    private TextToSpeech tts;
    private boolean isTtsInitialized = false;
    private Locale lastOutputLocale = Locale.getDefault();
    private static final int RECORD_AUDIO_PERMISSION_CODE = 1;
    private static final String TAG_SPEECH = "SpeechRecognizerIntent";
    private ActivityResultLauncher<Intent> speechRecognizerLauncher;
    // Launcher MỚI cho OcrTranslateActivity
    private ActivityResultLauncher<Intent> ocrResultLauncher;
    public static final String EXTRA_OCR_TEXT_RESULT = "com.example.ai_image_generator_application.OCR_TEXT_RESULT"; // Key để lấy kết quả

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mainExecutor = ContextCompat.getMainExecutor(this);

        tts = new TextToSpeech(this, this);

        String apiKey = BuildConfig.GEMINI_API_KEY;
        if (apiKey == null || apiKey.trim().isEmpty() || apiKey.equals("YOUR_API_KEY")) {
            Toast.makeText(this, "Please set your API Key in build.gradle", Toast.LENGTH_LONG).show();
            disableInteraction();
            return;
        }

        GenerativeModel gemini = new GenerativeModel("gemini-1.5-flash", apiKey);
        generativeModel = GenerativeModelFutures.from(gemini);

        setupLanguageSpinners();
        setupModeSelection();
        setupParaphraseToneSelection();
        setupSpeakButton();

        // --- Khởi tạo ActivityResultLauncher ---
        speechRecognizerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> { // Callback xử lý kết quả trả về
                    if (result.getResultCode() == AppCompatActivity.RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        // Lấy danh sách kết quả
                        ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                        if (matches != null && !matches.isEmpty()) {
                            String recognizedText = matches.get(0); // Lấy kết quả khớp nhất
                            Log.d(TAG_SPEECH, "Recognized via Intent: " + recognizedText);
                            binding.etInputText.setText(recognizedText);
                            binding.etInputText.setSelection(recognizedText.length()); // Di chuyển con trỏ
                        } else {
                            Log.d(TAG_SPEECH, "No speech recognized from Intent result");
                            Toast.makeText(this, "Could not recognize speech", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        // Xử lý trường hợp người dùng hủy hoặc có lỗi
                        Log.d(TAG_SPEECH, "Speech recognition cancelled or failed. ResultCode: " + result.getResultCode());
                        // Có thể thêm Toast nếu muốn, ví dụ:
                        if (result.getResultCode() != AppCompatActivity.RESULT_CANCELED) {
                            Toast.makeText(this, "Speech recognition failed", Toast.LENGTH_SHORT).show();
                        }
                    }
                    // Bật lại nút mic (nếu bạn có vô hiệu hóa nó khi launch)
                    // binding.btnSpeakInput.setEnabled(true); // Xem lại logic enable/disable nút
                    updateMicButtonStateAvailability(); // Hàm mới để bật lại nút
                });
        // --- Khởi tạo launcher MỚI cho OCR ---
        ocrResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == AppCompatActivity.RESULT_OK && result.getData() != null) {
                        String recognizedText = result.getData().getStringExtra(EXTRA_OCR_TEXT_RESULT);
                        if (recognizedText != null && !recognizedText.isEmpty()) {
                            Log.d("MainActivity", "Received OCR Text: " + recognizedText);
                            binding.etInputText.setText(recognizedText);
                            binding.etInputText.setSelection(recognizedText.length());
                            // Optional: Tự động chuyển sang chế độ Translate nếu đang ở Paraphrase
                            if (currentMode == Mode.PARAPHRASE) {
                                binding.radioTranslate.setChecked(true);
                            }
                            Toast.makeText(this, "Text recognized from image", Toast.LENGTH_SHORT).show();
                        } else {
                            Log.w("MainActivity", "Received null or empty text from OCR Activity");
                        }
                    } else {
                        Log.d("MainActivity", "OCR cancelled or failed. ResultCode: " + result.getResultCode());
                    }
                });

        binding.tvOutputText.setText("Result will appear here");
        binding.btnSpeakOutput.setEnabled(false);

        binding.btnAction.setOnClickListener(v -> {
            String inputText = binding.etInputText.getText().toString().trim();
            if (inputText.isEmpty()) {
                Toast.makeText(this, "Please enter text", Toast.LENGTH_SHORT).show();
                return;
            }

            if (currentMode == Mode.TRANSLATE) {
                LanguageItem selectedSourceLang = (LanguageItem) binding.spinnerSourceLang.getSelectedItem();
                LanguageItem selectedTargetLang = (LanguageItem) binding.spinnerTargetLang.getSelectedItem();

                if (selectedSourceLang == null || selectedTargetLang == null) {
                    Toast.makeText(this, "Please select source and target languages", Toast.LENGTH_SHORT).show();
                    return;
                }
                String sourceLangCode = selectedSourceLang.getCode();
                String targetLangCode = selectedTargetLang.getCode();
                if (sourceLangCode.equals(targetLangCode)) {
                    Toast.makeText(this, "Source and target languages cannot be the same", Toast.LENGTH_SHORT).show();
                    return;
                }
                lastOutputLocale = selectedTargetLang.getLocale();
                performTranslation(inputText, sourceLangCode, targetLangCode);

            } else {
                LanguageItem selectedSourceLang = (LanguageItem) binding.spinnerSourceLang.getSelectedItem();
                lastOutputLocale = (selectedSourceLang != null) ? selectedSourceLang.getLocale() : Locale.getDefault();
                performParaphrase(inputText, currentParaphraseTone);
            }
        });
        // Listener cho nút micro mới
        binding.btnSpeakInput.setOnClickListener(v -> {
            launchSpeechRecognizerIntent();
        });
        // --- Thêm Listener cho nút Swap
        binding.btnSwapLanguages.setOnClickListener(v -> {
            swapLanguages(); // Gọi hàm xử lý việc đảo ngôn ngữ
        });
        binding.btnOpenCameraOcr.setOnClickListener(v -> {
            Log.d("MainActivity", "Launching OcrTranslateActivity...");
            Intent ocrIntent = new Intent(MainActivity.this, OcrTranslateActivity.class);
            ocrResultLauncher.launch(ocrIntent);
        });

        updateMicButtonStateAvailability();
        Log.d(TAG_SPEECH, "onCreate - Initial Mic Button Enabled State: " + binding.btnSpeakInput.isEnabled());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            Log.d("TTS", "TTS Engine Shutdown.");
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsInitialized = true;
            Log.i("TTS", "TextToSpeech Engine Initialized Successfully.");
        } else {
            isTtsInitialized = false;
            Log.e("TTS", "TextToSpeech Engine Initialization Failed!");
            Toast.makeText(this, "TTS Initialization Failed!", Toast.LENGTH_SHORT).show();
            binding.btnSpeakOutput.setEnabled(false);
        }
    }

    private void setupSpeakButton() {
        binding.btnSpeakOutput.setOnClickListener(v -> {
            String textToSpeak = binding.tvOutputText.getText().toString();
            if (textToSpeak.isEmpty() || textToSpeak.equals("Result will appear here") || textToSpeak.startsWith("An error occurred") || textToSpeak.startsWith("Received no valid")) {
                Toast.makeText(this, "Nothing to speak", Toast.LENGTH_SHORT).show();
                return;
            }
            speakText(textToSpeak, lastOutputLocale);
        });
    }

    // --- Hàm mới để launch Intent ---
    private void launchSpeechRecognizerIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);

        // Lấy ngôn ngữ nguồn và đặt vào Intent (vẫn dùng toLanguageTag)
        LanguageItem selectedSourceItem = (LanguageItem) binding.spinnerSourceLang.getSelectedItem();
        String recognizerTagForIntent;
        String promptLanguageName;

        if (selectedSourceItem != null) {
            recognizerTagForIntent = selectedSourceItem.getRecognizerTag(); // Lấy tag từ LanguageItem
            promptLanguageName = selectedSourceItem.getName();
            Log.d(TAG_SPEECH, "Using Recognizer Tag from selected LanguageItem: " + recognizerTagForIntent);
        } else {
            // Dự phòng nếu không có gì được chọn (hiếm khi xảy ra)
            recognizerTagForIntent = "en-US"; // Mặc định
            promptLanguageName = "English";
            Log.w(TAG_SPEECH, "No item selected in source spinner, defaulting to: " + recognizerTagForIntent);
        }

        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognizerTagForIntent);
        // intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag); // Thường không cần thiết
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak in " + promptLanguageName + "...");

        try {
            // Vô hiệu hóa nút tạm thời khi đang chờ Activity hệ thống
            binding.btnSpeakInput.setEnabled(false);
            speechRecognizerLauncher.launch(intent); // Launch bằng launcher đã đăng ký
        } catch (ActivityNotFoundException e) {
            // Xử lý trường hợp không có ứng dụng nào xử lý được Intent này
            Log.e(TAG_SPEECH, "Speech recognition activity not found!", e);
            Toast.makeText(this, "Your device doesn't support speech recognition", Toast.LENGTH_SHORT).show();
            binding.btnSpeakInput.setEnabled(true); // Bật lại nút ngay nếu lỗi
        }
    }

    // Hàm cập nhật trạng thái nút micro
    // --- Hàm mới để cập nhật trạng thái nút dựa trên khả năng xử lý Intent ---
    private void updateMicButtonStateAvailability() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        // Kiểm tra xem có Activity nào có thể xử lý Intent này không
        boolean available = getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null;
        binding.btnSpeakInput.setEnabled(available);
        if (!available) {
            Toast.makeText(this, "Speech recognition not available on this device", Toast.LENGTH_SHORT).show();
            Log.w(TAG_SPEECH, "No Activity found to handle ACTION_RECOGNIZE_SPEECH");
        }
        Log.d(TAG_SPEECH, "updateMicButtonStateAvailability - Available: " + available + ", Enabled: " + binding.btnSpeakInput.isEnabled());
    }


    // Hàm cập nhật trạng thái nút nói kết quả (gọi khi TTS sẵn sàng hoặc có kết quả mới)
    private void updateSpeakOutputButtonState() {
        String outputText = binding.tvOutputText.getText().toString();
        boolean hasValidOutput = !outputText.isEmpty()
                && !outputText.equals("Result will appear here")
                && !outputText.startsWith("An error occurred")
                && !outputText.startsWith("Received no valid")
                && !outputText.startsWith("Invalid response"); // Thêm các trường hợp lỗi khác nếu cần

        binding.btnSpeakOutput.setEnabled(isTtsInitialized && hasValidOutput);
    }

    private void setupModeSelection() {
        binding.radioGroupMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radioTranslate) {
                currentMode = Mode.TRANSLATE;
                binding.languageSelectorLayout.setVisibility(View.VISIBLE);
                binding.checkboxExplainTranslation.setVisibility(View.VISIBLE); // Hiện CheckBox
                binding.radioGroupParaphraseTone.setVisibility(View.GONE);
                binding.btnAction.setText("Translate");

            } else if (checkedId == R.id.radioParaphrase) {
                currentMode = Mode.PARAPHRASE;
                binding.languageSelectorLayout.setVisibility(View.GONE);
                binding.checkboxExplainTranslation.setVisibility(View.GONE); // Ẩn CheckBox
                binding.radioGroupParaphraseTone.setVisibility(View.VISIBLE);
                binding.btnAction.setText("Paraphrase");

            }
            clearOutputAndDisableSpeak();
        });
        binding.radioTranslate.setChecked(true);
        binding.languageSelectorLayout.setVisibility(View.VISIBLE);
        binding.checkboxExplainTranslation.setVisibility(View.VISIBLE);
        binding.radioGroupParaphraseTone.setVisibility(View.GONE);
        binding.btnAction.setText("Translate");
        binding.checkboxExplainTranslation.setChecked(false);
    }
    private void setupCheckboxListener() {
        binding.checkboxExplainTranslation.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.d("Checkbox", "Explain translation checked: " + isChecked);
            // Khi người dùng thay đổi lựa chọn giải thích,
            // kết quả cũ (nếu có) có thể không còn phản ánh đúng yêu cầu mới.
            // Vì vậy, nên xóa output cũ.
            clearOutputAndDisableSpeak();
        });
    }
    private void setupParaphraseToneSelection() {
        binding.radioGroupParaphraseTone.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radioFriendly) {
                currentParaphraseTone = ParaphraseTone.FRIENDLY;
            } else if (checkedId == R.id.radioFormal) {
                currentParaphraseTone = ParaphraseTone.FORMAL;
            } else if (checkedId == R.id.radioProfessional) {
                currentParaphraseTone = ParaphraseTone.PROFESSIONAL;
            }
            clearOutputAndDisableSpeak();
        });
        binding.radioFriendly.setChecked(true);
        currentParaphraseTone = ParaphraseTone.FRIENDLY;
    }

    private void setupLanguageSpinners() {
        languageList = new ArrayList<>();
        // Thêm recognizerTag chính xác vào đây
        languageList.add(new LanguageItem("English", "en", "en-US"));
        languageList.add(new LanguageItem("Vietnamese", "vi", "vi-VN"));
        languageList.add(new LanguageItem("Spanish", "es", "es-ES")); // Tiếng TBN hoạt động
        languageList.add(new LanguageItem("French", "fr", "fr-FR"));
        languageList.add(new LanguageItem("German", "de", "de-DE"));
        languageList.add(new LanguageItem("Japanese", "ja", "ja-JP"));
        languageList.add(new LanguageItem("Korean", "ko", "ko-KR"));
        languageList.add(new LanguageItem("Chinese", "zh", "zh-CN"));

        ArrayAdapter<LanguageItem> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, languageList);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        binding.spinnerSourceLang.setAdapter(adapter);
        binding.spinnerTargetLang.setAdapter(adapter);

        String deviceLangCode = getDeviceLanguage();
        int sourceDefaultPosition = 0;
        int targetDefaultPosition = 1;

        for (int i = 0; i < languageList.size(); i++) {
            if (languageList.get(i).getCode().equals(deviceLangCode)) {
                sourceDefaultPosition = i;
                targetDefaultPosition = deviceLangCode.equals("vi") ? 0 : 1;
                if (sourceDefaultPosition == targetDefaultPosition) {
                    targetDefaultPosition = (targetDefaultPosition == 0) ? 1 : 0;
                }
                break;
            }
        }
        binding.spinnerSourceLang.setSelection(sourceDefaultPosition);
        binding.spinnerTargetLang.setSelection(targetDefaultPosition);

        AdapterView.OnItemSelectedListener clearListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                clearOutputAndDisableSpeak();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        binding.spinnerSourceLang.setOnItemSelectedListener(clearListener);
        binding.spinnerTargetLang.setOnItemSelectedListener(clearListener);
    }

    private String getDeviceLanguage() {
        return Locale.getDefault().getLanguage();
    }

    private void performTranslation(String textToTranslate, String sourceLangCode, String targetLangCode) {
        setLoadingState(true);
        String prompt;
        // Lấy trạng thái hiện tại của CheckBox
        boolean explainDetails = binding.checkboxExplainTranslation.isChecked();

        LanguageItem sourceItem = (LanguageItem) binding.spinnerSourceLang.getSelectedItem();
        LanguageItem targetItem = (LanguageItem) binding.spinnerTargetLang.getSelectedItem();
        String sourceLangName = (sourceItem != null) ? sourceItem.getName() : sourceLangCode;
        String targetLangName = (targetItem != null) ? targetItem.getName() : targetLangCode;


        if (explainDetails) {
            // Prompt yêu cầu dịch và giải thích thêm (nếu có)
            prompt = "Translate the following text from " + sourceLangName + " (" + sourceLangCode + ") " +
                    "to " + targetLangName + " (" + targetLangCode + "). " +
                    "After the primary translation, if there are important nuances, common alternative translations, " +
                    "or relevant grammatical points related to the translation that a learner might find useful, " +
                    "please briefly explain them in a separate section labeled 'Explanation:'." +
                    "\n\nText to translate:\n\"" + textToTranslate + "\"";
            Log.d("Prompt", "Requesting translation WITH explanation.");

        } else {
            // Prompt chỉ yêu cầu dịch trực tiếp, không giải thích
            prompt = "Translate the following text *directly* and *concisely* from " + sourceLangName + " (" + sourceLangCode + ") " +
                    "to " + targetLangName + " (" + targetLangCode + "). " +
                    "Provide *only* the most suitable translated text. Do not include any additional explanations, commentary, alternatives, or introductory phrases like 'Here is the translation:'." +
                    "\n\nText to translate:\n\"" + textToTranslate + "\"";
            Log.d("Prompt", "Requesting translation WITHOUT explanation.");
        }

        // Gọi API với prompt đã được điều chỉnh
        callGeminiApi(prompt, "GeminiTranslateError");
    }

    private void performParaphrase(String textToParaphrase, ParaphraseTone tone) {
        setLoadingState(true);
        String toneInstruction;
        switch (tone) {
            case FORMAL:
                toneInstruction = "formal";
                break;
            case PROFESSIONAL:
                toneInstruction = "professional";
                break;
            case FRIENDLY:
            default:
                toneInstruction = "friendly and conversational";
                break;
        }
        String prompt = "Paraphrase the following text in a " + toneInstruction + " tone, rewriting it clearly in different words while keeping the original meaning:\n\n\"" + textToParaphrase + "\"";
        callGeminiApi(prompt, "GeminiParaphraseError");
    }

    private void callGeminiApi(String prompt, String errorTag) {
        Content content = new Content.Builder().addText(prompt).build();
        binding.btnSpeakOutput.setEnabled(false);

        if (generativeModel == null) {
            Toast.makeText(this, "Model not initialized", Toast.LENGTH_SHORT).show();
            setLoadingState(false);
            return;
        }

        ListenableFuture<GenerateContentResponse> responseFuture = generativeModel.generateContent(content);

        Futures.addCallback(responseFuture, new FutureCallback<GenerateContentResponse>() {
            @Override
            public void onSuccess(GenerateContentResponse result) {
                setLoadingState(false);
                String resultText = null; // Khởi tạo là null
                boolean success = false;
                try {
                    // Kiểm tra kết quả trả về và nội dung text
                    if (result != null) {
                        resultText = result.getText(); // Lấy text an toàn hơn
                    }

                    if (resultText != null && !resultText.trim().isEmpty()) {
                        binding.tvOutputText.setText(resultText);
                        success = true;
                    } else {
                        binding.tvOutputText.setText("Received no valid response from AI.");
                        Log.w(errorTag, "Received null response or empty text.");
                    }
                } catch (InvalidStateException e) {
                    Log.e(errorTag, "Invalid response state: " + e.getMessage(), e);
                    binding.tvOutputText.setText("Invalid response or content blocked.");
                    resultText = null; // Đảm bảo text là null nếu lỗi
                } catch (Exception e) {
                    Log.e(errorTag, "Error processing result: " + e.getMessage(), e);
                    binding.tvOutputText.setText("Error processing result.");
                    resultText = null; // Đảm bảo text là null nếu lỗi
                } finally {
                    // Cập nhật nút nói chỉ khi TTS sẵn sàng và có kết quả hợp lệ
                    updateSpeakOutputButtonState();
                }
            }

            @Override
            public void onFailure(@NonNull Throwable t) {
                Log.e(errorTag, "API call failed: " + t.getMessage(), t);
                setLoadingState(false);
                binding.tvOutputText.setText("An error occurred: " + t.getLocalizedMessage());
                // Vẫn gọi update để đảm bảo nút bị vô hiệu hóa nếu không có kết quả
                updateSpeakOutputButtonState();
                Toast.makeText(MainActivity.this, "Error: " + t.getLocalizedMessage(), Toast.LENGTH_LONG).show();
            }
        }, mainExecutor);
    }

    private void setLoadingState(boolean isLoading) {
        Log.d("DEBUG_STATE", "setLoadingState called with isLoading: " + isLoading);
        binding.progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        binding.tvOutputText.setVisibility(isLoading ? View.GONE : View.VISIBLE);
        binding.btnAction.setEnabled(!isLoading);
        binding.btnSpeakOutput.setEnabled(false); // Luôn tắt khi loading, sẽ bật lại sau khi có kết quả

        // Bật/tắt nút micro chỉ dựa vào isLoading và recognizerAvailable
        if (isLoading) {
            binding.btnSpeakInput.setEnabled(false);
        } else {
            updateMicButtonStateAvailability(); // Gọi hàm kiểm tra Intent khi hết loading
        }
        Log.d("DEBUG_STATE", "setLoadingState - Mic Button Enabled: " + binding.btnSpeakInput.isEnabled());


        setRadioGroupEnabled(binding.radioGroupMode, !isLoading);
        setRadioGroupEnabled(binding.radioGroupParaphraseTone, !isLoading);
        binding.spinnerSourceLang.setEnabled(!isLoading);
        binding.spinnerTargetLang.setEnabled(!isLoading);
        binding.etInputText.setEnabled(!isLoading);

        if (isLoading) {
            binding.tvOutputText.setText(""); // Xóa text cũ khi loading
        } else {
            // Khi không loading, đảm bảo icon mic đúng
            if (binding.btnSpeakInput.isEnabled()) {
                //binding.btnSpeakInput.setImageResource(android.R.drawable.ic_btn_speak_now);
            }
        }
    }

    private void setRadioGroupEnabled(RadioGroup radioGroup, boolean enabled) {
        for (int i = 0; i < radioGroup.getChildCount(); i++) {
            radioGroup.getChildAt(i).setEnabled(enabled);
        }
    }

    private void disableInteraction() {
        setRadioGroupEnabled(binding.radioGroupMode, false);
        setRadioGroupEnabled(binding.radioGroupParaphraseTone, false);
        binding.spinnerSourceLang.setEnabled(false);
        binding.spinnerTargetLang.setEnabled(false);
        binding.etInputText.setEnabled(false);
        binding.btnAction.setEnabled(false);
        binding.btnSpeakInput.setEnabled(false);
        binding.btnSpeakOutput.setEnabled(false);
    }

    private void clearOutputAndDisableSpeak() {
        if (binding != null) {
            binding.tvOutputText.setText("Result will appear here");
            binding.btnSpeakOutput.setEnabled(false); // Quan trọng: Vô hiệu hóa nút nói kết quả
        }
    }

    private void speakText(String text, Locale locale) {
        if (!isTtsInitialized) {
            Toast.makeText(this, "TTS is not ready yet", Toast.LENGTH_SHORT).show();
            return;
        }

        int langResult = tts.setLanguage(locale);

        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e("TTS", "Language not supported: " + locale);
            Toast.makeText(this, "TTS language (" + locale.getDisplayLanguage() + ") not supported", Toast.LENGTH_SHORT).show();
            return;
        } else {
            Log.i("TTS", "Setting language to: " + locale + " - Result: " + langResult);
        }

        String utteranceId = UUID.randomUUID().toString();
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
    }

    private void swapLanguages() {
        // Lấy vị trí hiện tại của hai spinner
        int sourcePosition = binding.spinnerSourceLang.getSelectedItemPosition();
        int targetPosition = binding.spinnerTargetLang.getSelectedItemPosition();

        // Kiểm tra xem vị trí có hợp lệ không (đề phòng)
        if (sourcePosition == AdapterView.INVALID_POSITION || targetPosition == AdapterView.INVALID_POSITION) {
            Log.w("SwapLanguages", "Cannot swap, invalid spinner position.");
            return;
        }

        // Đảo vị trí lựa chọn của hai spinner
        // Quan trọng: Tạm thời bỏ listener để tránh gọi clearOutput nhiều lần không cần thiết
        // hoặc chấp nhận việc clearOutput xảy ra (thường là hành vi mong muốn khi đổi ngôn ngữ)

        Log.d("SwapLanguages", "Swapping positions: Source(" + sourcePosition + ") <-> Target(" + targetPosition + ")");

        // Thực hiện việc đặt lại lựa chọn. Listener sẽ được trigger ở đây.
        binding.spinnerSourceLang.setSelection(targetPosition, true); // true để có animation (tùy chọn)
        binding.spinnerTargetLang.setSelection(sourcePosition, true);

        // Listener `clearListener` đã được gắn vào spinner sẽ tự động được gọi
        // khi setSelection thay đổi lựa chọn, nó sẽ gọi clearOutputAndDisableSpeak().
        // Đây thường là hành vi đúng đắn khi đảo ngôn ngữ.

        // Optional: Thêm Toast để thông báo
        // Toast.makeText(this, "Languages swapped", Toast.LENGTH_SHORT).show();
    }
}