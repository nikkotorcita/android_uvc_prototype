package com.usbhost.camera;

import com.usbhost.camera.R.id;

import android.os.Bundle;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.ActionBar.LayoutParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

public class CameraPreview extends Activity implements OnClickListener{
	
	private static final String TAG = "UVC Camera Preview";
	private static final String ACTION_USB_PERMISSION = "com.usbhost.camera.USB_PERMISSION";
	private int mState = UvcConstants.STATE_DEVICE_NULL;
	
	CameraDevice mCamera;
	
	PendingIntent mPermissionIntent;
	
	Button cameraSwitch;
	TextView cameraStatus;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_camera_preview);
		
		cameraSwitch = (Button) findViewById(R.id.camera_switch);
		cameraStatus = (TextView) findViewById(R.id.camera_status);
		
		cameraSwitch.setOnClickListener(this);
		
		mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
	    IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
	    registerReceiver(mUsbReceiver, filter);
	    
	    mCamera = new CameraDevice(this);
	    RelativeLayout previewLayout = (RelativeLayout) findViewById(R.id.preview_area);
	    mCamera.setRenderingView(previewLayout);
	}
	
	@Override
	public void onResume() {
		super.onResume();
	}
	
	@Override
	public void onPause() {
		super.onPause();
		mCamera.stopStreaming();
		Log.v(TAG, "here inside onPause()");
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		mCamera.close();
		unregisterReceiver(mUsbReceiver);
	}
	
	@Override
	public void onClick(View v) {
		if(v.getId() == R.id.camera_switch) {
			switch(mState) {
			case UvcConstants.STATE_DEVICE_NULL:
				scanDevice();
				break;
			case UvcConstants.STATE_DEVICE_FOUND:
				connect();
				break;
			case UvcConstants.STATE_CONNECTED:
				stopPreview();
				break;
			}
		}
	}
	
	private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
		
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if(ACTION_USB_PERMISSION.equals(action)) {
				synchronized(this) {
					if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
						mCamera.isPermitted = true;
					}
					else
						mCamera.isPermitted = false;
				}
			}
		}
	};
	
	private void scanDevice() {
		if(mCamera.scan()) {
			cameraStatus.setText("UVC camera found");
			cameraSwitch.setText("START PREVIEW");
			mState = UvcConstants.STATE_DEVICE_FOUND;
		}
		else {
			cameraStatus.setText("No UVC camera found");
		}
		Log.v(TAG, "scanDevice() mState = " + mState);
	}
	
	private void connect() {
		if(mCamera.connectToDevice()) {
			mState = UvcConstants.STATE_CONNECTED;
			cameraStatus.setText("UVC camera streaming");
		}
		else {
			mState = UvcConstants.STATE_DEVICE_NULL;
			cameraStatus.setText("Error connecting to UVC device");
			cameraSwitch.setText("SCAN DEVICE");
		}
		Log.v(TAG, "connect() mState = " + mState);
	}
	
	private void stopPreview() {
		
	}
}
