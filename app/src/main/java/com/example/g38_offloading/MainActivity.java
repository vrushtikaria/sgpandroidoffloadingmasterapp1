package com.example.g38_offloading;

import static java.lang.Double.parseDouble;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

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
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    //Buttons, lists and other elements
    Button scanButton,sendButton, slaveBatteryButton, slaveLocationButton;
    ListView listViewDevices;
    TextView statusText;
    TextView textLocation;
    TextView textBattery;
    String statusTextContent = "";
    TextView infoBox;
    EditText matrixSize;
    TextView info;

    String deviceName;

    //variables for location
    FusedLocationProviderClient mFusedLocationClient;
    int PERMISSION_ID = 44;
    String myLatitude, myLongitude;

    //variables for bluetooth connections
    int REQUEST_ENABLE_BLUETOOTH=1;
    BluetoothAdapter bluetoothAdapter;
    BluetoothDevice[] btDevices;
    int available_devices=0; //maintained at master to keep track of number of available slaves that were free
    ArrayAdapter<String> arrayAdapter; //maintained at master to show list of available devices in list view
    ArrayList<BluetoothDevice> stringArrayList= new ArrayList<>(); //maintains the list of connected devices
    ArrayList<BluetoothSocket> connected_socket= new ArrayList<>(); //maintains the list of connected sockets
    private static final String APP_NAME= "MobOffloading";
    private static final UUID MY_UUID=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    SendReceiveHandler sendReceive;
    DataConversionSerial dataConversionSerial;

    //Maps to store slave details such as connection status, battery levels, location etc.
    Map<BluetoothSocket,ArrayList<String>> connection_status=new HashMap<BluetoothSocket, ArrayList<String>>(); //maintained at master to keep track of whether the slave was busy and free
    Map<String,Integer> battery_slave_delta =new HashMap<String,Integer>(); //battery level drop of slaves
    Map<String,Integer> battery_slave_initial =new HashMap<String,Integer>(); //initial battery level of slaves
    Map<String,String> location_slave_initial =new HashMap<String,String>();
    Map<Integer,Long> row_sent_time=new HashMap<Integer, Long>(); //maintained at master to check at what time row was sent to slave
    ArrayList<Integer> row_check=new ArrayList<Integer>(); //maintained at master to check what all rows are yet to be sent
    Map<Integer,String> outputRows_check=new HashMap<Integer, String>(); //maintained at master to check what all rows were received from slave

    //battery level related variables
    int battery_level_master = 0; ///battery level of the master
    int battery_thresh =30; //battery threshold for offloading
    int battery_level_start=0;
    String battery_status ="";

    //connections status values
    static final int STATE_LISTENING =1;
    static final int STATE_CONNECTING=2;
    static final int STATE_CONNECTED=3;
    static final int STATE_CONNECTION_FAILED=4;
    static final int STATE_MESSAGE_RECEIVED=5;
    static final int STATE_BATTERY_LOW=6;
    static final int STATE_DISCONNECTED=7;

    //matrix related variables
    int total_rowcount=0; //maintained at master to maintain how many rows were there in matrix
    int output_rowcount=0; //maintained at master to check how many rows were received
    int broadcasting_started=1; //maintained at master so that if row result was not received even after 5 seconds from one slave it can be sent to other available slaves
    int[][] inputs_A;
    int[][] inputs_B;
    int[][] output_Array;

    //performance monitoring variables
    long start_time; //maintained at master to keep track of time at which matrix multiplication was started either by mobile offloading or without offloading
    long finish_time; //maintained at master to keep track of time at which matrix multiplication was completed either by mobile offloading or without offloading

    private Handler offloadingHandler=new Handler();  //handles the connection status and messages received

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayShowHomeEnabled(true);
        actionBar.setIcon(R.mipmap.ic_launcher);

        //Initialising Buttons, lists and other elements
        listViewDevices=(ListView) findViewById(R.id.listViewDevices);
        slaveBatteryButton = (Button) findViewById(R.id.slaveBatteryButton);
        slaveLocationButton = (Button) findViewById(R.id.slaveLocationButton);
        textBattery = (TextView) findViewById(R.id.textBattery);
        textLocation = (TextView) findViewById(R.id.textLocation);
        scanButton = (Button) findViewById(R.id.scanBtn);
        sendButton = (Button) findViewById(R.id.sendBtn);
        statusText = (TextView) findViewById(R.id.statusText);
        matrixSize = (EditText) findViewById(R.id.matrixSize);
        infoBox = (TextView) findViewById(R.id.infoText);
        info = (TextView) findViewById(R.id.info_popup);
        infoBox.setMovementMethod(ScrollingMovementMethod.getInstance());

        dataConversionSerial = new DataConversionSerial();
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        // method to get the location
        getLastLocation();



        //Below will enable the bluetooth in the device in case it's disables
        bluetoothAdapter=BluetoothAdapter.getDefaultAdapter();

        if(!bluetoothAdapter.isEnabled())
        {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent,REQUEST_ENABLE_BLUETOOTH);
        }

        this.registerReceiver(this.mBatInfoReceiver,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        deviceName = BluetoothAdapter.getDefaultAdapter().getName(); //get the bluetooth name of the device

        setListeners();

    }

    //below broadcast Receiver is getting the battery level of the device. It will always show the latest value of the batter level
    private BroadcastReceiver mBatInfoReceiver= new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            battery_level_master=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,0);
            textBattery.setText("My battery level: "+String.valueOf(battery_level_master)+"%");
        }
    };

    //--------------------------------------------------------------------------------------------------------
    //setting all listeners
    void setListeners(){

        //initialise scan button
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stringArrayList= new ArrayList<>();
                bluetoothAdapter.startDiscovery();
                IntentFilter intentFilterConnection=new IntentFilter(BluetoothDevice.ACTION_FOUND);
                registerReceiver(myReceiver,intentFilterConnection);
            }
        });


        slaveBatteryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // inflate the layout of the popup window
                LayoutInflater inflater = (LayoutInflater)
                        getSystemService(LAYOUT_INFLATER_SERVICE);
                View popupView = inflater.inflate(R.layout.popup, null);

                // create the popup window
                int width = LinearLayout.LayoutParams.WRAP_CONTENT;
                int height = LinearLayout.LayoutParams.WRAP_CONTENT;
                boolean focusable = true; // lets taps outside the popup also dismiss it
                final PopupWindow popupWindow = new PopupWindow(popupView, width, height, focusable);

                if (!battery_slave_initial.isEmpty()) {
                    String information = "";
                    for (Map.Entry<String, Integer> entry : battery_slave_initial.entrySet()) {
                        String key = entry.getKey();
                        Integer value = entry.getValue();
                        information += key + ": " + Integer.toString(value) + "/n";
                    }
                    info.setText(information);
                }

                popupWindow.showAtLocation(popupView, Gravity.CENTER, 0, 0);

                // dismiss the popup window when touched
                popupView.setOnTouchListener(new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        popupWindow.dismiss();
                        return true;
                    }
                });

            }
        });

        slaveLocationButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // inflate the layout of the popup window
                LayoutInflater inflater = (LayoutInflater)
                        getSystemService(LAYOUT_INFLATER_SERVICE);
                View popupView = inflater.inflate(R.layout.popup, null);

                // create the popup window
                int width = LinearLayout.LayoutParams.WRAP_CONTENT;
                int height = LinearLayout.LayoutParams.WRAP_CONTENT;
                boolean focusable = true; // lets taps outside the popup also dismiss it
                final PopupWindow popupWindow = new PopupWindow(popupView, width, height, focusable);

                if (!location_slave_initial.isEmpty()) {
                    String information = "";
                    for (Map.Entry<String, String> entry : location_slave_initial.entrySet()) {
                        String key = entry.getKey();
                        String value = entry.getValue();
                        information += key + ": " + value + "/n";
                    }
                    info.setText(information);
                }

                popupWindow.showAtLocation(popupView, Gravity.CENTER, 0, 0);

                // dismiss the popup window when touched
                popupView.setOnTouchListener(new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        popupWindow.dismiss();
                        return true;
                    }
                });
            }
        });


        //to process what to do on selecting an available device from the list
        listViewDevices.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

                ConnectSlave connectSlave = new ConnectSlave(btDevices[position]);
                connectSlave.start();
                statusText.setText("Connecting");
            }
        });

        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //If batter level less than threshold disconnect from all the connected slaves and communicate to slaves to disconnect
                if(battery_level_master<battery_thresh)
                {
                    statusText.setText("Battery Low can't connect");
                    for(BluetoothSocket socket:connected_socket)
                    {
                        sendReceive = new SendReceiveHandler(socket);
                        sendReceive.start();
                        try {
                            sendReceive.write(dataConversionSerial.objectToByteArray((deviceName + ":Battery Level:Batter level is low").toString()));
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    connected_socket=new ArrayList<BluetoothSocket>();
                    connection_status=new HashMap<BluetoothSocket, ArrayList<String>>();
                    available_devices=0;

                }
                //If matrix size is entered with in the limits of 2 and 13 and if there are available devices master is ready to offload
                if(matrixSize.getText().toString()!=null && !matrixSize.getText().toString().isEmpty() && available_devices>0 && Integer.parseInt(matrixSize.getText().toString())<13 && Integer.parseInt(matrixSize.getText().toString())>=2) {
                    sendButton.setEnabled(false);
                    output_rowcount = 0;
                    offloadingReceiver.run(); //start failure recovery algorithm in background which will continuously check if any row result is not received in 5 seconds once sent till offloading is done
                    total_rowcount = Integer.parseInt(matrixSize.getText().toString());

                    for (int i = 0; i < total_rowcount; i++) {
                        row_check.add(i);
                    }

                    //randomly generate 2 square matrices of size total_rowcount*total_rowcount
                    inputs_A = new int[total_rowcount][total_rowcount];
                    inputs_B = new int[total_rowcount][total_rowcount];
                    output_Array = new int[total_rowcount][total_rowcount];
                    inputs_A = generateRandom(total_rowcount, total_rowcount);
                    inputs_B = generateRandom(total_rowcount, total_rowcount);

                    //Start sending messages to available slaves
                    try {
                        battery_level_start = battery_level_master;
                        start_time = System.nanoTime();
                        sendMessages();
                        broadcasting_started = 0;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                //If there are not available devices display the message to master that no devices are available
                else if(available_devices<=0)
                {
                    Toast.makeText(getApplicationContext(),"No devices available",Toast.LENGTH_LONG).show();
                }
                //entered matrix size is not within the limits of 2 & 13
                else if((matrixSize.getText().toString()!=null && !matrixSize.getText().toString().isEmpty()) && (Integer.parseInt(matrixSize.getText().toString())>=13 || Integer.parseInt(matrixSize.getText().toString())<2))
                {
                    Toast.makeText(getApplicationContext(),"Please enter Matrix Size between 2 & 13",Toast.LENGTH_LONG).show();
                }
                //Matrix size is not given
                else
                {
                    Toast.makeText(getApplicationContext(),"Please enter Matrix Size",Toast.LENGTH_LONG).show();
                }
            }
        });

    }


    //--------------------------------------------------------------------------------------------------------
    /*
    Updates listview with available bluetooth devices
     */
    BroadcastReceiver myReceiver=new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action=intent.getAction();
            if(BluetoothDevice.ACTION_FOUND.equals(action))
            {
                BluetoothDevice device=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if(device.getName()!=null)
                {
                    int count=0;
                    for(int i=0;i<stringArrayList.size();i++)
                    {
                        if(!(stringArrayList.get(i).getName()).equals(device.getName()))
                        {
                            count++;
                        }
                    }
                    if(count==stringArrayList.size())
                    {
                        stringArrayList.add(device);
                    }

                    String[] strings=new String[stringArrayList.size()];
                    btDevices=new BluetoothDevice[stringArrayList.size()];
                    int index=0;

                    if(stringArrayList.size()>0)
                    {
                        for(BluetoothDevice device1: stringArrayList)
                        {
                            btDevices[index]=device1;
                            strings[index]=device1.getName();
                            index++;
                        }

                        arrayAdapter=new ArrayAdapter<String>(getApplicationContext(),android.R.layout.simple_list_item_1,strings);
                        listViewDevices.setAdapter(arrayAdapter);
                    }
                }

            }
        }
    };

    //
    private Runnable offloadingReceiver=new Runnable() {
        @Override
        public void run() {
            if(broadcasting_started==0 && output_rowcount!=total_rowcount)
            {
                for(int row_number:row_sent_time.keySet())
                {
                    if (!row_check.contains(row_number) && !outputRows_check.containsKey(row_number))
                    {

                        long current_time=System.nanoTime();
                        if(TimeUnit.NANOSECONDS.toMillis(current_time-row_sent_time.get(row_number))>5000)
                        {
                            System.out.println("Row number not sent:"+row_number);
                            row_check.add(row_number);
                            System.out.println(row_check.get(0));
                            sendMessages();
                        }
                    }
                }
            }
            //this will make sure this fuction is always running without any delay when this function is started
            offloadingHandler.postDelayed(this,0);
        }
    };


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
                            myLatitude=Double.toString(location.getLatitude());
                            myLongitude=Double.toString(location.getLongitude());
                            textLocation.setText("My location: "+ myLatitude + ", " + myLongitude);
                        }
                    }
                });
            } else {
                Toast.makeText(this, "Please turn on" + " your location...", Toast.LENGTH_LONG).show();
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
            myLatitude=Double.toString(mLastLocation.getLatitude());
            myLongitude=Double.toString(mLastLocation.getLongitude());
            textLocation.setText("My location: "+ myLatitude + ", " + myLongitude);
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

    //--------------------------------------------------------------------------------------------------------

    //sendMessage function will send the row by row of matrixA and entire matrixB and row number to available slaves which are free
    private void sendMessages()
    {
        Log.d("Inside message","I am in sendMessages");
        //If battery level less than threshold disconnect from all the connected slaves and communicate to slaves to disconnect
        if(battery_level_master<battery_thresh)
        {
            statusText.setText("Battery Low can't connect");
            for(BluetoothSocket socket1:connected_socket)
            {
                sendReceive = new SendReceiveHandler(socket1);
                sendReceive.start();
                try {
                    sendReceive.write(dataConversionSerial.objectToByteArray((deviceName + ":Battery Level:Batter level is low").toString()));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            connected_socket=new ArrayList<BluetoothSocket>();
            connection_status=new HashMap<BluetoothSocket, ArrayList<String>>();
            available_devices=0;
            return;

        }
        //Checks if there are any free slaves to offload
        if(available_devices>0) {
            for (BluetoothSocket socket : connected_socket) {
                //Checks if there are any rows that are yet to be sent
                if (socket != null && connection_status.get(socket).get(1) == "free" && output_rowcount != total_rowcount) {
                    //If batter level less than threshold disconnect from all the connected slaves and communicate to slaves to disconnect
                    if(battery_level_master<battery_thresh)
                    {
                        statusText.setText("Battery Low can't connect");
                        for(BluetoothSocket socket1:connected_socket)
                        {
                            sendReceive = new SendReceiveHandler(socket1);
                            sendReceive.start();
                            try {
                                sendReceive.write(dataConversionSerial.objectToByteArray((deviceName + ":Battery Level:Batter level is low").toString()));
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        connected_socket=new ArrayList<BluetoothSocket>();
                        connection_status=new HashMap<BluetoothSocket, ArrayList<String>>();
                        available_devices=0;
                        break;

                    }
                    try {
                        //Check if there are any rows that are yet to be sent
                        if (row_check.size() > 0) {
                            sendReceive = new SendReceiveHandler(socket);
                            sendReceive.start();

                            ArrayList<String> temp = connection_status.get(socket);
                            temp.set(1, "busy");
                            available_devices--;
                            connection_status.put(socket, temp);
                            Log.d("MySocket", connection_status.get(socket).get(1));
                            int temp_row = row_check.get(0);
                            row_check.remove(0);
                            row_sent_time.put(temp_row, System.nanoTime());
                            serialDecoder request = new serialDecoder(inputs_A[temp_row], inputs_B, temp_row);

                            sendReceive.write(dataConversionSerial.objectToByteArray(request));


                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    //index_inputs++;
                }

            }
        }

    }

    //This function generate matrix of size rc*cc with random numbers between 1 & 10
    private int[][] generateRandom(int rc,int cc)
    {
        Random rand=new Random();
        int[][] random_array=new int[rc][cc];
        for(int i=0;i<rc;i++)
        {
            for(int j=0;j<cc;j++)
            {
                random_array[i][j]=rand.nextInt(10);
            }
        }
        return random_array;
    }


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
                    connected_socket.remove(bluetoothSocket);
                    location_slave_initial.remove(bluetoothSocket);
                    battery_slave_initial.remove(bluetoothSocket);

                }
                e.printStackTrace();
            }

            inputStream = tempIn;
            outputStream = tempOut;
        }

        //This will always check if there is any message that has to be sent to a particular socket form this device by always reading the output stream with the help of input stream
        public void run()
        {
            byte[] buffer=new byte[102400];
            int bytes;

            while(battery_level_master>=battery_thresh)
            {
                try
                {
                    if(connection_status.containsKey(bluetoothSocket)) {
                        bytes = inputStream.read(buffer);
                        handler.obtainMessage(STATE_MESSAGE_RECEIVED, bytes, -1, buffer).sendToTarget();
                    }

                }
                catch (IOException e)
                {
                    if(connection_status.containsKey(bluetoothSocket))
                    {
                        if(connection_status.get(bluetoothSocket).get(1)=="free"){
                            available_devices--;
                        }
                        connection_status.remove(bluetoothSocket);
                        connected_socket.remove(bluetoothSocket);
                        location_slave_initial.remove(bluetoothSocket);
                        battery_slave_initial.remove(bluetoothSocket);

                    }

                    e.printStackTrace();
                }
            }
            if(battery_level_master<battery_thresh)
            {

                try
                {
                    write(dataConversionSerial.objectToByteArray(deviceName +":Battery Level:Batter level is low"));
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
                try
                {
                    bytes=inputStream.read(buffer);
                    handler.obtainMessage(STATE_MESSAGE_RECEIVED,bytes,-1,buffer).sendToTarget();
                }
                catch (IOException e)
                {
                    if(connection_status.containsKey(bluetoothSocket))
                    {
                        if(connection_status.get(bluetoothSocket).get(1)=="free"){
                            available_devices--;
                        }
                        connection_status.remove(bluetoothSocket);
                        connected_socket.remove(bluetoothSocket);
                        location_slave_initial.remove(bluetoothSocket);
                        battery_slave_initial.remove(bluetoothSocket);
                    }
                    e.printStackTrace();
                    Message message=Message.obtain();
                    message.what=STATE_CONNECTION_FAILED;
                    handler.sendMessage(message);
                }
            }

        }
        //This function is used to write the message to the output stream of the particular device that has to be sent to certain socket
        public void write(byte[] bytes)
        {
            try
            {
                if(battery_level_master>=battery_thresh)
                {
                    System.out.println("In outputstream: "+bytes);
                    outputStream.write(bytes);
                }
            }
            catch (IOException e)
            {
                e.printStackTrace();
                Message message=Message.obtain();
                message.what=STATE_CONNECTION_FAILED;
                handler.sendMessage(message);
            }
        }

    }


    //--------------------------------------------------------------------------------------------------------
    //Class to connect to slave with Bluetooth

    private class ConnectSlave extends Thread
    {
        private BluetoothDevice device;
        private BluetoothSocket socket;

        public ConnectSlave(BluetoothDevice device1)
        {
            device=device1;

            try {
                if(battery_level_master>=battery_thresh)
                {
                    socket = device.createRfcommSocketToServiceRecord(MY_UUID);
                }
                else
                {
                    listViewDevices.setAdapter(null);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void run()
        {
            try
            {
                if(battery_level_master>=battery_thresh)
                {
                    socket.connect();
                    Message message = Message.obtain();
                    message.what = STATE_CONNECTED;
                    sendReceive=new SendReceiveHandler(socket);
                    sendReceive.start();
                    handler.sendMessage(message);

                    connected_socket.add(socket);
                    //if the slave socket in not already in the connected devices socket list it will added to that list at master
                    if(!connection_status.containsKey(socket))
                    {
                        ArrayList<String> temp=new ArrayList<String>();
                        temp.add(device.getName());
                        temp.add("free");
                        available_devices++;
                        connection_status.put(socket,temp);
                    }

                }
                else
                {
                    Message message = Message.obtain();
                    message.what = STATE_BATTERY_LOW;
                    handler.sendMessage(message);
                    listViewDevices.setAdapter(null);
                }

            }
            catch (IOException e)
            {
                e.printStackTrace();
                Message message=Message.obtain();
                message.what=STATE_CONNECTION_FAILED;
                handler.sendMessage(message);
            }
        }
    }



    //Get the distance between two locations here between two devices in miles
    private static double distance_latlong(double lat1, double lon1, double lat2, double lon2) {
        if ((lat1 == lat2) && (lon1 == lon2)) {
            return 0;
        }
        else {
            double theta = lon1 - lon2;
            double dist = Math.sin(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2)) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(Math.toRadians(theta));
            dist = Math.acos(dist);
            dist = Math.toDegrees(dist);
            dist = dist * 60 * 1.1515;

            return (dist);
        }
    }

    //This handler will handle what to do based on the msg received and sets the message in status according to it
    Handler handler=new Handler(new Handler.Callback()
    {
        @Override
        public boolean handleMessage(@NonNull Message msg)
        {
            switch(msg.what) {
                case STATE_LISTENING:
                    statusText.setText("Listening");
                    break;
                case STATE_CONNECTING:
                    statusText.setText("Connecting");
                    break;
                case STATE_CONNECTED:
                    statusText.setText("Connected");
                    break;
                case STATE_CONNECTION_FAILED:
                    statusText.setText("Connection Failed");
                    break;
                case STATE_DISCONNECTED:
                    statusText.setText("Disconnected");
                    break;

                //this functionality will be called if slave has received message from master or vice versa
                case STATE_MESSAGE_RECEIVED:

                    //If batter level less than threshold disconnect from all the connected slaves and communicate to slaves to disconnect
                    if (battery_level_master < battery_thresh) {
                        statusText.setText("Battery Low can't connect");
                        for (BluetoothSocket socket : connected_socket) {
                            sendReceive = new SendReceiveHandler(socket);
                            sendReceive.start();
                            try {
                                sendReceive.write(dataConversionSerial.objectToByteArray((deviceName + ":Battery Level: Battery level is low").toString()));
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        connected_socket = new ArrayList<BluetoothSocket>();
                        connection_status = new HashMap<BluetoothSocket, ArrayList<String>>();
                        available_devices = 0;

                    }
                    byte[] readBuff = (byte[]) msg.obj;
                    try {
                        //message received from slave
                        Object o = dataConversionSerial.byteArrayToObject(readBuff);
                        //if object is of type string normal message related to connection status or battery level is received by master from slave
                        if (o instanceof String) {
                            String tempMsg = (String) o;
                            String[] messages = tempMsg.split(":");
                            //If battery level low message is being received from slave disconnect to that slave and remove that slave from connected devices
                            if (messages[2].equals("Batter level is low")) {
                                ArrayList<String> temp = new ArrayList<>();
                                ArrayList<String> temp1 = new ArrayList<>();
                                temp.add(messages[0]);
                                temp.add("busy");
                                temp1.add(messages[0]);
                                temp1.add("free");
                                for (BluetoothSocket key : connection_status.keySet()) {
                                    if (temp.equals(connection_status.get(key)) || temp1.equals(connection_status.get(key))) {
                                        available_devices--;
                                        connected_socket.remove(key);
                                        connection_status.remove(key);
                                        location_slave_initial.remove(key);
                                        battery_slave_initial.remove(key);
                                        Toast.makeText(getApplicationContext(), "Removing device" + connected_socket.size(), Toast.LENGTH_LONG).show();
                                        battery_status += "Batter level is low at " + messages[0] + " hence disconnected" + "\n";
                                        infoBox.setText(battery_status);
                                        break;
                                    }
                                }

                            }
                                /*if slave has sent a msg to master asking it to set it to free master will set the availability status of slave to free.
                                This will happen when slave has accepted offloading after rejecting offloading and that time offloading at master is already done
                                 */
                            else if (messages[2].equals("SetMeFree")) {
                                ArrayList<String> temp = new ArrayList<>();
                                temp.add(messages[0]);
                                temp.add("busy");
                                for (BluetoothSocket key : connection_status.keySet()) {
                                    if (temp.equals(connection_status.get(key))) {
                                        temp.set(1, "free");
                                        System.out.println("I am set to free");
                                        available_devices++;
                                        System.out.println("Available devices count" + available_devices);
                                        connection_status.put(key, temp);
                                        break;
                                    }
                                }

                            }
                                /*
                                Otherwise slave has sent just it's battery level stats to master
                                 */
                            else {
                                //if the battery is not in the Map already
                                if (!battery_slave_initial.containsKey(messages[0])) {
                                    battery_status += messages[0] + ":" + messages[1] + ":" + messages[2] + "\n";
                                    infoBox.setText(battery_status.trim());
                                    //If slave is connecting to master for the first time slave will send it's battery level information and GPS location to master
                                    if (messages.length > 3) {
                                        Double dist = distance_latlong(parseDouble(myLatitude), Double.parseDouble(myLongitude), Double.parseDouble(messages[4].split(",")[0]), Double.parseDouble(messages[4].split(",")[1]));

                                        //if slave is within 0.1 mile radius then only connect
                                        if (dist <= 0.1) {
                                            battery_slave_initial.put(messages[0], Integer.parseInt(messages[2]));
                                            location_slave_initial.put(messages[0], messages[4]);
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
                                                    Toast.makeText(getApplicationContext(), "Device too far....so disconnecting", Toast.LENGTH_LONG).show();
                                                    connection_status.remove(socket);
                                                    connected_socket.remove(socket);
                                                    location_slave_initial.remove(socket);
                                                    battery_slave_initial.remove(socket);
                                                    available_devices--;
                                                }
                                            }
                                        }

                                        System.out.println(dist);
                                    }
                                    //If slave is connecting to master for the first time slave will send it's battery level information and it hasn't sent it's GPS location
                                    else {
                                        battery_slave_initial.put(messages[0], Integer.parseInt(messages[2]));
                                    }
                                }
                                //otherwise slave is sending it's battery stats post completion of offloading so that master can check the batter level drop at slaves
                                if (battery_slave_initial.containsKey(messages[0]) && output_rowcount == total_rowcount) {
                                    battery_slave_delta.put(messages[0], Math.abs(battery_slave_initial.get(messages[0]) - Integer.parseInt(messages[2])));
                                }
                            }
                        }
                        //Master has received the matrix multiplication row result as response from slave
                        else {
                            serialEncoder tempMsg = (serialEncoder) o;
                            ArrayList<String> temp = new ArrayList<>();
                            //if output result doesn't contain the row result sent by slave it will be stored in output result
                            if (!outputRows_check.containsKey(tempMsg.getRow())) {
                                statusTextContent += tempMsg.getDeviceName() + ": Received row " + tempMsg.getRow() + ": " + Arrays.toString(tempMsg.getrowResult()) + "\n";
                                infoBox.setText(statusTextContent.trim());
                                output_Array[tempMsg.getRow()] = tempMsg.getrowResult();
                                output_rowcount++;
                                outputRows_check.put(tempMsg.getRow(), "received");
                            }
                            //Once receiving result from slave master will set slaves availablity status to free
                            temp.add(tempMsg.getDeviceName());
                            temp.add("busy");
                            for (BluetoothSocket key : connection_status.keySet()) {
                                if (temp.equals(connection_status.get(key))) {
                                    temp.set(1, "free");
                                    System.out.println("I am set to free");
                                    available_devices++;
                                    System.out.println("Available devices count" + available_devices);
                                    connection_status.put(key, temp);
                                    break;
                                }
                            }
                                /*if master has received results of all the rows it will calculate the matrix multiplication without offloading at its end display below stats
                                    .  Output Result
                                    .  Battery level drop at servers
                                    .  Battery level drop at master because of offloading
                                    .  Time taken for matrix multiplication by offloading in nano seconds
                                    .  Time taken for matrix multiplication without offloading in nano seconds
                                    .  Battery level drop at master for matrix multiplication without offloading
                                 */
                            if (outputRows_check.size() == total_rowcount) {
                                finish_time = System.nanoTime();
                                broadcasting_started = 1;
                                offloadingHandler.removeCallbacks(offloadingReceiver);
                                int batter_change_off = battery_level_start - battery_level_master;
                                for (BluetoothSocket socket : connected_socket) {
                                    sendReceive = new SendReceiveHandler(socket);
                                    sendReceive.start();
                                    sendReceive.write(dataConversionSerial.objectToByteArray(deviceName + ":Battery Level:Send Battery Level"));
                                }

                                String output_print = "[";
                                for (int i = 0; i < output_Array.length; i++) {
                                    if (i != output_Array.length - 1) {
                                        output_print += Arrays.toString(output_Array[i]) + "\n";
                                    } else {
                                        output_print += Arrays.toString(output_Array[i]);
                                    }
                                }
                                output_print += "]\nBattery Levels drop at Slaves:\n";
                                for (String server_device : battery_slave_delta.keySet()) {
                                    output_print += server_device + " : " + battery_slave_delta.get(server_device) + "\n";
                                }
                                output_print += "Battery Level drop at Master:" + batter_change_off + "\n";
                                output_print += "Time taken by MobOff(ns)= " + (finish_time - start_time) + "\n";

                                start_time = System.nanoTime();
                                battery_level_start = battery_level_master;
                                int[][] output_Array_master = new int[total_rowcount][total_rowcount];
                                for (int i = 0; i < total_rowcount; i++) {
                                    for (int j = 0; j < total_rowcount; j++) {
                                        output_Array_master[i][j] = 0;
                                        for (int k = 0; k < total_rowcount; k++) {
                                            output_Array_master[i][j] += inputs_A[i][k] * inputs_B[k][j];
                                        }
                                    }
                                }
                                finish_time = System.nanoTime();

                                output_print += "Time taken without MobOff(ns)= " + (finish_time - start_time) + "\n";
                                output_print += "Battery Level drop without offloading at Master:" + (battery_level_start - battery_level_master);
                                //Set all the variables to null so that master will be available for next offloading
                                battery_slave_delta = new HashMap<String, Integer>();
                                outputRows_check = new HashMap<Integer, String>();
                                row_check = new ArrayList<Integer>();
                                row_sent_time = new HashMap<Integer, Long>();
                                statusTextContent = "";
                                battery_status = "";


                                infoBox.setText("Output received from slaves: \n" + output_print.trim());


                            }
                            //if there are rows that are yet to be calculated send that rows to available devices
                            if (broadcasting_started == 0 && available_devices > 0 && connected_socket.size() > 0 && output_rowcount != total_rowcount && battery_level_master >= battery_thresh) {
                                sendMessages();
                            }
                        }

                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (ClassNotFoundException e) {
                        e.printStackTrace();
                    }
                    break;
                case STATE_BATTERY_LOW:
                    statusText.setText("Battery Low Can't connect");
                    break;
            }
            return true;
        }
    });

}

