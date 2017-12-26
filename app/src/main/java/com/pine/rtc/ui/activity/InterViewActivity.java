package com.pine.rtc.ui.activity;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.RequiresApi;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.pine.rtc.R;
import com.pine.rtc.controller.MediaProjectionScreenShot;
import com.pine.rtc.controller.MediaRecordController;
import com.pine.rtc.org.component.AppRTCAudioManager;
import com.pine.rtc.org.component.AppRTCClient;
import com.pine.rtc.org.component.DirectRTCClient;
import com.pine.rtc.org.component.PeerConnectionClient;
import com.pine.rtc.org.component.UnhandledExceptionHandler;
import com.pine.rtc.org.component.WebSocketRTCClient;
import com.pine.rtc.org.lib.VideoFileRenderer;
import com.pine.rtc.ui.fragment.InterViewFragment;
import com.pine.rtc.util.DialogUtil;

import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.RendererCommon;
import org.webrtc.SessionDescription;
import org.webrtc.StatsReport;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoRenderer;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

public class InterViewActivity extends Activity implements AppRTCClient.SignalingEvents,
        PeerConnectionClient.PeerConnectionEvents, InterViewFragment.OnCallEvents {
    public static final String EXTRA_ROOMID = "org.appspot.apprtc.ROOMID";
    private static final String TAG = InterViewActivity.class.getSimpleName();
    // List of mandatory application permissions.
    private static final String[] MANDATORY_PERMISSIONS = {"android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.RECORD_AUDIO", "android.permission.INTERNET"};
    private static final int MEDIA_PROJECTION_REQUEST_CODE = 1;
    // Peer connection statistics callback period in ms.
    private static final int STAT_CALLBACK_PERIOD = 1000;
    private final int MSG_TIME_TICK = 1;
    private final ProxyRenderer mRemoteProxyRender = new ProxyRenderer();
    private final ProxyRenderer mLocalProxyRender = new ProxyRenderer();
    private final List<VideoRenderer.Callbacks> mRemoteRenders =
            new ArrayList<VideoRenderer.Callbacks>();
    //    MediaRecorderController mMediaRecorderController;
    MediaRecordController mMediaRecordController;
    private PeerConnectionClient mPeerConnectionClient = null;
    private AppRTCClient mAppRtcClient;
    private AppRTCClient.SignalingParameters mSignalingParameters;
    private AppRTCAudioManager mAudioManager = null;
    private EglBase mRootEglBase;
    private SurfaceViewRenderer pipRendererView;
    private SurfaceViewRenderer fullscreenRendererView;
    private VideoFileRenderer mVideoFileRenderer;
    private Toast mLogToast;
    private boolean mActivityRunning;
    private AppRTCClient.RoomConnectionParameters mRoomConnectionParameters;
    private PeerConnectionClient.PeerConnectionParameters mPeerConnectionParameters;
    private boolean mIceConnected;
    private boolean mIsError;
    private boolean mCallControlFragmentVisible = true;
    private long mCallStartedTimeMs = 0;
    private boolean mIsFrontCamera = true;
    private boolean mMicEnabled = true;
    private boolean mSpeakerOn = true;
    private boolean mScreenCaptureEnabled = false;
    // True if local view is in the fullscreen renderer.
    private boolean mIsSwappedFeeds;
    // Controls
    private InterViewFragment mInterViewFragment;
    private String mRoomId;
    private boolean mIsRecording;
    private MediaProjection mMediaProjection;
    private MediaProjectionScreenShot mMediaProjectionScreenShot;
    private String mRemoteVideoFilePath;

    private TextView recordTimeText;
    private Long mRecordStartTime;
    private DateFormat mRecordTimeFormat;

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_TIME_TICK:
                    long time = (System.currentTimeMillis() - mRecordStartTime) / 1000L;
                    long minute = time % 3600 / 60;
                    long second = time % 60;
                    StringBuilder stringBuilder = new StringBuilder();
                    stringBuilder.append(minute < 10 ? ("0" + minute) : minute).append(":")
                            .append(second < 10 ? ("0" + second) : second);
                    mInterViewFragment.onRecorderTimeTick(stringBuilder.toString());
                    recordTimeText.setText(mRecordTimeFormat.format(new Date()));
                    sendEmptyMessageDelayed(MSG_TIME_TICK, 500);
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Thread.setDefaultUncaughtExceptionHandler(new UnhandledExceptionHandler(this));
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setContentView(R.layout.activity_inter_view);

        mIceConnected = false;
        mSignalingParameters = null;

        // Create UI controls.
        pipRendererView = (SurfaceViewRenderer) findViewById(R.id.pip_video_view);
        fullscreenRendererView = (SurfaceViewRenderer) findViewById(R.id.fullscreen_video_view);
        mInterViewFragment = new InterViewFragment();

        recordTimeText = (TextView) findViewById(R.id.record_time_tv);
        mRecordTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        // Show/hide call control fragment on view click.
        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleCallControlFragmentVisibility();
            }
        };

        // Swap feeds on pip view click.
        pipRendererView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setSwappedFeeds(!mIsSwappedFeeds);
            }
        });

        fullscreenRendererView.setOnClickListener(listener);
        mRemoteRenders.add(mRemoteProxyRender);

        final Intent intent = getIntent();

        // Create video renderers.
        mRootEglBase = EglBase.create();
        pipRendererView.init(mRootEglBase.getEglBaseContext(), null);
        pipRendererView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);

        fullscreenRendererView.init(mRootEglBase.getEglBaseContext(), null);
        fullscreenRendererView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);

        pipRendererView.setZOrderMediaOverlay(true);
        pipRendererView.setEnableHardwareScaler(true /* enabled */);
        fullscreenRendererView.setEnableHardwareScaler(true /* enabled */);
        // Start with local feed in fullscreen and swap it to the pip when the call is connected.
        setSwappedFeeds(true /* mIsSwappedFeeds */);

        // Check for mandatory permissions.
        for (String permission : MANDATORY_PERMISSIONS) {
            if (checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                logAndToast("Permission " + permission + " is not granted");
                setResult(RESULT_CANCELED);
                finish();
                return;
            }
        }

        Uri roomUri = intent.getData();
        if (roomUri == null) {
            logAndToast(getString(R.string.missing_url));
            Log.e(TAG, "Didn't get any URL in intent!");
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        // Get Intent parameters.
        mRoomId = intent.getStringExtra(EXTRA_ROOMID);
        Log.d(TAG, "Room ID: " + mRoomId);
        if (mRoomId == null || mRoomId.length() == 0) {
            logAndToast(getString(R.string.missing_url));
            Log.e(TAG, "Incorrect room ID in intent!");
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        DisplayMetrics displayMetrics = getDisplayMetrics();
        int videoWidth = displayMetrics.widthPixels;
        int videoHeight = displayMetrics.heightPixels;
        mPeerConnectionParameters =
                new PeerConnectionClient.PeerConnectionParameters(true, false,
                        false, videoWidth, videoHeight, 15, 1700, "VP8", true, false, 32,
                        "OPUS", false, false, false, false, false, false, false, false, null);

        // Create connection client. Use DirectRTCClient if room name is an IP otherwise use the
        // standard WebSocketRTCClient.
        if (!DirectRTCClient.IP_PATTERN.matcher(mRoomId).matches()) {
            mAppRtcClient = new WebSocketRTCClient(this);
        } else {
            Log.i(TAG, "Using DirectRTCClient because room name looks like an IP.");
            mAppRtcClient = new DirectRTCClient(this);
        }
        mRoomConnectionParameters =
                new AppRTCClient.RoomConnectionParameters(getString(R.string.pref_room_server_url_default),
                        roomUri.toString(), mRoomId, false, null);

        // Send intent arguments to fragments.
        mInterViewFragment.setArguments(intent.getExtras());
        // Activate call and HUD fragments and start the call.
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.add(R.id.call_fragment_container, mInterViewFragment);
        ft.commit();

        mPeerConnectionClient = PeerConnectionClient.getInstance();
        mPeerConnectionClient.createPeerConnectionFactory(
                getApplicationContext(), mPeerConnectionParameters, InterViewActivity.this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mMediaRecordController = MediaRecordController.getInstance();
            mMediaRecordController.onCreate();
        }
        startCall();
    }

    @TargetApi(17)
    private DisplayMetrics getDisplayMetrics() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        WindowManager windowManager =
                (WindowManager) getApplication().getSystemService(Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getRealMetrics(displayMetrics);
        return displayMetrics;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                if (resultCode == RESULT_OK && data != null) {
                    if (mMediaProjection == null) {
                        mMediaProjection = ((MediaProjectionManager) getSystemService(
                                Context.MEDIA_PROJECTION_SERVICE)).getMediaProjection(resultCode, data);
                    }
                    setupMediaProjectionScreenShot();
                    setupMediaRecordController();
                    mInterViewFragment.enableSupportButtons(true, true);
                    return;
                }
            }
            mInterViewFragment.enableSupportButtons(false, false);
            return;
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void setupMediaProjectionScreenShot() {
        if (mMediaProjectionScreenShot == null && mMediaProjection != null) {
            mMediaProjectionScreenShot = MediaProjectionScreenShot.getInstance().setupScreenShot(
                    new MediaProjectionScreenShot.OnShotListener() {
                        @Override
                        public void onFinish(Bitmap bitmap) {
                            DialogUtil.popShotScreenDialog(InterViewActivity.this, bitmap);
                        }

                        @Override
                        public void onSaveFinish(String filePath) {
                            String msg = "截图已经保存在 " + filePath;
                            Toast.makeText(InterViewActivity.this, msg, Toast.LENGTH_SHORT).show();
                        }
                    }, mMediaProjection);
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void setupMediaRecordController() {
        if (mMediaRecordController != null && mMediaProjection != null) {
            File file = new File(Environment.getExternalStorageDirectory().getPath() + "/rtc");
            if (!file.exists()) {
                file.mkdir();
            }
            mRemoteVideoFilePath = Environment.getExternalStorageDirectory().getPath() + "/rtc/room.mp4";
            mMediaRecordController.setupController(new MediaRecordController.OnRecordListener() {
                @Override
                public void onFinish(String filePath) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mInterViewFragment.onRecorderChange(false);
                            logAndToast("结束录制，文件已保存在" + mRemoteVideoFilePath);
                        }
                    });
                }
            }, mMediaProjection);
        }
    }

    private boolean useCamera2() {
        return Camera2Enumerator.isSupported(this);
    }

    private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        // First, try to find front facing camera
        Logging.d(TAG, "Looking for front facing cameras.");
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating front facing camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        // Front facing camera not found, try something else
        Logging.d(TAG, "Looking for other cameras.");
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating other camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }
        return null;
    }

    // Activity interfaces
    @Override
    public void onStop() {
        super.onStop();
        mActivityRunning = false;
        // Don't stop the video when using screencapture to allow user to show other apps to the remote
        // end.
        if (mPeerConnectionClient != null && !mScreenCaptureEnabled) {
            mPeerConnectionClient.stopVideoSource();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        mActivityRunning = true;
        // Video is not paused for screencapture. See onPause.
        if (mPeerConnectionClient != null && !mScreenCaptureEnabled) {
            mPeerConnectionClient.startVideoSource();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mInterViewFragment != null) {
            mInterViewFragment.onSpeakerChange(mSpeakerOn);
            mInterViewFragment.onMuteChange(mMicEnabled);
        }
    }

    @Override
    protected void onDestroy() {
        Thread.setDefaultUncaughtExceptionHandler(null);
        if (mLogToast != null) {
            mLogToast.cancel();
        }
        disconnect();
        mActivityRunning = false;
        mRootEglBase.release();
        if (mMediaProjectionScreenShot != null) {
            mMediaProjectionScreenShot.release(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (mMediaRecordController != null) {
                mMediaRecordController.release(true);
            }
        }
        mHandler.removeMessages(MSG_TIME_TICK);
        super.onDestroy();
    }

    private void startCall() {
        if (mAppRtcClient == null) {
            Log.e(TAG, "AppRTC client is not allocated for a call.");
            return;
        }
        mCallStartedTimeMs = System.currentTimeMillis();

        // Start room connection.
        logAndToast(getString(R.string.connecting_to, mRoomConnectionParameters.roomUrl));
        mAppRtcClient.connectToRoom(mRoomConnectionParameters);

        // Create and audio manager that will take care of audio routing,
        // audio modes, audio device enumeration etc.
        mAudioManager = AppRTCAudioManager.create(getApplicationContext());
        // Store existing audio settings and change audio mode to
        // MODE_IN_COMMUNICATION for best possible VoIP performance.
        Log.d(TAG, "Starting the audio manager...");
        mAudioManager.start(new AppRTCAudioManager.AudioManagerEvents() {
            // This method will be called each time the number of available audio
            // devices has changed.
            @Override
            public void onAudioDeviceChanged(
                    AppRTCAudioManager.AudioDevice audioDevice, Set<AppRTCAudioManager.AudioDevice> availableAudioDevices) {
                onAudioManagerDevicesChanged(audioDevice, availableAudioDevices);
            }
        });
    }

    // Should be called from UI thread
    private void callConnected() {
        final long delta = System.currentTimeMillis() - mCallStartedTimeMs;
        Log.i(TAG, "Call connected: delay=" + delta + "ms");
        if (mPeerConnectionClient == null || mIsError) {
            Log.w(TAG, "Call is connected in closed or error state");
            return;
        }
        // Enable statistics callback.
        mPeerConnectionClient.enableStatsEvents(true, STAT_CALLBACK_PERIOD);
        setSwappedFeeds(false /* mIsSwappedFeeds */);
        requestMediaProjection();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                onToggleSpeaker();
            }
        });
        /** Test Code (not correct) begin **/
