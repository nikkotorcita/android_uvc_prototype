package com.usbhost.camera;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.apache.http.util.ByteArrayBuffer;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

class CameraDevice {
	
	private static final String TAG = "UVC Camera Device";
	private static final String ACTION_USB_PERMISSION = "com.usbhost.camera.USB_PERMISSION";
	private static int BYTES_PER_PIXEL = 2;
	private static int IMAGE_WIDTH = 640;
	private static int IMAGE_HEIGHT = 480;
	private static int IMAGE_BUFFER_SIZE = IMAGE_WIDTH * IMAGE_HEIGHT * BYTES_PER_PIXEL;
	private Context mContext;
	private BlockingQueue<byte[]> frameBufferQueue;
	public boolean isPermitted;
	public static int state;
	private boolean isStreaming = false;

	
	UsbManager mManager;
	UsbInterface mStreamingIntf;
	UsbDevice mDevice;
	UsbDeviceConnection mDeviceConnection;
	UsbEndpoint mBulkEpIn;
	UsbRequest mUsbRequest;
	
	CameraControls mCameraControls;
	
	PendingIntent mPermissionIntent;
	
	String deviceName;
	
	FrameGrabberThread frameGrabber;
	RenderingView renderingView;
	
	public CameraDevice(Context context) {
		mContext = context;
		mManager = (UsbManager)mContext.getSystemService(Context.USB_SERVICE);
		
		mPermissionIntent = PendingIntent.getBroadcast(mContext, 0, new Intent(ACTION_USB_PERMISSION), 0);
		
		frameBufferQueue = new ArrayBlockingQueue<byte[]>(3);
		frameGrabber = new FrameGrabberThread();
		renderingView = new RenderingView(mContext);
	}
	
	public void setRenderingView(RelativeLayout layout) {
		layout.addView(renderingView);
	}
	
	public void setDevice(UsbDevice device) {
		mDevice = device;
	}
	
	public boolean scan() {
		HashMap<String, UsbDevice> deviceList = mManager.getDeviceList();
		
		Iterator<UsbDevice> deviceIterator = deviceList.values().iterator();
		while(deviceIterator.hasNext()) {
			UsbDevice device = deviceIterator.next();
			for(int i = 0; i < device.getInterfaceCount(); i++) {
				UsbInterface intf = device.getInterface(i);
				
				if(intf.getInterfaceClass() == 14 && intf.getInterfaceSubclass() == 2 && intf.getInterfaceProtocol() == 0) {
					Log.v(TAG, "Found video streaming interface");
					mStreamingIntf = intf; 
					mBulkEpIn = mStreamingIntf.getEndpoint(0);
				}
			}
			
			if(mStreamingIntf != null) {
				mDevice = device;
				mManager.requestPermission(mDevice, mPermissionIntent);
				deviceName = device.getDeviceName().toString();
				return true;
			}
			
			else {
				mDevice = null;
			}
		}
		return false;
	}
	
	public boolean connectToDevice() {
		if(mManager.hasPermission(mDevice)) {
			mDeviceConnection = mManager.openDevice(mDevice);
			if(!mDeviceConnection.claimInterface(mStreamingIntf, false)) {
				return false;
			}
			mCameraControls = new CameraControls(mDeviceConnection);
			initUvc();
			frameGrabber.start();
			renderingView.start();
			isStreaming = true;
			return true;
		}
		return false;
	}
	
