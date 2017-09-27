package rtmppush.hx.com.rtmppush;

import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.RelativeLayout;

import com.alex.livertmppushsdk.FdkAacEncode;
import com.alex.livertmppushsdk.RtmpSessionManager;
import com.alex.livertmppushsdk.SWVideoEncoder;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Main2Activity extends AppCompatActivity implements SurfaceHolder.Callback , android.hardware.Camera.PreviewCallback{
    private String rtmpPushUrl = "rtmp://192.168.1.102:1935/live/stream";//推流路径
    /**
     * surface
     */
    private SurfaceView surfaceView = null;//预览界面
    private SurfaceHolder holder;
    /**
     * camera
     */
    private Camera camera = null;//摄像头
    private SWVideoEncoder swVideoEncoder = null;//编码器
    private int WIDTH = 480, HEIGHT = 640;//设置宽和高
    private int FRAMERATE = 20, BITRATE = 800 * 1000;//设置帧频和比特率
    private int codecType = android.graphics.ImageFormat.NV21;//编码类型
    private int degress = 0;//镜头旋转角度
    private boolean isFront = true;//是否前置镜头

    /**
     * audioRecord
     */
    private AudioRecord audioRecord = null;//录音机
    private int recordBufferSize = 0;//录音缓冲区大小
    private byte[] recordBuffer = null;//录音缓冲区
    private FdkAacEncode fdkAacEncode = null;//编码器
    private int aacInit = 0;
    private int SAMPLE_RATE = 22050;
    private int CHANNEL_NUMBER = 2;//声道

    /**
     * push
     */
    private byte[] yuvEdit = new byte[WIDTH * HEIGHT * 3 / 2];
    private RtmpSessionManager rtmpSessionManager;//会话管理器
    private Queue<byte[]> queue = new LinkedList<>();
    private Lock queueLock = new ReentrantLock();
    private boolean isPushing = false;//是否正在推送

    private Thread h264Thread = null;//处理视频的线程
    private Thread aacThread = null;//处理音频的线程

    // region 处理h264的回调
   private  Runnable h264Runable=new Runnable() {
        @Override
        public void run() {
           while(!h264Thread.isInterrupted()&&isPushing){
               int size=queue.size();
               if(size>0){
                   queueLock.lock();
                   byte[] yuvData=queue.poll();
                   queueLock.unlock();
                   if(yuvData==null){
                       continue;
                   }
                  if(isFront){
                      yuvEdit=swVideoEncoder.YUV420pRotate270(yuvData,HEIGHT,WIDTH);
                  }else{
                      yuvEdit=swVideoEncoder.YUV420pRotate90(yuvData,HEIGHT,WIDTH);
                  }
                 byte[] h264Data=  swVideoEncoder.EncoderH264(yuvEdit);

                   if(h264Data!=null){
                       rtmpSessionManager.InsertVideoData(h264Data);
                   }
               }
           }
            queue.clear();

        }
    };

    //处理aac的回调
    private  Runnable aacRunnable=new Runnable() {
        @Override
        public void run() {
            while (!aacThread.isInterrupted()&&isPushing){
               int len= audioRecord.read(recordBuffer,0,recordBuffer.length);
                if(len!=AudioRecord.ERROR_BAD_VALUE&&len!=0){
                    if(aacInit!=0){
                     byte[] aacBuffer=   fdkAacEncode.FdkAacEncode(aacInit,recordBuffer);
                       if(aacBuffer!=null){
                           rtmpSessionManager.InsertAudioData(aacBuffer);
                       }
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        //设置常亮
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main2);

        initSurface();
        initAudioRecord();
        startPush();

    }

    private void initSurface() {
        DisplayMetrics displayMetrics=new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
       int screenWidth= displayMetrics.widthPixels;
        int screenHeight=displayMetrics.heightPixels;
        int iNewWidth=(int)(screenWidth* 3.0/4.0);
        RelativeLayout rCameraLayout = (RelativeLayout) findViewById(R.id.cameraRelative);

         RelativeLayout.LayoutParams layoutParams=new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT,RelativeLayout.LayoutParams.MATCH_PARENT);
         int margin=screenWidth-iNewWidth;
          layoutParams.setMargins(margin,0,0,0);
        //初始化surfaceView
        surfaceView = (SurfaceView) this.findViewById(R.id.surfaceView);
        holder=surfaceView.getHolder();
        holder.setFixedSize(HEIGHT,WIDTH);
        holder.setKeepScreenOn(true);
        holder.addCallback(this);
        surfaceView.setLayoutParams(layoutParams);

    }


    private void initAudioRecord() {
        recordBufferSize=AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_CONFIGURATION_STEREO,AudioFormat.ENCODING_PCM_16BIT);
        audioRecord=new AudioRecord(MediaRecorder.AudioSource.MIC,SAMPLE_RATE,  AudioFormat.CHANNEL_CONFIGURATION_STEREO,AudioFormat.ENCODING_PCM_16BIT,recordBufferSize);
      recordBuffer=new byte[recordBufferSize];
        fdkAacEncode=new FdkAacEncode();
        aacInit=fdkAacEncode.FdkAacInit(SAMPLE_RATE,CHANNEL_NUMBER);
    }


    private void startPush() {
     rtmpSessionManager=new RtmpSessionManager();
     rtmpSessionManager.Start(rtmpPushUrl);

      swVideoEncoder=new SWVideoEncoder(WIDTH,HEIGHT,FRAMERATE,BITRATE);  ;
       swVideoEncoder.start(codecType);

        isPushing=true;

        //启动视频编码线程
        h264Thread = new Thread(h264Runable);
        h264Thread.setPriority(Thread.MAX_PRIORITY);
        h264Thread.start();
        //启动音频编码线程
        audioRecord.startRecording();
        aacThread = new Thread(aacRunnable);
        aacThread.setPriority(Thread.MAX_PRIORITY);
        aacThread.start();

    }



    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
         degress=getDisplayOritation(getDispalyRotation(),0);
        //打开摄像头
        if(camera==null){
            camera=Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT);
        }
        initCamera();
    }


    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {
       camera.autoFocus(new Camera.AutoFocusCallback() {
           @Override
           public void onAutoFocus(boolean b, Camera camera) {
               if(b){
                   initCamera();
               }
           }
       });
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {

    }

    @Override   //camera PreviewFrame
    public void onPreviewFrame(byte[] data, android.hardware.Camera camera) {
       if(!isPushing){
           return;
       }
        //格式转换
        byte[] yuv420Data=null;
        if(codecType==ImageFormat.YV12){
            yuv420Data=new byte[data.length];
            swVideoEncoder.swapYV12toI420_Ex(data,yuv420Data,HEIGHT,WIDTH);
        }else{
            yuv420Data = swVideoEncoder.swapNV21toI420(data, HEIGHT, WIDTH);

        }
        if(yuv420Data==null){
            return;
        }
        //清除帧数据
        queueLock.lock();
        if (queue.size() > 0) {
            queue.clear();
        }
        //加入新的帧数据
        queue.offer(yuv420Data);
        queueLock.unlock();
    }

    /**
     * 初始化摄像头
     */
    private void initCamera() {
        Camera.Parameters parameters = camera.getParameters();//获取摄像头的参数

        //设置格式
        List<Integer> previewFormats = parameters.getSupportedPreviewFormats();//获取摄像头支持的所有格式
        int flagNv21 = 0, flagYv12 = 0;
        for (int f : previewFormats) {
            if (ImageFormat.YV12 == f) {
                flagYv12 = f;
            }
            if (ImageFormat.NV21 == f) {
                flagNv21 = f;
            }
        }

        if (flagNv21 != 0) {
            codecType = flagNv21;
        } else if (flagYv12 != 0) {
            codecType = flagYv12;
        }

        parameters.setPreviewSize(HEIGHT, WIDTH);//这里两个参数是反的，否则像素比例失真
        parameters.setPreviewFormat(codecType);//设置预览格式
        parameters.setPreviewFrameRate(FRAMERATE);
        degress = getDisplayOritation(getDispalyRotation(), 0);
        parameters.setRotation(degress);

        camera.setParameters(parameters);//重设参数
        camera.setDisplayOrientation(degress);

        camera.setPreviewCallback(this);//设置预览回调，很重要
        try {
            camera.setPreviewDisplay(holder);//设置预览设备
        } catch (IOException e) {
            e.printStackTrace();
        }
        camera.cancelAutoFocus();//这一句可以自动对焦
        camera.startPreview();//开始预览
    }


    /**
     * 获取摄像头旋转角度
     *
     * @param degrees
     * @param cameraId
     * @return
     */
    private int getDisplayOritation(int degrees, int cameraId) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        int result = 0;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (info.orientation - degrees + 360) % 360;
        }
        return result;
    }

    /**
     * 获取屏幕的旋转角度
     *
     * @return
     */
    private int getDispalyRotation() {
        int i = getWindowManager().getDefaultDisplay().getRotation();
        switch (i) {
            case Surface.ROTATION_0:
                return 0;
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
        }
        return 0;
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if(isPushing){
           stopPush();
        }
    }

    private void stopPush() {
        isPushing = false;//修改状态

        h264Thread.interrupt();//中断线程
        aacThread.interrupt();

        audioRecord.stop();//停止编码
        swVideoEncoder.stop();

        rtmpSessionManager.Stop();//终止会话

        queueLock.lock();//清除帧数据
        queue.clear();
        queueLock.unlock();
    }
}
