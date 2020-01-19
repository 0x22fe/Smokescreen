package com.x22fe.smokescreen;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;

// Modified from https://github.com/bauerjj/Android-Simple-Bluetooth-Example

public class MainActivity extends AppCompatActivity {

    // Constants
    private final static String SMOKESCREEN = "RPI";
    private final static UUID BTMODULEUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); // "random" unique identifier
    private final static int REQUEST_ENABLE_BT = 1; // Used to identify adding bluetooth names
    private final static int MESSAGE_READ = 2; // Used in bluetooth handler to identify message update
    private final static int CONNECTING_STATUS = 3; // Used in bluetooth handler to identify message status
    private final String TAG = MainActivity.class.getSimpleName();

    // GUI Components
    private TextView mBluetoothStatus;
    private TextView mReadBuffer;
    private Switch mBluetoothToggle;
    private Switch mSmokescreenToggle;
    private Button mListPairedDevicesBtn;
    private Button mDiscoverBtn;
    private BluetoothAdapter mBTAdapter;
    private Set<BluetoothDevice> mPairedDevices;
    private ArrayAdapter<String> mBTArrayAdapter;
    // Handlers
    private final BroadcastReceiver blReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                // Add the name to the list if it is a smokescreen device
                if (TextUtils.isEmpty(device.getName()))
                    return;
                if (device.getName().toUpperCase().contains(SMOKESCREEN)) {
                    mBTArrayAdapter.add(device.getName() + "\n" + device.getAddress());
                    mBTArrayAdapter.notifyDataSetChanged();
                }
            }
        }
    };
    private ListView mDevicesListView;
    private Handler mHandler; // Our main handler that will receive callback notifications
    private ConnectedThread mConnectedThread; // Bluetooth background worker thread to send and receive data
    private BluetoothSocket mBTSocket = null; // Bi-directional client-to-client data path
    private AdapterView.OnItemClickListener mDeviceClickListener = new AdapterView.OnItemClickListener() {
        public void onItemClick(AdapterView<?> av, View v, int arg2, long arg3) {

            if (!mBTAdapter.isEnabled()) {
                Toast.makeText(getBaseContext(), "Bluetooth not on", Toast.LENGTH_SHORT).show();
                return;
            }

            mBluetoothStatus.setText("Connecting...");
            // Get the device MAC address, which is the last 17 chars in the View
            String info = ((TextView) v).getText().toString();
            final String address = info.substring(info.length() - 17);
            final String name = info.substring(0, info.length() - 17);

            // Spawn a new thread to avoid blocking the GUI one
            new Thread() {
                public void run() {
                    boolean fail = false;

                    BluetoothDevice device = mBTAdapter.getRemoteDevice(address);

                    try {
                        mBTSocket = createBluetoothSocket(device);
                    } catch (IOException e) {
                        fail = true;
                        Toast.makeText(getBaseContext(), "Socket creation failed", Toast.LENGTH_SHORT).show();
                    }
                    // Establish the Bluetooth socket connection.
                    try {
                        mBTSocket.connect();
                    } catch (IOException e) {
                        try {
                            fail = true;
                            mBTSocket.close();
                            mHandler.obtainMessage(CONNECTING_STATUS, -1, -1)
                                    .sendToTarget();
                        } catch (IOException e2) {
                            // Insert code to deal with this
                            Toast.makeText(getBaseContext(), "Socket creation failed", Toast.LENGTH_SHORT).show();
                        }
                    }
                    if (fail == false) {
                        mConnectedThread = new ConnectedThread(mBTSocket);
                        mConnectedThread.start();

                        mHandler.obtainMessage(CONNECTING_STATUS, 1, -1, name)
                                .sendToTarget();
                    }
                }
            }.start();
        }
    };

    // Audio Processing
    private HashMap<String, Integer> voicePairs;
    private SoundPool voicePool;
    private String voiceText;
    private TextToSpeech mTts;
    private Context context;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        voicePairs = new HashMap<String, Integer>();
        voicePool = new SoundPool.Builder().setAudioAttributes(
                new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN).setUsage(AudioAttributes.USAGE_MEDIA).build()
        ).setMaxStreams(10).build();
        context = getApplicationContext();
        LinearLayout mLoadingView = findViewById(R.id.loading_view);
        LinearLayout mAppView = findViewById(R.id.app_view);

        mLoadingView.setVisibility(View.VISIBLE);
        mAppView.setVisibility(View.INVISIBLE);

        // Load audio data
        new PreSpeech("");

        mLoadingView.setVisibility(View.INVISIBLE);
        mAppView.setVisibility(View.VISIBLE);

        mBluetoothStatus = findViewById(R.id.bluetoothStatus);
        mReadBuffer = findViewById(R.id.readBuffer);
        mBluetoothToggle = findViewById(R.id.switch_bluetooth);
        mDiscoverBtn = findViewById(R.id.button_discover);
        mListPairedDevicesBtn = findViewById(R.id.button_paired);
        mSmokescreenToggle = findViewById(R.id.switch_start);
        mSmokescreenToggle.setText(getString(R.string.smokescreen_disconnected));

        mBTArrayAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1);
        mBTAdapter = BluetoothAdapter.getDefaultAdapter(); // get a handle on the bluetooth radio

        mDevicesListView = findViewById(R.id.list_devices_view);
        mDevicesListView.setAdapter(mBTArrayAdapter); // assign model to view
        mDevicesListView.setOnItemClickListener(mDeviceClickListener);

        // Ask for location permission if not already allowed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 1);

        mHandler = new Handler() {
            public void handleMessage(android.os.Message msg) {
                if (msg.what == MESSAGE_READ) {
                    String readMessage = null;
                    readMessage = new String((byte[]) msg.obj, StandardCharsets.UTF_8);
                    mReadBuffer.setText("");

                    JSONObject json;

                    try {
                        json = new JSONObject(readMessage);
                        int key = json.getInt("timestamp");
                        JSONArray arr = json.getJSONArray("detected");

                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject j = arr.getJSONObject(i);

                            String obj_name = j.getString("name");
                            double obj_angle = j.getDouble("angle");

                            Integer n = voicePairs.get(obj_name);
                            if (n == null)
                                throw new JSONException("Unknown object name");

                            float vright = (float) (obj_angle);
                            float vleft = 1.0F - vright;

                            // Set volume by position
                            voicePool.play(n, vleft, vright, 1, 0, 1.0F);
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }

                if (msg.what == CONNECTING_STATUS) {
                    if (msg.arg1 == 1) {
                        mBluetoothStatus.setText("Connected to Device: " + msg.obj);

                        // mSmokescreenToggle.setText(getString(R.string.smokescreen_connected));
                        Toast.makeText(getBaseContext(), "Connected to device", Toast.LENGTH_SHORT).show();

                    } else {
                        mBluetoothStatus.setText("Connection Failed");
                    }
                }
            }
        };

        if (mBTArrayAdapter == null) {
            // Device does not support Bluetooth
            mBluetoothStatus.setText("Status: Bluetooth not found");
            Toast.makeText(getApplicationContext(), getString(R.string.bluetooth_device_not_found), Toast.LENGTH_SHORT).show();
        } else {

            mSmokescreenToggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // First check to make sure thread was created
                    if (mConnectedThread != null) {
                        if (mBluetoothToggle.isChecked()) {
                            mConnectedThread.write("1");
                        } else {
                            mConnectedThread.write("0");
                        }
                    } else {
                        Toast.makeText(getApplicationContext(), getString(R.string.smokescreen_no_connection), Toast.LENGTH_SHORT).show();
                        mSmokescreenToggle.setChecked(false);
                    }
                }
            });
            mBluetoothToggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mBluetoothToggle.isChecked())
                        bluetoothOn(v);
                    else
                        bluetoothOff(v);
                }
            });
            mListPairedDevicesBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    listPairedDevices(v);
                }
            });

            mDiscoverBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    discover(v);
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        voicePool.release();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(blReceiver);
    }

    private void bluetoothOn(View view) {
        if (!mBTAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            mBluetoothStatus.setText("Bluetooth enabled");
            Toast.makeText(getApplicationContext(), getString(R.string.bluetooth_on), Toast.LENGTH_SHORT).show();

        } else {
            Toast.makeText(getApplicationContext(), getString(R.string.bluetooth_already_on), Toast.LENGTH_SHORT).show();
        }
    }

    // Enter here after user selects "yes" or "no" to enabling radio
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent Data) {
        // Check which request we're responding to
        if (requestCode == REQUEST_ENABLE_BT) {
            // Make sure the request was successful
            if (resultCode == RESULT_OK) {
                // The user picked a contact.
                // The Intent's data Uri identifies which contact was selected.
                mBluetoothStatus.setText("Enabled");
            } else
                mBluetoothStatus.setText("Disabled");
        }
    }

    private void bluetoothOff(View view) {
        mBTAdapter.disable(); // turn off
        mBluetoothStatus.setText("Bluetooth disabled");
        Toast.makeText(getApplicationContext(), getString(R.string.bluetooth_turned_off), Toast.LENGTH_SHORT).show();
    }

    private void discover(View view) {
        // Check if the device is already discovering
        if (mBTAdapter.isDiscovering()) {
            mBTAdapter.cancelDiscovery();
            Toast.makeText(getApplicationContext(), getString(R.string.bluetooth_stopped_discovery), Toast.LENGTH_SHORT).show();
        } else {
            if (mBTAdapter.isEnabled()) {
                mBTArrayAdapter.clear(); // clear items
                mBTAdapter.startDiscovery();
                Toast.makeText(getApplicationContext(), getString(R.string.bluetooth_started_discovery), Toast.LENGTH_SHORT).show();
                registerReceiver(blReceiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
            } else {
                Toast.makeText(getApplicationContext(), getString(R.string.bluetooth_not_on), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void listPairedDevices(View view) {
        mBTArrayAdapter.clear();
        mPairedDevices = mBTAdapter.getBondedDevices();
        if (mBTAdapter.isEnabled()) {
            // Put it's one to the adapter
            for (BluetoothDevice device : mPairedDevices)
                mBTArrayAdapter.add(device.getName() + "\n" + device.getAddress());

            Toast.makeText(getApplicationContext(), getString(R.string.bluetooth_list_paired), Toast.LENGTH_SHORT).show();
        } else
            Toast.makeText(getApplicationContext(), getString(R.string.bluetooth_not_on), Toast.LENGTH_SHORT).show();
    }

    private BluetoothSocket createBluetoothSocket(BluetoothDevice device) throws IOException {
        try {
            final Method m = device.getClass().getMethod("createInsecureRfcommSocketToServiceRecord", UUID.class);
            return (BluetoothSocket) m.invoke(device, BTMODULEUUID);
        } catch (Exception e) {
            Log.e(TAG, "Could not create Insecure RFComm Connection", e);
        }
        return device.createRfcommSocketToServiceRecord(BTMODULEUUID);
    }

    private class PreSpeech implements TextToSpeech.OnInitListener {
        String tts;

        public PreSpeech(String tts) {
            this.tts = tts;
            mTts = new TextToSpeech(MainActivity.this, this);
        }

        @Override
        public void onInit(int status) {
            if (status != TextToSpeech.SUCCESS) {
                Log.e(SMOKESCREEN, "Failed to load speech engine");
                Toast.makeText(MainActivity.this, getString(R.string.speech_failed_load),
                        Toast.LENGTH_SHORT).show();
                return;
            }

            // Load audio files to disk
            String[] vobjs = getResources().getStringArray(R.array.object_recognition_array);

            for (String s : vobjs) {
                File path = context.getFilesDir();
                String fpath = path.toString() + "/" + s + ".wav";
                File nf = new File(fpath);

                int i = TextToSpeech.SUCCESS;
                if (!nf.exists()) {
                    HashMap<String, String> myHashRender = new HashMap<String, String>();
                    myHashRender.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, s);
                    i = mTts.synthesizeToFile(s, myHashRender, fpath);
                }

                if (i == TextToSpeech.SUCCESS) {
                    // Load audio file
                    int id = voicePool.load(fpath, 1);
                    voicePairs.put(s, id);
                    Log.i(SMOKESCREEN, "Loaded voice text \"" + s + "\"");
                } else {
                    Log.e(SMOKESCREEN, "Failed to load voice text \"" + s + "\"");
                    Toast.makeText(MainActivity.this, "Failed to load voice text \"" + s + "\"",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            /*
            if (!nf.exists() || true) {
                HashMap<String, String> myHashRender = new HashMap<String, String>();
                myHashRender.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, tts);
                int i = mTts.synthesizeToFile(voiceText, myHashRender, fpath);
                if (i == TextToSpeech.SUCCESS) {
                    Log.i(SMOKESCREEN, "Loaded voice text \"" + tts + "\"");
                    /*
                    Toast.makeText(MainActivity.this, "Loaded voice text \"" + voiceText + "\"",
                            Toast.LENGTH_SHORT).show();
                     /
                } else {
                    Log.e(SMOKESCREEN, "Failed to load voice text \"" + tts + "\"");
                    /*
                    Toast.makeText(MainActivity.this, "Failed to load voice text \"" + voiceText + "\"",
                            Toast.LENGTH_SHORT).show();
                     /
                }
            */
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the input and output streams, using temp objects because
            // Member streams are final
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];  // Buffer store for the stream
            int bytes; // Bytes returned from read()
            // Keep listening to the InputStream until an exception occurs
            while (true) {
                try {
                    // Read from the InputStream
                    bytes = mmInStream.available();
                    if (bytes != 0) {
                        buffer = new byte[1024];
                        SystemClock.sleep(100); // Pause and wait for rest of data. Adjust this depending on your sending speed.
                        bytes = mmInStream.available(); // How many bytes are ready to be read?
                        bytes = mmInStream.read(buffer, 0, bytes); // Record how many bytes we actually read
                        mHandler.obtainMessage(MESSAGE_READ, bytes, -1, buffer)
                                .sendToTarget(); // Send the obtained bytes to the UI activity
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    break;
                }
            }
        }

        /* Call this from the main activity to send data to the remote device */
        public void write(String input) {
            byte[] bytes = input.getBytes();           // Converts entered String into bytes
            try {
                mmOutStream.write(bytes);
            } catch (IOException e) {
            }
        }

        /* Call this from the main activity to shutdown the connection */
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
            }
        }
    }
}