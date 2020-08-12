package se.lth.math.marslogger;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.annotation.NonNull;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

public class Camera2Proxy {

    private static final String TAG = "Camera2Proxy";

    private Activity mActivity;

    private int mCameraId = CameraCharacteristics.LENS_FACING_BACK;
    private String mCameraIdStr = "";
    private Size mPreviewSize;
    private Size mVideoSize;
    private CameraManager mCameraManager;
    private CameraCharacteristics mCameraCharacteristics;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private Rect sensorArraySize;

    private CaptureRequest mPreviewRequest;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private ImageReader mImageReader;
    private Surface mPreviewSurface;
    private SurfaceTexture mPreviewSurfaceTexture = null;
    private OrientationEventListener mOrientationEventListener;

    private int mDisplayRotate = 0;
    private int mDeviceOrientation = 0;
    private int mZoom = 1;

    private boolean mOIS = false;
    private boolean mDIS = false;

    private BufferedWriter mFrameMetadataWriter = null;

    // https://stackoverflow.com/questions/3786825/volatile-boolean-vs-atomicboolean
    private volatile boolean mRecordingMetadata = false;

    private FocalLengthHelper mFocalLengthHelper = new FocalLengthHelper();

    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "onOpened");
            mCameraDevice = camera;
            initPreviewRequest();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.d(TAG, "onDisconnected");
            releaseCamera();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "Camera Open failed, error: " + error);
            releaseCamera();
        }
    };

    public void startRecordingCaptureResult(String captureResultFile) {
        try {
            mFrameMetadataWriter = new BufferedWriter(
                    new FileWriter(captureResultFile, false));
            String header = "Timestamp[nanosec],fx[px],fy[px],Frame No.," +
                    "Exposure time[nanosec],Sensor frame duration[nanosec]," +
                    "Frame readout time[nanosec]," +
                    "ISO,Focal length,Focus distance";

            mFrameMetadataWriter.write(header + "\n");
            mRecordingMetadata = true;
        } catch (IOException err) {
            System.err.println("IOException in opening frameMetadataWriter at "
                    + captureResultFile + ":" + err.getMessage());
        }
    }

    public void stopRecordingCaptureResult() {
        if (mRecordingMetadata) {
            mRecordingMetadata = false;
            try {
                mFrameMetadataWriter.flush();
                mFrameMetadataWriter.close();
            } catch (IOException err) {
                System.err.println("IOException in closing frameMetadataWriter: " +
                        err.getMessage());
            }
            mFrameMetadataWriter = null;
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    public Camera2Proxy(Activity activity) {
        mActivity = activity;
        mCameraManager = (CameraManager) mActivity.getSystemService(Context.CAMERA_SERVICE);
        mOrientationEventListener = new OrientationEventListener(mActivity) {
            @Override
            public void onOrientationChanged(int orientation) {
                mDeviceOrientation = orientation;
            }
        };
    }

    public Size configureCamera(int width, int height) {
        try {
            mCameraIdStr = CameraUtils.getRearCameraId(mCameraManager);
            mCameraCharacteristics = mCameraManager.getCameraCharacteristics(mCameraIdStr);

            sensorArraySize = mCameraCharacteristics.get(
                    CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

            StreamConfigurationMap map = mCameraCharacteristics.get(CameraCharacteristics
                    .SCALER_STREAM_CONFIGURATION_MAP);
            mVideoSize = CameraUtils.chooseVideoSize(
                    map.getOutputSizes(MediaRecorder.class), width, height, width);

            mFocalLengthHelper.setLensParams(mCameraCharacteristics);
            mFocalLengthHelper.setmImageSize(mVideoSize);


            mPreviewSize = CameraUtils.chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                    width, height, mVideoSize);
            Log.d(TAG, "Video size " + mVideoSize.toString() +
                    " preview size " + mPreviewSize.toString());

            int hw_level = mCameraCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            Log.d(TAG, "HW support: " + hw_level);
            int [] camCap = mCameraCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            Log.d(TAG, "Camera Capabilites: " + Arrays.toString(camCap));
            int calibQuality = mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION);
            Log.d(TAG, "Calibration Quality: " + calibQuality);
            if (Build.VERSION.SDK_INT >= 28) {
                int poseRef = mCameraCharacteristics.get(CameraCharacteristics.LENS_POSE_REFERENCE);
                Log.d(TAG, "Camera Pose Ref: " + poseRef);
                float[] poseT = mCameraCharacteristics.get(CameraCharacteristics.LENS_POSE_TRANSLATION);
                Log.d(TAG, "Camera Pose T: " + Arrays.toString(poseT));
                float[] poseR = mCameraCharacteristics.get(CameraCharacteristics.LENS_POSE_ROTATION);
                Log.d(TAG, "Camera Pose R: " + Arrays.toString(poseR));
            }


        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        return mPreviewSize;
    }

    @SuppressLint("MissingPermission")
    public void openCamera(int width, int height) {
        Log.v(TAG, "openCamera");
        startBackgroundThread();
        mOrientationEventListener.enable();
        if (mCameraIdStr.isEmpty()) {
            configureCamera(width, height);
        }
        try {
            mCameraManager.openCamera(mCameraIdStr, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void releaseCamera() {
        Log.v(TAG, "releaseCamera");
        if (null != mCaptureSession) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
        mOrientationEventListener.disable();
        mPreviewSurfaceTexture = null;
        mCameraIdStr = "";
        stopRecordingCaptureResult();
        stopBackgroundThread();
    }

    public void setImageAvailableListener(ImageReader.OnImageAvailableListener
                                                  onImageAvailableListener) {
        if (mImageReader == null) {
            Log.w(TAG, "setImageAvailableListener: mImageReader is null");
            return;
        }
        mImageReader.setOnImageAvailableListener(onImageAvailableListener, null);
    }

    public void setPreviewSurface(SurfaceHolder holder) {
        mPreviewSurface = holder.getSurface();
    }

    public void setPreviewSurfaceTexture(SurfaceTexture surfaceTexture) {
        mPreviewSurfaceTexture = surfaceTexture;
    }


    private class NumExpoIso {
        public Long mNumber;
        public Long mExposureNanos;
        public Integer mIso;

        public NumExpoIso(Long number, Long expoNanos, Integer iso) {
            mNumber = number;
            mExposureNanos = expoNanos;
            mIso = iso;
        }
    }

    private final int kMaxExpoSamples = 10;
    private ArrayList<NumExpoIso> expoStats = new ArrayList<>(kMaxExpoSamples);

    private void setExposureAndIso() {
        Long exposureNanos = CameraCaptureActivity.mDesiredExposureTime;
        Long desiredIsoL = 30L * 30000000L / exposureNanos;
        Integer desiredIso = desiredIsoL.intValue();
        if (!expoStats.isEmpty()) {
            int index = expoStats.size() / 2;
            Long actualExpo = expoStats.get(index).mExposureNanos;
            Integer actualIso = expoStats.get(index).mIso;
            if (actualExpo <= exposureNanos) {
                exposureNanos = actualExpo;
                desiredIso = actualIso;
            } else {
                desiredIsoL = actualIso * actualExpo / exposureNanos;
                desiredIso = desiredIsoL.intValue();
            }
        }

        // fix exposure
        mPreviewRequestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
        Range<Long> exposureTimeRange = mCameraCharacteristics.get(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (exposureTimeRange != null) {
            Log.d(TAG, "exposure time range " + exposureTimeRange.toString());
        }

        mPreviewRequestBuilder.set(
                CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNanos);
        Log.d(TAG, "Exposure time set to " + exposureNanos);

        // fix ISO
        Range<Integer> isoRange = mCameraCharacteristics.get(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (isoRange != null) {
            Log.d(TAG, "ISO range " + isoRange.toString());
        }

        mPreviewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, desiredIso);
        Log.d(TAG, "ISO set to " + desiredIso);
    }

    private void initPreviewRequest() {
        try {
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

            // Set control elements, we want auto white balance
            mPreviewRequestBuilder.set(
                    CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            mPreviewRequestBuilder.set(
                    CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);

            // fix focus distance
            mPreviewRequestBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            Float minFocusDistance = mCameraCharacteristics.get(
                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            if (minFocusDistance == null)
                minFocusDistance = 5.0f;
            mPreviewRequestBuilder.set(
                    CaptureRequest.LENS_FOCUS_DISTANCE, minFocusDistance);
            Log.d(TAG, "Focus distance set to its min value:" + minFocusDistance);

            //Disable OIS
            int[] ois_modes = mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            Log.d(TAG, "OIS modes:" + Arrays.toString(ois_modes));
            for (int mode : ois_modes) {
                if (mode == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF) {
                    // OFF is a valid config, use it!
                    mPreviewRequestBuilder.set(
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF);
                    mOIS = false;
                    break;
                } else {
                    // Only ON is a valid mode, nothing to be done.
                    mOIS = true;
                }
            }

            //Disable DIS
            int[] dis_modes = mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            Log.d(TAG, "DIS modes:" + Arrays.toString(dis_modes));
            for (int mode : dis_modes) {
                if (mode == CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF) {
                    // OFF is a valid config, use it!
                    mPreviewRequestBuilder.set(
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
                    mDIS = false;
                    break;
                } else {
                    // Only ON is a valid mode, nothing to be done.
                    mDIS = true;
                }
            }

            if (mPreviewSurfaceTexture != null && mPreviewSurface == null) { // use texture view
                mPreviewSurfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(),
                        mPreviewSize.getHeight());
                mPreviewSurface = new Surface(mPreviewSurfaceTexture);
            }
            mPreviewRequestBuilder.addTarget(mPreviewSurface);
            mCameraDevice.createCaptureSession(Arrays.asList(mPreviewSurface),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            mCaptureSession = session;
                            mPreviewRequest = mPreviewRequestBuilder.build();
                            startPreview();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "ConfigureFailed. session: mCaptureSession");
                        }
                    }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    private CameraCaptureSession.CaptureCallback mSessionCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(CameraCaptureSession session,
                                               CaptureRequest request,
                                               TotalCaptureResult result) {
                    Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    Long number = result.getFrameNumber();
                    Long exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);

                    Long frmDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION);
                    Long frmReadoutNs = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
                    Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                    if (expoStats.size() > kMaxExpoSamples) {
                        expoStats.subList(0, kMaxExpoSamples / 2).clear();
                    }
                    expoStats.add(new NumExpoIso(number, exposureTimeNs, iso));

                    Float fl = result.get(CaptureResult.LENS_FOCAL_LENGTH);

                    Float fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE);

                    Rect rect = result.get(CaptureResult.SCALER_CROP_REGION);
                    mFocalLengthHelper.setmFocalLength(fl);
                    mFocalLengthHelper.setmFocusDistance(fd);
                    mFocalLengthHelper.setmCropRegion(rect);
                    SizeF sz_focal_length = mFocalLengthHelper.getFocalLengthPixel();
                    String delimiter = ",";
                    StringBuilder sb = new StringBuilder();
                    sb.append(timestamp);
                    sb.append(delimiter + sz_focal_length.getWidth());
                    sb.append(delimiter + sz_focal_length.getHeight());
                    sb.append(delimiter + number);
                    sb.append(delimiter + exposureTimeNs);
                    sb.append(delimiter + frmDurationNs);
                    sb.append(delimiter + frmReadoutNs);
                    sb.append(delimiter + iso);
                    sb.append(delimiter + fl);
                    sb.append(delimiter + fd);
                    String frame_info = sb.toString();
                    if (mRecordingMetadata) {
                        try {
                            mFrameMetadataWriter.write(frame_info + "\n");
                        } catch (IOException err) {
                            System.err.println("Error writing captureResult: " + err.getMessage());
                        }
                    }
                    ((CameraCaptureActivity) mActivity).updateCaptureResultPanel(
                            sz_focal_length.getWidth(), exposureTimeNs, mOIS, mDIS);
                }

                @Override
                public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request,
                                                CaptureResult partialResult) {
//                    Log.d(TAG, "mSessionCaptureCallback,  onCaptureProgressed");
                }
            };


    public void startPreview() {
        Log.v(TAG, "startPreview");
        if (mCaptureSession == null || mPreviewRequestBuilder == null) {
            Log.w(TAG, "startPreview: mCaptureSession or mPreviewRequestBuilder is null");
            return;
        }
        try {
            mCaptureSession.setRepeatingRequest(
                    mPreviewRequest, mSessionCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void stopPreview() {
        Log.v(TAG, "stopPreview");
        if (mCaptureSession == null || mPreviewRequestBuilder == null) {
            Log.w(TAG, "stopPreview: mCaptureSession or mPreviewRequestBuilder is null");
            return;
        }
        try {
            mCaptureSession.stopRepeating();
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public Map<String,String> getCapabilitiesString() {
        Log.v(TAG, "getCapabilities");
        Map<String, String> capMap = new TreeMap<>();

        int [] camCap = mCameraCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        capMap.put("REQUEST_AVAILABLE_CAPABILITIES", Arrays.toString(camCap));

        int hwLevel = mCameraCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        capMap.put("INFO_SUPPORTED_HARDWARE_LEVEL", Integer.toString(hwLevel));

        int[] oisModes = mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        capMap.put("LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION", Arrays.toString(oisModes));

        int[] stabilizationModes = mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        capMap.put("CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES", Arrays.toString(stabilizationModes));

        int calibQuality = mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION);
        capMap.put("LENS_INFO_FOCUS_DISTANCE_CALIBRATION", Integer.toString(calibQuality));

        if (Build.VERSION.SDK_INT >= 23) {
            float[] intrinsicC = mCameraCharacteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
            capMap.put("LENS_INTRINSIC_CALIBRATION", Arrays.toString(intrinsicC));

            float[] RadialD = mCameraCharacteristics.get(CameraCharacteristics.LENS_RADIAL_DISTORTION);
            capMap.put("LENS_RADIAL_DISTORTION", Arrays.toString(RadialD));
        }

        if (Build.VERSION.SDK_INT >= 28) {
            int[] oisDataModes = mCameraCharacteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES);
            capMap.put("STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES", Arrays.toString(oisDataModes));

            int poseRef = mCameraCharacteristics.get(CameraCharacteristics.LENS_POSE_REFERENCE);
            capMap.put("LENS_POSE_REFERENCE", Integer.toString(poseRef));

            float [] poseT = mCameraCharacteristics.get(CameraCharacteristics.LENS_POSE_TRANSLATION);
            capMap.put("LENS_POSE_TRANSLATION", Arrays.toString(poseT));

            float [] poseR = mCameraCharacteristics.get(CameraCharacteristics.LENS_POSE_ROTATION);
            capMap.put("LENS_POSE_ROTATION", Arrays.toString(poseR));
        }


        return capMap;
    }

    void changeManualFocusPoint(float eventX, float eventY, int viewWidth, int viewHeight) {
        final int y = (int) ((eventX / (float) viewWidth) * (float) sensorArraySize.height());
        final int x = (int) ((eventY / (float) viewHeight) * (float) sensorArraySize.width());
        final int halfTouchWidth = 400;
        final int halfTouchHeight = 400;
        MeteringRectangle focusAreaTouch = new MeteringRectangle(Math.max(x - halfTouchWidth, 0),
                Math.max(y - halfTouchHeight, 0),
                halfTouchWidth * 2,
                halfTouchHeight * 2,
                MeteringRectangle.METERING_WEIGHT_MAX - 1);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS,
                new MeteringRectangle[]{focusAreaTouch});
        try {
            mCaptureSession.setRepeatingRequest(
                    mPreviewRequestBuilder.build(), null, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        setExposureAndIso();

        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_MODE,
                CameraMetadata.CONTROL_MODE_AUTO);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_AUTO);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_START);

        try {
            mCaptureSession.setRepeatingRequest(
                    mPreviewRequestBuilder.build(),
                    mSessionCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void startBackgroundThread() {
        if (mBackgroundThread == null || mBackgroundHandler == null) {
            Log.v(TAG, "startBackgroundThread");
            mBackgroundThread = new HandlerThread("CameraBackground");
            mBackgroundThread.start();
            mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        }
    }

    private void stopBackgroundThread() {
        Log.v(TAG, "stopBackgroundThread");
        try {
            if (mBackgroundThread != null) {
                mBackgroundThread.quitSafely();
                mBackgroundThread.join();
            }
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
