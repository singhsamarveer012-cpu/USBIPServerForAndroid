package org.cgutman.usbip.config;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.cgutman.usbipserverforandroid.R;

import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder> {

    private final Context context;
    private final List<DeviceInfo> deviceList;

    public DeviceAdapter(Context context, List<DeviceInfo> deviceList) {
        this.context = context;
        this.deviceList = deviceList;
    }

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context)
                .inflate(R.layout.item_device, parent, false);
        return new DeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        DeviceInfo device = deviceList.get(position);
        holder.bind(device);
    }

    @Override
    public int getItemCount() {
        return deviceList.size();
    }

    public static class DeviceViewHolder extends RecyclerView.ViewHolder {
        private final ImageView deviceIcon;
        private final TextView deviceName;
        private final TextView deviceInfo;
        private final TextView deviceStatus;
        private final View statusIndicator;

        public DeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            deviceIcon = itemView.findViewById(R.id.deviceIcon);
            deviceName = itemView.findViewById(R.id.deviceName);
            deviceInfo = itemView.findViewById(R.id.deviceInfo);
            deviceStatus = itemView.findViewById(R.id.deviceStatus);
            statusIndicator = itemView.findViewById(R.id.statusIndicator);
        }

        public void bind(DeviceInfo device) {
            // Set device name
            deviceName.setText(device.getName());

            // Set device info (Vendor/Product IDs)
            deviceInfo.setText(String.format("VID: %s, PID: %s • %s",
                    device.getVendorId(),
                    device.getProductId(),
                    device.getDeviceClass()));

            // Set connection status and color coding
            if (device.isConnected()) {
                deviceStatus.setText(R.string.device_connected);
                deviceStatus.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.connected_green));
                setStatusIndicatorColor(R.color.connected_green);
                deviceIcon.setColorFilter(
                        ContextCompat.getColor(itemView.getContext(), getDeviceIconColor(device.getDeviceClass())));
            } else {
                deviceStatus.setText(R.string.device_not_connected);
                deviceStatus.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.disconnected_red));
                setStatusIndicatorColor(R.color.disconnected_red);
            }

            // Set device class icon
            deviceIcon.setImageResource(getDeviceIcon(device.getDeviceClass()));
        }

        private int getDeviceIcon(String deviceClass) {
            switch (deviceClass.toLowerCase()) {
                case "storage":
                    return android.R.drawable.ic_menu_recent_history;
                case "hid":
                    return android.R.drawable.ic_menu_help;
                case "communication":
                    return android.R.drawable.ic_dialog_dialer;
                case "audio":
                case "audio/video":
                    return android.R.drawable.ic_lock_silent_mode;
                case "video":
                    return android.R.drawable.ic_menu_camera;
                case "printer":
                    return android.R.drawable.ic_menu_send;
                case "imaging":
                case "physical":
                    return android.R.drawable.ic_menu_report_image;
                default:
                    return android.R.drawable.ic_menu_gallery;
            }
        }

        private int getDeviceIconColor(String deviceClass) {
            switch (deviceClass.toLowerCase()) {
                case "storage":
                    return R.color.device_storage;
                case "hid":
                    return R.color.device_hid;
                case "communication":
                    return R.color.device_communication;
                case "audio":
                case "audio/video":
                    return R.color.device_input;
                default:
                    return R.color.device_unknown;
            }
        }

        private void setStatusIndicatorColor(int colorResId) {
            statusIndicator.getBackground().setTint(
                    ContextCompat.getColor(itemView.getContext(), colorResId));
        }
    }
}
