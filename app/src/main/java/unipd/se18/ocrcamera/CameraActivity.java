package unipd.se18.ocrcamera;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * The Activity useful for making photos
 */
public class CameraActivity extends AppCompatActivity {

    // Final shared variables
    /**
     * TAG used for logs
     */
    private static final String TAG = "CameraActivity";

    /**
     * Code for the permission requested
     */
    private final int MY_CAMERA_REQUEST_CODE = 200;

    /**
     * SparseIntArray used for the conversion from screen rotation to Bitmap orientation
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    // Shared variables
    /**
     * The CameraDevice used to interact with the physical camera.
     */
    private CameraDevice mCameraDevice;

    /**
     * CaptureRequest.Builder used for the camera preview.
     */
    private CaptureRequest.Builder mCaptureRequestBuilder;

    /**
     * CameraCaptureSession used for the camera preview;
     */
    private CameraCaptureSession mCameraCaptureSession;

    /**
     * Size used for the camera preview
     */
    private Size mPreviewSize;

    /**
     * ImageReader used for capturing images.
     */
    private ImageReader mImageReader;

    /**
     * Thread used for running tasks in background
     */
    private HandlerThread mBackgroundHandlerThread;

    /**
     * Handler associated to the background Thread
     */
    private Handler mBackgroundHandler;

    private TextureView mCameraTextureView;
    private String filePath;


