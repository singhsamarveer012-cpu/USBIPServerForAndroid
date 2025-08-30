package org.cgutman.usbip.config;

import android.hardware.usb.UsbDevice;

public class DeviceInfo {
    private String name;
    private String vendorId;
    private String productId;
    private String busId;
    private String deviceClass;
    private boolean isConnected;

    public DeviceInfo() {
    }

    public DeviceInfo(UsbDevice device, boolean isConnected) {
        this.name = device.getDeviceName();
        this.name = this.name.substring(this.name.lastIndexOf('/') + 1);
        this.vendorId = String.format("%04X", device.getVendorId());
        this.productId = String.format("%04X", device.getProductId());
        this.busId = String.format("%d-%d", device.getDeviceId() / 1000, device.getDeviceId() % 1000);
        this.deviceClass = getDeviceClassName(device.getDeviceClass());
        this.isConnected = isConnected;
    }

    private String getDeviceClassName(int deviceClass) {
        switch (deviceClass) {
            case 1:
                return "Audio";
            case 2:
                return "Communication";
            case 3:
                return "HID";
            case 5:
                return "Physical";
            case 6:
                return "Imaging";
            case 7:
                return "Printer";
            case 8:
                return "Storage";
            case 9:
                return "Hub";
            case 10:
                return "CDC-Data";
            case 11:
                return "Smart Card";
            case 13:
                return "Content Security";
            case 14:
                return "Video";
            case 15:
                return "Personal Healthcare";
            case 16:
                return "Audio/Video";
            case 220:
                return "Diagnostic";
            case 224:
                return "Wireless";
            case 239:
                return "Misc";
            case 254:
                return "Application";
            case 255:
                return "Vendor Spec";
            default:
                return "Unknown";
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVendorId() {
        return vendorId;
    }

    public void setVendorId(String vendorId) {
        this.vendorId = vendorId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getBusId() {
        return busId;
    }

    public void setBusId(String busId) {
        this.busId = busId;
    }

    public String getDeviceClass() {
        return deviceClass;
    }

    public void setDeviceClass(String deviceClass) {
        this.deviceClass = deviceClass;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public void setConnected(boolean connected) {
        isConnected = connected;
    }
}
