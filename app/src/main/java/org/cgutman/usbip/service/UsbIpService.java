package org.cgutman.usbip.service;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.cgutman.usbip.config.UsbIpConfig;
import org.cgutman.usbip.server.UsbDeviceInfo;
import org.cgutman.usbip.server.UsbIpServer;
import org.cgutman.usbip.server.UsbRequestHandler;
import org.cgutman.usbip.server.protocol.ProtoDefs;
import org.cgutman.usbip.server.protocol.UsbIpDevice;
import org.cgutman.usbip.server.protocol.UsbIpInterface;
import org.cgutman.usbip.server.protocol.dev.UsbIpDevicePacket;
import org.cgutman.usbip.server.protocol.dev.UsbIpSubmitUrb;
import org.cgutman.usbip.server.protocol.dev.UsbIpSubmitUrbReply;
import org.cgutman.usbip.server.protocol.dev.UsbIpUnlinkUrb;
import org.cgutman.usbip.server.protocol.dev.UsbIpUnlinkUrbReply;
import org.cgutman.usbip.usb.UsbControlHelper;
import org.cgutman.usbip.usb.UsbDeviceDescriptor;
import org.cgutman.usbip.usb.XferUtils;
import org.cgutman.usbipserverforandroid.R;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.SparseArray;

import androidx.core.app.NotificationCompat;

public class UsbIpService extends Service implements UsbRequestHandler {

	private UsbManager usbManager;

	private SparseArray<AttachedDeviceContext> connections;
	private SparseArray<Boolean> permission;
	private HashMap<Socket, AttachedDeviceContext> socketMap;
	private UsbIpServer server;
	private WakeLock cpuWakeLock;
	private WifiLock highPerfWifiLock;
	private WifiLock lowLatencyWifiLock;

	private static final boolean DEBUG = true; // Set to true for enhanced logging during testing

	private static final int NOTIFICATION_ID = 100;

	private final static String CHANNEL_ID = "serviceInfo";

	private static final String ACTION_USB_PERMISSION = "org.cgutman.usbip.USB_PERMISSION";
	private PendingIntent usbPermissionIntent;
	private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			System.out.println("USB BroadcastReceiver received action: " + action);