//        if (MediaCodecVideoEncoderUtil.isDeviceSupportRecorder(MediaFormat.MIMETYPE_VIDEO_AVC)) {
//            File file = new File(Environment.getExternalStorageDirectory().getPath() + "/rtc");
//            if (!file.exists()) {
//                file.mkdir();
//            }
//            mRemoteVideoFilePath = Environment.getExternalStorageDirectory().getPath() + "/rtc/room.mp4";
//            if (mVideoFileRenderer == null) {
//                mVideoFileRenderer = new VideoFileRenderer(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
//                        mPeerConnectionClient, mRootEglBase.getEglBaseContext());
//            }
//            mVideoFileRenderer.setRecorderListener(new VideoFileRenderer.IRecorderListener() {
//                @Override
//                public void onRecorderStart() {
//                    mIsRecording = true;
//                    mInterViewFragment.onRecorderChange(true);
//                    logAndToast("开始录制");
//                }
//
//                @Override
//                public void onRecorderCompleting() {
//                    logAndToast("正在结束录制，请等待 ……");
//                }
//
//                @Override
//                public void onRecorderComplete() {
//                    mIsRecording = false;
//                    mInterViewFragment.onRecorderChange(false);
//                    logAndToast("结束录制，文件已保存在" + mRemoteVideoFilePath);
//                }
//
//                @Override
//                public void onRecorderError() {
//                    mIsRecording = false;
//                    mInterViewFragment.onRecorderChange(false);
//                    logAndToast("录制出错");
//                    File tmpFile = new File(mRemoteVideoFilePath);
//                    if (tmpFile.exists()) {
//                        tmpFile.delete();
//                    }
//                }
//            });
//        }
        /** Test Code (not correct) end **/
    }

    // This method is called when the audio manager reports audio device change,
    // e.g. from wired headset to speakerphone.
    private void onAudioManagerDevicesChanged(
            final AppRTCAudioManager.AudioDevice device, final Set<AppRTCAudioManager.AudioDevice> availableDevices) {
        Log.d(TAG, "onAudioManagerDevicesChanged: " + availableDevices + ", "
                + "selected: " + device);
        switch (device) {
            case SPEAKER_PHONE:
                mSpeakerOn = true;
                break;
            case EARPIECE:
            case WIRED_HEADSET:
            case BLUETOOTH:
            default:
                mSpeakerOn = false;
                break;
        }
        if (mInterViewFragment != null) {
            mInterViewFragment.onSpeakerChange(mSpeakerOn);
        }
    }

    // Disconnect from remote resources, dispose of local resources, and exit.
    private void disconnect() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mActivityRunning = false;
                mRemoteProxyRender.setTarget(null);
                mLocalProxyRender.setTarget(null);
                if (mAppRtcClient != null) {
                    mAppRtcClient.disconnectFromRoom();
                    mAppRtcClient = null;
                }
                if (mPeerConnectionClient != null) {
                    mPeerConnectionClient.close();
                    mPeerConnectionClient = null;
                }
                if (pipRendererView != null) {
                    pipRendererView.release();
                    pipRendererView = null;
                }
                if (mVideoFileRenderer != null) {
                    mVideoFileRenderer.release();
                    mVideoFileRenderer = null;
                }
                if (fullscreenRendererView != null) {
                    fullscreenRendererView.release();
                    fullscreenRendererView = null;
                }
                if (mAudioManager != null) {
                    mAudioManager.stop();
                    mAudioManager = null;
                }
                if (mIceConnected && !mIsError) {
                    setResult(RESULT_OK);
                } else {
                    setResult(RESULT_CANCELED);
                }
                finish();
            }
        });
    }

    private void disconnectWithErrorMessage(final String errorMessage) {
        if (!mActivityRunning) {
            Log.e(TAG, "Critical error: " + errorMessage);
            disconnect();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(getText(R.string.channel_error_title))
                    .setMessage(errorMessage)
                    .setCancelable(false)
                    .setNeutralButton(R.string.ok,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int id) {
                                    dialog.cancel();
                                    disconnect();
                                }
                            })
                    .create()
                    .show();
        }
    }

    private VideoCapturer createVideoCapturer() {
        VideoCapturer videoCapturer = null;
        if (useCamera2()) {
            Logging.d(TAG, "Creating capturer using camera2 API.");
            videoCapturer = createCameraCapturer(new Camera2Enumerator(this));
        } else {
            Logging.d(TAG, "Creating capturer using camera1 API.");
            videoCapturer = createCameraCapturer(new Camera1Enumerator(true));
        }
        if (videoCapturer == null) {
            reportError("Failed to open camera");
            return null;
        }
        return videoCapturer;
    }

    private void setSwappedFeeds(boolean isSwappedFeeds) {
        Logging.d(TAG, "setSwappedFeeds: " + isSwappedFeeds);
        this.mIsSwappedFeeds = isSwappedFeeds;
        mLocalProxyRender.setTarget(isSwappedFeeds ? fullscreenRendererView : pipRendererView);
        mRemoteProxyRender.setTarget(isSwappedFeeds ? pipRendererView : fullscreenRendererView);
        fullscreenRendererView.setMirror(isSwappedFeeds);
        pipRendererView.setMirror(!isSwappedFeeds);
    }

    // CallFragment.OnCallEvents interface implementation.
    @Override
    public void onCallHangUp() {
        disconnect();
    }

    @Override
    public boolean onCameraSwitch() {
        if (mPeerConnectionClient != null) {
            mIsFrontCamera = !mIsFrontCamera;
            mPeerConnectionClient.switchCamera();
        }
        return mIsFrontCamera;
    }

    @Override
    public void onScreenCapture() {
        if (mMediaProjectionScreenShot != null) {
            mMediaProjectionScreenShot.startScreenShot();
        }

        /** For EglRenderScreenShot(Another way of Screenshot) begin **/
//        SurfaceViewRenderer renderer = mRemoteProxyRender.getTarget();
//        if (renderer != null) {
//            EglRenderScreenShot.getInstance().setupScreenShot(renderer, new EglRenderScreenShot.OnShotListener() {
//                @Override
//                public void onScreenShot(Bitmap bitmap) {
//                    DialogUtil.popShotScreenDialog(InterViewActivity.this, bitmap);
//                }
//
//                @Override
//                public void onScreenShotSave(String filePath) {
//                    String msg = "截图已经保存在 " + filePath;
//                    Toast.makeText(InterViewActivity.this, msg, Toast.LENGTH_SHORT).show();
//                }
//            }, mHandler).screenShot();
//        } else {
//            Toast.makeText(InterViewActivity.this, "无法截屏", Toast.LENGTH_LONG).show();
//        }
        /** For EglRenderScreenShot(Another way of Screenshot) end **/
    }

    private boolean requestMediaProjection() {
        if (Build.VERSION.SDK_INT >= 21) {
            startActivityForResult(
                    ((MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE))
                            .createScreenCaptureIntent(), MEDIA_PROJECTION_REQUEST_CODE);
        } else {
            Toast.makeText(InterViewActivity.this, "版本过低，截屏录像功能无法实现", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    @Override
    public void onVideoRecord(final View recordButton) {
        /** Test Code (not correct) begin **/
//        if (mPeerConnectionClient.isRecorderPrepared() && mVideoFileRenderer != null) {
//            if (!mIsRecording) {
//                startRecorder(recordButton);
//            } else {
//                stopRecorder(recordButton);
//            }
//        }
        /** Test Code (not correct) end **/
        if (mPeerConnectionClient.isRecorderPrepared() && Build.VERSION.SDK_INT >= 21) {
            if (!mIsRecording) {
                startRecorder();
            } else {
                stopRecorder();
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void startRecorder() {
        if (mMediaRecordController != null) {
            logAndToast("开始录制");
            mIsRecording = true;
            mMediaRecordController.startRecord(mRemoteVideoFilePath);
            mInterViewFragment.onRecorderChange(true);
            mRecordStartTime = System.currentTimeMillis();
            mHandler.sendEmptyMessage(MSG_TIME_TICK);
            recordTimeText.setVisibility(View.VISIBLE);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void stopRecorder() {
        if (mMediaRecordController != null) {
            logAndToast("正在结束录制，请等待 ……");
            mIsRecording = false;
            mMediaRecordController.stopRecord();
            mInterViewFragment.onRecorderChange(false);
            mHandler.removeMessages(MSG_TIME_TICK);
            recordTimeText.setVisibility(View.GONE);
        }
    }

    /** Test Code (not correct) begin **/
//    private void startRecorder(final View recordButton) {
//        mIsRecording = true;
//        DisplayMetrics displayMetrics = getDisplayMetrics();
//        mVideoFileRenderer.startRecord(mRemoteVideoFilePath,
//                displayMetrics.widthPixels, displayMetrics.heightPixels);
//    }
//
//    private void stopRecorder(final View recordButton) {
//        mIsRecording = false;
//        mVideoFileRenderer.stopRecord();
//    }

    /**
     * Test Code (not correct) end
     **/

    @Override
    public boolean onToggleMic() {
        if (mPeerConnectionClient != null) {
            mMicEnabled = !mMicEnabled;
            mPeerConnectionClient.setAudioEnabled(mMicEnabled);
        }
        return mMicEnabled;
    }

    @Override
    public boolean onToggleSpeaker() {
        if (mAudioManager != null) {
            mSpeakerOn = !mSpeakerOn;
            mAudioManager.setAudioDeviceInternal(mSpeakerOn ?
                    AppRTCAudioManager.AudioDevice.SPEAKER_PHONE : AppRTCAudioManager.AudioDevice.EARPIECE);
        }
        return mSpeakerOn;
    }

    // Helper functions.
    private void toggleCallControlFragmentVisibility() {
        if (!mIceConnected || !mInterViewFragment.isAdded()) {
            return;
        }
        // Show/hide call control fragment
        mCallControlFragmentVisible = !mCallControlFragmentVisible;
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        if (mCallControlFragmentVisible) {
            ft.show(mInterViewFragment);
        } else {
            ft.hide(mInterViewFragment);
        }
        ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
        ft.commit();
    }

    // -----Implementation of AppRTCClient.AppRTCSignalingEvents ---------------
    // All callbacks are invoked from websocket signaling looper thread and
    // are routed to UI thread.
    private void onConnectedToRoomInternal(final AppRTCClient.SignalingParameters params) {
        final long delta = System.currentTimeMillis() - mCallStartedTimeMs;

        mSignalingParameters = params;
        logAndToast("Creating peer connection, delay=" + delta + "ms");
        VideoCapturer videoCapturer = null;
        if (mPeerConnectionParameters.videoCallEnabled) {
            videoCapturer = createVideoCapturer();
        }
        mPeerConnectionClient.createPeerConnection(mRootEglBase.getEglBaseContext(), mLocalProxyRender,
                mRemoteRenders, videoCapturer, mSignalingParameters);

        if (mSignalingParameters.initiator) {
            logAndToast("Creating OFFER...");
            // Create offer. Offer SDP will be sent to answering client in
            // PeerConnectionEvents.onLocalDescription event.
            mPeerConnectionClient.createOffer();
        } else {
            if (params.offerSdp != null) {
                mPeerConnectionClient.setRemoteDescription(params.offerSdp);
                logAndToast("Creating ANSWER...");
                // Create answer. Answer SDP will be sent to offering client in
                // PeerConnectionEvents.onLocalDescription event.
                mPeerConnectionClient.createAnswer();
            }
            if (params.iceCandidates != null) {
                // Add remote ICE candidates from room.
                for (IceCandidate iceCandidate : params.iceCandidates) {
                    mPeerConnectionClient.addRemoteIceCandidate(iceCandidate);
                }
            }
        }
    }

    @Override
    public void onConnectedToRoom(final AppRTCClient.SignalingParameters params) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                onConnectedToRoomInternal(params);
            }
        });
    }

    @Override
    public void onRemoteDescription(final SessionDescription sdp) {
        final long delta = System.currentTimeMillis() - mCallStartedTimeMs;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mPeerConnectionClient == null) {
                    Log.e(TAG, "Received remote SDP for non-initilized peer connection.");
                    return;
                }
                logAndToast("Received remote " + sdp.type + ", delay=" + delta + "ms");
                mPeerConnectionClient.setRemoteDescription(sdp);
                if (!mSignalingParameters.initiator) {
                    logAndToast("Creating ANSWER...");
                    // Create answer. Answer SDP will be sent to offering client in
                    // PeerConnectionEvents.onLocalDescription event.
                    mPeerConnectionClient.createAnswer();
                }
            }
        });
    }

    @Override
    public void onRemoteIceCandidate(final IceCandidate candidate) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mPeerConnectionClient == null) {
                    Log.e(TAG, "Received ICE candidate for a non-initialized peer connection.");
                    return;
                }
                mPeerConnectionClient.addRemoteIceCandidate(candidate);
            }
        });
    }

    @Override
    public void onRemoteIceCandidatesRemoved(final IceCandidate[] candidates) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mPeerConnectionClient == null) {
                    Log.e(TAG, "Received ICE candidate removals for a non-initialized peer connection.");
                    return;
                }
                mPeerConnectionClient.removeRemoteIceCandidates(candidates);
            }
        });
    }

    @Override
    public void onChannelClose() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                logAndToast("Remote end hung up; dropping PeerConnection");
                disconnect();
            }
        });
    }

    @Override
    public void onChannelError(final String description) {
        reportError(description);
    }

    // -----Implementation of PeerConnectionClient.PeerConnectionEvents.---------
    // Send local peer connection SDP and ICE candidates to remote party.
    // All callbacks are invoked from peer connection client looper thread and
    // are routed to UI thread.
    @Override
    public void onLocalDescription(final SessionDescription sdp) {
        final long delta = System.currentTimeMillis() - mCallStartedTimeMs;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mAppRtcClient != null) {
                    logAndToast("Sending " + sdp.type + ", delay=" + delta + "ms");
                    if (mSignalingParameters.initiator) {
                        mAppRtcClient.sendOfferSdp(sdp);
                    } else {
                        mAppRtcClient.sendAnswerSdp(sdp);
                    }
                }
                if (mPeerConnectionParameters.videoMaxBitrate > 0) {
                    Log.d(TAG, "Set video maximum bitrate: " + mPeerConnectionParameters.videoMaxBitrate);
                    mPeerConnectionClient.setVideoMaxBitrate(mPeerConnectionParameters.videoMaxBitrate);
                }
            }
        });
    }

    @Override
    public void onIceCandidate(final IceCandidate candidate) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mAppRtcClient != null) {
                    mAppRtcClient.sendLocalIceCandidate(candidate);
                }
            }
        });
    }

    @Override
    public void onIceCandidatesRemoved(final IceCandidate[] candidates) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mAppRtcClient != null) {
                    mAppRtcClient.sendLocalIceCandidateRemovals(candidates);
                }
            }
        });
    }

    @Override
    public void onIceConnected() {
        final long delta = System.currentTimeMillis() - mCallStartedTimeMs;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                logAndToast("ICE connected, delay=" + delta + "ms");
                mIceConnected = true;
                callConnected();
            }
        });
    }

    @Override
    public void onIceDisconnected() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                logAndToast("ICE disconnected");
                mIceConnected = false;
                disconnect();
            }
        });
    }

    @Override
    public void onPeerConnectionClosed() {
    }

    @Override
    public void onPeerConnectionStatsReady(final StatsReport[] reports) {
    }

    @Override
    public void onPeerConnectionError(final String description) {
        reportError(description);
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {

    }

    // Log |msg| and Toast about it.
    private void logAndToast(String msg) {
        Log.d(TAG, msg);
        if (mLogToast != null) {
            mLogToast.cancel();
        }
        mLogToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
        mLogToast.show();
    }

    private void reportError(final String description) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!mIsError) {
                    mIsError = true;
                    disconnectWithErrorMessage(description);
                }
            }
        });
    }

    private class ProxyRenderer implements VideoRenderer.Callbacks {
        private VideoRenderer.Callbacks target;

        synchronized public void renderFrame(VideoRenderer.I420Frame frame) {
            if (target == null) {
                Logging.d(TAG, "Dropping frame in proxy because target is null.");
                VideoRenderer.renderFrameDone(frame);
                return;
            }

            target.renderFrame(frame);
        }

        synchronized public SurfaceViewRenderer getTarget() {
            if (target instanceof SurfaceViewRenderer) {
                return (SurfaceViewRenderer) target;
            }
            return null;
        }

        synchronized public void setTarget(VideoRenderer.Callbacks target) {
            this.target = target;
        }
    }
}