	public void stopStreaming() {
		if(isStreaming) {
			frameGrabber.mStop = true;
			try {
				frameGrabber.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			renderingView.stop();
			isStreaming = false;
		}
	}
	
	public void close() {
		mDeviceConnection.close();
		mDeviceConnection = null;
		
		mDevice = null;
	}
	
	private void initUvc() {
		
		Log.v(TAG, "scanning mode = " + mCameraControls.getScanMode());
		
		if(!mCameraControls.probeControl(UvcConstants.GET_CUR)) {
			Log.e(TAG, "error in probe control [GET_CUR]");
		}
		Log.v(TAG, "[1]control values = " + mCameraControls.formattedValues());
		
		mCameraControls.setDefault();
		
		//mCameraControls.setFrameIndex(UvcConstants.VGA);
		mCameraControls.setFormatIndex(2);
		
		if(!mCameraControls.probeControl(UvcConstants.SET_CUR)) {
			Log.e(TAG, "error in probe control [SET_CUR]");
		}
		if(!mCameraControls.probeControl(UvcConstants.GET_CUR)) {
			Log.e(TAG, "error in probe control [GET_CUR]");
		}
		Log.v(TAG, "[2]control values = " + mCameraControls.formattedValues());
		
		if(!mCameraControls.commitControl(UvcConstants.SET_CUR)) {
			Log.e(TAG, "error in commit control");
		}
		if(!mCameraControls.startStreaming()) {
			Log.e(TAG, "error starting streaming");
		}
	}
	
	private class FrameGrabberThread extends Thread {
		public boolean mStop = false;
		int cnt = 0;
		ByteArrayBuffer rawBuf = new ByteArrayBuffer(IMAGE_BUFFER_SIZE);
//		byte[] frameBuf = new byte[IMAGE_BUFFER_SIZE];
		byte[] frameBuf = null;
		byte[] epBuf = new byte[UvcConstants.BULK_TRANSFER_SIZE];
	
		public void run() {
			Log.v(TAG, "Starting FrameGrabber thread");
			while(!mStop) {
				if(cnt == 0) {
					do{
						cnt = mDeviceConnection.bulkTransfer(mBulkEpIn, epBuf, UvcConstants.BULK_TRANSFER_SIZE, 0);
						if(epBuf[0] == UvcConstants.PAYLOAD_HEADER_LENGTH && 
								(epBuf[1] == UvcConstants.PAYLOAD_HEADER_0 || epBuf[1] == UvcConstants.PAYLOAD_HEADER_1)) {
							break;
						}
					}while(true);
				}
				
				rawBuf.append(epBuf, UvcConstants.PAYLOAD_HEADER_LENGTH, cnt - UvcConstants.PAYLOAD_HEADER_LENGTH);
				do{
					cnt = mDeviceConnection.bulkTransfer(mBulkEpIn, epBuf, UvcConstants.BULK_TRANSFER_SIZE, 0);
					if(epBuf[0] == UvcConstants.PAYLOAD_HEADER_LENGTH && 
							(epBuf[1] == UvcConstants.PAYLOAD_HEADER_0 || epBuf[1] == UvcConstants.PAYLOAD_HEADER_1)) {
//						System.arraycopy(rawBuf.buffer(), 0, frameBuf, 0, IMAGE_BUFFER_SIZE);
						frameBuf = insertHuffman(rawBuf.buffer());
						if(frameBuf != null) {
							try {
								frameBufferQueue.put(frameBuf);
//								frameBufferQueue.put(rawBuf.toByteArray());
							
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
						}
						rawBuf.clear();
						break;
					}
					else {
						rawBuf.append(epBuf, 0, cnt);
					}
				}while(true);
			}
		}
	}
	
	private class RenderingView extends SurfaceView implements Runnable {
		int i = 0;
		Thread renderer = null;
		SurfaceHolder surfaceHolder;
		Canvas canvas;
		byte[] yuvData = new byte[IMAGE_BUFFER_SIZE];
		byte[] jpegData;
		YuvImage yuvImage;
		Bitmap bitmap;
		ByteArrayOutputStream imgStream = new ByteArrayOutputStream();
		volatile boolean running = false;
		
		private Rect rect = new Rect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
		private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		
		public RenderingView(Context context) {
			super(context);
			surfaceHolder = getHolder();
			surfaceHolder.setFixedSize(IMAGE_WIDTH, IMAGE_HEIGHT);
		}
		
		public void start() {
			running = true;
			renderer = new Thread(this);
			renderer.start();
			Log.v(TAG, "started renderer thread");
		}
		
		public void stop() {
			boolean retry = true;
			running = false;
			while(retry) {
				try {
					renderer.join();
					retry = false;
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		
		@Override
		public void run() {
			while(running) {
				try {
					jpegData = frameBufferQueue.take();
					Log.v(TAG, "hdr = " + Integer.toHexString(jpegData[0]) + " " + Integer.toHexString(jpegData[1]));
//					yuvData = frameBufferQueue.take();
//					yuvImage = new YuvImage(yuvData, ImageFormat.YUY2, IMAGE_WIDTH, IMAGE_HEIGHT, null);
//					yuvImage.compressToJpeg(rect, 50, imgStream);
//					bitmap = BitmapFactory.decodeByteArray(imgStream.toByteArray(), 0, imgStream.toByteArray().length);
					bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
					}
//					imgStream.reset();
					catch (InterruptedException e) {
					e.printStackTrace();
				}
				
				if(surfaceHolder.getSurface().isValid() && bitmap != null) {
					canvas = surfaceHolder.lockCanvas();
					canvas.drawBitmap(bitmap, 0, 0, paint);
					bitmap = null;
					surfaceHolder.unlockCanvasAndPost(canvas);
				}
			}
		}
	}
	
	private byte[] insertHuffman(byte[] mJpegData) {
		byte[] jpegData;
		int index = 0;
		for(int i = 0; i < mJpegData.length - 1; i++) {
			if(mJpegData[i] == (byte) 0xff && mJpegData[i + 1] == (byte) 0xda) {
				index = i;
			}
		}
		
		if(index > 0) {
			jpegData = new byte[UvcConstants.HUFFMAN_TABLE.length + mJpegData.length];
			System.arraycopy(mJpegData, 0, jpegData, 0, index);
			System.arraycopy(UvcConstants.HUFFMAN_TABLE, 0, jpegData, index, UvcConstants.HUFFMAN_TABLE.length);
			System.arraycopy(mJpegData, index, jpegData, UvcConstants.HUFFMAN_TABLE.length + index, mJpegData.length - index);
			return jpegData;
		}
		Log.v(TAG, "index is < 0");
		return null;
	}
}