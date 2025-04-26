package com.example.ai_image_generator_application;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import android.Manifest; // Cần import
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.Image;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.example.ai_image_generator_application.databinding.ActivityOcrTranslateBinding; // Tạo view binding
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions; // Dùng LATIN làm mặc định

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OcrTranslateActivity extends AppCompatActivity {

    private static final String TAG = "OcrTranslateActivity";
    private ActivityOcrTranslateBinding binding; // Sử dụng ViewBinding
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private TextRecognizer textRecognizer;

    // Launcher để yêu cầu quyền Camera
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Log.d(TAG, "Camera permission granted");
                    startCamera();
                } else {
                    Log.w(TAG, "Camera permission denied");
                    Toast.makeText(this, "Camera permission is required to use this feature.", Toast.LENGTH_LONG).show();
                    finish(); // Đóng Activity nếu không có quyền
                }
            });
    private ActivityResultLauncher<PickVisualMediaRequest> pickMediaLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityOcrTranslateBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        cameraExecutor = Executors.newSingleThreadExecutor(); // Executor cho CameraX
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS); // Khởi tạo ML Kit Recognizer
        // Sử dụng PickVisualMedia (ưu tiên)
        pickMediaLauncher = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) {
                Log.d(TAG, "Photo selected from gallery: " + uri);
                processImageFromUri(uri); // Xử lý ảnh từ URI
            } else {
                Log.d(TAG, "No media selected");
                Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show();
                // Đảm bảo các nút được bật lại nếu không chọn gì
                showLoading(false);
            }
        });

        // Kiểm tra quyền Camera
        checkCameraPermissionAndStart();

        // Listener cho nút chụp ảnh
        binding.btnCaptureOcr.setOnClickListener(v -> takePhoto());

        // --- Listener cho nút mở thư viện MỚI ---
        binding.btnOpenGallery.setOnClickListener(v -> {
            Log.d(TAG, "Opening image picker...");
            showLoading(true); // Hiện loading tạm thời khi mở picker
            binding.btnCaptureOcr.setEnabled(false); // Tắt nút chụp
            binding.btnOpenGallery.setEnabled(false);// Tắt nút gallery

            // Sử dụng PickVisualMedia
            pickMediaLauncher.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE) // Chỉ chọn ảnh
                    .build());

            // Sử dụng GetContent (dự phòng)
            // getContentLauncher.launch("image/*");
        });
    }
    // --- Hàm MỚI để xử lý ảnh từ URI ---
    private void processImageFromUri(Uri imageUri) {
        try {
            Log.d(TAG, "Processing image from URI: " + imageUri);
            // Tạo InputImage từ URI
            InputImage image = InputImage.fromFilePath(this, imageUri);

            // Hiện loading (đã được gọi trước đó khi mở picker, hoặc gọi lại ở đây nếu cần)
            showLoading(true);

            // Chạy nhận dạng (không có ImageProxy để đóng)
            runTextRecognition(image, null);

        } catch (IOException e) {
            Log.e(TAG, "Failed to create InputImage from URI.", e);
            Toast.makeText(this, "Failed to load image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            showLoading(false); // Tắt loading nếu lỗi
        }
    }
    private void checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindPreview(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error getting camera provider", e);
                Toast.makeText(this, "Error initializing camera.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressLint("UnsafeOptInUsageError") // Cần cho getRotationDegrees
    private void bindPreview(@NonNull ProcessCameraProvider cameraProvider) {
        // Đảm bảo không có gì đang bind trước đó
        cameraProvider.unbindAll();

        Preview preview = new Preview.Builder().build();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        preview.setSurfaceProvider(binding.cameraPreviewView.getSurfaceProvider());

        imageCapture = new ImageCapture.Builder()
                // .setTargetRotation(binding.cameraPreviewView.getDisplay().getRotation()) // Có thể cần nếu xoay ảnh
                .build();

        try {
            // Bind use cases to camera
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
            Log.d(TAG, "CameraX Usecases bound successfully");
        } catch (Exception e) {
            Log.e(TAG, "Use case binding failed", e);
            Toast.makeText(this, "Failed to bind camera use cases.", Toast.LENGTH_SHORT).show();
            finish();
        }
    }


    private void takePhoto() {
        if (imageCapture == null) {
            Log.w(TAG, "ImageCapture is null, cannot take photo.");
            return;
        }

        // Hiển thị ProgressBar
        showLoading(true);

        imageCapture.takePicture(
                ContextCompat.getMainExecutor(this), // Callback trên luồng chính
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                        Log.d(TAG, "Image captured successfully");
                        @SuppressLint("UnsafeOptInUsageError") // Cần cho getImage()
                        Image mediaImage = imageProxy.getImage();
                        if (mediaImage != null) {
                            // Tạo InputImage cho ML Kit, cần xử lý rotation
                            InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
                            runTextRecognition(image, imageProxy); // Truyền cả imageProxy để đóng sau
                        } else {
                            Log.e(TAG, "mediaImage is null");
                            imageProxy.close(); // Đóng proxy nếu không dùng được ảnh
                            showLoading(false);
                            Toast.makeText(OcrTranslateActivity.this, "Failed to capture image data.", Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "Photo capture failed: " + exception.getMessage(), exception);
                        showLoading(false);
                        Toast.makeText(OcrTranslateActivity.this, "Photo capture failed: " + exception.getLocalizedMessage(), Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    private void runTextRecognition(InputImage image, @Nullable ImageProxy imageProxyToClose) {
        Log.d(TAG, "Running ML Kit Text Recognition...");
        textRecognizer.process(image)
                .addOnSuccessListener(visionText -> { // Dùng lambda cho gọn
                    if (imageProxyToClose != null) { // Chỉ đóng nếu là ảnh từ camera
                        imageProxyToClose.close();
                        Log.d(TAG,"ImageProxy closed for camera image.");
                    } else {
                        Log.d(TAG,"Processing gallery image, no ImageProxy to close.");
                    }
                    showLoading(false); // Tắt loading khi thành công
                    String resultText = visionText.getText();
                    Log.d(TAG, "Text Recognition Success. Detected text length: " + resultText.length());
                    if (resultText.trim().isEmpty()) {
                        Toast.makeText(OcrTranslateActivity.this, "No text detected in the image.", Toast.LENGTH_LONG).show();
                        // Không trả về nếu không có text, chỉ hiển thị Toast
                        // finish(); // Không nên finish ở đây, người dùng có thể muốn thử lại
                    } else {
                        returnRecognizedText(resultText); // Gửi kết quả về MainActivity
                    }
                })
                .addOnFailureListener(e -> { // Dùng lambda
                    if (imageProxyToClose != null) { // Chỉ đóng nếu là ảnh từ camera
                        imageProxyToClose.close();
                        Log.d(TAG,"ImageProxy closed on failure for camera image.");
                    } else {
                        Log.d(TAG,"Processing gallery image failed, no ImageProxy to close.");
                    }
                    showLoading(false); // Tắt loading khi lỗi
                    Log.e(TAG, "Text recognition failed", e);
                    Toast.makeText(OcrTranslateActivity.this, "Text recognition failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    // finish(); // Không nên finish ở đây, người dùng có thể muốn thử lại
                });
    }

    private void returnRecognizedText(String text) {
        Intent resultIntent = new Intent();
        resultIntent.putExtra(MainActivity.EXTRA_OCR_TEXT_RESULT, text); // Sử dụng key đã định nghĩa
        setResult(AppCompatActivity.RESULT_OK, resultIntent);
        finish(); // Đóng Activity và quay lại MainActivity
    }

    // --- Cập nhật showLoading để quản lý cả nút gallery ---
    private void showLoading(boolean isLoading) {
        binding.ocrProgressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        // Luôn bật/tắt cả hai nút cùng lúc khi loading/xong
        binding.btnCaptureOcr.setEnabled(!isLoading);
        binding.btnOpenGallery.setEnabled(!isLoading);
        Log.d(TAG,"Setting loading state: " + isLoading);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown(); // Rất quan trọng: đóng executor
        }
        if (textRecognizer != null) {
            // textRecognizer.close(); // ML Kit thường tự quản lý lifecycle, nhưng kiểm tra tài liệu nếu cần
        }
        Log.d(TAG, "OcrTranslateActivity destroyed");
    }
}