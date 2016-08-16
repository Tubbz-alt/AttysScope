/**
 Copyright 2016 Bernd Porr, mail@berndporr.me.uk

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 **/

/**
 * Modified code from:
 * https://developer.android.com/guide/topics/connectivity/bluetooth.html
 */

package uk.me.berndporr.www.attysplot;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Scanner;
import java.util.UUID;


/**
 * Created by bp1 on 14/08/16.
 */
public class AttysComm extends Thread {
    public final static int BT_CONNECTED = 0;
    public final static int BT_ERROR = 1;
    public final static int BT_RETRY = 2;

    private BluetoothSocket mmSocket;
    private Scanner inScanner = null;
    private boolean doRun;
    private float[][] ringBuffer = null;
    final private int nMem = 1000;
    private int inPtr = 0;
    private int outPtr = 0;
    final private int nRawFieldsPerLine = 14;
    private ConnectThread connectThread = null;
    private boolean isConnected = false;
    final private int nChannels = 11;
    private InputStream mmInStream = null;
    private OutputStream mmOutStream = null;
    private static final String TAG = "AttysComm";
    private boolean fatalError = false;
    private Handler parentHandler;
    private BluetoothDevice bluetoothDevice;

    private class ConnectThread extends Thread {
        private BluetoothSocket mmSocket;
        private boolean connectionEstablished;

        public BluetoothSocket getBluetoothSocket() {
            return mmSocket;
        }

        public boolean hasActiveConnection() { return connectionEstablished; }

        public ConnectThread(BluetoothDevice device) {
            // Use a temporary object that is later assigned to mmSocket,
            // because mmSocket is final
            bluetoothDevice = device;
            BluetoothSocket tmp = null;
            connectionEstablished = false;

            // Get a BluetoothSocket to connect with the given BluetoothDevice
            try {
                UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
                //tmp = device.createRfcommSocketToServiceRecord(uuid);
                tmp = device.createInsecureRfcommSocketToServiceRecord(uuid);
            } catch (Exception e) {
                Log.d(TAG,"Could not get rfComm socket");
                parentHandler.sendEmptyMessage(BT_ERROR);
            }
            mmSocket = tmp;
            if (tmp != null) Log.d(TAG,"Got rfComm socket");
        }

