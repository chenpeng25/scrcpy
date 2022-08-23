package com.genymobile.scrcpy.glec;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.genymobile.scrcpy.Ln;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;


/**
 * Android OpenGL ES 离屏渲染（offscreen render）
 */
public class EGLRender implements SurfaceTexture.OnFrameAvailableListener {

    private static final String TAG = "EncodeDecodeSurface";
    private static final int UNDERRUN_CHECK_INTERVAL = 200;
    private static final int UNDERRUN_CHECK_PERCENT = 10;
    private static final boolean VERBOSE = true;

    private Surface decodeSurface;
    private onFrameCallBack mCallBack;
    private long mCurrentTimeMs = 0;
    //1、为什么设计两个EglContext、mEglSurface
    private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
    private EGLContext mEGLContextEncoder = EGL14.EGL_NO_CONTEXT;
    private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
    //2、为什么设计两个EGLSurface
    private EGLSurface mEGLSurface = EGL14.EGL_NO_SURFACE;
    private EGLSurface mEGLSurfaceEncoder = EGL14.EGL_NO_SURFACE;

    private int mEncodedCount = 0;
    //帧率
    private int mFps;
    private boolean mFrameAvailable = false;
    //帧间隔
    private int mFrameIntervalMs = 0;

    private RenderHandler mHandler;
    private HandlerThread mHandlerThread;


    private ReentrantLock mLock = new ReentrantLock();
    private int mNextCheckCount = 0;
    private boolean mNotifyError = true;
    private volatile AtomicBoolean mStart = new AtomicBoolean(false);
    private long mStartTimeMs = 0;

    private SurfaceTexture mSurfaceTexture;
    private STextureRender mTextureRender;
    private long mTimeBaseNs = 0;
    private long mTimeOffset;
    private int mHeight,mWidth;

    private final int[] confAttr =
            {
                    EGL14.EGL_RED_SIZE,   8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE,  8,
                    EGL14.EGL_ALPHA_SIZE, 8,// if you need the alpha channel
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,// very important!
                    EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,//EGL_WINDOW_BIT EGL_PBUFFER_BIT we will create a pixelbuffer surface*/
                    EGL14.EGL_NONE
            };



    private class RenderHandler extends Handler {
        static final int MSG_DRAW_IMAGE = 1;

