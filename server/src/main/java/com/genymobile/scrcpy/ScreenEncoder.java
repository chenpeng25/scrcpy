package com.genymobile.scrcpy;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.opengl.GLES20;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

import com.genymobile.scrcpy.glec.EGLRender;
import com.genymobile.scrcpy.wrappers.SurfaceControl;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenEncoder implements Device.RotationListener, Device.FoldListener, AsyncProcessor {

    private static final int DEFAULT_I_FRAME_INTERVAL = 10; // seconds
    private static final int REPEAT_FRAME_DELAY_US = 100_000; // repeat after 100ms
    private static final String KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder";

    // Keep the values in descending order
    private static final int[] MAX_SIZE_FALLBACK = {2560, 1920, 1600, 1280, 1024, 800};
    private static final int MAX_CONSECUTIVE_ERRORS = 3;

    private final AtomicBoolean resetCapture = new AtomicBoolean();

    private final Device device;
    private final Streamer streamer;
    private final String encoderName;
    private final List<CodecOption> codecOptions;
    private final int videoBitRate;
    private final int maxFps;
    private final boolean downsizeOnError;

    private boolean firstFrameSent;
    private int consecutiveErrors;

    private Thread thread;
    private final AtomicBoolean stopped = new AtomicBoolean();


    //是否固定帧率
    private boolean mIsFixedFrame = true;
    private EGLRender mEglRender;
    private final EGLRender.onFrameCallBack mFrameCallBack = new EGLRender.onFrameCallBack() {
        @Override
        public void onError() {
            Ln.e("EglRender onError!");
        }

        @Override
        public void onStop() {
            Ln.v("EglRender onStop!");
            mEglRender.releaseResource();
            mEglRender = null;
        }
    };

    public ScreenEncoder(Device device, Streamer streamer, int videoBitRate, int maxFps, List<CodecOption> codecOptions, String encoderName,
                         boolean downsizeOnError) {
        this.device = device;
        this.streamer = streamer;
        this.videoBitRate = videoBitRate;
        this.maxFps = maxFps;
        this.codecOptions = codecOptions;
        this.encoderName = encoderName;
        this.downsizeOnError = downsizeOnError;
    }

    @Override
    public void onFoldChanged(int displayId, boolean folded) {
        resetCapture.set(true);
    }

    @Override
    public void onRotationChanged(int rotation) {
        resetCapture.set(true);
    }

    private boolean consumeResetCapture() {
        return resetCapture.getAndSet(false);
    }

    private void streamScreen() throws IOException, ConfigurationException {
        Codec codec = streamer.getCodec();
        printMediaCodecInfo();
        MediaCodec mediaCodec = createMediaCodec(codec, encoderName);
        MediaFormat format = createFormat(codec.getMimeType(), videoBitRate, maxFps, codecOptions);
        IBinder display = createDisplay();
        device.setRotationListener(this);
        device.setFoldListener(this);

        streamer.writeVideoHeader(device.getScreenInfo().getVideoSize());

        boolean alive;
        try {
            do {
                ScreenInfo screenInfo = device.getScreenInfo();
                Rect contentRect = screenInfo.getContentRect();

                // include the locked video orientation
                Rect videoRect = screenInfo.getVideoSize().toRect();
                format.setInteger(MediaFormat.KEY_WIDTH, videoRect.width());
                format.setInteger(MediaFormat.KEY_HEIGHT, videoRect.height());
                Surface surface = null;
                try {
                    mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

                    if (mIsFixedFrame) {
                        this.mEglRender = new EGLRender(mediaCodec.createInputSurface(), videoRect.width(), videoRect.height(), 30, 500);
                        this.mEglRender.setCallBack(mFrameCallBack);
                        surface = mEglRender.getDecodeSurface();
                    } else {
                        surface = mediaCodec.createInputSurface();
                    }

                    // does not include the locked video orientation
                    Rect unlockedVideoRect = screenInfo.getUnlockedVideoSize().toRect();
                    int videoRotation = screenInfo.getVideoRotation();
                    int layerStack = device.getLayerStack();
                    setDisplaySurface(display, surface, videoRotation, contentRect, unlockedVideoRect, layerStack);

                    if (this.mIsFixedFrame) {
                        this.mEglRender.setStartTimeNs(SystemClock.elapsedRealtimeNanos());
                        this.mEglRender.start();
                        Ln.i("Encoder running");
                    }
                    mediaCodec.start();

                    alive = encode(mediaCodec, streamer);
                    // do not call stop() on exception, it would trigger an IllegalStateException
                    mediaCodec.stop();
                } catch (IllegalStateException | IllegalArgumentException e) {
                    Ln.e("Encoding error: " + e.getClass().getName() + ": " + e.getMessage());
                    if (!prepareRetry(device, screenInfo)) {
                        throw e;
                    }
                    Ln.i("Retrying...");
                    alive = true;
                } finally {
                    mediaCodec.reset();
                    if (surface != null) {
                        surface.release();
                    }

                    Ln.i("Encoder end");
                    if (this.mEglRender != null) {
                        this.mEglRender.stop();
                    }

                }
            } while (alive);
        } finally {
            mediaCodec.release();
            device.setRotationListener(null);
            device.setFoldListener(null);
            SurfaceControl.destroyDisplay(display);
        }
    }

    private boolean prepareRetry(Device device, ScreenInfo screenInfo) {
        if (firstFrameSent) {
            ++consecutiveErrors;
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                // Definitively fail
                return false;
            }

            // Wait a bit to increase the probability that retrying will fix the problem
            SystemClock.sleep(50);
            return true;
        }

        if (!downsizeOnError) {
            // Must fail immediately
            return false;
        }

        // Downsizing on error is only enabled if an encoding failure occurs before the first frame (downsizing later could be surprising)

        int newMaxSize = chooseMaxSizeFallback(screenInfo.getVideoSize());
        if (newMaxSize == 0) {
            // Must definitively fail
            return false;
        }

        // Retry with a smaller device size
        Ln.i("Retrying with -m" + newMaxSize + "...");
        device.setMaxSize(newMaxSize);
        return true;
    }

    private static int chooseMaxSizeFallback(Size failedSize) {
        int currentMaxSize = Math.max(failedSize.getWidth(), failedSize.getHeight());
        for (int value : MAX_SIZE_FALLBACK) {
            if (value < currentMaxSize) {
                // We found a smaller value to reduce the video size
                return value;
            }
        }
        // No fallback, fail definitively
        return 0;
    }

    private boolean encode(MediaCodec codec, Streamer streamer) throws IOException {
        boolean eof = false;
        boolean alive = true;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (!consumeResetCapture() && !eof) {
            if (stopped.get()) {
                alive = false;
                break;
            }
            int outputBufferId = codec.dequeueOutputBuffer(bufferInfo, -1);

            boolean isKey = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
            if (isKey) {
                Ln.v("编出关键帧");
            }
            try {
                if (consumeResetCapture()) {
                    // must restart encoding with new size
                    break;
                }

                eof = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (outputBufferId >= 0) {
                    ByteBuffer codecBuffer = codec.getOutputBuffer(outputBufferId);

                    boolean isConfig = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!isConfig) {
                        // If this is not a config packet, then it contains a frame
                        firstFrameSent = true;
                        consecutiveErrors = 0;
                    }

                    streamer.writePacket(codecBuffer, bufferInfo);
                }
            } finally {
                if (outputBufferId >= 0) {
                    codec.releaseOutputBuffer(outputBufferId, false);
                }
            }
        }

        return !eof && alive;
    }

    private static MediaCodec createMediaCodec(Codec codec, String encoderName) throws IOException, ConfigurationException {
        if (encoderName != null) {
            Ln.d("Creating encoder by name: '" + encoderName + "'");
            try {
                return MediaCodec.createByCodecName(encoderName);
            } catch (IllegalArgumentException e) {
                Ln.e("Video encoder '" + encoderName + "' for " + codec.getName() + " not found\n" + LogUtils.buildVideoEncoderListMessage());
                throw new ConfigurationException("Unknown encoder: " + encoderName);
            } catch (IOException e) {
                Ln.e("Could not create video encoder '" + encoderName + "' for " + codec.getName() + "\n" + LogUtils.buildVideoEncoderListMessage());
                throw e;
            }
        }

        try {
            MediaCodec mediaCodec = MediaCodec.createEncoderByType(codec.getMimeType());
            Ln.d("Using video encoder: '" + mediaCodec.getName() + "'");
            return mediaCodec;
        } catch (IOException | IllegalArgumentException e) {
            Ln.e("Could not create default video encoder for " + codec.getName() + "\n" + LogUtils.buildVideoEncoderListMessage());
            throw e;
        }
    }

    public  void printMediaCodecInfo() {
        final MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        final MediaCodecInfo[] codecInfos = list.getCodecInfos();
        for (int i = 0; i < codecInfos.length; ++i) {
            MediaCodecInfo info = null;
            try {
                info =codecInfos[i];
            } catch (IllegalArgumentException e) {
                Ln.e( "Cannot retrieve decoder codec info", e);
            }
            if (info == null) {
                continue;
            }
            String codecInfo = "MediaCodec, name="+info.getName()+", [";

            for (String mimeType : info.getSupportedTypes()) {
                codecInfo += mimeType + ",";
                MediaCodecInfo.CodecCapabilities capabilities;
                try {
                    capabilities = info.getCapabilitiesForType(mimeType);
                } catch (IllegalArgumentException e) {
                    Ln.e( "Cannot retrieve decoder capabilities", e);
                    continue;
                }
                codecInfo += " max inst:"+capabilities.getMaxSupportedInstances()+",";
                String strColorFormatList = "";
                for (int colorFormat : capabilities.colorFormats) {
                    strColorFormatList += " 0x" + Integer.toHexString(colorFormat);
                }
                codecInfo += strColorFormatList + "] [";
            }
            Ln.d(codecInfo);
        }
    }


    private static MediaFormat createFormat(String videoMimeType, int bitRate, int maxFps, List<CodecOption> codecOptions) {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, videoMimeType);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        // must be present to configure the encoder, but does not impact the actual frame rate, which is variable
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, DEFAULT_I_FRAME_INTERVAL); //10
        // display the very first frame, and recover from bad quality when no new frames
        // 当画面静止时,重复最后一帧，不影响界面显示
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_DELAY_US); // 100_000µs
        if (maxFps > 0) {
            // The key existed privately before Android 10:
            // <https://android.googlesource.com/platform/frameworks/base/+/625f0aad9f7a259b6881006ad8710adce57d1384%5E%21/>
            // <https://github.com/Genymobile/scrcpy/issues/488#issuecomment-567321437>
            format.setFloat(KEY_MAX_FPS_TO_ENCODER, maxFps);
        }

        if (codecOptions != null) {
            for (CodecOption option : codecOptions) {
                String key = option.getKey();
                Object value = option.getValue();
                CodecUtils.setCodecOption(format, key, value);
                Ln.d("Video codec option set: " + key + " (" + value.getClass().getSimpleName() + ") = " + value);
            }
        }

        Ln.d("encoder video format: " + format.toString());

        return format;
    }

    private static IBinder createDisplay() {
        // Since Android 12 (preview), secure displays could not be created with shell permissions anymore.
        // On Android 12 preview, SDK_INT is still R (not S), but CODENAME is "S".
        boolean secure = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && !"S"
                .equals(Build.VERSION.CODENAME));
        return SurfaceControl.createDisplay("scrcpy", secure);
    }

    private static void setDisplaySurface(IBinder display, Surface surface, int orientation, Rect deviceRect, Rect displayRect, int layerStack) {
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(display, surface);
            SurfaceControl.setDisplayProjection(display, orientation, deviceRect, displayRect);
            SurfaceControl.setDisplayLayerStack(display, layerStack);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }

    @Override
    public void start(TerminationListener listener) {
        thread = new Thread(() -> {
            // Some devices (Meizu) deadlock if the video encoding thread has no Looper
            // <https://github.com/Genymobile/scrcpy/issues/4143>
            Looper.prepare();

            try {
                streamScreen();
            } catch (ConfigurationException e) {
                // Do not print stack trace, a user-friendly error-message has already been logged
            } catch (IOException e) {
                // Broken pipe is expected on close, because the socket is closed by the client
                if (!IO.isBrokenPipe(e)) {
                    Ln.e("Video encoding error", e);
                }
            } finally {
                Ln.d("Screen streaming stopped");
                listener.onTerminated(true);
            }
        }, "video");
        thread.start();
    }

    @Override
    public void stop() {
        if (thread != null) {
            stopped.set(true);
        }
    }

    @Override
    public void join() throws InterruptedException {
        if (thread != null) {
            thread.join();
        }
    }

    //---------------------------------------------------------------------------------------------------


    // RGB color values for generated frames
    private static final int TEST_R0 = 0;
    private static final int TEST_G0 = 136;
    private static final int TEST_B0 = 0;
    private static final int TEST_R1 = 236;
    private static final int TEST_G1 = 50;
    private static final int TEST_B1 = 186;

    /**
     * Generates a frame of data using GL commands.  We have an 8-frame animation
     * sequence that wraps around.  It looks like this:
     * <pre>
     *   0 1 2 3
     *   7 6 5 4
     * </pre>
     * We draw one of the eight rectangles and leave the rest set to the clear color.
     */
    private void generateSurfaceFrame(int frameIndex, int mWidth, int mHeight) {
        frameIndex %= 8;

        int startX, startY;
        if (frameIndex < 4) {
            // (0,0) is bottom-left in GL
            startX = frameIndex * (mWidth / 4);
            startY = mHeight / 2;
        } else {
            startX = (7 - frameIndex) * (mWidth / 4);
            startY = 0;
        }

        GLES20.glClearColor(TEST_R0 / 255.0f, TEST_G0 / 255.0f, TEST_B0 / 255.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        GLES20.glScissor(startX, startY, mWidth / 4, mHeight / 2);
        GLES20.glClearColor(TEST_R1 / 255.0f, TEST_G1 / 255.0f, TEST_B1 / 255.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }

    /**
     * Generates the presentation time for frame N, in nanoseconds.
     */
    private long computePresentationTimeNsec(int frameIndex) {
        final long ONE_BILLION = 1000000000;
        return frameIndex * ONE_BILLION / maxFps;
    }

}
