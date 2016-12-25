package com.wlanjie.streaming;

import android.annotation.TargetApi;
import android.content.res.Configuration;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;

import com.wlanjie.streaming.camera.CameraView;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
public abstract class Encoder {

    protected Queue<Frame> cache = new ConcurrentLinkedQueue<>();

    private Queue<Frame> muxerCache = new ConcurrentLinkedQueue<>();

    private final Object mPublishLock = new Object();

    int mOrientation = Configuration.ORIENTATION_PORTRAIT;

    private boolean isStop = false;

    /**
     * first frame time
     */
    long mPresentTimeUs;

    AudioRecord mAudioRecord;

    private Thread audioRecordThread;

    private boolean audioRecordLoop;

    Builder mBuilder;

    // Y, U (Cb) and V (Cr)
    // yuv420                     yuv yuv yuv yuv
    // yuv420p (planar)   yyyy*2 uu vv
    // yuv420sp(semi-planner)   yyyy*2 uv uv
    // I420 -> YUV420P   yyyy*2 uu vv
    // YV12 -> YUV420P   yyyy*2 vv uu
    // NV12 -> YUV420SP  yyyy*2 uv uv
    // NV21 -> YUV420SP  yyyy*2 vu vu
    // NV16 -> YUV422SP  yyyy uv uv
    // YUY2 -> YUV422SP  yuyv yuyvo
    protected class Frame {
        byte[] data;
        int dts;
        boolean isVideo;
    }

    public static class Builder {
        protected CameraView cameraView;
        protected boolean isSoftEncoder;
        protected int width = 720;
        protected int height = 1280;
        protected int fps = 24;
        protected int audioSampleRate = 44100;
        protected int audioBitRate = 32 * 1000; // 32 kbps
        protected int previewWidth;
        protected int previewHeight;
        protected int videoBitRate = 500 * 1000; // 500 kbps
        protected String x264Preset = "veryfast";
        protected String videoCodec = "video/avc";
        protected String audioCodec = "audio/mp4a-latm";

        public Builder setCameraView(CameraView cameraView) {
            this.cameraView = cameraView;
            return this;
        }

        public Builder setSoftEncoder(boolean softEncoder) {
            isSoftEncoder = softEncoder;
            return this;
        }

        public Builder setWidth(int width) {
            this.width = width;
            return this;
        }

        public Builder setHeight(int height) {
            this.height = height;
            return this;
        }

        public Builder setFps(int fps) {
            this.fps = fps;
            return this;
        }

        public Builder setAudioSampleRate(int audioSampleRate) {
            this.audioSampleRate = audioSampleRate;
            return this;
        }

        public Builder setAudioBitRate(int audioBitRate) {
            this.audioBitRate = audioBitRate;
            return this;
        }

        public Encoder build() {
            return isSoftEncoder ? new SoftEncoder(this) : new HardEncoder(this);
        }
    }

    public Encoder(Builder builder) {
        mBuilder = builder;
    }

    public void start(final String url) throws IllegalArgumentException, IllegalStateException {

        if (mBuilder.cameraView == null) {
            throw new IllegalArgumentException("must be call Builder.setCameraView() method.");
        }

        if (TextUtils.isEmpty(url) || !url.startsWith("rtmp://")) {
            throw new IllegalArgumentException("url must be rtmp://");
        }

        if (connect(url) != 0) {
            throw new RuntimeException("connect rtmp server error.");
        }

        // the referent PTS for video and audio encoder.
        mPresentTimeUs = System.nanoTime() / 1000;

        mAudioRecord = chooseAudioRecord();
        if (mAudioRecord == null) {
            throw new IllegalStateException("start audio record failed.");
        }
        mBuilder.previewWidth = mBuilder.cameraView.getSurfaceWidth();
        mBuilder.previewHeight = mBuilder.cameraView.getSurfaceHeight();
        setEncoderResolution(mBuilder.width, mBuilder.height);
        openEncoder();

        startPreview();
        startAudioRecord();
        mBuilder.cameraView.setFacing(CameraView.FACING_FRONT);
        mBuilder.cameraView.start();

        new Thread(new Runnable() {
            @Override
            public void run() {
                startPublish();
            }
        }).start();
    }

//    private void startPublish() {
//        Thread mPublishThread = new Thread(new Runnable() {
//            @Override
//            public void run() {
//                while (!isStop) {
//                    while (!cache.isEmpty()) {
//                        Frame frame = cache.poll();
//                        if (frame.isVideo) {
//                            writeVideo(frame.dts, frame.data);
//                        } else {
//                            writeAudio(frame.dts, frame.data, mBuilder.audioSampleRate, mAudioRecord.getChannelCount());
//                        }
//                        frame.data = null;
//                        frame.dts = 0;
//                        frame.isVideo = false;
//                        muxerCache.offer(frame);
//                    }
////
//                    synchronized (mPublishLock) {
//                        SystemClock.sleep(500);
//                    }
//                }
//            }
//        });
//        mPublishThread.start();
//    }

