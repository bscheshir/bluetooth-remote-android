package ru.bscheshir.bluetoothremote;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Message;
import android.os.SystemClock;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements View.OnClickListener, Button.OnLongClickListener, SeekBar.OnSeekBarChangeListener {
    private static final String TAG = "BLUETOOTH";
    // SPP UUID сервиса. Должен совпадать с прописанным в мозгах НС-06
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    //Экземпляры классов наших кнопок
    ToggleButton redButton;
    ToggleButton discoverButton;
    Button musicButton;
    Button stepperButton;
    TextView txtArduino;
    SeekBar seekBarVolume;

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        txtArduino.setText(Integer.toString(seekBarVolume.getProgress()));    // обновляем TextView
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {

    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        int progress = seekBarVolume.getProgress();
        //Пишем данные в выходной поток
        byte[] ba = new byte[4]; //пишем в поток массив из 4х байт.
        ba[2] = (byte) 1;
        ba[3] = (byte) progress;//-127..127
        mConnectedThread.write(ba);//используем специализированый тред. Из основного просто вызываем его ф-ю
    }

    @Override
    public boolean onLongClick(View v) {
        int pin = 1;
        int value = 0;
        if (v == musicButton){
            pin = 1;
            value = 2;
        }
        byte[] ba = new byte[4]; //пишем в поток массив из 4x байт.
        ba[0] = (byte) pin;
        ba[1] = (byte) value;
        mConnectedThread.write(ba);//используем специализированый тред. Из основного просто вызываем его ф-ю
        //Не нужна дальнейшая обработка события
        return true;
    }


    //Эта функция и будет вызываться при нажатии кнопок
    @Override
    public void onClick(View v) {

        //кнопка поиска. Создаём локальную переменную, аналогично onCreate
        if (v == discoverButton) {

            if(discoverButton.isChecked()) {
                mBluetoothAdapter.startDiscovery();
                tryConnect();
            } else {
                mBluetoothAdapter.cancelDiscovery();
            }
            return;
        }

        //Пытаемся послать данные
        int pin = 0;
        int value = 0;
        //В зависимости от того, какая кнопка была нажата,
        //изменяем данные для посылки
        if (v == redButton) {
            pin = 13;
            value = (redButton.isChecked() ? 1 : 0);// + 130;
        } else if (v == stepperButton) {
            pin = 12;
            value = 1;
        } else if (v == musicButton) {
            pin = 1;
            value = 1;
        }

        //Пишем данные в выходной поток
//            String message
//            byte[] msgBuffer = message.getBytes();
        byte[] ba = new byte[4]; //пишем в поток массив из 4x байт.
        ba[0] = (byte) pin;
        ba[1] = (byte) value;
        mConnectedThread.write(ba);//используем специализированый тред. Из основного просто вызываем его ф-ю
    }

    // Определяем несколько констант для типов сообщений очереди между main activity и другими потоками
    private interface MessageConstants {
        public static final int MESSAGE_NONE = 0;
        public static final int MESSAGE_READ = 1;
        public static final int MESSAGE_WRITE = 2;
        public static final int MESSAGE_TOAST = 3;
        // ... (Add other message types here as needed.)
    }

    private Handler mHandler; // handler для получения информации из Bluetooth. Отделённый хеадер с сообщениями
    private BluetoothAdapter mBluetoothAdapter = null;
    private StringBuilder sb = new StringBuilder(); //хранение принимаемой строки
    private ConnectedThread mConnectedThread; // тред, отвечающий за приём и отправку сообщений по bluetooth

    //Сокет, с помощью которого мы будем отправлять данные на Arduino
    BluetoothSocket clientSocket;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //"Соединям" вид кнопки в окне приложения с реализацией
        redButton = (ToggleButton) findViewById(R.id.toggleRedLed);
        stepperButton = (Button) findViewById(R.id.stepperButton);
        discoverButton = (ToggleButton) findViewById(R.id.discoverButton);
        musicButton = (Button) findViewById(R.id.musicButton);
        txtArduino = (TextView) findViewById(R.id.textViewArduino);      // для вывода текста, полученного от Arduino
        seekBarVolume = (SeekBar) findViewById(R.id.seekBarVolume);

        //Добавлем "слушатель нажатий" к кнопке
        redButton.setOnClickListener(this);
        stepperButton.setOnClickListener(this);
        discoverButton.setOnClickListener(this);
        //Добавляем слушатели на кнопку управления платой mp3 плеера/степпера
        musicButton.setOnClickListener(this);
        musicButton.setOnLongClickListener(this);
        //Добавляем слушатель к дифференцированой полоске
        seekBarVolume.setOnSeekBarChangeListener(this);

        // добавляем хандлер с встроеной очередью для приёма сообщений из тредов.
        // Его можно вызвать из другого треда и отправить себе сообщение
        // Сам же он может взаимодействовать с UI
        mHandler = new Handler() {
            public void handleMessage(android.os.Message msg) {
                switch (msg.what) {
                    case MessageConstants.MESSAGE_READ:                                     // если приняли сообщение в Handler
                        byte[] readBuf = (byte[]) msg.obj;
                        String incomingString = new String(readBuf, 0, msg.arg1);
                        sb.append(incomingString);                                          // формируем строку
                        int endOfLineIndex = sb.indexOf("\r\n");                            // определяем символы конца строки
                        if (endOfLineIndex > 0) {                                           // если встречаем конец строки,
                            String stringBufferLine = sb.substring(0, endOfLineIndex);      // то извлекаем строку
                            sb.delete(0, sb.length());                                      // и очищаем sb
                            txtArduino.setText(stringBufferLine);                           // обновляем TextView
                        }
                        Log.d(TAG, "...Строка:" + sb.toString() + "Байт:" + msg.arg1 + "...");
                        break;
                    case MessageConstants.MESSAGE_TOAST:
                        Toast.makeText(getApplicationContext(), msg.obj.toString(), Toast.LENGTH_LONG).show();
                        break;
                }
            }
        };

        tryConnect();

        // Регистрация оповещения/широковещания, когда устройство найдено
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        registerReceiver(mReceiver, filter);

        //Выводим сообщение об успешном подключении
        Message toastMsg = mHandler.obtainMessage(MessageConstants.MESSAGE_TOAST, "CONNECTED");
        toastMsg.sendToTarget();
    }

    @Override
    public void onStop() {
        super.onStop();
        mConnectedThread.cancel();
        mConnectedThread = null;
    }

    @Override
    public void onRestart() {
        super.onRestart();
        mConnectedThread = new ConnectedThread(clientSocket);
        mConnectedThread.start();
    }

    //подключение вынесем в отдельный метод
    public void tryConnect() {

        //Включаем bluetooth. Если он уже включен, то ничего не произойдет
        String enableBT = BluetoothAdapter.ACTION_REQUEST_ENABLE;
        startActivityForResult(new Intent(enableBT), 0);

        //Мы хотим использовать тот bluetooth-адаптер, который задается по умолчанию
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        //Устройство с данным адресом - наш Bluetooth
        //Адрес опредеяется следующим образом: установите соединение
        //между ПК и модулем (пин: 1234), а затем посмотрите в настройках
        //соединения адрес модуля. Скорее всего он будет аналогичным.

        //Читаем значения по умолчанию
        String defaultDeviceName = getResources().getString(R.string.default_device_name);
        String defaultDeviceHardwareAddress = getResources().getString(R.string.default_device_hardware_address);
        //Читаем сохранённые в настройках значения
        SharedPreferences sharedPref = this.getSharedPreferences(
                getString(R.string.preference_file_key), Context.MODE_PRIVATE);
        String deviceName = sharedPref.getString(getString(R.string.device_name), defaultDeviceName);
        String deviceHardwareAddress = sharedPref.getString(getString(R.string.device_hardware_address), defaultDeviceHardwareAddress);

        //Перебираем сопряженные устройства
        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        if (pairedDevices.size() > 0) {
            // There are paired devices. Get the name and address of each paired device.
            for (BluetoothDevice device : pairedDevices) {
                String currentDeviceName = device.getName();
                if (deviceName.equalsIgnoreCase(currentDeviceName)) {
                    deviceHardwareAddress = device.getAddress(); // MAC address
                    break;
                }
            }
        }
        // Экономия ресурсов.
        mBluetoothAdapter.cancelDiscovery();

        //Установить указатель на удалённый узел с таким адресом
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(deviceHardwareAddress);

        //Пытаемся проделать эти действия
        try {

            //Две вещи необходимы для соединения - MAC address устройства
            //UUID сервиса (этого приложения)
//            clientSocket = device.createRfcommSocketToServiceRecord(MY_UUID);

            //Инициируем соединение с устройством альтернативным методом, без UUID
            Method m = device.getClass().getMethod("createRfcommSocket", new Class[]{int.class});
            clientSocket = (BluetoothSocket) m.invoke(device, 1);
            clientSocket.connect();

            mConnectedThread = new ConnectedThread(clientSocket);
            mConnectedThread.start();

//            Thread.State mConnectedThreadState = mConnectedThread.getState();
//            mHandler.obtainMessage(MessageConstants.MESSAGE_TOAST, mConnectedThreadState).sendToTarget();
//            String s = mConnectedThreadState.toString();

            //В случае появления любых ошибок, выводим в лог сообщение
        } catch (IOException e) {
            Log.d(TAG, e.getMessage());
        } catch (SecurityException e) {
            Log.d(TAG, e.getMessage());
        } catch (NoSuchMethodException e) {
            Log.d(TAG, e.getMessage());
        } catch (IllegalArgumentException e) {
            Log.d(TAG, e.getMessage());
        } catch (IllegalAccessException e) {
            Log.d(TAG, e.getMessage());
        } catch (InvocationTargetException e) {
            Log.d(TAG, e.getMessage());
        }
    }

    // Тред для получения найденого устройства
    // Создание приёмника BroadcastReceiver для ACTION_FOUND
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction(); // Намерение по фильтру
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // Поиск устройств нашел устройство. Получить объект BluetoothDevice и его инфо из намерения
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String deviceName = device.getName();
                String deviceHardwareAddress = device.getAddress(); // MAC address

                // Записать в общие настройки
                SharedPreferences sharedPref = context.getSharedPreferences(
                        getString(R.string.preference_file_key), Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sharedPref.edit();
                editor.putString(getString(R.string.device_name), deviceName);
                editor.putString(getString(R.string.device_hardware_address), deviceHardwareAddress);
                editor.commit();
            }
        }
    };


    //Тред для общения с блютузом. Для приёма в отрыве от MainActivity
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        private byte[] mmBuffer; // mmBuffer store for the stream

        public ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the input and output streams; using temp objects because
            // member streams are final.
            try {
                tmpIn = socket.getInputStream();
            } catch (IOException e) {
                Log.e(TAG, "Error occurred when creating input stream", e);
            }
            try {
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "Error occurred when creating output stream", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        //Отладчик упорно не желает попадать внутрь этого метода.
        //Однако, судя по результату, он вполне себе работает.
        public void run() {
            mmBuffer = new byte[256];
            int numBytes; // Количество байт, прочитаных read()

            // Прдолжаем слушать, пока не произойдёт ошибка
            while (true) {
                try {
                    // Читаем из InputStream.
                    numBytes = mmInStream.read(mmBuffer);
                    // Send the obtained bytes to the UI activity.
                    Message readMsg = mHandler.obtainMessage(
                            MessageConstants.MESSAGE_READ, numBytes, -1,
                            mmBuffer);
                    readMsg.sendToTarget();
                } catch (IOException e) {
                    Log.d(TAG, "Input stream was disconnected", e);
                    break;
                }
            }
        }

        // Вызывается в main activity для отправки сообщения по потоку вывода.
        public void write(byte[] bytes) {
            try {
                mmOutStream.write(bytes);
                SystemClock.sleep(30);
                // Расшарить буфер, из которого сейчас/ранее получали для UI
                // предполагается, что из него и отправляем в другое устройство
                Message writtenMsg = mHandler.obtainMessage(
                        MessageConstants.MESSAGE_WRITE, -1, -1, mmBuffer);
                writtenMsg.sendToTarget();
            } catch (IOException e) {
                Log.e(TAG, "Error occurred when sending data", e);

                // Отправить сообщение с ошибкой в activity.
                Message writeErrorMsg =
                        mHandler.obtainMessage(MessageConstants.MESSAGE_TOAST);
                Bundle bundle = new Bundle();
                bundle.putString("toast",
                        "Couldn't send data to the device");
                writeErrorMsg.setData(bundle);
                mHandler.sendMessage(writeErrorMsg);
            }
        }

        // Вызывается в main activity для закрытия соединения
        public void cancel() {
            //сначала потоки, потом сокет
            if (mmInStream != null) {
                try {
                    mmInStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "Could not close the input stream", e);
                }
            }
            if (mmOutStream != null) {
                try {
                    mmOutStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "Could not close the output stream", e);
                }
            }
            if (mmSocket != null) {
                try {
                    mmSocket.close();
                } catch (IOException e) {
                    Log.e(TAG, "Could not close the connect socket", e);
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Не забывать "разрегистрировать" приёмник действия нахождения устройства - ACTION_FOUND.
        unregisterReceiver(mReceiver);
    }
}
