package com.example.ai_image_generator_application;

import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
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
        LanguageItem(String name, String code) { this.name = name; this.code = code; }
        public String getName() { return name; }
        public String getCode() { return code; }
        public Locale getLocale() {
            return new Locale(code);
        }
        @NonNull @Override public String toString() { return name; }
    }

    private enum Mode { TRANSLATE, PARAPHRASE }
    private enum ParaphraseTone { FRIENDLY, FORMAL, PROFESSIONAL }

    private ActivityMainBinding binding;
    private GenerativeModelFutures generativeModel;
    private Executor mainExecutor;
    private List<LanguageItem> languageList;
    private Mode currentMode = Mode.TRANSLATE;
    private ParaphraseTone currentParaphraseTone = ParaphraseTone.FRIENDLY;

    private TextToSpeech tts;
    private boolean isTtsInitialized = false;
    private Locale lastOutputLocale = Locale.getDefault();

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
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            Log.d("TTS", "TTS Engine Shutdown.");
        }
        super.onDestroy();
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

    private void setupModeSelection() {
        binding.radioGroupMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radioTranslate) {
                currentMode = Mode.TRANSLATE;
                binding.languageSelectorLayout.setVisibility(View.VISIBLE);
                binding.radioGroupParaphraseTone.setVisibility(View.GONE);
                binding.btnAction.setText("Translate");

            } else if (checkedId == R.id.radioParaphrase) {
                currentMode = Mode.PARAPHRASE;
                binding.languageSelectorLayout.setVisibility(View.GONE);
                binding.radioGroupParaphraseTone.setVisibility(View.VISIBLE);
                binding.btnAction.setText("Paraphrase");

            }
            clearOutputAndDisableSpeak();
        });
        binding.radioTranslate.setChecked(true);
        binding.btnAction.setText("Translate");
        binding.languageSelectorLayout.setVisibility(View.VISIBLE);
        binding.radioGroupParaphraseTone.setVisibility(View.GONE);
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
        languageList.add(new LanguageItem("English", "en"));
        languageList.add(new LanguageItem("Vietnamese", "vi"));
        languageList.add(new LanguageItem("Spanish", "es"));
        languageList.add(new LanguageItem("French", "fr"));
        languageList.add(new LanguageItem("German", "de"));
        languageList.add(new LanguageItem("Japanese", "ja"));
        languageList.add(new LanguageItem("Korean", "ko"));
        languageList.add(new LanguageItem("Chinese", "zh"));

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
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
        binding.spinnerSourceLang.setOnItemSelectedListener(clearListener);
        binding.spinnerTargetLang.setOnItemSelectedListener(clearListener);
    }

    private String getDeviceLanguage() {
        return Locale.getDefault().getLanguage();
    }

    private void performTranslation(String textToTranslate, String sourceLangCode, String targetLangCode) {
        setLoadingState(true);
        String prompt = "Translate the following text from " + sourceLangCode + " to " + targetLangCode + ":\n\n\"" + textToTranslate + "\"";
        callGeminiApi(prompt, "GeminiTranslateError");
    }

    private void performParaphrase(String textToParaphrase, ParaphraseTone tone) {
        setLoadingState(true);
        String toneInstruction;
        switch (tone) {
            case FORMAL: toneInstruction = "formal"; break;
            case PROFESSIONAL: toneInstruction = "professional"; break;
            case FRIENDLY: default: toneInstruction = "friendly and conversational"; break;
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
                try {
                    String resultText = (result != null) ? result.getText() : null;
                    if (resultText != null && !resultText.trim().isEmpty()) {
                        binding.tvOutputText.setText(resultText);
                        binding.btnSpeakOutput.setEnabled(isTtsInitialized);
                    } else {
                        binding.tvOutputText.setText("Received no valid response.");
                        Log.w(errorTag, "Received null response or empty text.");
                        binding.btnSpeakOutput.setEnabled(false);
                    }
                } catch (InvalidStateException e) {
                    Log.e(errorTag, "Invalid response state: " + e.getMessage(), e);
                    binding.tvOutputText.setText("Invalid response or content blocked.");
                    binding.btnSpeakOutput.setEnabled(false);
                } catch (Exception e) {
                    Log.e(errorTag, "Error processing result: " + e.getMessage(), e);
                    binding.tvOutputText.setText("Error processing result.");
                    binding.btnSpeakOutput.setEnabled(false);
                }
            }

            @Override
            public void onFailure(@NonNull Throwable t) {
                Log.e(errorTag, "API call failed: " + t.getMessage(), t);
                setLoadingState(false);
                binding.tvOutputText.setText("An error occurred: " + t.getLocalizedMessage());
                binding.btnSpeakOutput.setEnabled(false);
                Toast.makeText(MainActivity.this, "Error: " + t.getLocalizedMessage(), Toast.LENGTH_LONG).show();
            }
        }, mainExecutor);
    }

    private void setLoadingState(boolean isLoading) {
        binding.progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        binding.tvOutputText.setVisibility(isLoading ? View.GONE : View.VISIBLE);
        binding.btnAction.setEnabled(!isLoading);
        binding.btnSpeakOutput.setEnabled(false);

        setRadioGroupEnabled(binding.radioGroupMode, !isLoading);
        setRadioGroupEnabled(binding.radioGroupParaphraseTone, !isLoading);
        binding.spinnerSourceLang.setEnabled(!isLoading);
        binding.spinnerTargetLang.setEnabled(!isLoading);
        binding.etInputText.setEnabled(!isLoading);

        if (isLoading) {
            binding.tvOutputText.setText("");
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
        binding.btnSpeakOutput.setEnabled(false);
    }

    private void clearOutputAndDisableSpeak() {
        if (binding != null) {
            binding.tvOutputText.setText("Result will appear here");
            binding.btnSpeakOutput.setEnabled(false);
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
}