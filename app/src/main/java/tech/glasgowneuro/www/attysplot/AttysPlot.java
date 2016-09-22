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

package tech.glasgowneuro.www.attysplot;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import com.google.android.gms.appindexing.Action;
import com.google.android.gms.appindexing.AppIndex;
import com.google.android.gms.common.api.GoogleApiClient;

import java.io.File;
import java.util.Arrays;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

public class AttysPlot extends AppCompatActivity {

    private Timer timer = null;
    // screen refresh rate
    private final int REFRESH_IN_MS = 150;

    private RealtimePlotView realtimePlotView = null;
    private InfoView infoView = null;

    private BluetoothAdapter BA;
    private AttysComm attysComm = null;
    private BluetoothDevice btAttysDevice = null;

    private static final String TAG = "AttysPlot";

    private Highpass[] highpass = null;
    private float[] gain;
    private IIR_notch[] iirNotch;
    private boolean[] invert;

    private boolean showAcc = true;
    private boolean showGyr = true;
    private boolean showMag = true;
    private boolean showCh1 = true;
    private boolean showCh2 = true;

    private float ch1Div = 1;
    private float ch2Div = 1;

    public enum DataAnalysis {
        NONE,
        AC,
        DC,
        ECG
    }

    private DataAnalysis dataAnalysis = DataAnalysis.NONE;

    // for data analysis
    private double max,min;
    private float t2 = 0;
    private int timestamp = 0;
    private int doNotDetect = 0;
    private float[] analysisBuffer;
    private int analysisPtr = 0;

    Highpass ecgHighpass = new Highpass();

    String[] labels = {"Acc x", "Acc y", "Acc z", "Gyr x", "Gyr y", "Gyr z", "Mag x", "Mag y", "Mag z",
            "ADC 1", "ADC 2"};

    private String dataFilename = null;
    private byte dataSeparator = 0;

    /**
     * App Indexing API.
     * See https://g.co/AppIndexing/AndroidStudio for more information.
     */
    private GoogleApiClient client;
    private Action viewAction;

    Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AttysComm.MESSAGE_ERROR:
                    Toast.makeText(getApplicationContext(),
                            "Bluetooth connection problem", Toast.LENGTH_LONG).show();
                    finish();
                    break;
                case AttysComm.MESSAGE_CONNECTED:
                    Toast.makeText(getApplicationContext(),
                            "Bluetooth connected", Toast.LENGTH_SHORT).show();
                    break;
                case AttysComm.MESSAGE_CONFIGURE:
                    Toast.makeText(getApplicationContext(),
                            "Configuring Attys", Toast.LENGTH_SHORT).show();
                    break;
                case AttysComm.MESSAGE_RETRY:
                    Toast.makeText(getApplicationContext(),
                            "Bluetooth - trying to connect. Please be patient.",
                            Toast.LENGTH_SHORT).show();
                    break;
                case AttysComm.MESSAGE_STARTED_RECORDING:
                    Toast.makeText(getApplicationContext(),
                            "Started recording data to external storage.",
                            Toast.LENGTH_SHORT).show();
                    break;
                case AttysComm.MESSAGE_STOPPED_RECORDING:
                    Toast.makeText(getApplicationContext(),
                            "Finished recording data to external storage.",
                            Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    };


