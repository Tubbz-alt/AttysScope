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

package uk.me.berndporr.www.attysplot;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.widget.CheckBox;
import android.widget.Toast;

//import com.google.android.gms.appindexing.Action;
//import com.google.android.gms.appindexing.AppIndex;
//import com.google.android.gms.common.api.GoogleApiClient;

import java.util.Arrays;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

public class AttysPlot extends Activity {

    private Timer timer = null;

    private RealtimePlotView realtimePlotView = null;

    private CheckBox showAccelerobter, showGyroscope, showMagnetometer, showADC1, showADC2;

    private BluetoothAdapter BA;
    private AttysComm attysComm = null;
    private BluetoothDevice btAttysDevice = null;

    private static final String TAG = "AttysPlot";

    private Highpass [] highpass = null;
    private float [] gain;


    Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AttysComm.BT_ERROR:
                    Toast.makeText(getApplicationContext(),
                            "Bluetooth error", Toast.LENGTH_LONG).show();
                    finish();
                    break;
                case AttysComm.BT_CONNECTED:
                    Toast.makeText(getApplicationContext(),
                            "Bluetooth connected", Toast.LENGTH_LONG).show();
                    break;
                case AttysComm.BT_RETRY:
                    Toast.makeText(getApplicationContext(),
                            "Bluetooth connection problems - trying again. Please be patient.", Toast.LENGTH_LONG).show();
                    break;
            }
        }
    };


    /**
     * ATTENTION: This was auto-generated to implement the App Indexing API.
     * See https://g.co/AppIndexing/AndroidStudio for more information.
     */
    // private GoogleApiClient client;






    private BluetoothDevice connect2Bluetooth() {

        Intent turnOn = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivityForResult(turnOn, 0);

        BA = BluetoothAdapter.getDefaultAdapter();

        if (BA == null) {
            Log.d(TAG, "no bluetooth adapter!");
            finish();
        }

        Set<BluetoothDevice> pairedDevices;
        pairedDevices = BA.getBondedDevices();

        if (pairedDevices == null) {
            Log.d(TAG, "No paired devices available. Exiting.");
            finish();
        }

        for (BluetoothDevice bt : pairedDevices) {
            String b = bt.getName();
            Log.d(TAG, b);
            if (b.startsWith("GN-ATTYS")) {
                Log.d(TAG,"Found an Attys");
                return bt;
            }
        }
        return null;
    }


    private class UpdatePlotTask extends TimerTask {

        public void run() {

            float[] adcch=new float[1];

            if (attysComm != null) {
                if (attysComm.hasFatalError()) {
                    // Log.d(TAG,String.format("No bluetooth connection"));
                    handler.sendEmptyMessage(AttysComm.BT_ERROR);
                    return;
                }
            }
            if (attysComm != null) {
                if (!attysComm.hasActiveConnection()) return;
            }
            int nCh = 0;
            if (attysComm != null) nCh = attysComm.getnChannels();
            //long t0 = System.currentTimeMillis();
            if (attysComm != null) {
                float[] tmpSample = new float[nCh];
                int n = attysComm.getNumSamplesAvilable();
                if (realtimePlotView != null) {
                    realtimePlotView.startAddSamples(n);
                    for (int i = 0; ((i < n) && (attysComm != null)); i++) {
                        float[] sample = attysComm.getSampleFromBuffer();
                        for (int j = 0; j < nCh; j++) {
                            float v = sample[j];
                            if (j > 8) {
                                v = highpass[j].filter(v);
                            }
                            v = v * gain[j];
                            sample[j] = v;
                        }
                        int nRealChN = 0;
                        if (showAccelerobter.isChecked()) {
                            tmpSample[nRealChN++] = sample[0];
                            tmpSample[nRealChN++] = sample[1];
                            tmpSample[nRealChN++] = sample[2];
                        }
                        if (showGyroscope.isChecked()) {
                            tmpSample[nRealChN++] = sample[3];
                            tmpSample[nRealChN++] = sample[4];
                            tmpSample[nRealChN++] = sample[5];
                        }
                        if (showMagnetometer.isChecked()) {
                            tmpSample[nRealChN++] = sample[6];
                            tmpSample[nRealChN++] = sample[7];
                            tmpSample[nRealChN++] = sample[8];
                        }
                        if (showADC1.isChecked()) {
                            tmpSample[nRealChN++] = sample[9];
                        }
                        if (showADC2.isChecked()) {
                            tmpSample[nRealChN++] = sample[10];
                        }
                        realtimePlotView.addSamples(Arrays.copyOfRange(tmpSample,0,nRealChN));
                        // Log.d(TAG,String.format("data = %f",sample[10]));
                        //long t1 = System.currentTimeMillis();
                        //Log.i(TAG, "Timing: " + ( t1 - t0) );
                    }
                    if (realtimePlotView != null) {
                        realtimePlotView.stopAddSamples();
                    }
                    // Log.d(TAG,String.format("%d samples",n));
                }
            }
        }
    }


    @Override
    public void onBackPressed() {
        Log.d(TAG,String.format("Back button pressed"));
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }


    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);

        setContentView(R.layout.activity_plot_window);

        btAttysDevice = connect2Bluetooth();
        if (btAttysDevice == null) {
            Context context = getApplicationContext();
            CharSequence text = "Could not find any paired Attys devices.";
            int duration = Toast.LENGTH_LONG;
            Toast toast = Toast.makeText(context, text, duration);
            toast.show();
            finish();
        }

        attysComm = new AttysComm(btAttysDevice,handler);

        int nChannels = attysComm.getnChannels();
        highpass = new Highpass[nChannels];
        gain = new float[nChannels];
        for(int i = 0;i<nChannels;i++) {
            highpass[i] = new Highpass();
            highpass[i].setAlpha(0.01F);
            gain[i] = 1;
            if (i > 5) gain[i] = 50;
            if (i > 8) gain[i] = 200;
        }

        attysComm.start();

        realtimePlotView = (RealtimePlotView) findViewById(R.id.realtimeplotview);
        realtimePlotView.setMaxChannels(15);

        showAccelerobter = (CheckBox) findViewById(R.id.showacc);
        showAccelerobter.setChecked(true);
        showGyroscope = (CheckBox) findViewById(R.id.showgyr);
        showGyroscope.setChecked(true);
        showMagnetometer = (CheckBox) findViewById(R.id.showmag);
        showMagnetometer.setChecked(true);
        showADC1 = (CheckBox) findViewById(R.id.showadc1);
        showADC1.setChecked(true);
        showADC2 = (CheckBox) findViewById(R.id.showadc2);
        showADC2.setChecked(true);

        timer = new Timer();
        UpdatePlotTask updatePlotTask = new UpdatePlotTask();
        timer.schedule(updatePlotTask,0,200);

        // ATTENTION: This was auto-generated to implement the App Indexing API.
        // See https://g.co/AppIndexing/AndroidStudio for more information.
