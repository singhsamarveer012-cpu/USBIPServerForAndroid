package org.cgutman.usbip.config;

import org.cgutman.usbip.service.UsbIpService;

import org.cgutman.usbipserverforandroid.R;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Modern eyecandy USB/IP configuration activity with connected devices list
public class UsbIpConfig extends AppCompatActivity {

	private Button serviceButton;
	private Button refreshButton;
	private TextView serviceReadyText;
	private TextView serverStatus;
	private ImageView serverIcon;
	private TextView noDevicesText;
	private RecyclerView devicesRecyclerView;

	private DeviceAdapter deviceAdapter;
	private List<DeviceInfo> deviceList;
	private UsbManager usbManager;
	private boolean running;
	private Handler handler;

	// Broadcast receivers for USB device events
	private BroadcastReceiver usbReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action) ||
					UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
				refreshDevices();
			}
		}
	};

	private ActivityResultLauncher<String> requestPermissionLauncher = registerForActivityResult(
			new ActivityResultContracts.RequestPermission(), isGranted -> {
				if (isGranted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
					startService(new Intent(UsbIpConfig.this, UsbIpService.class));
					running = true;
					updateStatus();
				}
			});

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Enable edge-to-edge display for modern look
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			getWindow().setDecorFitsSystemWindows(false);
			getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
			getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
		} else {
			getWindow().setFlags(
					android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
					android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
		}

		setContentView(R.layout.activity_usbip_config);

		// Initialize views
		initializeViews();

		// Initialize USB manager and device tracking
		usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
		deviceList = new ArrayList<>();
		handler = new Handler(Looper.getMainLooper());

		// Setup RecyclerView for device list
		setupDeviceList();

		// Register USB device notification receivers
		registerUSBReceivers();

		// Check service status and update UI
		checkServiceStatus();
		refreshDevices();

		// Setup button click listeners with animations
		setupButtonListeners();
	}

	private void initializeViews() {
		serviceButton = findViewById(R.id.serviceButton);
		refreshButton = findViewById(R.id.refreshButton);
		serviceReadyText = findViewById(R.id.serviceReadyText);
		serverIcon = findViewById(R.id.serverIcon);
		serverStatus = findViewById(R.id.serviceStatus);
		noDevicesText = findViewById(R.id.noDevicesText);
		devicesRecyclerView = findViewById(R.id.devicesRecyclerView);

	}

	private void setupDeviceList() {
		deviceAdapter = new DeviceAdapter(this, deviceList);
		devicesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
		devicesRecyclerView.setAdapter(deviceAdapter);

		// Add item decoration for spacing
		devicesRecyclerView.addItemDecoration(new androidx.recyclerview.widget.DividerItemDecoration(
				this, LinearLayoutManager.VERTICAL));
	}

	private void registerUSBReceivers() {
		IntentFilter usbFilter = new IntentFilter();
		usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
		usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
		registerReceiver(usbReceiver, usbFilter);
	}

	private void setupButtonListeners() {
		serviceButton.setOnClickListener(v -> toggleService());
		refreshButton.setOnClickListener(v -> {
			refreshDevices();
			animateRefreshButton();
		});
	}

	private void animateRefreshButton() {
		ObjectAnimator rotation = ObjectAnimator.ofFloat(refreshButton, "rotation", 0f, 360f);
		rotation.setDuration(500);
		rotation.start();
	}

	private void toggleService() {
		if (running) {
			stopService(new Intent(this, UsbIpService.class));
			running = false;
			Snackbar.make(findViewById(android.R.id.content),
					"USB/IP Service Stopped", Snackbar.ANIMATION_MODE_SLIDE).show();
		} else {
			if (ContextCompat.checkSelfPermission(this,
					Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
				startService(new Intent(this, UsbIpService.class));
				running = true;
				Snackbar.make(findViewById(android.R.id.content),
						"USB/IP Service Started", Snackbar.ANIMATION_MODE_SLIDE).show();
			} else {
				requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
				return;
			}
		}
		updateStatus();
		refreshDevices();
	}

	private void checkServiceStatus() {
		running = isServiceRunning(UsbIpService.class);
		updateStatus();
	}

	private boolean isServiceRunning(Class<?> serviceClass) {
		ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
		for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
			if (serviceClass.getName().equals(service.service.getClassName())) {
				return true;
			}
		}
		return false;
	}

	private void updateStatus() {
		if (running) {
			serverStatus.setText(R.string.server_running);
			serviceReadyText.setText(R.string.ready_text);
			serverIcon.setImageResource(android.R.drawable.ic_media_play);
			serverIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_running));
			serviceButton.setText(R.string.stop_service);
		} else {
			serverStatus.setText(R.string.server_stopped);
			serviceReadyText.setText(R.string.port_info);
			serverIcon.setImageResource(android.R.drawable.ic_media_pause);
			serverIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_stopped));
			serviceButton.setText(R.string.start_service);
		}

		// Animate server icon status change
		animateStatusChange();
	}

	private void animateStatusChange() {
		ObjectAnimator scaleX = ObjectAnimator.ofFloat(serverIcon, "scaleX", 1f, 1.2f, 1f);
		ObjectAnimator scaleY = ObjectAnimator.ofFloat(serverIcon, "scaleY", 1f, 1.2f, 1f);
		scaleX.setDuration(300);
		scaleY.setDuration(300);
		scaleX.start();
		scaleY.start();
	}

	private void refreshDevices() {
		deviceList.clear();

		// Get all connected USB devices
		Map<String, UsbDevice> deviceMap = usbManager.getDeviceList();
		for (UsbDevice device : deviceMap.values()) {
			// Check if device is currently connected to our service
			boolean isConnected = isMyServiceRunning(UsbIpService.class) &&
					isDeviceAttachedToService(device);

			DeviceInfo deviceInfo = new DeviceInfo(device, isConnected);
			deviceList.add(deviceInfo);
		}

		// Update UI
		updateDeviceList();
	}

	private boolean isDeviceAttachedToService(UsbDevice device) {
		// This is a simplified check - in a real implementation,
		// you'd communicate with the service to get exact attachment status
		// For now, we'll assume devices are "connected" when service is running
		return running;
	}

	private boolean isMyServiceRunning(Class<?> serviceClass) {
		return running;
	}

	private void updateDeviceList() {
		deviceAdapter.notifyDataSetChanged();

		if (deviceList.isEmpty()) {
			noDevicesText.setVisibility(View.VISIBLE);
			devicesRecyclerView.setVisibility(View.GONE);
		} else {
			noDevicesText.setVisibility(View.GONE);
			devicesRecyclerView.setVisibility(View.VISIBLE);
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		checkServiceStatus();
		refreshDevices();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (usbReceiver != null) {
			unregisterReceiver(usbReceiver);
		}
		if (handler != null) {
			handler.removeCallbacksAndMessages(null);
		}
	}
}