        public RenderHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            if (msg.what == MSG_DRAW_IMAGE) {
                EGLRender.this.mLock.lock();
                try {
                    if (!mFrameAvailable) {
                        sendEmptyMessageDelayed(MSG_DRAW_IMAGE, 10);
                    } else if (mStart.get()) {
                        sendEmptyMessageDelayed(MSG_DRAW_IMAGE, (long) mFrameIntervalMs);
                        mCurrentTimeMs = SystemClock.elapsedRealtime();
                        if (mStartTimeMs <= 0) {
                            mNextCheckCount = mNextCheckCount + EGLRender.UNDERRUN_CHECK_INTERVAL;
                            mStartTimeMs = mCurrentTimeMs;
                            makeCurrent(1);
                        }
                        mSurfaceTexture.updateTexImage();
                        drawImage();
                        setPresentationTime(((mCurrentTimeMs - mStartTimeMs) + mTimeOffset) * 1000000);
                        swapBuffers();
                        mEncodedCount = mEncodedCount + 1;
                        if (mEncodedCount > mNextCheckCount) {
                            mNextCheckCount = mNextCheckCount + EGLRender.UNDERRUN_CHECK_INTERVAL;
                            checkPerformanceError((long)mEncodedCount, mCurrentTimeMs);
                        }
                       mLock.unlock();
                    } else {
                        mLock.unlock();
                    }
                } catch (Exception e) {
                    Log.e("EncodeDecodeSurface", "error in draw image ", e);
                } finally {
                    mLock.unlock();
                }
            }
        }
    }



    /**
     *
     */
    public interface onFrameCallBack {
        void onError();

        void onStop();
    }



    public EGLRender() {
    }

    public EGLRender(Surface surface,int width,int height,int fps,long timeOffset) {
        Ln.v(":::EGLRender:::+width"+width+"X height"+height);
        mWidth = width;
        mHeight = height;
        mTimeOffset = timeOffset;
        initFPs(fps);
        eglSetup(surface);
        makeCurrent();
        setup();

    }

    private void initFPs(int fps) {
        Log.d(TAG, "initFPs :" + fps);
        this.mFps = fps;
        this.mFrameIntervalMs = 1000 / fps;
    }

    /**
        Display → Config → Surface
     */
    /*private void eglSetup(Surface surface){
        //Display代表显示器，在有些系统上可以有多个显示器，也就会有多个Display
        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY );
        if (this.mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("unable to get EGL14 display");
        }

        int[] version = new int[2];
        //初始化egl。并传回EGL版本号
        if (EGL14.eglInitialize(mEGLDisplay,version,0,version,1)){
            EGLConfig[] configs = new EGLConfig[1];
            if (EGL14.eglChooseConfig(mEGLDisplay,confAttr,0,configs,0,configs.length,new int[1], 0)){
                EGLConfig configEncoder = getConfig(2);
                final int[] attrib_list = {
                        EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                        EGL14.EGL_NONE
                };
                this.mEGLContext  = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT, attrib_list, 0);
                checkEglError("eglCreateContext");
                if (this.mEGLContext == null) {
                    throw new RuntimeException("null context");
                }

                this.mEGLContextEncoder = EGL14.eglCreateContext(this.mEGLDisplay, configEncoder, this.mEGLContext, attrib_list, 0);
                if (this.mEGLContextEncoder == null) {
                    throw new RuntimeException("null context2");
                }

                final int[] surfaceAttribs = {
                        EGL14.EGL_WIDTH, mWidth,
                        EGL14.EGL_HEIGHT, mHeight,
                        EGL14.EGL_NONE
                };

                mEGLSurface =  EGL14.eglCreatePbufferSurface(mEGLDisplay, configs[0], surfaceAttribs, 0);
                checkEglError("eglCreatePbufferSurface");
                if (this.mEGLSurface == null) {
                    throw new RuntimeException("surface was null");
                }
                this.mEGLSurfaceEncoder = EGL14.eglCreateWindowSurface(this.mEGLDisplay, configEncoder, surface, new int[]{EGL14.EGL_NONE}, 0);
                checkEglError("eglCreateWindowSurface");
                if (this.mEGLSurfaceEncoder == null) {
                    throw new RuntimeException("surface was null");
                }
                return;
            }
            throw new RuntimeException("unable to find RGB888+recordable ES2 EGL config");

        }

        this.mEGLDisplay = null;
        throw new RuntimeException("unable to initialize EGL14");


    }*/

    private void eglSetup(Surface surface) {
        this.mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (this.mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("unable to get EGL14 display");
        }
        int[] version = new int[2];
        if (EGL14.eglInitialize(this.mEGLDisplay, version, 0, version, 1)) {
            EGLConfig[] configs = new EGLConfig[1];
            if (EGL14.eglChooseConfig(this.mEGLDisplay, new int[]{12324, 8, 12323, 8, 12322, 8, 12321, 8, 12352, 4, 12339, 1, 12344}, 0, configs, 0, configs.length, new int[1], 0)) {
                EGLConfig configEncoder = getConfig(2);
                int[] attrib_list = new int[]{12440, 2, 12344};
                this.mEGLContext = EGL14.eglCreateContext(this.mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT, attrib_list, 0);
                checkEglError("eglCreateContext");
                if (this.mEGLContext == null) {
                    throw new RuntimeException("null context");
                }
                this.mEGLContextEncoder = EGL14.eglCreateContext(this.mEGLDisplay, configEncoder, this.mEGLContext, attrib_list, 0);
                checkEglError("eglCreateContext");
                if (this.mEGLContextEncoder == null) {
                    throw new RuntimeException("null context2");
                }
                this.mEGLSurface = EGL14.eglCreatePbufferSurface(this.mEGLDisplay, configs[0], new int[]{12375, this.mWidth, 12374, this.mHeight, 12344}, 0);
                checkEglError("eglCreatePbufferSurface");
                if (this.mEGLSurface == null) {
                    throw new RuntimeException("surface was null");
                }
                this.mEGLSurfaceEncoder = EGL14.eglCreateWindowSurface(this.mEGLDisplay, configEncoder, surface, new int[]{12344}, 0);
                checkEglError("eglCreateWindowSurface");
                if (this.mEGLSurfaceEncoder == null) {
                    throw new RuntimeException("surface was null");
                }
                return;
            }
            throw new RuntimeException("unable to find RGB888+recordable ES2 EGL config");
        }
        this.mEGLDisplay = null;
        throw new RuntimeException("unable to initialize EGL14");
    }



    public void makeCurrent() {
        if (!EGL14.eglMakeCurrent(this.mEGLDisplay, this.mEGLSurface, this.mEGLSurface, this.mEGLContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }


    private void setup() {
        Log.d(TAG,"setup");
        this.mTextureRender = new STextureRender(this.mWidth, this.mHeight);
        this.mTextureRender.surfaceCreated();
        this.mSurfaceTexture = new SurfaceTexture(this.mTextureRender.getTextureId());
        this.mSurfaceTexture.setDefaultBufferSize(this.mWidth, this.mHeight);
        this.mSurfaceTexture.setOnFrameAvailableListener(this);
        this.decodeSurface = new Surface(this.mSurfaceTexture);
    }

    public Surface getDecodeSurface() {
        return this.decodeSurface;
    }





    /*private EGLConfig getConfig(int version){

        final int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE, 0,	//EGL14.EGL_STENCIL_SIZE, 8,
                // with_depth_buffer ? 16 : 0,
                EGL14.EGL_NONE
        };

        int renderableType = EGL14.EGL_OPENGL_ES2_BIT;
        if (version >= 3) {
            renderableType = 4 | 64;
        }
        EGLConfig[] configs = new EGLConfig[1];
        if (EGL14.eglChooseConfig(mEGLDisplay,attribList,0, configs, 0, configs.length, new int[1], 0)){
            return configs[0];
        }

        Log.w("EncodeDecodeSurface", "unable to find RGB8888 / " + version + " EGLConfig");
        return null;

    }*/
    private EGLConfig getConfig(int version) {
        int renderableType = 4;
        if (version >= 3) {
            renderableType = 4 | 64;
        }
        EGLConfig[] configs = new EGLConfig[1];
        if (EGL14.eglChooseConfig(this.mEGLDisplay, new int[]{12324, 8, 12323, 8, 12322, 8, 12321, 8, 12352, renderableType, 12344, 0, 12344}, 0, configs, 0, configs.length, new int[1], 0)) {
            return configs[0];
        }
        Log.w("EncodeDecodeSurface", "unable to find RGB8888 / " + version + " EGLConfig");
        return null;
    }




    /**
     * Creates an EGL surface associated with an offscreen buffer.
     */
    private EGLSurface createOffscreenSurface(final int width, final int height) {
         Log.v(TAG, "createOffscreenSurface:");
        final int[] surfaceAttribs = {
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE
        };
        EGLSurface result = null;
        try {
            //result = EGL14.eglCreatePbufferSurface(mEglDisplay, mEglConfig, surfaceAttribs, 0);
            checkEglError("eglCreatePbufferSurface");
            if (result == null) {
                throw new RuntimeException("surface was null");
            }
        } catch (final IllegalArgumentException e) {
            Log.e(TAG, "createOffscreenSurface", e);
        } catch (final RuntimeException e) {
            Log.e(TAG, "createOffscreenSurface", e);
        }
        return result;
    }


    private void checkEglError(String msg) {
        int error = EGL14.eglGetError();
        if (error != 12288) {
            throw new RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error));
        }
    }


    public void setStartTimeNs(long startTimeNs){
        mTimeBaseNs = startTimeNs;
    }


    public void setCallBack(onFrameCallBack callBack){
        this.mCallBack = callBack;
    }

    public void setPresentationTime(long nsecs) {
        EGLExt.eglPresentationTimeANDROID(this.mEGLDisplay, this.mEGLSurfaceEncoder, this.mTimeBaseNs + nsecs);
        checkEglError("eglPresentationTimeANDROID");
    }

    public boolean swapBuffers() {
        Ln.v("swapBuffers:");
        boolean result = EGL14.eglSwapBuffers(this.mEGLDisplay, this.mEGLSurfaceEncoder);
        checkEglError("eglSwapBuffers");
        return result;
    }


    public void makeCurrent(int index) {
        if (index == 0) {
            if (!EGL14.eglMakeCurrent(this.mEGLDisplay, this.mEGLSurface, this.mEGLSurface, this.mEGLContext)) {
                throw new RuntimeException("eglMakeCurrent failed");
            }
        } else if (!EGL14.eglMakeCurrent(this.mEGLDisplay, this.mEGLSurfaceEncoder, this.mEGLSurfaceEncoder, this.mEGLContextEncoder)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }


    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        Ln.v(":::onFrameAvailable:::");
        this.mFrameAvailable = true;

    }
    public void drawImage() {
        Ln.v("drawImage:");
        this.mTextureRender.drawFrame();
    }

    public void releaseResource() {
        eglRelease();
        this.mSurfaceTexture.release();
        this.mSurfaceTexture = null;
        this.mTextureRender = null;
        this.decodeSurface.release();
        this.decodeSurface = null;
    }


    private void eglRelease() {
        EGL14.eglDestroyContext(this.mEGLDisplay, this.mEGLContext);
        EGL14.eglDestroyContext(this.mEGLDisplay, this.mEGLContextEncoder);
        EGL14.eglDestroySurface(this.mEGLDisplay, this.mEGLSurface);
        EGL14.eglDestroySurface(this.mEGLDisplay, this.mEGLSurfaceEncoder);
        EGL14.eglTerminate(this.mEGLDisplay);
        this.mEGLContext = EGL14.EGL_NO_CONTEXT;
        this.mEGLContextEncoder = EGL14.EGL_NO_CONTEXT;
        this.mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        this.mEGLSurface = EGL14.EGL_NO_SURFACE;
        this.mEGLSurfaceEncoder = EGL14.EGL_NO_SURFACE;
    }


    private void checkPerformanceError(long count, long timeMs) {
        long frameCount = (timeMs - this.mStartTimeMs) / ((long) this.mFrameIntervalMs);
        if (((frameCount - count) * 100) / frameCount > 10) {
            Log.w("EncodeDecodeSurface", "frame underrun more than 10% !!!");
            if (this.mNotifyError) {
                this.mCallBack.onError();
                this.mNotifyError = false;
            }
        }
    }

    public void start() {
        this.mStart.set(true);
        this.mHandlerThread = new HandlerThread("RenderThread");
        this.mHandlerThread.start();
        this.mHandler = new RenderHandler(this.mHandlerThread.getLooper());
        this.mHandler.sendEmptyMessage(RenderHandler.MSG_DRAW_IMAGE);
    }

    public void stop() {
        this.mStart.set(false);
        this.mHandlerThread.quitSafely();
        this.mLock.lock();
        this.mLock.unlock();
        this.mCallBack.onStop();
    }



}