    /**
     * Callback of the camera states
     */
    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            //This is called when the camera is open
            Log.e(TAG, "onOpened");
            mCameraDevice = camera;
            createCameraPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            mCameraDevice = null;
        }
    };


    /**
     * SurfaceTextureListener used for the preview
     */
    private TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            //Open your camera
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // Transform you image captured size according to the surface width and height
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        // Initializing of the UI components
        mCameraTextureView = findViewById(R.id.camera_view);
        assert mCameraTextureView != null;
        mCameraTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        Button mButtonTakePhoto = findViewById(R.id.take_photo_button);
        mButtonTakePhoto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                takePhoto(mCameraDevice);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.e(TAG, "onResume");
        startBackgroundThread();
        if (mCameraTextureView.isAvailable()) {
            openCamera();
        } else {
            mCameraTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        Log.e(TAG, "onPause");
        //closeCamera();
        stopBackgroundThread();
        super.onPause();
    }


    protected void startBackgroundThread() {
        mBackgroundHandlerThread = new HandlerThread("Camera Background");
        mBackgroundHandlerThread.start();
        mBackgroundHandler = new Handler(mBackgroundHandlerThread.getLooper());
    }

    //The method that stops the thread when the camera is closed
    protected void stopBackgroundThread() {
        mBackgroundHandlerThread.quitSafely();
        try {
            mBackgroundHandlerThread.join();
            mBackgroundHandlerThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    /**
     * Opens a connection with a camera if it is permitted, otherwise return.
     */
    private void openCamera() {
        String cameraId;
        //A system service manager for detecting, characterizing, and connecting to Camera devices.
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        Log.e(TAG, "is camera open");
        try {
            //Get the camera ID
            cameraId = manager.getCameraIdList()[0];
            //Get the characteristics of the camera
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            mPreviewSize  = map.getOutputSizes(SurfaceTexture.class)[0];
            // Add permission for camera and let user grant the permission
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(CameraActivity.this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, MY_CAMERA_REQUEST_CODE);
                return;
            }
            //Opens the camera
            manager.openCamera(cameraId, mStateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Controls the output of the permissions requests.
     *
     * @param requestCode  The code assigned to the request
     * @param permissions  The list of the permissions requested
     * @param grantResults The results of the requests
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[],
                                           @NonNull int[] grantResults) {
        if (requestCode == MY_CAMERA_REQUEST_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(CameraActivity.this, "Sorry!!!, you can't use this app without granting permission", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    /**
     * Creates the preview of the camera capture frames.
     */
    private void createCameraPreview() {
        try {
            //Creates a new surface identical at textureView
            SurfaceTexture texture = mCameraTextureView.getSurfaceTexture();
            assert texture != null;
            //Set the default size of the image buffers
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            Surface surface = new Surface(texture);
            //Create a CaptureRequest.Builder for new capture requests, initialized with template for a target use case.
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequestBuilder.addTarget(surface);
            //Create a new camera capture session by providing the target output set of Surfaces to the camera device.
            mCameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                /*
                    This method is called when the camera device has finished configuring itself,
                    and the session can start processing capture requests.
                    @param session The session returned by CameraDevice.createCaptureSession(SessionConfiguration).
                           This value must never be null
                 */
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    //The camera is already closed
                    if (null == mCameraDevice) {
                        return;
                    }
                    // When the session is ready, we start displaying the preview.
                    mCameraCaptureSession = cameraCaptureSession;
                    updatePreview();
                }

                /*
                    This method is called if the session cannot be configured as requested.
                    @param session The session returned by CameraDevice.createCaptureSession(SessionConfiguration).
                           This value must never be null
                 */
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(CameraActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Updates the preview of the camera frames.
     */
    private void updatePreview() {
        if (null == mCameraDevice) {
            Log.e(TAG, "updatePreview error, return");
        }
        mCaptureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        try {
            mCameraCaptureSession.setRepeatingRequest(mCaptureRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Takes a photo.
     */
    private void takePhoto(CameraDevice camera) {
        //Verify if the object camera is Null
        if (null == camera) {
            Log.e(TAG, "cameraDevice is null");
            return;
        }
        //A system service manager for detecting, characterizing, and connecting to the camera devices
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            //Get the properties from the camera device
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(mCameraDevice.getId());
            //Create a vector that contains the sizes of the image format jpeg
            Size[] jpegSizes;
            //Initialize the vector
            jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
            //Declaring the variables and reassigning the value of them
            int width = 600;
            int height = 450;
            if (jpegSizes != null && 0 < jpegSizes.length) {
                width = jpegSizes[0].getWidth();
                height = jpegSizes[0].getHeight();
            }
            //Create a new reader for images of the desired size and format
            mImageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2);
            List<Surface> outputSurfaces = new ArrayList<>(2);
            outputSurfaces.add(mImageReader.getSurface());
            outputSurfaces.add(new Surface(mCameraTextureView.getSurfaceTexture()));
            //A builder for capture requests
            final CaptureRequest.Builder captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            //Add a surface to the list of targets for this request
            captureBuilder.addTarget(mImageReader.getSurface());
            //Set a capture request field to a value
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            //Getting the orientation of the window and putting in the captureBuilder
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));
            //Callback interface for being notified that a new image is available.
            ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
                /*
                    Callback that is called when a new image is available from ImageReader
                    @param reader the ImageReader the callback is associated with.
                 */
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image;
                    //Trying to acquire the last image available and putting it on a vector to save it
                    image = reader.acquireLatestImage();
                    //Save the photo
                    try {
                        savePhoto(image);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }finally {
                        if (image != null) {
                            //Free up this frame for reuse
                            image.close();
                        }
                    }



                }
            };

            //Register a listener to be invoked when a new image becomes available from the ImageReader
            mImageReader.setOnImageAvailableListener(readerListener, mBackgroundHandler);

            //Callback object for tracking the progress of a CaptureRequest submitted to the camera device
            final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
                /*
                    This method is called when an image capture has fully completed and all the result metadata is available.
                    @param session the session returned by CameraDevice.createCaptureSession(SessionConfiguration).
                           This value must never be null.
                    @param request The request that was given to the CameraDevice. This value must never be null.
                    @param result The total output metadata from the capture, including the final capture parameters
                           and the state of the camera system during capture.
                 */
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    Toast.makeText(CameraActivity.this, "Saved:" + filePath, Toast.LENGTH_SHORT).show();
                    //Starts the activity for the OCR recognition
                }
            };

            //Create a new camera capture session by providing the target output set of Surfaces to the camera device.
            mCameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                /*
                    This method is called when the camera device has finished configuring itself,
                    and the session can start processing capture requests.
                    @param session The session returned by CameraDevice.createCaptureSession(SessionConfiguration).
                           This value must never be null
                 */
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    try {
                        //Submit a request for an image to be captured by the camera device.
                        session.capture(captureBuilder.build(), captureListener, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                /*
                    This method is called if the session cannot be configured as requested.
                    @param session The session returned by CameraDevice.createCaptureSession(SessionConfiguration).
                           This value must never be null
                 */
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    /**
     * Saves a photo previously taken.
     */
    private void savePhoto(Image image) throws IOException {
        FileOutputStream output = null;
        byte[] bytes;
        int currentTime = Calendar.getInstance().getTime().hashCode();
        final File file = new File(Environment.getExternalStorageDirectory() + "/" + Environment.DIRECTORY_PICTURES + "/camera2/" + currentTime + ".jpg");
        filePath = file.getPath();
        //Create the folder
        file.mkdirs();
        //Verify that a file with the same name exists. If it exist it deletes and make a new one
        try {
            if (file.exists()) {
                file.delete();
            }
            file.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            bytes = new byte[buffer.capacity()];
            buffer.get(bytes);
            output = new FileOutputStream(file);
            output.write(bytes);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (null != output) {
                output.flush();
                output.close();
            }
        }

    }

    /**
     * Closes resources related to the camera.
     */
    private void closeCamera() {

    }
}