    /**
     * open h264 aac encoder
     * @return true success, false failed
     */
    abstract boolean openEncoder();

    /**
     * close h264 aac encoder
     */
    abstract void closeEncoder();

    /**
     * covert pcm to aac
     * @param aacFrame pcm data
     * @param size pcm data size
     */
    abstract void convertPcmToAac(byte[] aacFrame, int size);

    /**
     * convert yuv to h264
     * @param data yuv data
     */
    abstract void rgbaEncoderToH264(byte[] data);

    /**
     * start audio record
     */
    private void startAudioRecord() {
        final byte pcmBuffer[] = new byte[4096];
        audioRecordThread = new Thread(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                mAudioRecord.startRecording();
                while (audioRecordLoop) {
                    int size = mAudioRecord.read(pcmBuffer, 0, pcmBuffer.length);
                    if (size <= 0) {
                        continue;
                    }
                    convertPcmToAac(pcmBuffer, size);
//                    int pts = (int) (System.nanoTime() / 1000 - mPresentTimeUs);
//                    writeAudioTest(pcmBuffer, pts);
//                    SystemClock.sleep(40);
                }
            }
        });
        audioRecordLoop = true;
        audioRecordThread.start();
    }

    /**
     * start camera preview
     */
    private void startPreview() {
        mBuilder.cameraView.addCallback(new CameraView.Callback() {

            public void onCameraOpened(CameraView cameraView, int previewWidth, int previewHeight) {

            }

            /**
             * Called when camera is closed.
             *
             * @param cameraView The associated {@link CameraView}.
             */
            public void onCameraClosed(CameraView cameraView) {
            }

            /**
             * Called when a picture is taken.
             *
             * @param cameraView The associated {@link CameraView}.
             * @param data       JPEG data.
             */
            public void onPreviewFrame(CameraView cameraView, byte[] data) {
                rgbaEncoderToH264(data);
//                int pts = (int) (System.nanoTime() / 1000 - mPresentTimeUs);
//                writeVideoTest(data, mBuilder.previewWidth, mBuilder.previewHeight, true, 0, pts);
//                synchronized (Encoder.class) {
//                    SystemClock.sleep(40);
//                }
            }

            @Override
            public void onPreviewSize(int width, int height) {
                mBuilder.previewWidth = width;
                mBuilder.previewHeight = height;
            }
        });
    }

    /**
     * stop audio record
     */
    private void stopAudioRecord() {
        audioRecordLoop = false;
        audioRecordThread = null;
        if (mAudioRecord != null) {
            mAudioRecord.setRecordPositionUpdateListener(null);
            mAudioRecord.stop();
            mAudioRecord.release();
            mAudioRecord = null;
        }
    }

    /**
     * stop camera preview, audio record and close encoder
     */
    public void stop() {
        mBuilder.cameraView.stop();
        stopAudioRecord();
        closeEncoder();
        destroy();
        isStop = true;
        cache.clear();
    }

    public void setVideoHDMode() {
        mBuilder.videoBitRate = 1200 * 1000;  // 1200 kbps
        mBuilder.x264Preset = "veryfast";
    }

    public void setVideoSmoothMode() {
        mBuilder.videoBitRate = 500 * 1000;  // 500 kbps
        mBuilder.x264Preset = "superfast";
    }

    /**
     *  Add ADTS header at the beginning of each and every AAC packet.
     *  This is needed as MediaCodec encoder generates a packet of raw
     *  AAC data.
     *
     *  Note the packetLen must count in the ADTS header itself.
     **/
    protected void addADTStoPacket(byte[] packet, int packetLen) {
        int profile = 2;  //AAC LC
        //39=MediaCodecInfo.CodecProfileLevel.AACObjectELD;
        int freqIdx = 4;  //44.1KHz
        int chanCfg = 2;  //CPE

        // fill in ADTS data
        packet[0] = (byte) 0xFF;
        packet[1] = (byte) 0xF9;
        packet[2] = (byte) (((profile - 1) << 6) + ( freqIdx << 2) + (chanCfg >> 2));
        packet[3] = (byte) (((chanCfg & 3) << 6) + (packetLen >> 11));
        packet[4] = (byte) ((packetLen & 0x7FF) >> 3);
        packet[5] = (byte) (((packetLen & 7) << 5) + 0x1F);
        packet[6] = (byte) 0xFC;
    }

    /**
     * init audio record
     * @return audio record
     */
    private AudioRecord chooseAudioRecord() {
        int minBufferSize = AudioRecord.getMinBufferSize(mBuilder.audioSampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord mic = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, mBuilder.audioSampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize);
        if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
            mic = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, mBuilder.audioSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize);
            if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
                mic = null;
            }
        }
        return mic;
    }

    /**
     * this method call by jni
     * muxer flv h264 success
     * @param h264 flv h264 data
     * @param pts pts
     * @param isSequenceHeader
     */
    private void muxerH264Success(byte[] h264, int pts, int isSequenceHeader) {
        Frame frame;
        if (!muxerCache.isEmpty()) {
            frame = muxerCache.poll();
        } else {
            frame = new Frame();
        }
        frame.isVideo = true;
        frame.data = h264;
        frame.dts = pts;
        cache.offer(frame);
    }

    /**
     * this method call by jni
     * muxer flv aac data success
     * @param aac flv aac data
     * @param pts pts
     * @param isSequenceHeader
     */
    private void muxerAacSuccess(byte[] aac, int pts, int isSequenceHeader) {
        Frame frame;
        if (!muxerCache.isEmpty()) {
            frame = muxerCache.poll();
        } else {
            frame = new Frame();
        }
        frame.isVideo = false;
        frame.data = aac;
        frame.dts = pts;
        cache.offer(frame);
    }

    private native void startPublish();

    /**
     * set output width and height
     * @param outWidth output width
     * @param outHeight output height
     */
    private native void setEncoderResolution(int outWidth, int outHeight);

    /**
     * connect rtmp server
     * @param url rtmp url
     * @return 0 is success, other failed.
     */
    protected native int connect(String url);

    /**
     * publish audio to rtmp server
     * @param timestamp pts
     * @param data aac data
     * @param sampleRate aac sample rate
     * @param channel aac channel
     * @return 0 is success, other failed.
     */
    public native int writeAudio(long timestamp, byte[] data, int sampleRate, int channel);

    /**
     * publish video to rtmp server
     * @param timestamp pts
     * @param data h264 data
     * @return 0 is success, other failed.
     */
    public native int writeVideo(long timestamp, byte[] data);

    /**
     * muxer flv h264 data
     * @param data h264 data
     * @param pts pts
     */
    protected native void muxerH264(byte[] data, int pts);

    /**
     * muxer flv aac data
     * @param data aac data
     * @param pts pts
     */
    protected native void muxerAac(byte[] data, int pts);

    /**
     * destroy rtmp resources {@link #connect(String url)}
     */
    protected native void destroy();

    /**
     * convert NV21 to I420
     * @param yuvFrame NV21 data
     * @param width preview width
     * @param height preview height
     * @param flip
     * @param rotate
     * @return I420 data
     */
    protected native byte[] NV21ToI420(byte[] yuvFrame, int width, int height, boolean flip, int rotate);

    /**
     * convert NV21 to NV12
     * @param yuvFrame NV21 data
     * @param width preview width
     * @param height preview height
     * @param flip
     * @param rotate
     * @return NV12 data
     */
    protected native byte[] NV21ToNV12(byte[] yuvFrame, int width, int height, boolean flip, int rotate);

    protected native byte[] rgbaToI420(byte[] rgbaFrame, int width, int height, boolean flip, int rotate);

    protected native int writeVideoTest(byte[] rgbaFrame, int width, int height, boolean flip, int rotate, int pts);

    protected native int writeAudioTest(byte[] pcmFrame, int pts);

    static {
        System.loadLibrary("wlanjie");
    }
}
