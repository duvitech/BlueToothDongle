package com.example.george.bluetoothdongle;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import android.widget.Toast;

import java.security.Timestamp;

public class DataUpdateService extends Service implements LocationListener,IScannedDeviceListener {
    private static final String TAG = "DataUpdateService";
    private static final int UPDATE_INTERVAL = 20000;
    private static final int UPDATE_DISTANCE = 1;

    // Binder given to clients
    private final IBinder mBinder = new LocalBinder();
    private boolean mBound = false;
    private BluetoothScannerService mService;
    private ScannerListenerReceiver receiver;
    private CabTagCommService mCabTagService = null;
    private static String mConnectedDongleName = null;
    private DongleCommService mDongleService = null;
    private StringBuffer mOutStringBuffer;

    private IDataServiceListener mListener = null;

    boolean mDonglePresent = false;
    String mDongleAddress = null;

    boolean mCabTagPresent = false;
    String mCabTagAddress = null;

    boolean mLocationListener = false;
    Location mLastLocation = null;
    Timestamp mLastLocationUpdate = null;

    OBDIIData mOBDIIData = null;
    Timestamp mLastODBIIDataUpdate = null;
    private BluetoothAdapter mBluetoothAdapter;


    public DataUpdateService() {
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        LocationManager locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        receiver = new ScannerListenerReceiver(this);
        registerReceiver(receiver, new IntentFilter(BluetoothScannerService.DONGLE_DETECTED));
        registerReceiver(receiver, new IntentFilter(BluetoothScannerService.CABTAG_DETECTED));

        // Register the listener with the Location Manager to receive location updates
        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, UPDATE_INTERVAL, UPDATE_DISTANCE, this);
        mLocationListener = true;

        // get scanner service reference
        Intent btIntent = new Intent(this, BluetoothScannerService.class);
        bindService(btIntent, mConnection, Context.BIND_AUTO_CREATE);