//        client = new GoogleApiClient.Builder(this).addApi(AppIndex.API).build();
    }


    @Override
    public void onStart() {
        super.onStart();

        // ATTENTION: This was auto-generated to implement the App Indexing API.
        // See https://g.co/AppIndexing/AndroidStudio for more information.

   /**
        client.connect();
        Action viewAction = Action.newAction(
                Action.TYPE_VIEW, // TODO: choose an action type.
                "AttysPlot Page", // TODO: Define a title for the content shown.
                // TODO: If you have web page content that matches this app activity's content,
                // make sure this auto-generated web page URL is correct.
                // Otherwise, set the URL to null.
                Uri.parse("http://www.berndporr.me.uk"),
                // TODO: Make sure this auto-generated app URL is correct.
                Uri.parse("android-app://uk.me.berndporr.www.attysplot/http/host/path")
        );
        AppIndex.AppIndexApi.start(client, viewAction);
        **/
    }

    private void killAttysComm() {
        if (attysComm != null) {
            attysComm.cancel();
            try {
                attysComm.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            attysComm = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        Log.d(TAG, String.format("Destroy!"));
        killAttysComm();
    }

    @Override
    protected void onRestart() {
        super.onRestart();

        Log.d(TAG, String.format("Restarting"));
        realtimePlotView.resetX();
        killAttysComm();
        attysComm = new AttysComm(btAttysDevice, handler);
        attysComm.start();
    }


    @Override
    public void onStop() {
        super.onStop();

        Log.d(TAG,String.format("Stopped"));

        killAttysComm();

        /**
        // ATTENTION: This was auto-generated to implement the App Indexing API.
        // See https://g.co/AppIndexing/AndroidStudio for more information.
        Action viewAction = Action.newAction(
                Action.TYPE_VIEW, // TODO: choose an action type.
                "AttysPlot Page", // TODO: Define a title for the content shown.
                // TODO: If you have web page content that matches this app activity's content,
                // make sure this auto-generated web page URL is correct.
                // Otherwise, set the URL to null.
                Uri.parse("http://host/path"),
                // TODO: Make sure this auto-generated app URL is correct.
                Uri.parse("android-app://uk.me.berndporr.www.attysplot/http/host/path")
        );
        AppIndex.AppIndexApi.end(client, viewAction);
        client.disconnect();
         **/
    }
}