    AttysComm.MessageListener messageListener = new AttysComm.MessageListener() {
        @Override
        public void haveMessage(int msg) {
           handler.sendEmptyMessage(msg);
        }
    };


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
                Log.d(TAG, "Found an Attys");
                return bt;
            }
        }
        return null;
    }



    private class UpdatePlotTask extends TimerTask {


        private void showLargeText(String s) {
            String small = new String();
            if (showCh1) {
                small = small + new String().format("ADC1 = %fV/div", ch1Div);
            }
            if (showCh1 && showCh2) {
                small = small + ", ";
            }
            if (showCh2) {
                small = small + new String().format("ADC2 = %fV/div", ch2Div);
            }
            if (infoView != null) {
                if (attysComm != null) {
                    infoView.drawText(s,small);
                }
            }
        }


        private void doAnalysis(float v) {

            switch (dataAnalysis) {
                case ECG:
                    double h = ecgHighpass.filter(v*1000);
                    if (h<0) h=0;
                    h = h * h;
                    if (h > max) {
                        max = h;
                    }
                    max = max - 0.1 * max / attysComm.getSamplingRateInHz();
                    //Log.d(TAG,String.format("h=%f,max=%f",h,max));
                    if (doNotDetect > 0) {
                        doNotDetect--;
                    } else {
                        if (h > (max/2)) {
                            float t = (float)(timestamp - t2)/attysComm.getSamplingRateInHz();
                            float bpm = 1/t*60;
                            if ((bpm > 40) && (bpm<300)) {
                                showLargeText(String.format("%03d BPM",(int)bpm));
                            }
                            t2 = timestamp;
                            // do not detect for 100ms
                            doNotDetect = 100;
                        }
                    }
                    break;
                case NONE:
                    break;
                case DC:
                    double a = 1.0/attysComm.getSamplingRateInHz();
                    max = v*a - (1-a) * max;
                    int interval = (int) attysComm.getSamplingRateInHz();
                    if ((timestamp % interval) == 0) {
                        if (max < 0.01) {
                            showLargeText(String.format("%fmV", max*1000.0));
                        } else {
                            showLargeText(String.format("%fV", max));
                        }
                    }
                    break;
                case AC:
                    analysisBuffer[analysisPtr] = v;
                    analysisPtr++;
                    //Log.d(TAG,String.format("ana=%d",analysisPtr));
                    if (!(analysisPtr < analysisBuffer.length)) {
                        analysisPtr = 0;
                        min = 2;
                        max = -2;
                        for (int i = 0; i < analysisBuffer.length; i++) {
                            if (analysisBuffer[i] > max) {
                                max = analysisBuffer[i];
                            }
                            if (analysisBuffer[i] < min) {
                                min = analysisBuffer[i];
                            }
                        }
                        double diff = max - min;
                        if (diff < 0.01) {
                            showLargeText(String.format("%1.03fmVpp", diff * 1000));
                        } else {
                            showLargeText(String.format("%1.03fVpp", diff));
                        }
                    }
                    break;
            }
        }

        public void run() {

            if (attysComm != null) {
                if (attysComm.hasFatalError()) {
                    // Log.d(TAG,String.format("No bluetooth connection"));
                    handler.sendEmptyMessage(AttysComm.MESSAGE_ERROR);
                    return;
                }
            }
            if (attysComm != null) {
                if (!attysComm.hasActiveConnection()) return;
            }
            int nCh = 0;
            if (attysComm != null) ecgHighpass.setAlpha(100.0F / attysComm.getSamplingRateInHz());
            if (attysComm != null) nCh = attysComm.NCHANNELS;
            if (attysComm != null) {
                float[] tmpSample = new float[nCh];
                float[] tmpMin = new float[nCh];
                float[] tmpMax = new float[nCh];
                float[] tmpTick = new float[nCh];
                String[] tmpLabels = new String[nCh];
                int n = attysComm.getNumSamplesAvilable();
                if (realtimePlotView != null) {
                    realtimePlotView.startAddSamples(n);
                    for (int i = 0; ((i < n) && (attysComm != null)); i++) {
                        float[] sample = attysComm.getSampleFromBuffer();
                        if (sample != null) {
                            doAnalysis(sample[9]);
                            timestamp++;
                            for (int j = 0; j < nCh; j++) {
                                float v = sample[j];
                                if (j > 8) {
                                    v = highpass[j].filter(v);
                                    v = iirNotch[j].filter(v);
                                }
                                v = v * gain[j];
                                if (invert[j]) {
                                    sample[j] = -v;
                                } else {
                                    sample[j] = v;
                                }
                            }
                            int nRealChN = 0;
                            int sn = 0;
                            if (showAcc) {
                                if (attysComm != null) {
                                    float min = -attysComm.getAccelFullScaleRange();
                                    float max = attysComm.getAccelFullScaleRange();

                                    for (int k = 0; k < 3; k++) {
                                        tmpMin[nRealChN] = min;
                                        tmpMax[nRealChN] = max;
                                        tmpTick[nRealChN] = gain[k] * 1.0F; // 1G
                                        tmpLabels[nRealChN] = labels[k];
                                        tmpSample[nRealChN++] = sample[k];
                                    }
                                }
                            }
                            if (showGyr) {
                                if (attysComm != null) {
                                    float min = -attysComm.getGyroFullScaleRange();
                                    float max = attysComm.getGyroFullScaleRange();
                                    for (int k = 0; k < 3; k++) {
                                        tmpMin[nRealChN] = min;
                                        tmpMax[nRealChN] = max;
                                        tmpTick[nRealChN] = gain[k + 3] * 1000.0F; // 1000DPS
                                        tmpLabels[nRealChN] = labels[k + 3];
                                        tmpSample[nRealChN++] = sample[k + 3];
                                    }
                                }
                            }
                            if (showMag) {
                                if (attysComm != null) {
                                    for (int k = 0; k < 3; k++) {
                                        tmpMin[nRealChN] = -attysComm.getMagFullScaleRange();
                                        tmpMax[nRealChN] = attysComm.getMagFullScaleRange();
                                        tmpLabels[nRealChN] = labels[k + 6];
                                        tmpTick[nRealChN] = gain[k + 6] * 1000.0E-6F; //1000uT
                                        tmpSample[nRealChN++] = sample[k + 6];
                                    }
                                }
                            }
                            if (showCh1) {
                                if (attysComm != null) {
                                    tmpMin[nRealChN] = -attysComm.getADCFullScaleRange(0);
                                    tmpMax[nRealChN] = attysComm.getADCFullScaleRange(0);
                                    tmpTick[nRealChN] = ch1Div * gain[9];
                                    tmpLabels[nRealChN] = labels[9];
                                    tmpSample[nRealChN++] = sample[9];
                                }
                            }
                            if (showCh2) {
                                if (attysComm != null) {
                                    tmpMin[nRealChN] = -attysComm.getADCFullScaleRange(1);
                                    tmpMax[nRealChN] = attysComm.getADCFullScaleRange(1);
                                    tmpTick[nRealChN] = ch2Div * gain[10];
                                    tmpLabels[nRealChN] = labels[10];
                                    tmpSample[nRealChN++] = sample[10];
                                }
                            }
                            realtimePlotView.addSamples(Arrays.copyOfRange(tmpSample, 0, nRealChN),
                                    Arrays.copyOfRange(tmpMin, 0, nRealChN),
                                    Arrays.copyOfRange(tmpMax, 0, nRealChN),
                                    Arrays.copyOfRange(tmpTick, 0, nRealChN),
                                    Arrays.copyOfRange(tmpLabels, 0, nRealChN));
                        }
                    }
                    if (realtimePlotView != null) {
                        realtimePlotView.stopAddSamples();
                    }
                }
            }
        }
    }


    @Override
    public void onBackPressed() {
        Log.d(TAG, String.format("Back button pressed"));
        if (!attysComm.hasActiveConnection()) {
            attysComm.cancel();
        }
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

        Toolbar myToolbar = (Toolbar) findViewById(R.id.my_toolbar);
        setSupportActionBar(myToolbar);

        btAttysDevice = connect2Bluetooth();
        if (btAttysDevice == null) {
            Context context = getApplicationContext();
            CharSequence text = "Could not find any paired Attys devices.";
            int duration = Toast.LENGTH_LONG;
            Toast toast = Toast.makeText(context, text, duration);
            toast.show();
            finish();
        }

        attysComm = new AttysComm(btAttysDevice);
        attysComm.registerMessageListener(messageListener);

        int nChannels = attysComm.NCHANNELS;
        highpass = new Highpass[nChannels];
        gain = new float[nChannels];
        iirNotch = new IIR_notch[nChannels];
        invert = new boolean[nChannels];
        for (int i = 0; i < nChannels; i++) {
            highpass[i] = new Highpass();
            iirNotch[i] = new IIR_notch();
            gain[i] = 1;
            if ((i>5) && (i<9)) {gain[i] = 50;}
        }
        // 1sec
        analysisBuffer = new float[(int)attysComm.getSamplingRateInHz()];

        getsetAttysPrefs();

        attysComm.start();

        for (int i = 0; i < nChannels; i++) {
            iirNotch[i].setParameters((float) 50.0 / attysComm.getSamplingRateInHz(), 0.9F);
            iirNotch[i].setIsActive(true);
            highpass[i].setAlpha(1.0F / attysComm.getSamplingRateInHz());
        }

        realtimePlotView = (RealtimePlotView) findViewById(R.id.realtimeplotview);
        realtimePlotView.setMaxChannels(15);

        infoView = (InfoView) findViewById(R.id.infoview);
        infoView.setZOrderOnTop(true);
        infoView.setZOrderMediaOverlay(true);

        timer = new Timer();
        UpdatePlotTask updatePlotTask = new UpdatePlotTask();
        timer.schedule(updatePlotTask, 0, REFRESH_IN_MS);

        // ATTENTION: This was auto-generated to implement the App Indexing API.
        // See https://g.co/AppIndexing/AndroidStudio for more information.
        client = new GoogleApiClient.Builder(this).addApi(AppIndex.API).build();
    }


    private void enterFilename() {

        final EditText filenameEditText = new EditText(this);
        filenameEditText.setSingleLine(true);

        final int REQUEST_EXTERNAL_STORAGE = 1;
        String[] PERMISSIONS_STORAGE = {
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };

        int permission = ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);

        if (permission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    PERMISSIONS_STORAGE,
                    REQUEST_EXTERNAL_STORAGE
            );
        }

        filenameEditText.setHint("");
        filenameEditText.setText(dataFilename);

        new AlertDialog.Builder(this)
                .setTitle("Enter filename")
                .setMessage("Enter the filename of the CSV file")
                .setView(filenameEditText)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        dataFilename = filenameEditText.getText().toString();
                        dataFilename = dataFilename.replaceAll("[^a-zA-Z0-9.-]", "_");
                        if (!dataFilename.contains(".")) {
                            switch (dataSeparator) {
                                case AttysComm.DATA_SEPARATOR_COMMA:
                                    dataFilename = dataFilename + ".csv";
                                break;
                                case AttysComm.DATA_SEPARATOR_SPACE:
                                case AttysComm.DATA_SEPARATOR_TAB:
                                    dataFilename = dataFilename + ".dat";
                            }
                        }
                        Toast.makeText(getApplicationContext(),
                                "Press rec to record to '"+ dataFilename +"'",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                    }
                })
                .show();
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main_menu_attysplot, menu);

        return true;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {


        switch (item.getItemId()) {

            case R.id.preferences:
                Intent intent = new Intent(this,PrefsActivity.class);
                startActivity(intent);
                return true;

            case R.id.toggleRec:
                if (attysComm.isRecording()) {
                    attysComm.stopRec();
                } else {
                    if (dataFilename != null) {
                        File file = new File(Environment.getExternalStorageDirectory().getPath(),
                                dataFilename.trim());
                        attysComm.setDataSeparator(dataSeparator);
                        java.io.FileNotFoundException e = attysComm.startRec(file);
                        if (e != null) {
                            Log.d(TAG, "Could not open data file: "+e.getMessage());
                            return true;
                        }
                        if (attysComm.isRecording()) {
                            Log.d(TAG,"Saving to "+file.getAbsolutePath());
                        }
                    } else {
                        Toast.makeText(getApplicationContext(),
                                "To record enter a filename first", Toast.LENGTH_SHORT).show();
                    }
                }
                return true;

            case R.id.enterFilename:
                enterFilename();
                return true;

            case R.id.Ch1toggleDC:
                boolean a = highpass[9].getIsActive();
                a = !a;
                item.setChecked(a);
                highpass[9].setActive(a);
                return true;

            case R.id.Ch2toggleDC:
                a = highpass[10].getIsActive();
                a = !a;
                item.setChecked(a);
                highpass[10].setActive(a);
                return true;

            case R.id.Ch1notch:
                a = iirNotch[9].getIsActive();
                a = !a;
                item.setChecked(a);
                iirNotch[9].setIsActive(a);
                return true;

            case R.id.Ch2notch:
                a = iirNotch[10].getIsActive();
                a = !a;
                item.setChecked(a);
                iirNotch[10].setIsActive(a);
                return true;

            case R.id.Ch1invert:
                a = invert[9];
                a = !a;
                invert[9] = a;
                item.setChecked(a);
                return true;

            case R.id.Ch2invert:
                a = invert[10];
                a = !a;
                invert[10] = a;
                item.setChecked(a);
                return true;

            case R.id.Ch1gain1:
            case R.id.Ch1gain2:
            case R.id.Ch1gain5:
            case R.id.Ch1gain10:
            case R.id.Ch1gain20:
            case R.id.Ch1gain50:
            case R.id.Ch1gain100:
            case R.id.Ch1gain200:
            case R.id.Ch1gain500:
                String t = item.getTitle().toString();
                int g = Integer.parseInt(t);
                gain[9] = (float)g;
                if (g<20) {
                    ch1Div = 1;
                } else {
                    ch1Div = 1E-3F;
                }
                Toast.makeText(getApplicationContext(),
                        String.format("Channel 1 gain set to x%d",g),Toast.LENGTH_LONG).show();
                return true;

            case R.id.Ch2gain1:
            case R.id.Ch2gain2:
            case R.id.Ch2gain5:
            case R.id.Ch2gain10:
            case R.id.Ch2gain20:
            case R.id.Ch2gain50:
            case R.id.Ch2gain100:
            case R.id.Ch2gain200:
            case R.id.Ch2gain500:
                t = item.getTitle().toString();
                g = Integer.parseInt(t);
                Toast.makeText(getApplicationContext(),
                        String.format("Channel 2 gain set to x%d",g),Toast.LENGTH_LONG).show();
                gain[10] = (float)g;
                if (g<20) {
                    ch2Div = 1;
                } else {
                    ch2Div = 1E-3F;
                }
                return true;

            case R.id.largeStatusOff:
                dataAnalysis = DataAnalysis.NONE;
                return true;

            case R.id.largeStatusAC:
                dataAnalysis = DataAnalysis.AC;
                return true;

            case R.id.largeStatusDC:
                dataAnalysis = DataAnalysis.DC;
                return true;

            case R.id.largeStatusBPM:
                dataAnalysis = DataAnalysis.ECG;
                return true;

            default:
                // If we got here, the user's action was not recognized.
                // Invoke the superclass to handle it.
                return super.onOptionsItemSelected(item);

        }
    }



    @Override
    public void onStart() {
        super.onStart();

        client.connect();
        viewAction = Action.newAction(
                Action.TYPE_VIEW,
                "AttysPlot Homepage",
                Uri.parse("http://www.attys.tech")
        );
        AppIndex.AppIndexApi.start(client, viewAction);
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


    private void getsetAttysPrefs() {
        byte mux=0;

        Log.d(TAG, String.format("Setting preferences"));
        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(this);
        boolean ecg_mode = prefs.getBoolean("ECG_mode",false);
        if (ecg_mode) {
            mux = AttysComm.ADC_MUX_ECG_EINTHOVEN;
        } else {
            mux = AttysComm.ADC_MUX_NORMAL;
        }
        byte gain0 = (byte)(Integer.parseInt(prefs.getString("ch1_gainpref", "0")));
        attysComm.setAdc0_gain_index(gain0);
        attysComm.setAdc0_mux_index(mux);
        byte gain1 = (byte)(Integer.parseInt(prefs.getString("ch2_gainpref", "0")));
        attysComm.setAdc1_gain_index(gain1);
        attysComm.setAdc1_mux_index(mux);
        int current = Integer.parseInt(prefs.getString("ch2_current", "-1"));
        if (current < 0) {
            attysComm.enableCurrents(false,false,false);
        } else {
            attysComm.setBiasCurrent((byte)current);
            attysComm.enableCurrents(false,false,true);
        }
        byte data_separator = (byte)(Integer.parseInt(prefs.getString("data_separator", "0")));
        attysComm.setDataSeparator(data_separator);

        showAcc = prefs.getBoolean("acc",true);
        showGyr = prefs.getBoolean("gyr",true);
        showMag = prefs.getBoolean("mag",true);
        showCh1 = prefs.getBoolean("ch1",true);
        showCh2 = prefs.getBoolean("ch2",true);
    }

    @Override
    protected void onRestart() {
        super.onRestart();

        Log.d(TAG, String.format("Restarting"));
        realtimePlotView.resetX();
        killAttysComm();
        attysComm = new AttysComm(btAttysDevice);
        attysComm.registerMessageListener(messageListener);
        getsetAttysPrefs();
        attysComm.start();
    }


    @Override
    public void onStop() {
        super.onStop();

        Log.d(TAG,String.format("Stopped"));

        killAttysComm();

        AppIndex.AppIndexApi.end(client, viewAction);
        client.disconnect();
    }
}