        startService(btIntent);


        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind");
        mBound = true;
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mDongleService != null) {
            mDongleService.stop();
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        Log.d(TAG, "onLocationChanged");
        Log.d(TAG, "Position: " + location.getLatitude() + ", " + location.getLongitude());
        if(mListener != null)
            mListener.onLocationUpdated(location);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        Log.d(TAG, "onStatusChanged");

    }

    @Override
    public void onProviderEnabled(String provider) {
        Log.d(TAG, "onProviderEnabled");

    }

    @Override
    public void onProviderDisabled(String provider) {
        Log.d(TAG, "onProviderDisabled");

    }

    @Override
    public void onDongleDetected(String address) {
        Log.d(TAG, "onDongleDetected");
        if(mListener != null)
            mListener.onDongleDetected(address);
       // mService.disableDongleDiscovery();
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if(device != null)
           setupDongleChannel(device);
        else
            Log.w(TAG,"Dongle Name NOT FOUND");
    }

    @Override
    public void onCabTagDetected(String address) {
        Log.d(TAG, "onCabTagDetected");
        if(mListener != null)
            mListener.onCabTagDetected(address);
    }

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        DataUpdateService getService() {
            // Return this instance of LocalService so clients can call public methods
            return DataUpdateService.this;
        }
    }

    // Pubolic Binding methods

    public void RegisterListener(IDataServiceListener listener) {
        mListener = listener;
    }

    public void UnregisterListener(IDataServiceListener listener) {
        mListener = null;
    }

    public void sendDongleCommand(String command){
        sendCommand(command);
    }

    public boolean isDongleConnectd(){
        if(mDongleService != null && mDongleService.getState() ==  DongleCommService.STATE_CONNECTED)
            return true;

        return false;
    }

    public int getDongleStatus(){
        if(mDongleService != null)
            return mDongleService.getState();

        return 0;
    }


    public String getCabTagId(){
        if(mCabTagAddress != null)
            return mCabTagAddress;

        return "No CabTag Found";
    }

    public String getDongleId(){
        if(mDongleAddress != null)
            return mDongleAddress;

        return "No Dongle Found";
    }


    private void setupDongleChannel(BluetoothDevice device){
        Log.d(TAG, "setupDongleChannel");
        if(mDongleService != null){
            // we are already connected
            Log.d(TAG, "Already connected to a dongle");

            mDongleService.stop();
            mDongleService = null;
            // initialize the DongleCommService to perform a bluetooth connection
            mDongleService = new DongleCommService(getBaseContext(), mHandler);
            // Initialize the buffer for outgoing messages
            mOutStringBuffer = new StringBuffer("");
            mDongleService.connect(device, false);


        }
        else {
            // initialize the DongleCommService to perform a bluetooth connection
            mDongleService = new DongleCommService(getBaseContext(), mHandler);
            // Initialize the buffer for outgoing messages
            mOutStringBuffer = new StringBuffer("");
            mDongleService.connect(device, false);
        }
    }


    private void sendCommand(String dongleCommand){
        // Check that we're actually connected before trying anything
        if (mDongleService.getState() != DongleCommService.STATE_CONNECTED) {
            Log.d(TAG, "Send Failed Not Connected");
            mService.enableDongleDiscovery();
            mDongleService.start();
            return;
        }

        // Check that there's actually something to send
        if (dongleCommand.length() > 0) {
            if(!dongleCommand.endsWith("\r"))
                dongleCommand = dongleCommand + "\r";

            // Get the message bytes and tell the BluetoothChatService to write
            byte[] send = dongleCommand.getBytes();
            mDongleService.write(send);

            // Reset out string buffer to zero
            mOutStringBuffer.setLength(0);
            // and clear the edit text field
            //mOutEditText.setText(mOutStringBuffer);
        }
    }

    private void showToast(String message){
        Toast.makeText(getApplicationContext(), message,
                Toast.LENGTH_SHORT).show();
    }

    private final Handler mHandler = new Handler(){

        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG,"Message Received");

            switch (msg.what) {
                case Constants.MESSAGE_STATE_CHANGE:
                    Log.d(TAG,"Message: MESSAGE_STATE_CHANGE");

                    if(mListener != null)
                        mListener.onDongleStateChange(msg.arg1);

                    switch (msg.arg1) {
                        case DongleCommService.STATE_CONNECTED:
                            Log.d(TAG,"Message: State Connected");
                            //setStatus(getString(R.string.title_connected_to, mConnectedDongleName));
                            mService.disableDongleDiscovery();
                            break;
                        case DongleCommService.STATE_CONNECTING:
                            Log.d(TAG,"Message: State Connecting");
                            //setStatus(R.string.title_connecting);
                            break;
                        case DongleCommService.STATE_LISTEN:
                        case DongleCommService.STATE_NONE:
                            Log.d(TAG,"Message: Not Connected");
                            //setStatus(R.string.title_not_connected);
                            mService.enableDongleDiscovery();
                            //mDongleService.start();
                            break;
                    }
                    break;
                case Constants.MESSAGE_WRITE:
                    Log.d(TAG,"Message: MESSAGE_WRITE");
                    byte[] writeBuf = (byte[]) msg.obj;
                    // construct a string from the buffer
                    String writeMessage = new String(writeBuf);
                    if(writeMessage.endsWith("\r"))
                        writeMessage = writeMessage.substring(0, writeMessage.length()-1);

                    //Log.d(TAG,"Message: " + writeMessage);
                    break;
                case Constants.MESSAGE_READ:
                    Log.d(TAG,"Message: MESSAGE_READ");
                    byte[] readBuf = (byte[]) msg.obj;
                    // construct a string from the valid bytes in the buffer
                    String readMessage = new String(readBuf, 0, msg.arg1);
                    if(mListener != null)
                        mListener.onDongleResponse(readMessage);
                    //Log.d(TAG,"Message: " + readMessage);
                    break;
                case Constants.MESSAGE_DEVICE_NAME:
                    Log.d(TAG,"Message: MESSAGE_DEVICE_NAME");
                    // save the connected device's name
                    mConnectedDongleName = msg.getData().getString(Constants.DEVICE_NAME);

                    Log.d(TAG,"Connected TO: " + mConnectedDongleName);
                    break;
                case Constants.MESSAGE_TOAST:
                    Log.d(TAG,"Message: MESSAGE_TOAST");
                    break;
            }
        }
    };

    /** Defines callbacks for service binding, passed to bindService() */
    private ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            BluetoothScannerService.LocalBinder binder = (BluetoothScannerService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            mService.startScanner();
            mService.enableDongleDiscovery();
            mService.disableCabTagDiscovery();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
        }
    };

}
