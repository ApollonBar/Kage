package com.absinthe.kage.device;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.absinthe.kage.connect.UDP;
import com.absinthe.kage.device.model.DeviceConfig;
import com.absinthe.kage.device.model.DeviceInfo;
import com.absinthe.kage.protocol.IpMessageConst;
import com.absinthe.kage.protocol.IpMessageProtocol;
import com.absinthe.kage.utils.ToastUtil;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DeviceScanner {

    private static final String TAG = DeviceScanner.class.getSimpleName();
    private final byte[] LOCK = new byte[0];

    private UDP mUDP;
    private IScanCallback mScanCallback;
    private NoticeOnlineThread mNoticeOnlineThread;
    private Map<String, Device> mDevices = new ConcurrentHashMap<>();
    private Map<String, Integer> mTimeCounter = new HashMap<>();
    private DeviceConfig mConfig;
    private int mCountThreshold = 3;    //3次询问无回复则判定为无响应
    private final static int TIMEOUT = 30000;   //30秒间隔询问无回复则判定为无响应

    public void setConfig(DeviceConfig config) {
        synchronized (LOCK) {
            mConfig = config;
        }
    }

    public Device queryDevice(String key) {
        return mDevices.get(key);
    }

    public Map<String, Device> getDevices() {
        return mDevices;
    }

    public boolean startScan(int period, IScanCallback scanCallback) {
        mScanCallback = scanCallback;
        synchronized (LOCK) {
            if (mConfig == null) {
                return false;
            }
            if (mUDP != null) {
                mUDP.stopReceive();
            }
            mUDP = new UDP(mConfig.localHost, mConfig.broadcastMonitorPort);
        }
        mUDP.startReceive((ip, port, data) -> {
                    IpMessageProtocol ipMessage = null;
                    try {
                        ipMessage = new IpMessageProtocol(data);
                    } catch (Exception e) {
                        Log.e(TAG, "parse UDP data error:" + e.toString());
                    }
                    if (ipMessage == null) {
                        return;
                    }

            int cmd = 0x000000FF & ipMessage.getCmd();
            Log.d("sasa", "MSG:"+ data);

            Device dev = mDevices.get(ip);
                    switch (cmd) {
                        case IpMessageConst.IP_MSG_BR_EXIT:
                            if (dev != null && !dev.isConnected()) {
                                mDevices.remove(ip);
                                if (mScanCallback != null) {
                                    mScanCallback.onDeviceOffline(dev);
                                }
                            }
                            break;
                        case IpMessageConst.IP_MSG_BR_ENTRY:
                            new Handler(Looper.getMainLooper()).post(() -> ToastUtil.makeText("IP_MSG_BR_ENTRY"));
                            IpMessageProtocol ipMsgSend = new IpMessageProtocol();
                            ipMessage.setVersion(String.valueOf(IpMessageConst.VERSION));
                            ipMessage.setSenderName(mConfig.name);
                            ipMessage.setCmd(IpMessageConst.IP_MSG_ANS_ENTRY); // 回送报文命令
                            ipMessage.setAdditionalSection(mConfig.name + IpMessageProtocol.DELIMITER
                                    + mConfig.uuid + IpMessageProtocol.DELIMITER
                                    + "0" + IpMessageProtocol.DELIMITER
                                    + "0" + "\0"); // 附加信息里加入用户名和分组信息/
                            if (mUDP != null) {
                                mUDP.notify(ipMsgSend, ip, port);
                            }
                            break;
                        case IpMessageConst.IP_MSG_ANS_ENTRY:
                            new Handler(Looper.getMainLooper()).post(() -> ToastUtil.makeText("IP_MSG_ANS_ENTRY"));
                            if (dev == null) {
                                String protocolVersion = ipMessage.getVersion();
                                dev = new Device(mConfig, protocolVersion);
                                dev.setIp(ip);
                                dev.setName(ipMessage.getSenderName());
                                String additionalSection = ipMessage.getAdditionalSection();
                                String[] userInfo = additionalSection.split(IpMessageProtocol.DELIMITER);
                                if (userInfo.length < 1) {
                                    dev.setName(ipMessage.getSenderName());
                                }
                                if (userInfo.length >= 2) {
                                    dev.setFunctionCode(userInfo[1]);
                                }
                                mDevices.put(ip, dev);
                                if (mScanCallback != null) {
                                    mScanCallback.onDeviceOnline(dev);
                                }
                            } else if (isDeviceInfoChanged(ipMessage, dev)) {
                                dev.setName(ipMessage.getSenderName());
                                String protocolVersion = ipMessage.getVersion();
                                String additionalSection = ipMessage.getAdditionalSection();
                                String[] userInfo = additionalSection.split(":");
                                if (userInfo.length < 1) {
                                    dev.setName(ipMessage.getSenderName());
                                }
                                if (userInfo.length >= 2) {
                                    dev.setFunctionCode(userInfo[1]);
                                }
                                if (mScanCallback != null) {
                                    mScanCallback.onDeviceInfoChanged(dev);
                                }
                            }
                            recount(dev);
                            if (mScanCallback != null) {
                                mScanCallback.onDeviceNotice(dev);
                            }
                            break;
                        default:
                            break;
                    }
                }

        );
        synchronized (LOCK) {
            if (mNoticeOnlineThread != null) {
                mNoticeOnlineThread.setStopped(true);
                mNoticeOnlineThread.interrupt();
            }

            mNoticeOnlineThread = new NoticeOnlineThread(mUDP);
            mNoticeOnlineThread.setPeriod(period);
            mNoticeOnlineThread.start();
        }
        return true;
    }

    private boolean isDeviceInfoChanged(IpMessageProtocol ipMessage, Device dev) {
        String name = dev.getName();
        String senderName = ipMessage.getSenderName();
        if (!TextUtils.isEmpty(name)) {
            return !name.equals(senderName);
        } else {
            return !TextUtils.isEmpty(senderName);
        }
    }

    public void stopScan() {
        synchronized (LOCK) {
            if (mNoticeOnlineThread != null) {
                mNoticeOnlineThread.setStopped(true);
                mNoticeOnlineThread.interrupt();
                mNoticeOnlineThread = null;
            }
            if (mUDP != null) {
                mUDP.stopReceive();
                mUDP = null;
            }
            offlineALlDevices();
            mScanCallback = null;//必须在offlineALlDevices之后
        }
    }

    public int getScanPeriod() {
        synchronized (LOCK) {
            if (mNoticeOnlineThread != null && !mNoticeOnlineThread.isStopped()) {
                return mNoticeOnlineThread.getPeriod();
            }
            return -1;
        }
    }

    public DeviceInfo onlineDevice(DeviceInfo deviceInfo) {
        if (!mDevices.containsKey(deviceInfo.getIp())) {
            String ip = deviceInfo.getIp();
            String name = deviceInfo.getName();
            String protocolVersion = deviceInfo.getProtocolVersion();
            String functionCode = deviceInfo.getFunctionCode();

            //二维码扫描或者其他方式获得的连接信息中,如果不包含协议版本或者functioncode，则不加入到设备列表中
            if (TextUtils.isEmpty(protocolVersion)
                    || TextUtils.isEmpty(functionCode)) {
                return null;
            }

            Device dev = new Device(mConfig, protocolVersion);
            dev.setIp(ip);
            dev.setName(name);
            dev.setFunctionCode(functionCode);
            mDevices.put(ip, dev);
            if (mScanCallback != null) {
                mScanCallback.onDeviceOnline(dev);
            }
            recount(dev);
            return dev.getDeviceInfo();
        } else {
            return mDevices.get(deviceInfo.getIp()).getDeviceInfo();
        }
    }

    public boolean offlineDevice(DeviceInfo deviceInfo) {
        if (deviceInfo == null) {
            return false;
        }
        String ip = deviceInfo.getIp();
        if (null != ip && mDevices.containsKey(ip)) {
            Device device = mDevices.remove(ip);
            if (null != device) {
                if (mScanCallback != null) {
                    mScanCallback.onDeviceOffline(device);
                }
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    class NoticeOnlineThread extends Thread {
        public static final int MIN_PERIOD = 1000; //间隔至少1秒
        public static final int DEFAULT_PERIOD = 6000;
        private UDP mUDP;
        private transient boolean isStopped;
        private int mPeriod = DEFAULT_PERIOD;

        public void setStopped(boolean stopped) {
            isStopped = stopped;
        }

        public boolean isStopped() {
            return isStopped;
        }

        public void setPeriod(int period) {
            period = Math.max(period, MIN_PERIOD);
            mPeriod = period;
        }

        public int getPeriod() {
            return mPeriod;
        }

        public NoticeOnlineThread(UDP udp) {
            this.mUDP = udp;
        }

        @Override
        public void run() {
            IpMessageProtocol ipMsgSend = new IpMessageProtocol();
            ipMsgSend.setVersion(String.valueOf(IpMessageConst.VERSION));
            ipMsgSend.setSenderName(mConfig.name);
            ipMsgSend.setCmd(IpMessageConst.IP_MSG_BR_ENTRY); // 上线命令
            ipMsgSend.setAdditionalSection(mConfig.name + IpMessageProtocol.DELIMITER
                    + mConfig.uuid + IpMessageProtocol.DELIMITER
                    + "0" + IpMessageProtocol.DELIMITER
                    + "0" + "\0"); // 附加信息里加入用户名和分组信息/
            String broadCastHost = mConfig.broadCastHostInWifi;
            int broadcastPort = mConfig.broadcastPort;
            String broadCastHostInAp = mConfig.broadCastHostInAp;
            while (!isInterrupted() && !isStopped()) {
                checkOffline();
                if (mUDP == null) {
                    break;
                }
                mUDP.notify(ipMsgSend, broadCastHost, broadcastPort);
                mUDP.notify(ipMsgSend, broadCastHostInAp, broadcastPort);

                try {
                    sleep(mPeriod);
                } catch (InterruptedException e) {
                    Log.e(TAG, "NoticeOnlineThread InterruptedException,");
                }
                if (isStopped()) {
                    break;
                }
            }
        }
    }

    private void checkOffline() {
        Iterator<Map.Entry<String, Device>> iterator = mDevices.entrySet().iterator();
        long spaceTime;

        while (iterator.hasNext()) {
            Device device = iterator.next().getValue();
            spaceTime = System.currentTimeMillis() - device.getOnlineTime();
            Log.v(TAG, "checkOffline ip=" + device.getIp() + ",state=" + device.getState() + ",spaceTime=" + spaceTime);
            if (spaceTime > TIMEOUT && device.getState() == DeviceInfo.STATE_IDLE) {
                iterator.remove();
                if (mScanCallback != null) {
                    mScanCallback.onDeviceOffline(device);
                }
            }
        }
    }

    private void recount(Device device) {
        device.setOnlineTime(System.currentTimeMillis());
    }

    private void offlineALlDevices() {
        Iterator<Map.Entry<String, Device>> iterator = mDevices.entrySet().iterator();
        while (iterator.hasNext()) {
            Device device = iterator.next().getValue();
            iterator.remove();
            if (mScanCallback != null) {
                mScanCallback.onDeviceOffline(device);
            }
        }
    }

    public interface IScanCallback {
        void onDeviceOnline(Device device);

        void onDeviceOffline(Device device);

        void onDeviceInfoChanged(Device device);

        void onDeviceNotice(Device device);

    }

}