			if (ACTION_USB_PERMISSION.equals(action)) {
				UsbDevice dev = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
				System.out.println("USB permission response for device: " +
						(dev != null ? dev.getDeviceName() : "null"));

				synchronized (dev) {
					boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
					permission.put(dev.getDeviceId(), granted);
					System.out.println("Permission " + (granted ? "GRANTED" : "DENIED") + " for device " +
							(dev != null ? dev.getDeviceName() : "unknown"));
					dev.notifyAll();
				}
			} else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
				UsbDevice dev = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
				System.out.println(" USB Device ATTACHED: "
						+ (dev != null ? dev.getDeviceName() + " (" + dev.getVendorId() + ":" + dev.getProductId() + ")"
								: "null"));
				if (dev != null) {
					System.out.println("Device details: VID=" + dev.getVendorId() + " PID=" + dev.getProductId() +
							" Name=" + dev.getDeviceName() + " Class=" + dev.getDeviceClass() +
							" Subclass=" + dev.getDeviceSubclass() + " Protocol=" + dev.getDeviceProtocol());

					if (!usbManager.hasPermission(dev)) {
						System.out.println("Requesting permission for device: " + dev.getDeviceName());
						permission.put(dev.getDeviceId(), null);
						usbManager.requestPermission(dev, usbPermissionIntent);
					} else {
						System.out.println("Device already has permission: " + dev.getDeviceName());
					}
				} else {
					System.out.println("ERROR: No device information in attach event");
				}
			} else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
				UsbDevice dev = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
				System.out.println("USB Device DETACHED: " + (dev != null ? dev.getDeviceName() : "null"));
				if (dev != null) {
					cleanupDetachedDevice(dev.getDeviceId());
				}
			} else {
				System.out.println("Unknown action received: " + action);
			}
		}
	};

	private void updateNotification() {
		Intent intent = new Intent(this, UsbIpConfig.class);
		intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

		int intentFlags = 0;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			intentFlags |= PendingIntent.FLAG_IMMUTABLE;
		}

		PendingIntent pendIntent = PendingIntent.getActivity(this, 0, intent, intentFlags);

		NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
				.setSmallIcon(R.drawable.notification_icon)
				.setOngoing(true)
				.setSilent(true)
				.setTicker("USB/IP Server Running")
				.setContentTitle("USB/IP Server Running")
				.setAutoCancel(false)
				.setContentIntent(pendIntent)
				.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);

		if (connections.size() == 0) {
			builder.setContentText("No devices currently shared");
		} else {
			builder.setContentText(String.format("Sharing %d device(s)", connections.size()));
		}

		startForeground(NOTIFICATION_ID, builder.build());
	}

	@SuppressLint({ "UseSparseArrays", "UnspecifiedRegisterReceiverFlag" })
	@Override
	public void onCreate() {
		super.onCreate();

		usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
		connections = new SparseArray<>();
		permission = new SparseArray<>();
		socketMap = new HashMap<>();

		int intentFlags = 0;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			// This PendingIntent must be mutable to allow the framework to populate
			// EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED.
			intentFlags |= PendingIntent.FLAG_MUTABLE;
		}

		Intent i = new Intent(ACTION_USB_PERMISSION);
		i.setPackage(getPackageName());

		usbPermissionIntent = PendingIntent.getBroadcast(this, 0, i, intentFlags);
		IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED);
		} else {
			registerReceiver(usbReceiver, filter);
		}

		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

		cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "USBIPServerForAndroid:Service");
		cpuWakeLock.acquire();

		highPerfWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "USBIPServerForAndroid:Service:HP");
		highPerfWifiLock.acquire();

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			lowLatencyWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
					"USBIPServerForAndroid:Service:LL");
			lowLatencyWifiLock.acquire();
		}

		server = new UsbIpServer();
		server.start(this);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Service Info",
					NotificationManager.IMPORTANCE_DEFAULT);
			NotificationManager notificationManager = getSystemService(NotificationManager.class);
			notificationManager.createNotificationChannel(channel);
		}

		updateNotification();
	}

	public void onDestroy() {
		super.onDestroy();

		server.stop();
		unregisterReceiver(usbReceiver);

		if (lowLatencyWifiLock != null) {
			lowLatencyWifiLock.release();
		}
		highPerfWifiLock.release();
		cpuWakeLock.release();
	}

	@Override
	public IBinder onBind(Intent intent) {
		// Not currently bindable
		return null;
	}

	// Here we're going to enumerate interfaces and endpoints
	// to eliminate possible speeds until we've narrowed it
	// down to only 1 which is our speed real speed. In a typical
	// USB driver, the host controller knows the real speed but
	// we need to derive it without HCI help.
	private final static int FLAG_POSSIBLE_SPEED_LOW = 0x01;
	private final static int FLAG_POSSIBLE_SPEED_FULL = 0x02;
	private final static int FLAG_POSSIBLE_SPEED_HIGH = 0x04;
	private final static int FLAG_POSSIBLE_SPEED_SUPER = 0x08;

	private int detectSpeed(UsbDevice dev, UsbDeviceDescriptor devDesc) {
		int possibleSpeeds = FLAG_POSSIBLE_SPEED_LOW |
				FLAG_POSSIBLE_SPEED_FULL |
				FLAG_POSSIBLE_SPEED_HIGH |
				FLAG_POSSIBLE_SPEED_SUPER;

		for (int i = 0; i < dev.getInterfaceCount(); i++) {
			UsbInterface iface = dev.getInterface(i);
			for (int j = 0; j < iface.getEndpointCount(); j++) {
				UsbEndpoint endpoint = iface.getEndpoint(j);
				if ((endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) ||
						(endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC)) {
					// Low speed devices can't implement bulk or iso endpoints
					possibleSpeeds &= ~FLAG_POSSIBLE_SPEED_LOW;
				}

				if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_CONTROL) {
					if (endpoint.getMaxPacketSize() > 8) {
						// Low speed devices can't use control transfer sizes larger than 8 bytes
						possibleSpeeds &= ~FLAG_POSSIBLE_SPEED_LOW;
					}
					if (endpoint.getMaxPacketSize() < 64) {
						// High speed devices can't use control transfer sizes smaller than 64 bytes
						possibleSpeeds &= ~FLAG_POSSIBLE_SPEED_HIGH;
					}
					if (endpoint.getMaxPacketSize() < 512) {
						// Super speed devices can't use control transfer sizes smaller than 512 bytes
						possibleSpeeds &= ~FLAG_POSSIBLE_SPEED_SUPER;
					}
				} else if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) {
					if (endpoint.getMaxPacketSize() > 8) {
						// Low speed devices can't use interrupt transfer sizes larger than 8 bytes
						possibleSpeeds &= ~FLAG_POSSIBLE_SPEED_LOW;
					}
					if (endpoint.getMaxPacketSize() > 64) {
						// Full speed devices can't use interrupt transfer sizes larger than 64 bytes
						possibleSpeeds &= ~FLAG_POSSIBLE_SPEED_FULL;
					}
					if (endpoint.getMaxPacketSize() > 512) {
						// High speed devices can't use interrupt transfer sizes larger than 512 bytes
						possibleSpeeds &= ~FLAG_POSSIBLE_SPEED_HIGH;
					}
				} else if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
					// A bulk endpoint alone can accurately distiniguish between
					// full, high, and super speed devices
					switch (endpoint.getMaxPacketSize()) {
						case 512:
							// High speed devices can only use 512 byte bulk transfers
							possibleSpeeds = FLAG_POSSIBLE_SPEED_HIGH;
							break;
						case 1024:
							// Super speed devices can only use 1024 byte bulk transfers
							possibleSpeeds = FLAG_POSSIBLE_SPEED_SUPER;
							break;
						default:
							// Otherwise it must be full speed
							possibleSpeeds = FLAG_POSSIBLE_SPEED_FULL;
							break;
					}
				}
			}
		}

		if (devDesc != null) {
			if (devDesc.bcdUSB < 0x200) {
				// High speed only supported on USB 2.0 or higher
				possibleSpeeds &= ~FLAG_POSSIBLE_SPEED_HIGH;
			}
			if (devDesc.bcdUSB < 0x300) {
				// Super speed only supported on USB 3.0 or higher
				possibleSpeeds &= ~FLAG_POSSIBLE_SPEED_SUPER;
			}
		}

		// Return the lowest speed that we're compatible with
		System.out.printf("Speed heuristics for device %d left us with 0x%x\n",
				dev.getDeviceId(), possibleSpeeds);

		if ((possibleSpeeds & FLAG_POSSIBLE_SPEED_LOW) != 0) {
			return UsbIpDevice.USB_SPEED_LOW;
		} else if ((possibleSpeeds & FLAG_POSSIBLE_SPEED_FULL) != 0) {
			return UsbIpDevice.USB_SPEED_FULL;
		} else if ((possibleSpeeds & FLAG_POSSIBLE_SPEED_HIGH) != 0) {
			return UsbIpDevice.USB_SPEED_HIGH;
		} else if ((possibleSpeeds & FLAG_POSSIBLE_SPEED_SUPER) != 0) {
			return UsbIpDevice.USB_SPEED_SUPER;
		} else {
			// Something went very wrong in speed detection
			return UsbIpDevice.USB_SPEED_UNKNOWN;
		}
	}

	private static int deviceIdToBusNum(int deviceId) {
		return deviceId / 1000;
	}

	private static int deviceIdToDevNum(int deviceId) {
		return deviceId % 1000;
	}

	private static int devIdToDeviceId(int devId) {
		// This is the same algorithm as Android uses
		return ((devId >> 16) & 0xFF) * 1000 + (devId & 0xFF);
	}

	private static int busIdToBusNum(String busId) {
		if (busId.indexOf('-') == -1) {
			return -1;
		}

		return Integer.parseInt(busId.substring(0, busId.indexOf('-')));
	}

	private static int busIdToDevNum(String busId) {
		if (busId.indexOf('-') == -1) {
			return -1;
		}

		return Integer.parseInt(busId.substring(busId.indexOf('-') + 1));
	}

	private static int busIdToDeviceId(String busId) {
		return devIdToDeviceId(((busIdToBusNum(busId) << 16) & 0xFF0000) | busIdToDevNum(busId));
	}

	private UsbDeviceInfo getInfoForDevice(UsbDevice dev, UsbDeviceConnection devConn) {
		UsbDeviceInfo info = new UsbDeviceInfo();
		UsbIpDevice ipDev = new UsbIpDevice();

		ipDev.path = dev.getDeviceName();
		ipDev.busnum = deviceIdToBusNum(dev.getDeviceId());
		ipDev.devnum = deviceIdToDevNum(dev.getDeviceId());
		ipDev.busid = String.format("%d-%d", ipDev.busnum, ipDev.devnum);

		ipDev.idVendor = (short) dev.getVendorId();
		ipDev.idProduct = (short) dev.getProductId();
		ipDev.bcdDevice = -1;

		ipDev.bDeviceClass = (byte) dev.getDeviceClass();
		ipDev.bDeviceSubClass = (byte) dev.getDeviceSubclass();
		ipDev.bDeviceProtocol = (byte) dev.getDeviceProtocol();

		ipDev.bConfigurationValue = 0;
		ipDev.bNumConfigurations = (byte) dev.getConfigurationCount();

		ipDev.bNumInterfaces = (byte) dev.getInterfaceCount();

		info.dev = ipDev;
		info.interfaces = new UsbIpInterface[ipDev.bNumInterfaces];

		for (int i = 0; i < ipDev.bNumInterfaces; i++) {
			info.interfaces[i] = new UsbIpInterface();
			UsbInterface iface = dev.getInterface(i);

			info.interfaces[i].bInterfaceClass = (byte) iface.getInterfaceClass();
			info.interfaces[i].bInterfaceSubClass = (byte) iface.getInterfaceSubclass();
			info.interfaces[i].bInterfaceProtocol = (byte) iface.getInterfaceProtocol();
		}

		AttachedDeviceContext context = connections.get(dev.getDeviceId());
		UsbDeviceDescriptor devDesc = null;
		if (context != null) {
			// Since we're attached already, we can directly query the USB descriptors
			// to fill some information that Android's USB API doesn't expose
			devDesc = UsbControlHelper.readDeviceDescriptor(context.devConn);
			if (devDesc != null) {
				ipDev.bcdDevice = devDesc.bcdDevice;
			}
		}

		ipDev.speed = detectSpeed(dev, devDesc);

		return info;
	}

	@Override
	public List<UsbDeviceInfo> getDevices() {
		ArrayList<UsbDeviceInfo> list = new ArrayList<>();

		for (UsbDevice dev : usbManager.getDeviceList().values()) {
			AttachedDeviceContext context = connections.get(dev.getDeviceId());
			UsbDeviceConnection devConn = null;
			if (context != null) {
				devConn = context.devConn;
			}

			list.add(getInfoForDevice(dev, devConn));
		}

		return list;
	}

	public static void dumpInterfaces(UsbDevice dev) {
		for (int i = 0; i < dev.getInterfaceCount(); i++) {
			System.out.printf("%d - Iface %d (%02x/%02x/%02x)\n",
					i, dev.getInterface(i).getId(),
					dev.getInterface(i).getInterfaceClass(),
					dev.getInterface(i).getInterfaceSubclass(),
					dev.getInterface(i).getInterfaceProtocol());

			UsbInterface iface = dev.getInterface(i);
			for (int j = 0; j < iface.getEndpointCount(); j++) {
				System.out.printf("\t%d - Endpoint %d (%x/%x)\n",
						j, iface.getEndpoint(j).getEndpointNumber(),
						iface.getEndpoint(j).getAddress(),
						iface.getEndpoint(j).getAttributes());
			}
		}
	}

	private static void sendReply(Socket s, UsbIpSubmitUrbReply reply, int status) {
		reply.status = status;
		try {
			// We need to synchronize to avoid writing on top of ourselves
			synchronized (s) {
				s.getOutputStream().write(reply.serialize());
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private static void sendReply(Socket s, UsbIpUnlinkUrbReply reply, int status) {
		reply.status = status;
		try {
			// We need to synchronize to avoid writing on top of ourselves
			synchronized (s) {
				s.getOutputStream().write(reply.serialize());
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// FIXME: This dispatching could use some refactoring so we don't have to pass
	// a million parameters to this guy
	private void dispatchRequest(final AttachedDeviceContext context, final int deviceId, final Socket s,
			final UsbEndpoint selectedEndpoint, final ByteBuffer buff, final UsbIpSubmitUrb msg) {
		context.requestPool.submit(new Runnable() {
			@Override
			public void run() {
				UsbIpSubmitUrbReply reply = new UsbIpSubmitUrbReply(msg.seqNum,
						msg.devId, msg.direction, msg.ep);

				if (msg.direction == UsbIpDevicePacket.USBIP_DIR_IN) {
					// We need to store our buffer in the URB reply
					reply.inData = buff.array();
				}

				if (selectedEndpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
					if (DEBUG) {
						System.out.printf("Bulk transfer - %d bytes %s on EP %d\n",
								buff.array().length, msg.direction == UsbIpDevicePacket.USBIP_DIR_IN ? "in" : "out",
								selectedEndpoint.getEndpointNumber());
					}

					// Special handling for mass storage bulk transfers
					if (context.device.getDeviceClass() == UsbConstants.USB_CLASS_MASS_STORAGE
							|| hasMscInterface(context)) {
						handleMassStorageBulkXfer(context, deviceId, s, selectedEndpoint, buff, msg);
						return; // HandleMassStorageBulkXfer will send the reply
					}

					int res;
					do {
						res = XferUtils.doBulkTransfer(context.devConn, selectedEndpoint, buff.array(), 1000);

						if (context.requestPool.isShutdown()) {
							// Bail if the queue is being torn down
							return;
						}

						if (!context.activeMessages.contains(msg)) {
							// Somebody cancelled the URB, return without responding
							return;
						}
					} while (res == -110); // ETIMEDOUT

					if (DEBUG) {
						System.out.printf("Bulk transfer complete with %d bytes (wanted %d)\n",
								res, msg.transferBufferLength);
					}

					if (!context.activeMessages.remove(msg)) {
						// Somebody cancelled the URB, return without responding
						return;
					}

					if (res < 0) {
						// If the request failed, let's see if the device is still around
						UsbDevice dev = getDevice(deviceId);
						if (dev == null) {
							// The device is gone, so terminate the client
							server.killClient(s);
							return;
						}

						reply.status = res;
					} else {
						reply.actualLength = res;
						reply.status = ProtoDefs.ST_OK;
					}

					sendReply(s, reply, reply.status);
				} else if (selectedEndpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) {
					if (DEBUG) {
						System.out.printf("Interrupt transfer - %d bytes %s on EP %d\n",
								msg.transferBufferLength,
								msg.direction == UsbIpDevicePacket.USBIP_DIR_IN ? "in" : "out",
								selectedEndpoint.getEndpointNumber());
					}

					int res;
					do {
						res = XferUtils.doInterruptTransfer(context.devConn, selectedEndpoint, buff.array(), 1000);

						if (context.requestPool.isShutdown()) {
							// Bail if the queue is being torn down
							return;
						}

						if (!context.activeMessages.contains(msg)) {
							// Somebody cancelled the URB, return without responding
							return;
						}
					} while (res == -110); // ETIMEDOUT

					if (DEBUG) {
						System.out.printf("Interrupt transfer complete with %d bytes (wanted %d)\n",
								res, msg.transferBufferLength);
					}

					if (!context.activeMessages.remove(msg)) {
						// Somebody cancelled the URB, return without responding
						return;
					}

					if (res < 0) {
						reply.status = res;

						// If the request failed, let's see if the device is still around
						UsbDevice dev = getDevice(deviceId);
						if (dev == null) {
							// The device is gone, so terminate the client
							server.killClient(s);
							return;
						}
					} else {
						reply.actualLength = res;
						reply.status = ProtoDefs.ST_OK;
					}

					sendReply(s, reply, reply.status);
				} else {
					System.err.println("Unsupported endpoint type: " + selectedEndpoint.getType());
					context.activeMessages.remove(msg);
					server.killClient(s);
				}
			}
		});
	}

	@Override
	public void submitUrbRequest(Socket s, UsbIpSubmitUrb msg) {
		UsbIpSubmitUrbReply reply = new UsbIpSubmitUrbReply(msg.seqNum,
				msg.devId, msg.direction, msg.ep);

		int deviceId = devIdToDeviceId(msg.devId);

		AttachedDeviceContext context = connections.get(deviceId);
		if (context == null) {
			// This should never happen, but kill the connection if it does
			server.killClient(s);
			return;
		}

		UsbDeviceConnection devConn = context.devConn;

		// Control endpoint is handled with a special case
		if (msg.ep == 0) {
			// This is little endian
			ByteBuffer bb = ByteBuffer.wrap(msg.setup).order(ByteOrder.LITTLE_ENDIAN);

			byte requestType = bb.get();
			byte request = bb.get();
			short value = bb.getShort();
			short index = bb.getShort();
			short length = bb.getShort();

			if (length != 0) {
				reply.inData = new byte[length];
			}

			// This message is now active
			context.activeMessages.add(msg);

			int res;

			// We have to handle certain control requests (SET_CONFIGURATION/SET_INTERFACE)
			// by calling
			// Android APIs rather than just submitting the URB directly to the device
			if (!UsbControlHelper.handleInternalControlTransfer(context, requestType, request, value, index)) {
				do {
					res = XferUtils.doControlTransfer(devConn, requestType, request, value, index,
							(requestType & 0x80) != 0 ? reply.inData : msg.outData, length, 1000);

					if (context.requestPool.isShutdown()) {
						// Bail if the queue is being torn down
						return;
					}

					if (!context.activeMessages.contains(msg)) {
						// Somebody cancelled the URB, return without responding
						return;
					}
				} while (res == -110); // ETIMEDOUT
			} else {
				// Handled the request internally
				res = 0;
			}

			if (!context.activeMessages.remove(msg)) {
				// Somebody cancelled the URB, return without responding
				return;
			}

			if (res < 0) {
				// If the request failed, let's see if the device is still around
				UsbDevice dev = getDevice(deviceId);
				if (dev == null) {
					// The device is gone, so terminate the client
					server.killClient(s);
					return;
				}

				reply.status = res;
			} else {
				reply.actualLength = res;
				reply.status = ProtoDefs.ST_OK;
			}

			sendReply(s, reply, reply.status);
		} else {
			// Find the correct endpoint
			UsbEndpoint selectedEndpoint = null;
			if (context.activeConfigurationEndpointsByNumDir != null) {
				int endptNumDir = msg.ep
						+ (msg.direction == UsbIpDevicePacket.USBIP_DIR_IN ? UsbConstants.USB_DIR_IN : 0);
				selectedEndpoint = context.activeConfigurationEndpointsByNumDir.get(endptNumDir);
			} else {
				System.err.println("Attempted to transfer to non-control EP before SET_CONFIGURATION!");
			}

			if (selectedEndpoint == null) {
				System.err.println("EP not found: " + msg.ep);
				sendReply(s, reply, ProtoDefs.ST_NA);
				return;
			}

			ByteBuffer buff;
			if (msg.direction == UsbIpDevicePacket.USBIP_DIR_IN) {
				// The buffer is allocated by us
				buff = ByteBuffer.allocate(msg.transferBufferLength);
			} else {
				// The buffer came in with the request
				buff = ByteBuffer.wrap(msg.outData);
			}

			// This message is now active
			context.activeMessages.add(msg);

			// Dispatch this request asynchronously
			dispatchRequest(context, deviceId, s, selectedEndpoint, buff, msg);
		}
	}

	private UsbDevice getDevice(int deviceId) {
		for (UsbDevice dev : usbManager.getDeviceList().values()) {
			if (dev.getDeviceId() == deviceId) {
				return dev;
			}
		}

		return null;
	}

	private UsbDevice getDevice(String busId) {
		return getDevice(busIdToDeviceId(busId));
	}

	@Override
	public UsbDeviceInfo getDeviceByBusId(String busId) {
		UsbDevice dev = getDevice(busId);
		if (dev == null) {
			return null;
		}

		AttachedDeviceContext context = connections.get(dev.getDeviceId());
		UsbDeviceConnection devConn = null;
		if (context != null) {
			devConn = context.devConn;
		}

		return getInfoForDevice(dev, devConn);
	}

	@Override
	public boolean attachToDevice(Socket s, String busId) {
		UsbDevice dev = getDevice(busId);
		if (dev == null) {
			return false;
		}

		if (connections.get(dev.getDeviceId()) != null) {
			// Already attached
			return false;
		}

		// Removed HID device root handling - simplified to basic USB functionality
		if (!usbManager.hasPermission(dev)) {
			// Try to get permission from the user
			permission.put(dev.getDeviceId(), null);
			usbManager.requestPermission(dev, usbPermissionIntent);
			synchronized (dev) {
				while (permission.get(dev.getDeviceId()) == null) {
					try {
						dev.wait(1000);
					} catch (InterruptedException e) {
						return false;
					}
				}
			}

			// User may have rejected this
			if (!permission.get(dev.getDeviceId())) {
				return false;
			}
		}

		UsbDeviceConnection devConn = usbManager.openDevice(dev);
		if (devConn == null) {
			return false;
		}

		// Removed HID descriptor caching - simplified USB functionality

		// Attempt to wake/reset the device from low power states
		wakeDeviceIfNeeded(devConn, dev);

		// Create a context for this attachment
		AttachedDeviceContext context = new AttachedDeviceContext();
		context.devConn = devConn;
		context.device = dev;

		// Count all endpoints on all interfaces
		int endpointCount = 0;
		for (int i = 0; i < dev.getInterfaceCount(); i++) {
			endpointCount += dev.getInterface(i).getEndpointCount();
		}

		// Use a thread pool with a thread per endpoint
		context.requestPool = new ThreadPoolExecutor(endpointCount, endpointCount,
				Long.MAX_VALUE, TimeUnit.DAYS,
				new LinkedBlockingQueue<>(), new ThreadPoolExecutor.DiscardPolicy());

		// Create the active message set
		context.activeMessages = new HashSet<>();

		connections.put(dev.getDeviceId(), context);
		socketMap.put(s, context);

		updateNotification();
		return true;
	}

	/**
	 * Attempts to wake/reset a USB device from low power states (like D3)
	 * Enhanced version with multiple strategies, particularly useful for NVMe
	 * bridges
	 * and other power-managed devices that may sleep when idle.
	 */
	private void wakeDeviceIfNeeded(UsbDeviceConnection devConn, UsbDevice dev) {
		System.out.println("=== BEGINNING DEVICE WAKE SEQUENCE ===");
		System.out.println(
				"Target device: " + dev.getDeviceName() + " (" + dev.getVendorId() + ":" + dev.getProductId() + ")");

		int wakeAttempts = 0;
		boolean success = false;

		// Try wake sequence multiple times for stubborn devices
		for (int attempt = 1; attempt <= 3 && !success; attempt++) {
			System.out.println("\n--- Wake Attempt #" + attempt + " ---");
			wakeAttempts = attempt;

			try {
				// Strategy 1: USB Standardized Wake Commands
				System.out.println("1. Sending USB standard wake signals...");
				sendStandardWakeSignals(devConn);

				// Strategy 2: Force Configuration Reset
				System.out.println("2. Performing configuration reset...");
				resetDeviceConfiguration(devConn, dev);

				// Strategy 3: Interface Reconnection
				System.out.println("3. Forcing interface reconnection...");
				// For NVMe bridges, try mass storage specific commands
				if (dev.getVendorId() == 0x152d) { // JMicron vendor
					sendNvMeSpecificCommands(devConn, dev);
				}

				// Strategy 4: Multiple Vendor Wake Patterns
				System.out.println("4. Sending vendor-specific wake patterns...");
				sendVendorWakeCommands(devConn, dev);

				// Strategy 5: Aggressive Interface Reset
				System.out.println("5. Performing aggressive interface reset...");
				resetInterfaces(devConn, dev);

				// Wait for device to stabilize
				Thread.sleep(200);
				success = true; // If we reach here without exception, consider successful

			} catch (Exception e) {
				System.out.println("Wake attempt " + attempt + " encountered: " + e.getMessage());
				if (attempt == 3) {
					System.out.println("All wake attempts failed for this device");
				}
			}
		}

		System.out.println("=== WAKE SEQUENCE COMPLETED ===");
		System.out.println("Device: " + dev.getDeviceName());
		System.out.println("Attempts made: " + wakeAttempts);
		System.out.println("Status: " + (success ? "Completed with potential success" : "All attempts unsuccessful"));
		System.out.println("==========================================\n");
	}

	/**
	 * Sends USB standard wake signals like DEVICE_REMOTE_WAKEUP and PORT_RESET
	 */
	private void sendStandardWakeSignals(UsbDeviceConnection devConn) {
		// Device wake: SET_FEATURE DEVICE_REMOTE_WAKEUP
		int result1 = devConn.controlTransfer(0x00, // BM_REQUEST_DEVICE
				0x03, // SET_FEATURE
				0x01, // DEVICE_REMOTE_WAKEUP
				0x00, // Interface 0
				null, 0, 50);

		// Port reset: SET_FEATURE PORT_RESET
		int result2 = devConn.controlTransfer(0x23, // BM_REQUEST_PORT
				0x03, // SET_FEATURE
				0x04, // PORT_RESET
				0x00, // Interface 0
				null, 0, 50);

		System.out.println("Standard wake signals: DEVICE_WAKEUP=" + result1 + ", PORT_RESET=" + result2);
	}

	/**
	 * Forces a complete device configuration reset cycle
	 */
	private void resetDeviceConfiguration(UsbDeviceConnection devConn, UsbDevice dev) {
		if (dev.getConfigurationCount() > 0) {
			try {
				devConn.setConfiguration(dev.getConfiguration(0));
				System.out.println("configuration 0 force-set for wake");

				// Some devices need another configuration cycle
				Thread.sleep(50);
				devConn.setConfiguration(dev.getConfiguration(0));
				System.out.println("configuration cycle completed");

			} catch (IllegalArgumentException e) {
				System.out.println("Configuration reset: " + e.getMessage());
			} catch (InterruptedException e) {
				System.out.println("Configuration reset interrupted");
			}
		}
	}

	/**
	 * Sends JMicron NVMe bridge specific wake commands
	 */
	private void sendNvMeSpecificCommands(UsbDeviceConnection devConn, UsbDevice dev) {
		System.out.println("Sending JMicron NVMe specific commands...");

		// JMicron SCB (SCSI command block) wake sequence
		byte[] scbWake = new byte[] { 0x12, 0x01, 0x00, 0x00, 0x00, (byte) 0x80, 0x00, 0x00 };
		int scbResult = devConn.controlTransfer(
				0x21, // CLASS request OUT (mass storage)
				0xFE, // Wake/SCSI command
				0x00, // wValue
				0x00, // wIndex
				scbWake, 0, 100);

		// NVMe bridge initialization command
		byte[] nvmeInit = new byte[] { (byte) 0xE1, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };
		int nvmeResult = devConn.controlTransfer(
				0x40, // Vendor OUT
				0x0B, // NVMe init command (common pattern)
				0x00, // wValue
				0x00, // wIndex
				nvmeInit, 0, 100);

		System.out.println("NVMe commands: SCB_WAKE=" + scbResult + ", NVME_INIT=" + nvmeResult);
	}

	/**
	 * Sends multiple vendor-specific wake commands that different devices might
	 * respond to
	 */
	private void sendVendorWakeCommands(UsbDeviceConnection devConn, UsbDevice dev) {
		// Try common vendor wake patterns
		int[] vendorRequests = { 0x05, 0x06, 0x0B, 0xFF }; // Wake, Resume, NVMe Init, Vendor Cmd
		int[] vendorTypes = { 0x40, 0xC0 }; // OUT and IN directions

		for (int request : vendorRequests) {
			for (int type : vendorTypes) {
				try {
					int result = devConn.controlTransfer(type, request, 0, 0, null, 0, 30);
					if (result >= 0) {
						System.out.println("Vendor wake hit: 0x" + Integer.toHexString(type) +
								"/0x" + Integer.toHexString(request) + " = " + result);
					}
				} catch (Exception e) {
					// Ignore individual failures - expected for unsupported commands
				}
			}
		}
	}

	/**
	 * Performs an aggressive reset of USB interfaces with proper claiming/releasing
	 */
	private void resetInterfaces(UsbDeviceConnection devConn, UsbDevice dev) {
		for (int i = 0; i < dev.getInterfaceCount(); i++) {
			UsbInterface iface = dev.getInterface(i);
			try {
				// Force release then reclaim - this can wake some interfaces from D3
				devConn.releaseInterface(iface);
				Thread.sleep(20);
				devConn.claimInterface(iface, true);
				System.out.println("Reset interface " + i + " (force release/reclaim)");

				// Set alternate setting to trigger re-enumeration
				devConn.setInterface(iface);
				System.out.println("Set interface alternate setting " + i);

			} catch (Exception e) {
				System.out.println("Interface " + i + " reset error: " + e.getMessage());
			}
		}
	}

	/**
	 * Enhanced NTFS-aware handler for USB Mass Storage Class devices (NVMe bridges)
	 * Optimizes bulk transfers for complex filesystems like NTFS while maintaining
	 * FAT compatibility
	 */
	private void handleMassStorageBulkXfer(AttachedDeviceContext context, int deviceId, Socket s,
			UsbEndpoint selectedEndpoint, ByteBuffer buff, UsbIpSubmitUrb msg) {

		if (DEBUG) {
			System.out.printf("MSC Transfer: EP %d Direction %s Length %d\n",
					selectedEndpoint.getEndpointNumber(),
					msg.direction == UsbIpDevicePacket.USBIP_DIR_IN ? "IN" : "OUT",
					msg.transferBufferLength);
		}

		// NTFS-aware transfer optimization: Larger filesystem metadata requires bigger
		// chunks
		int transferSize = msg.transferBufferLength;

		// For large transfers (NTFS bulk data operations), use higher timeout and
		// adaptive retries
		final int timeout = transferSize > 65536 ? 5000 : 2000; // 5s for NTFS, 2s for FAT
		final int maxAttempts = transferSize > 131072 ? 5 : 3; // More retries for large NTFS operations

		int res = 0;
		int attempts = 0;
		boolean isNtfsStyleTransfer = transferSize > 65536; // NTFS typically needs larger transfers

		// NTFS-aware retry strategy with adaptive delays
		while (attempts < maxAttempts) {
			res = XferUtils.doBulkTransfer(context.devConn, selectedEndpoint, buff.array(), timeout);
			attempts++;

			// NTFS-specific error handling
			if (res == -110) { // ETIMEDOUT
				int delay = isNtfsStyleTransfer ? 200 : 50; // Longer delay for NTFS
				System.out.println("MSC Transfer timeout (NTFS=" + isNtfsStyleTransfer +
						"), retrying in " + delay + "ms... (attempt " + attempts + "/" + maxAttempts + ")");
				try {
					Thread.sleep(delay);
				} catch (InterruptedException e) {
					break;
				}
				continue;
			} else if (res == -5) { // EIO - Common with NTFS metadata access
				if (isNtfsStyleTransfer && attempts < maxAttempts - 1) {
					System.out.println("NTFS EIO error recovery, retrying... (attempt " + attempts + ")");
					try {
						Thread.sleep(100); // NTFS EIO recovery delay
					} catch (InterruptedException e) {
						break;
					}
					continue;
				}
				break;
			} else if (res < 0 && attempts >= maxAttempts) {
				System.out.println("MSC Transfer failed after " + attempts +
						" attempts: " + res + " (NTFS_optimized=" + isNtfsStyleTransfer + ")");
				break;
			} else {
				break; // Success or non-recoverable error
			}
		}

		if (!context.activeMessages.remove(msg)) {
			return; // Already responded
		}

		UsbIpSubmitUrbReply reply = new UsbIpSubmitUrbReply(msg.seqNum, msg.devId, msg.direction, msg.ep);

		if (res < 0) {
			// Enhanced error handling for mass storage devices
			UsbDevice dev = getDevice(deviceId);
			if (dev == null) {
				server.killClient(s);
				return;
			}

			// Convert to proper LINUX_USB_STATUS codes that Windows recognizes
			int status;
			switch (res) {
				case -1:
					status = -5;
					break; // EIO - USB I/O error
				case -19:
					status = -19;
					break; // ENODEV - Device disconnected
				case -12:
					status = -12;
					break; // ENOMEM - Out of memory
				case -100:
					status = -100;
					break; // ENETDOWN - Network unavailable
				case -110:
					status = -110;
					break; // ETIMEDOUT - Timeout
				default:
					status = res; // Pass through other errors
			}

			reply.status = status;
			reply.actualLength = 0;

			if (DEBUG) {
				System.out.printf("MSC Error: code=%d status=%d dev=%s\n", res, status, dev.getDeviceName());
			}

		} else {
			reply.actualLength = res;
			reply.status = ProtoDefs.ST_OK;

			// For IN transfers, include the data
			if (msg.direction == UsbIpDevicePacket.USBIP_DIR_IN) {
				// Ensure we only send the actual data length received
				byte[] responseData = new byte[res];
				System.arraycopy(buff.array(), 0, responseData, 0, res);
				reply.inData = responseData;
			}

			if (DEBUG) {
				System.out.printf("MSC Success: EP%d %s transferred %d bytes\n",
						selectedEndpoint.getEndpointNumber(),
						msg.direction == UsbIpDevicePacket.USBIP_DIR_IN ? "IN" : "OUT", res);
			}
		}

		sendReply(s, reply, reply.status);
	}

	/**
	 * Checks if the device has a mass storage class (MSC) interface
	 */
	private boolean hasMscInterface(AttachedDeviceContext context) {
		if (context.device == null) {
			return false;
		}

		for (int i = 0; i < context.device.getInterfaceCount(); i++) {
			UsbInterface iface = context.device.getInterface(i);
			if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE) {
				return true;
			}
		}

		return false;
	}

	// HID descriptor functionality removed for Android 15 compatibility

	// HID functionality removed for Android 15 compatibility

	private void cleanupDetachedDevice(int deviceId) {

		AttachedDeviceContext context = connections.get(deviceId);
		if (context == null) {
			return;
		}

		// Clear the this attachment's context
		connections.remove(deviceId);

		// Signal queue death
		context.requestPool.shutdownNow();

		// Release our claim to the interfaces
		for (int i = 0; i < context.device.getInterfaceCount(); i++) {
			context.devConn.releaseInterface(context.device.getInterface(i));
		}

		// Close the connection
		context.devConn.close();

		// Wait for the queue to die
		try {
			context.requestPool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
		} catch (InterruptedException e) {
		}

		updateNotification();
	}

	@Override
	public void detachFromDevice(Socket s, String busId) {
		UsbDevice dev = getDevice(busId);
		if (dev == null) {
			return;
		}

		cleanupDetachedDevice(dev.getDeviceId());
	}

	@Override
	public void cleanupSocket(Socket s) {
		AttachedDeviceContext context = socketMap.remove(s);
		if (context == null) {
			return;
		}

		cleanupDetachedDevice(context.device.getDeviceId());
	}

	@Override
	public void abortUrbRequest(Socket s, UsbIpUnlinkUrb msg) {
		AttachedDeviceContext context = socketMap.get(s);
		if (context == null) {
			return;
		}

		UsbIpUnlinkUrbReply reply = new UsbIpUnlinkUrbReply(msg.seqNum, msg.devId, msg.direction, msg.ep);

		boolean found = false;
		synchronized (context.activeMessages) {
			for (UsbIpSubmitUrb urbMsg : context.activeMessages) {
				if (msg.seqNumToUnlink == urbMsg.seqNum) {
					context.activeMessages.remove(urbMsg);
					found = true;
					break;
				}
			}
		}

		System.out.println("Removed URB? " + (found ? "yes" : "no"));
		sendReply(s, reply,
				found ? UsbIpSubmitUrb.USBIP_STATUS_URB_ABORTED : -22); // EINVAL
	}

}
