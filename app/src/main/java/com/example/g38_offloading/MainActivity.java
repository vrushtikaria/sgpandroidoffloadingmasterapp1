package com.example.g38_offloading;

import static java.lang.Double.parseDouble;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

import org.w3c.dom.Text;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    //Buttons, lists and other elements
    Button scanButton, sendButton, slaveBatteryButton, slaveLocationButton, viewReceivedButton, viewSentButton, viewPerformanceButton;
    Spinner listViewDevices;
    TextView statusText, textLocation, textBattery, info, connectedDevices;
    String statusTextContent = "";
    EditText matrixSize;

    String deviceName;
    String[] strings;
    int temp_count = 0;

    //variables for location
    FusedLocationProviderClient mFusedLocationClient;
    int PERMISSION_ID = 44;
    String myLatitude, myLongitude;

    //variables for bluetooth connections
    BluetoothAdapter bluetoothAdapter;
    BluetoothDevice[] btDevices;
    int REQUEST_ENABLE_BLUETOOTH = 1;
    long bt_connection_start_time = 0;
    int available_devices = 0;
    ArrayAdapter<String> btArrayAdapter;
    ArrayList<BluetoothDevice> btDeviceNameList = new ArrayList<>(); //maintains the list of available bt devices in proximity
    ArrayList<BluetoothSocket> btSocketList = new ArrayList<>();
    ArrayList<BluetoothDevice> btConnected = new ArrayList<>();
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    SendReceiveHandler sendReceive;
    DataConversionSerial dataConversionSerial;

    //Maps to store slave details such as connection status, battery levels, location etc.
    Map<BluetoothSocket, ArrayList<String>> connection_status = new HashMap<BluetoothSocket, ArrayList<String>>(); //maintained at master to keep track of whether the slave was busy and free
    Map<String, Integer> battery_slave_delta = new HashMap<String, Integer>(); //battery level drop of slaves
    Map<String, Integer> battery_slave_initial = new HashMap<String, Integer>(); //initial battery level of slaves
    Map<String, String> location_slave_initial = new HashMap<String, String>(); //initial location of slaves
    Map<String, Double> dist_slave_initial = new HashMap<String, Double>();
    Map<String, Double> scoreForOffloadingPreference = new HashMap<String, Double>();
    Map<Integer, Long> row_sent_time = new HashMap<Integer, Long>(); //maintains time at which row was sent
    ArrayList<Integer> rows_left = new ArrayList<Integer>(); //maintains rows to be sent
    Map<Integer, String> rowsReceivedSlaveCheck = new HashMap<Integer, String>(); //maintains rows received from slave
    Map<Integer, String> rowSlaveMapping = new HashMap<Integer,String>();
    Map<BluetoothDevice, BluetoothSocket> socketDeviceMap = new HashMap<>();

    //battery level related variables
    int battery_level_master = 0; ///battery level of the master
    int battery_thresh = 30; //battery threshold for offloading
    int battery_level_start_master = 0;
    String battery_status = "";

    //connections status values
    static final int CONNECTING = 2;
    static final int CONNECTED = 3;
    static final int CONNECTION_FAILED = 4;
    static final int MESSAGE_RECEIVED = 5;
    static final int BATTERY_LOW = 6;
    static final int DISCONNECTED = 7;

    //matrix related variables
    int total_rows = 0; //total rows in matrix to be sent
    int received_rows = 0; //total rows received from slave devices
    int result_receive_time_check = 1; //if row result was not received after 5 seconds from one slave it can be sent to other available slaves
    int[][] inputs_A;
    int[][] inputs_B;
    int[][] output_matrix;

    //performance monitoring variables (time)
    long start_time_off;
    long finish_time_off;
    long start_time_master;
    long finish_time_master;

    private Handler offloadingHandler = new Handler();  //handles the connection status and messages received

    //--------------------------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayShowHomeEnabled(true);
        actionBar.setIcon(R.mipmap.ic_launcher);

        //Initialising Buttons, lists and other elements
        listViewDevices = (Spinner) findViewById(R.id.listViewDevices);
        slaveBatteryButton = (Button) findViewById(R.id.slaveBatteryButton);
        slaveLocationButton = (Button) findViewById(R.id.slaveLocationButton);
        viewReceivedButton = (Button) findViewById(R.id.viewBtnReceived);
        viewSentButton = (Button) findViewById(R.id.viewBtnSent);
        viewPerformanceButton = (Button) findViewById(R.id.viewBtnPerformance);
        textBattery = (TextView) findViewById(R.id.textBattery);
        textLocation = (TextView) findViewById(R.id.textLocation);
        scanButton = (Button) findViewById(R.id.scanBtn);
        sendButton = (Button) findViewById(R.id.sendBtn);
        statusText = (TextView) findViewById(R.id.statusText);
        matrixSize = (EditText) findViewById(R.id.matrixSize);
        connectedDevices = (TextView) findViewById(R.id.connectedDevices);
        viewReceivedButton.setEnabled(false);
        viewSentButton.setEnabled(false);
        viewPerformanceButton.setEnabled(false);

        dataConversionSerial = new DataConversionSerial();
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        // method to get the location
        getLastLocation();

        btArrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<String>());
        listViewDevices.setAdapter(btArrayAdapter);

        //Below will enable the bluetooth in the device in case it's disables
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BLUETOOTH);
        }

        this.registerReceiver(this.mBatInfoReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        deviceName = BluetoothAdapter.getDefaultAdapter().getName(); //get the bluetooth name of the device

        setListeners();

    }

    //--------------------------------------------------------------------------------------------------------
    //Get battery level of your own device
    private BroadcastReceiver mBatInfoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            battery_level_master = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            textBattery.setText("My battery level: " + String.valueOf(battery_level_master) + "%");
        }
    };

    //--------------------------------------------------------------------------------------------------------
    //setting all listeners
    void setListeners() {

        //initialise scan button
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //temp_count=0;
                statusText.setText("-");
                bluetoothAdapter.startDiscovery();
                IntentFilter intentFilterConnection = new IntentFilter(BluetoothDevice.ACTION_FOUND);
                registerReceiver(myReceiver, intentFilterConnection);
            }
        });

        //initialise slave battery view button
        slaveBatteryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // inflate the layout of the popup window
                LayoutInflater inflater = (LayoutInflater)
                        getSystemService(LAYOUT_INFLATER_SERVICE);
                View popupView = inflater.inflate(R.layout.popup, null);
                info = (TextView) popupView.findViewById(R.id.info_popup);
                info.setSingleLine(false);
                String batInfo = "";
                // create the popup window
                int width = LinearLayout.LayoutParams.WRAP_CONTENT;
                int height = LinearLayout.LayoutParams.WRAP_CONTENT;
                boolean focusable = true; // lets taps outside the popup also dismiss it
                final PopupWindow popupWindow = new PopupWindow(popupView, width, height, focusable);
                info = (TextView) popupView.findViewById(R.id.info_popup);
                if (!battery_slave_initial.isEmpty()) {
                    for (Map.Entry<String, Integer> entry : battery_slave_initial.entrySet()) {
                        String key = entry.getKey();
                        Integer value = entry.getValue();
                        batInfo += key + ": " + Integer.toString(value) + "\n\n";
                        info.setText(batInfo);
                    }
                } else {
                    info.setText("No devices connected");
                }

                popupWindow.showAtLocation(popupView, Gravity.CENTER, 0, 0);

            }
        });

        //initialise slave location view button
        slaveLocationButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // inflate the layout of the popup window
                LayoutInflater inflater = (LayoutInflater)
                        getSystemService(LAYOUT_INFLATER_SERVICE);
                View popupView = inflater.inflate(R.layout.popup, null);
                info = (TextView) popupView.findViewById(R.id.info_popup);
                info.setSingleLine(false);
                // create the popup window
                int width = LinearLayout.LayoutParams.WRAP_CONTENT;
                int height = LinearLayout.LayoutParams.WRAP_CONTENT;
                boolean focusable = true; // lets taps outside the popup also dismiss it
                final PopupWindow popupWindow = new PopupWindow(popupView, width, height, focusable);
                String locInfo = "";
                if (!location_slave_initial.isEmpty()) {
                    for (Map.Entry<String, String> entry : location_slave_initial.entrySet()) {
                        String key = entry.getKey();
                        String value = entry.getValue();
                        locInfo += key + ": " + value + "\n\n";
                    }
                    info.setText(locInfo);
                } else {
                    info.setText("No devices connected");
                }

                popupWindow.showAtLocation(popupView, Gravity.CENTER, 0, 0);

            }
        });


        //to process what to do on selecting an available device from the list
        listViewDevices.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                temp_count++;
                if (temp_count > 1) {
                    ConnectSlave connectSlave = new ConnectSlave(btDevices[position]);
                    connectSlave.start();
                    statusText.setText("Connecting");
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });


        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if (battery_level_master < battery_thresh) {
                    statusText.setText("Battery Low can't connect");
                    for (BluetoothSocket socket : btSocketList) {
                        sendReceive = new SendReceiveHandler(socket);
                        sendReceive.start();
                        try {
                            sendReceive.write(dataConversionSerial.objectToByteArray((deviceName + ":Battery Level:Battery level low").toString()));
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    btSocketList = new ArrayList<BluetoothSocket>();
                    connection_status = new HashMap<BluetoothSocket, ArrayList<String>>();
                    available_devices = 0;

                } else {
                    rowsReceivedSlaveCheck = new HashMap<Integer, String>();
                    rowSlaveMapping = new HashMap<Integer, String>();
                    //If matrix size is entered with in the limits of 2 and 12 and if there are available devices master is ready to offload
                    if (matrixSize.getText().toString() != null && !matrixSize.getText().toString().isEmpty() && available_devices > 0 && Integer.parseInt(matrixSize.getText().toString()) <= 12 && Integer.parseInt(matrixSize.getText().toString()) >= 2) {
                        sendButton.setEnabled(false);
                        sendButton.setText("Processing");
                        received_rows = 0;
                        offloadingReceiver.run(); //start failure recovery algorithm in background which will continuously check if any row result is not received in 5 seconds once sent till offloading is done
                        total_rows = Integer.parseInt(matrixSize.getText().toString());

                        for (int i = 0; i < total_rows; i++) {
                            rows_left.add(i);
                        }

                        //generate 2 random square matrices of given size
                        inputs_A = new int[total_rows][total_rows];
                        inputs_B = new int[total_rows][total_rows];
                        output_matrix = new int[total_rows][total_rows];
                        inputs_A = generateRandom(total_rows, total_rows);
                        inputs_B = generateRandom(total_rows, total_rows);

                        //Start sending messages to available slaves
                        try {
                            battery_level_start_master = battery_level_master;
                            start_time_off = System.nanoTime();
                            sendMessages();
                            viewSentButton.setEnabled(true);
                            result_receive_time_check = 0;
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    //If there are not available devices display the message to master that no devices are available
                    else if (available_devices <= 0) {
                        Toast.makeText(getApplicationContext(), "No devices available", Toast.LENGTH_LONG).show();
                    }
                    //entered matrix size is not within the limits of 2 & 13
                    else if ((matrixSize.getText().toString() != null && !matrixSize.getText().toString().isEmpty()) && (Integer.parseInt(matrixSize.getText().toString()) >= 13 || Integer.parseInt(matrixSize.getText().toString()) < 2)) {
                        Toast.makeText(getApplicationContext(), "Please enter Matrix Size between 2 & 13", Toast.LENGTH_LONG).show();
                    }
                    //Matrix size is not given
                    else {
                        Toast.makeText(getApplicationContext(), "Please enter Matrix Size", Toast.LENGTH_LONG).show();
                    }
                }
            }
        });

        viewSentButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // inflate the layout of the popup window
                LayoutInflater inflater = (LayoutInflater)
                        getSystemService(LAYOUT_INFLATER_SERVICE);
                View popupView = inflater.inflate(R.layout.popup, null);
                info = (TextView) popupView.findViewById(R.id.info_popup);
                info.setSingleLine(false);
                // create the popup window
                int width = LinearLayout.LayoutParams.WRAP_CONTENT;
                int height = LinearLayout.LayoutParams.WRAP_CONTENT;
                boolean focusable = true; // lets taps outside the popup also dismiss it
                final PopupWindow popupWindow = new PopupWindow(popupView, width, height, focusable);

                String output_print = "";
                output_print += "Matrix A: \n";
                for (int i = 0; i < inputs_A.length; i++) {
                    output_print += Arrays.toString(inputs_A[i]) + "\n";
                }
                output_print += "\n\nMatrix B: \n";
                for (int i = 0; i < inputs_B.length; i++) {
                    output_print += Arrays.toString(inputs_B[i]) + "\n";
                }
                info.setText(output_print);
                popupWindow.showAtLocation(popupView, Gravity.CENTER, 0, 0);

            }
        });

        viewReceivedButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // inflate the layout of the popup window
                LayoutInflater inflater = (LayoutInflater)
                        getSystemService(LAYOUT_INFLATER_SERVICE);
                View popupView = inflater.inflate(R.layout.popup, null);
                info = (TextView) popupView.findViewById(R.id.info_popup);
                info.setSingleLine(false);
                // create the popup window
                int width = LinearLayout.LayoutParams.WRAP_CONTENT;
                int height = LinearLayout.LayoutParams.WRAP_CONTENT;
                boolean focusable = true; // lets taps outside the popup also dismiss it
                final PopupWindow popupWindow = new PopupWindow(popupView, width, height, focusable);
                String output_print = "";
                for (int i = 0; i < output_matrix.length; i++) {
                    output_print += "Row " + Integer.toString(i) + " from: "+ rowSlaveMapping.get(i) +"\n";
                    output_print += Arrays.toString(output_matrix[i]) + "\n";
                }

                info.setText(output_print);
                popupWindow.showAtLocation(popupView, Gravity.CENTER, 0, 0);

            }
        });

        viewPerformanceButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // inflate the layout of the popup window
                LayoutInflater inflater = (LayoutInflater)
                        getSystemService(LAYOUT_INFLATER_SERVICE);
                View popupView = inflater.inflate(R.layout.popup, null);
                info = (TextView) popupView.findViewById(R.id.info_popup);
                info.setSingleLine(false);
                // create the popup window
                int width = LinearLayout.LayoutParams.WRAP_CONTENT;
                int height = LinearLayout.LayoutParams.WRAP_CONTENT;
                boolean focusable = true; // lets taps outside the popup also dismiss it
                final PopupWindow popupWindow = new PopupWindow(popupView, width, height, focusable);
                String output_print = "";
                int battery_drop_offloading_master = battery_level_start_master - battery_level_master;
                output_print += "Time taken with offloading (ns)= " + (finish_time_off - start_time_off) + "\n";
                output_print += "Time taken without offloading (ns)= " + (finish_time_master - start_time_master) + "\n";
                output_print += "\nBattery Level drop at Master:" + battery_drop_offloading_master + "\n";
                output_print += "\nBattery Level drop without offloading at Master:" + (battery_level_start_master - battery_level_master) + "\n";
                output_print += "\nBattery Levels drop at slave:\n";
                for (String server_device : battery_slave_delta.keySet()) {
                    output_print += server_device + " : " + battery_slave_delta.get(server_device) + "\n";
                }

                info.setText(output_print);
                popupWindow.showAtLocation(popupView, Gravity.CENTER, 0, 0);
            }
        });

    }

    //--------------------------------------------------------------------------------------------------------
    //Update listview with available bluetooth devices

    BroadcastReceiver myReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device.getName() == null) {
                    return;
                } else {
                    if (!(btDeviceNameList.contains(device))) {
                        btDeviceNameList.add(device);
                    }
                    strings = new String[btDeviceNameList.size()];
                    btDevices = new BluetoothDevice[btDeviceNameList.size()];
                    int index = 0;

                    if (btDeviceNameList.size() > 0) {
                        for (BluetoothDevice device1 : btDeviceNameList) {
                            btDevices[index] = device1;
                            strings[index] = device1.getName();
                            index++;
                        }

                        btArrayAdapter.clear();
                        btArrayAdapter.addAll(strings);
                        btArrayAdapter.notifyDataSetChanged();
                    }
                }
            }
        }
    };

    //--------------------------------------------------------------------------------------------------------
    //GENERATING AND SENDING MATRICES BELOW

    //This function generate matrix of given size
    private int[][] generateRandom(int rc, int cc) {
        Random rand = new Random();
        int[][] random_array = new int[rc][cc];
        for (int i = 0; i < rc; i++) {
            for (int j = 0; j < cc; j++) {
                random_array[i][j] = rand.nextInt(10);
            }
        }
        return random_array;
    }

    //sends row by row of matrixA and entire matrixB and row number to available slaves which are free
    private void sendMessages() {
        //If battery level less than threshold disconnect from all the connected slaves and communicate to slaves to disconnect
        if (battery_level_master < battery_thresh) {
            statusText.setText("Master battery low, can't connect");
            for (BluetoothSocket socket1 : btSocketList) {
                sendReceive = new SendReceiveHandler(socket1);
                sendReceive.start();
                try {
                    sendReceive.write(dataConversionSerial.objectToByteArray((deviceName + ":Battery Level:Battery level low").toString()));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            //refresh everything
            btSocketList = new ArrayList<BluetoothSocket>();
            connection_status = new HashMap<BluetoothSocket, ArrayList<String>>();
            available_devices = 0;
            return;
        }
        //Checks if there are any free slaves to offload
        if (available_devices > 0) {
            Collections.sort(btConnected, new btComparator()); // to sort by score metric
            for (BluetoothDevice device : btConnected) {
                BluetoothSocket socket = socketDeviceMap.get(device);
                //sends if there are rows left to send
                if (socket != null && connection_status.get(socket).get(1) == "free" && received_rows != total_rows && rows_left.size() > 0) {
                    try {
                        sendReceive = new SendReceiveHandler(socket);
                        sendReceive.start();
                        ArrayList<String> temp = connection_status.get(socket);
                        temp.set(1, "busy");
                        available_devices--;
                        connection_status.put(socket, temp);
                        int temp_row = rows_left.get(0);
                        rows_left.remove(0);
                        row_sent_time.put(temp_row, System.nanoTime());
                        serialEncoder request = new serialEncoder(inputs_A[temp_row], inputs_B, temp_row);
                        sendReceive.write(dataConversionSerial.objectToByteArray(request));

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }


    // creates the comparator for comparing device score to give priority in offloading
    class btComparator implements Comparator<BluetoothDevice> {

        @Override
        public int compare(BluetoothDevice o1, BluetoothDevice o2) {
            if (scoreForOffloadingPreference.get(o1)>scoreForOffloadingPreference.get(o2))
                return 1;
            else
                return -1;
        }
    }



    //receiver for rows from slave devices
    private Runnable offloadingReceiver = new Runnable() {
        @Override
        public void run() {
            if (result_receive_time_check == 0 && received_rows != total_rows) {
                for (int row_number : row_sent_time.keySet()) {
                    if (!rows_left.contains(row_number) && !rowsReceivedSlaveCheck.containsKey(row_number)) {
                        long current_time = System.nanoTime();
                        if (TimeUnit.NANOSECONDS.toMillis(current_time - row_sent_time.get(row_number)) > 5000) {
                            System.out.println("Row number not sent:" + row_number);
                            rows_left.add(row_number);
                            System.out.println(rows_left.get(0));
                            sendMessages();
                        }
                    }
                }
            }
            //this will make sure this function is always running without any delay when this function is started
            offloadingHandler.postDelayed(this, 0);
        }
    };

    //--------------------------------------------------------------------------------------------------------
    //Class to support sending and receiving messages

    private class SendReceiveHandler extends Thread {
        private final BluetoothSocket bluetoothSocket;
        private final InputStream inputStream;
        private final OutputStream outputStream;

        public SendReceiveHandler(BluetoothSocket socket) {
            bluetoothSocket = socket;
            InputStream tempIn = null;
            OutputStream tempOut = null;

            try {
                tempIn = bluetoothSocket.getInputStream();
                tempOut = bluetoothSocket.getOutputStream();
            } catch (IOException e) {
                if (connection_status.containsKey(bluetoothSocket)) {
                    if (connection_status.get(bluetoothSocket).get(1) == "free") {
                        available_devices--;
                    }
                    connection_status.remove(bluetoothSocket);
                    btSocketList.remove(bluetoothSocket);
                    location_slave_initial.remove(bluetoothSocket);
                    battery_slave_initial.remove(bluetoothSocket);

                }
                e.printStackTrace();
            }

            inputStream = tempIn;
            outputStream = tempOut;
        }

        //This will always check if there is any message that has to be sent to a particular socket
        public void run() {
            byte[] buffer = new byte[102400];
            int bytes;
            while (battery_level_master >= battery_thresh) {
                try {
                    if (connection_status.containsKey(bluetoothSocket)) {
                        bytes = inputStream.read(buffer);
                        handler.obtainMessage(MESSAGE_RECEIVED, bytes, -1, buffer).sendToTarget();
                    }

                } catch (IOException e) {
                    if (connection_status.containsKey(bluetoothSocket)) {
                        if (connection_status.get(bluetoothSocket).get(1) == "free") {
                            available_devices--;
                        }
                        connection_status.remove(bluetoothSocket);
                        btSocketList.remove(bluetoothSocket);
                        location_slave_initial.remove(bluetoothSocket);
                        battery_slave_initial.remove(bluetoothSocket);
                    }
                    e.printStackTrace();
                }
            }
        }

        //This function is used to write the message to the output stream
        public void write(byte[] bytes) {
            try {
                if (battery_level_master >= battery_thresh) {
                    outputStream.write(bytes);
                }
            } catch (IOException e) {
                e.printStackTrace();
                Message message = Message.obtain();
                message.what = CONNECTION_FAILED;
                handler.sendMessage(message);
            }
        }

    }

    //--------------------------------------------------------------------------------------------------------
    //Class to connect to slave with Bluetooth

    private class ConnectSlave extends Thread {
        private BluetoothDevice device;
        private BluetoothSocket socket;

        public ConnectSlave(BluetoothDevice device1) {
            device = device1;
            try {
                if (battery_level_master >= battery_thresh) {
                    socket = device.createRfcommSocketToServiceRecord(MY_UUID);
                    bt_connection_start_time = System.nanoTime();
                } else {
                    listViewDevices.setAdapter(null);

                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void run() {
            try {
                if (battery_level_master >= battery_thresh) {
                    socket.connect();
                    Message message = Message.obtain();
                    message.what = CONNECTED;
                    sendReceive = new SendReceiveHandler(socket);
                    sendReceive.start();
                    handler.sendMessage(message);
                    btSocketList.add(socket);

                    //if it's a new connection, add to available devices
                    if (!connection_status.containsKey(socket)) {
                        ArrayList<String> temp = new ArrayList<String>();
                        temp.add(device.getName());
                        temp.add("free");
                        available_devices++;
                        connection_status.put(socket, temp);
                        socketDeviceMap.put(device, socket);
                        btConnected.add(device);
                    }
                } else {
                    Message message = Message.obtain();
                    message.what = BATTERY_LOW;
                    handler.sendMessage(message);
                    listViewDevices.setAdapter(null);
                }

            } catch (IOException e) {
                e.printStackTrace();
                Message message = Message.obtain();
                message.what = CONNECTION_FAILED;
                handler.sendMessage(message);
            }
        }
    }

    //--------------------------------------------------------------------------------------------------------

    //Get the distance between two coordinates (in kms)
    private static double calc_distance(double lat1, double lon1, double lat2, double lon2) {
        if ((lat1 == lat2) && (lon1 == lon2)) {
            return 0;
        } else {
            double theta = lon1 - lon2;
            double dist = Math.sin(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2)) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(Math.toRadians(theta));
            dist = Math.acos(dist);
            dist = Math.toDegrees(dist);
            dist = dist * 60 * 1.1515 * 1.609344;
            return (dist);
        }
    }

    //--------------------------------------------------------------------------------------------------------

    //get a score based on location and battery

    private static double scoreForOffloading (int bat, double dist){
        double score = ((bat/100)*0.5) + ((0.2/dist)*0.5);
        return score;
    }



    //--------------------------------------------------------------------------------------------------------

    //This handler will handle what to do based on the msg received and sets the message in status according to it
    Handler handler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case CONNECTING:
                    statusText.setText("Connecting");
                    break;
                case CONNECTED:
                    statusText.setText("Connected");
                    break;
                case CONNECTION_FAILED:
                    statusText.setText("Connection Failed");
                    break;
                case DISCONNECTED:
                    statusText.setText("Disconnected");
                    break;
                case BATTERY_LOW:
                    statusText.setText("Battery low Can't connect");
                    break;
                //received message from slave
                case MESSAGE_RECEIVED:
                    //disconnect all bt devices if master battery is low
                    if (battery_level_master < battery_thresh) {
                        statusText.setText("Master battery low can't connect");
                        for (BluetoothSocket socket : btSocketList) {
                            sendReceive = new SendReceiveHandler(socket);
                            sendReceive.start();
                            try {
                                sendReceive.write(dataConversionSerial.objectToByteArray((deviceName + ":Battery Level: Battery level is low").toString()));
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        btSocketList = new ArrayList<BluetoothSocket>();
                        connection_status = new HashMap<BluetoothSocket, ArrayList<String>>();
                        available_devices = 0;
                    }

                    byte[] readBuff = (byte[]) msg.obj;
                    try {
                        //message received from slave
                        Object o = dataConversionSerial.byteArrayToObject(readBuff);
                        if (o instanceof String) {
                            String tempMsg = (String) o;
                            String[] messages = tempMsg.split(":");

                            //If slave battery level is low - disconnect and remove slave
                            if (messages[2].equals("Battery level low")) {
                                available_devices--;
                                btSocketList.remove(messages[0]);
                                connection_status.remove(messages[0]);
                                location_slave_initial.remove(messages[0]);
                                battery_slave_initial.remove(messages[0]);
                                Toast.makeText(getApplicationContext(), messages[0] + " has low battery. Can't offload", Toast.LENGTH_LONG).show();
                                statusText.setText("Disconnected");
                                break;
                            }

                            //If slave denied offloading
                            else if (messages[1].equals("Denied")) {
                                available_devices--;
                                btSocketList.remove(messages[0]);
                                connection_status.remove(messages[0]);
                                location_slave_initial.remove(messages[0]);
                                battery_slave_initial.remove(messages[0]);
                                Toast.makeText(getApplicationContext(), messages[0] + " denied offloading", Toast.LENGTH_LONG).show();
                                statusText.setText("Disconnected");
                                break;
                            }

                            //Otherwise slave has sent just it's battery level stats to master
                            else {
                                //if the battery is not in the Map already
                                if (!battery_slave_initial.containsKey(messages[0])) {

                                    //If slave is connecting to master for the first time slave will send it's battery level information and GPS location to master
                                    if (messages.length > 3) {
                                        Double dist = calc_distance(parseDouble(myLatitude), Double.parseDouble(myLongitude), Double.parseDouble(messages[4].split(",")[0]), Double.parseDouble(messages[4].split(",")[1]));

                                        //if slave is within 0.2 km radius then only connect
                                        if (dist <= 0.2) {
                                            battery_slave_initial.put(messages[0], Integer.parseInt(messages[2]));
                                            location_slave_initial.put(messages[0], messages[4]);
                                            dist_slave_initial.put(messages[0],dist);
                                            scoreForOffloadingPreference.put(messages[0], scoreForOffloading(Integer.parseInt(messages[2]), dist));
                                            Toast.makeText(getApplicationContext(),"Connected to: " + messages[0] ,Toast.LENGTH_LONG).show();
                                            String tempBtNames="";
                                            for (BluetoothSocket device1 : connection_status.keySet()) {
                                                tempBtNames+=connection_status.get(device1).get(0);
                                                tempBtNames+=", ";
                                            }
                                            tempBtNames=tempBtNames.substring(0,tempBtNames.length()-2);
                                            connectedDevices.setText(tempBtNames);
                                        }
                                        //otherwise disconnect from slave
                                        else {
                                            for (BluetoothSocket socket : connection_status.keySet()) {
                                                if (connection_status.get(socket).get(0).equals(messages[0])) {
                                                    sendReceive = new SendReceiveHandler(socket);
                                                    sendReceive.start();
                                                    try {
                                                        sendReceive.write(dataConversionSerial.objectToByteArray(deviceName + ":Disconnect:Disconnect"));
                                                        //temp_socket.close();
                                                    } catch (IOException e) {
                                                        e.printStackTrace();
                                                    }
                                                    statusText.setText("Disconnected");
                                                    Toast.makeText(getApplicationContext(), "Slave device too far. Disconnecting", Toast.LENGTH_LONG).show();
                                                    connection_status.remove(socket);
                                                    btSocketList.remove(socket);
                                                    location_slave_initial.remove(socket);
                                                    battery_slave_initial.remove(socket);
                                                    available_devices--;
                                                }
                                            }
                                        }

                                        System.out.println(dist);
                                    } else {
                                        battery_slave_initial.put(messages[0], Integer.parseInt(messages[2]));
                                    }
                                }
                                //battery stats of slave post offloading
                                if (battery_slave_initial.containsKey(messages[0]) && received_rows == total_rows) {
                                    battery_slave_delta.put(messages[0], Math.abs(battery_slave_initial.get(messages[0]) - Integer.parseInt(messages[2])));
                                }
                            }
                        }
                        //Master has received the matrix multiplication row result as response from slave
                        else {
                            serialDecoder tempMsg = (serialDecoder) o;
                            ArrayList<String> temp = new ArrayList<>();
                            if (!rowsReceivedSlaveCheck.containsKey(tempMsg.getRow())) {
                                statusTextContent += "Received row " + tempMsg.getRow() + "from: " + tempMsg.getDeviceName() + "\n";

                                output_matrix[tempMsg.getRow()] = tempMsg.getrowResult();
                                received_rows++;
                                rowsReceivedSlaveCheck.put(tempMsg.getRow(), "received");
                                rowSlaveMapping.put(tempMsg.getRow(), tempMsg.getDeviceName());
                            }
                            //Once receiving result from slave master will set slaves availability status to free
                            temp.add(tempMsg.getDeviceName());
                            temp.add("busy");
                            for (BluetoothSocket key : connection_status.keySet()) {
                                if (temp.equals(connection_status.get(key))) {
                                    temp.set(1, "free");
                                    available_devices++;
                                    System.out.println("Available devices count" + available_devices);
                                    connection_status.put(key, temp);
                                    break;
                                }
                            }

                            //if all rows have been received
                            if (rowsReceivedSlaveCheck.size() == total_rows) {
                                viewReceivedButton.setEnabled(true);
                                viewPerformanceButton.setEnabled(true);
                                finish_time_off = System.nanoTime();
                                result_receive_time_check = 1;
                                sendButton.setEnabled(true);
                                sendButton.setText("Send Matrix");

                                offloadingHandler.removeCallbacks(offloadingReceiver);

                                for (BluetoothSocket socket : btSocketList) {
                                    sendReceive = new SendReceiveHandler(socket);
                                    sendReceive.start();
                                    sendReceive.write(dataConversionSerial.objectToByteArray(deviceName + ":Battery Level:Send Battery Level"));
                                }

                                //calculating matrix at master to check time difference
                                start_time_master = System.nanoTime();
                                battery_level_start_master = battery_level_master;
                                int[][] outputCalcAtMaster = new int[total_rows][total_rows];
                                for (int i = 0; i < total_rows; i++) {
                                    for (int j = 0; j < total_rows; j++) {
                                        outputCalcAtMaster[i][j] = 0;
                                        for (int k = 0; k < total_rows; k++) {
                                            outputCalcAtMaster[i][j] += inputs_A[i][k] * inputs_B[k][j];
                                        }
                                    }
                                }
                                finish_time_master = System.nanoTime();

                                //refresh
                                battery_slave_delta = new HashMap<String, Integer>();
                                rows_left = new ArrayList<Integer>();
                                row_sent_time = new HashMap<Integer, Long>();
                                statusTextContent = "";
                                battery_status = "";

                                Toast.makeText(getApplicationContext(),"All rows received from slaves", Toast.LENGTH_LONG).show();

                            }
                            //if there are rows that are yet to be calculated send that rows to available devices
                            if (result_receive_time_check == 0 && available_devices > 0 && btSocketList.size() > 0 && received_rows != total_rows && battery_level_master >= battery_thresh) {
                                sendMessages();
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (ClassNotFoundException e) {
                        e.printStackTrace();
                    }
                    break;
            }
            return true;
        }
    });

    //--------------------------------------------------------------------------------------------------------
    //for location of device

    @SuppressLint("MissingPermission")
    private void getLastLocation() {
        // check if permissions are given
        if (checkPermissions()) {
            // check if location is enabled
            if (isLocationEnabled()) {
                // getting last location from FusedLocationClient object
                mFusedLocationClient.getLastLocation().addOnCompleteListener(new OnCompleteListener<Location>() {
                    @Override
                    public void onComplete(@NonNull Task<Location> task) {
                        Location location = task.getResult();
                        if (location == null) {
                            requestNewLocationData();
                        } else {
                            myLatitude = Double.toString(location.getLatitude());
                            myLongitude = Double.toString(location.getLongitude());
                            textLocation.setText("My location: " + myLatitude + ", " + myLongitude);
                        }
                    }
                });
            } else {
                Toast.makeText(this, "Please turn on your location", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                startActivity(intent);
            }
        } else {
            // if permissions aren't available request for permissions
            requestPermissions();
        }
    }

    @SuppressLint("MissingPermission")
    private void requestNewLocationData() {

        // Initializing LocationRequest
        // object with appropriate methods
        LocationRequest mLocationRequest = new LocationRequest();
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        mLocationRequest.setInterval(5);
        mLocationRequest.setFastestInterval(0);
        mLocationRequest.setNumUpdates(1);

        // setting LocationRequest
        // on FusedLocationClient
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        mFusedLocationClient.requestLocationUpdates(mLocationRequest, mLocationCallback, Looper.myLooper());
    }

    private LocationCallback mLocationCallback = new LocationCallback() {

        @Override
        public void onLocationResult(LocationResult locationResult) {
            Location mLastLocation = locationResult.getLastLocation();
            myLatitude = Double.toString(mLastLocation.getLatitude());
            myLongitude = Double.toString(mLastLocation.getLongitude());
            textLocation.setText("My location: " + myLatitude + ", " + myLongitude);
        }
    };

    // method to check for permissions
    private boolean checkPermissions() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    // request for location permissions
    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_ID);
    }

    // check if location is enabled
    private boolean isLocationEnabled() {
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    @Override
    public void
    onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_ID) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getLastLocation();
            }
        }
    }


}
