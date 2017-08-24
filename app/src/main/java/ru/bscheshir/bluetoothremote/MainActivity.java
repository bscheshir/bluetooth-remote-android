package ru.bscheshir.bluetoothremote;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
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

public class MainActivity extends AppCompatActivity implements View.OnClickListener, SeekBar.OnSeekBarChangeListener {
    private static final String TAG = "BLUETOOTH";

    //Экземпляры классов наших кнопок
    ToggleButton redButton;
    ToggleButton greenButton;
    TextView txtArduino;
    SeekBar seekBarVolume;

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        //Пишем данные в выходной поток
        byte[] ba = new byte[3]; //пишем в поток массив из 2х байт.
        ba[0] = (byte) 8;
        ba[1] = (byte) progress;
        mConnectedThread.write(ba);//используем специализированый тред. Из основного просто вызываем его ф-ю
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {

    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {

    }

    // Определяем несколько констант для типов сообщений очереди между main activity и другими потоками
    private interface MessageConstants {
        public static final int MESSAGE_NONE = 0;
        public static final int MESSAGE_READ = 1;
        public static final int MESSAGE_WRITE = 2;
        public static final int MESSAGE_TOAST = 3;
        // ... (Add other message types here as needed.)
    }

    private Handler mHandler; // handler that gets info from Bluetooth service
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
        greenButton = (ToggleButton) findViewById(R.id.toggleGreenLed);
        txtArduino = (TextView) findViewById(R.id.textViewArduino);      // для вывода текста, полученного от Arduino
        seekBarVolume = (SeekBar) findViewById(R.id.seekBarVolume);

        //Добавлем "слушатель нажатий" к кнопке
        redButton.setOnClickListener(this);
        greenButton.setOnClickListener(this);
        //Добавляем слушатель к дифференцированой полоске
        seekBarVolume.setOnSeekBarChangeListener(this);

        //Включаем bluetooth. Если он уже включен, то ничего не произойдет
        String enableBT = BluetoothAdapter.ACTION_REQUEST_ENABLE;
        startActivityForResult(new Intent(enableBT), 0);

        //Мы хотим использовать тот bluetooth-адаптер, который задается по умолчанию
        BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        //Устройство с данным адресом - наш Bluetooth
        //Адрес опредеяется следующим образом: установите соединение
        //между ПК и модулем (пин: 1234), а затем посмотрите в настройках
        //соединения адрес модуля. Скорее всего он будет аналогичным.
        String deviceName = "HC-06";
        String deviceHardwareAddress = "98:D3:32:30:EB:AC";
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
                            txtArduino.setText(stringBufferLine);    // обновляем TextView
                        }
                        Log.d(TAG, "...Строка:" + sb.toString() + "Байт:" + msg.arg1 + "...");
                        break;
                    case MessageConstants.MESSAGE_TOAST:
                        Toast.makeText(getApplicationContext(), msg.obj.toString(), Toast.LENGTH_LONG).show();
                        break;
                }
            }
        };

        //Пытаемся проделать эти действия
        try {
            BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(deviceHardwareAddress);

            //Инициируем соединение с устройством
            Method m = device.getClass().getMethod("createRfcommSocket", new Class[]{int.class});
            clientSocket = (BluetoothSocket) m.invoke(device, 1);
            clientSocket.connect();

            mConnectedThread = new ConnectedThread(clientSocket);
            mConnectedThread.start();
            Thread.State mConnectedThreadState = mConnectedThread.getState();
            mHandler.obtainMessage(MessageConstants.MESSAGE_TOAST, mConnectedThreadState).sendToTarget();
            String s = mConnectedThreadState.toString();

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

        //Выводим сообщение об успешном подключении
        Message toastMsg = mHandler.obtainMessage(MessageConstants.MESSAGE_TOAST, "CONNECTED");
        toastMsg.sendToTarget();
    }

    //Как раз эта функция и будет вызываться при нажатии кнопок
    @Override
    public void onClick(View v) {

        //Пытаемся послать данные
        int pin = 0;
        int value = 0;
        //В зависимости от того, какая кнопка была нажата,
        //изменяем данные для посылки
        if (v == redButton) {
            pin = 13;
            value = (redButton.isChecked() ? 1 : 0);// + 130;
        } else if (v == greenButton) {
            pin = 12;
            value = (greenButton.isChecked() ? 1 : 0);// + 120;
        }

        //Пишем данные в выходной поток
//            String message
//            byte[] msgBuffer = message.getBytes();
        byte[] ba = new byte[3]; //пишем в поток массив из 3x байт. 256 байт максимум за одну передачу HC-06(источник не помню)
        ba[0] = (byte) pin;
        ba[1] = (byte) value;
        mConnectedThread.write(ba);//используем специализированый тред. Из основного просто вызываем его ф-ю
    }



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
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Could not close the connect socket", e);
            }
        }
    }
}