        public void run() {
            // Cancel discovery because it will slow down the connection
            // mBluetoothAdapter.cancelDiscovery();

            if (mmSocket != null) {
                try {
                    mmSocket.connect();
                } catch (IOException connectException) {

                    parentHandler.sendEmptyMessage(BT_RETRY);

                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e1) {
                        e1.printStackTrace();
                    }

                    try {
                        mmSocket.connect();
                    } catch (IOException e2) {

                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e3) {
                            e3.printStackTrace();
                        }

                        try {
                            mmSocket.connect();
                        } catch (IOException e4) {

                            e4.printStackTrace();

                            connectionEstablished = false;
                            fatalError = true;
                            Log.d(TAG, "Could not establish connection");
                            Log.d(TAG, connectException.getMessage());
                            parentHandler.sendEmptyMessage(BT_ERROR);

                            try {
                                mmSocket.close();
                            } catch (IOException closeException) {
                                Log.d(TAG, "Could not close connection");
                            }
                            mmSocket = null;
                            return;
                        }
                    }
                }
                connectionEstablished = true;
                Log.d(TAG, "Connected to socket");
            }
        }

        /** Will cancel an in-progress connection, and close the socket */
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) { }
        }
    }


    public boolean hasActiveConnection() { return isConnected;}



    public AttysComm(BluetoothDevice device, Handler handler) {

        // transmits errors etc
        parentHandler = handler;

        connectThread = new ConnectThread(device);
        // let's try to connect
        // b/c it's blocking we do it in another thread
        connectThread.start();

    }

    public void run() {

        try {
            // let's wait until we are connected
            connectThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
            isConnected = false;
        }

        if (!connectThread.hasActiveConnection()) {
            isConnected = false;
            return;
        }

        try {
            Log.d(TAG, "Getting streams");
            mmSocket = connectThread.getBluetoothSocket();
            if (mmSocket != null) {
                mmInStream = mmSocket.getInputStream();
                mmOutStream = mmSocket.getOutputStream();
                inScanner = new Scanner(mmInStream);
                ringBuffer = new float[nMem][nChannels];
                isConnected = true;
            }
        } catch (IOException e) {
            Log.d(TAG, "Could not get streams");
            isConnected = false;
            fatalError = true;
            mmInStream = null;
            mmOutStream = null;
            inScanner = null;
            ringBuffer = null;
            parentHandler.sendEmptyMessage(BT_ERROR);
        }

        // we only enter in the main loop if we have connected
        doRun = isConnected;

        Log.d(TAG, "Starting main data acquistion loop");
        parentHandler.sendEmptyMessage(BT_CONNECTED);
        // Keep listening to the InputStream until an exception occurs
        while (doRun) {
            try {
                // Read from the InputStream
                if ((inScanner != null) && inScanner.hasNextLine()) {
                    // get a line from the Attys
                    String oneLine;
                    if (inScanner != null) {
                        oneLine = inScanner.nextLine();
                    } else {
                        return;
                    }
                    // Log.d(TAG, oneLine);
                    // split up the CSV
                    String[] fields = oneLine.split(",");
                    // check that we have a full line:
                    // number of channels plus 01 and the timestamp
                    // Log.d(TAG, String.format("length = %d",fields.length));
                    if (fields.length == nRawFieldsPerLine) {
                        // the 1st field is always 0x01
                        int marker = Integer.parseInt(fields[0], 16);
                        // check if marker is 1
                        // Log.d(TAG, String.format("%d",marker));
                        if (marker == 1) {
                            /// now we are sure we have a full line
                            // timestamp = Integer.parseInt(fields[1], 16);
                            for (int i = 0; i < nChannels; i++) {
                                float norm = 0x8000;
                                if (i > 8) {
                                    norm = 0x800000;
                                }
                                try {
                                    ringBuffer[inPtr][i] = ((float) Integer.parseInt(fields[2 + i], 16) - norm) / norm;
                                } catch (Exception e) {
                                    ringBuffer[inPtr][i] = norm/2;
                                };
                            }
                            //Log.d(TAG,String.format("save:inPtr=%d,data=%f",inPtr,ringBuffer[inPtr][10]));
                            inPtr++;
                            if (inPtr == nMem) {
                                inPtr = 0;
                            }
                            // Log.d(TAG, String.format("%d", timestamp));
                        }
                    }
                } else {
                    //Log.d(TAG,"Caught up!");
                }
            } catch (Exception e) {
                Log.d(TAG, "System error message:" + e.getMessage());
                Log.d(TAG, "Could not read from stream. Closing down.");
                break;
            }
        }
    }

    public boolean hasFatalError() { return fatalError; }

    public float[] getSampleFromBuffer() {
        if (inPtr != outPtr) {
            // Log.d(TAG,String.format("get:outPtr=%d,data =%f",outPtr,ringBuffer[outPtr][10]));
            float[] sample = ringBuffer[outPtr];
            outPtr++;
            if (outPtr == nMem) {
                outPtr = 0;
            }
            return sample;
        } else {
            return null;
        }
    }

    public boolean isSampleAvilabale() {
        return (inPtr != outPtr);
    }

    public int getNumSamplesAvilable() {
        int n = 0;
        int tmpOutPtr = outPtr;
        while (inPtr != tmpOutPtr) {
            tmpOutPtr++;
            n++;
            if (tmpOutPtr == nMem) {
                tmpOutPtr = 0;
            }
        }
        return n;
    }

    public int getnChannels() {
        return nChannels;
    }


    /* Call this from the main activity to send data to the remote device */
    public void write(byte[] bytes) {
        try {
            mmOutStream.write(bytes);
        } catch (IOException e) {
        }
    }

    /* Call this from the main activity to shutdown the connection */
    public void cancel() {
        doRun = false;
        if (inScanner != null) {
            inScanner.close();
        }
        if (mmInStream != null) {
            try {
                mmInStream.close();
            } catch (IOException e) {}
        }
        if (mmOutStream != null) {
            try {
                mmOutStream.close();
            } catch (IOException e) {
            }
        }
        if (mmSocket != null) {
            try {
                mmSocket.close();
            } catch (IOException e) {
            }
        }
        mmSocket = null;
        isConnected = false;
        fatalError = false;
        mmInStream = null;
        mmOutStream = null;
        inScanner = null;
        ringBuffer = null;
    }
}