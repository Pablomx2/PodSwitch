# Reflection target: hidden BluetoothA2dp API invoked via reflection in BluetoothConnector.
# Keep the class and its members so name-based reflection survives R8/ProGuard.
-keep class android.bluetooth.BluetoothA2dp {
    *;
}
-keep class android.bluetooth.BluetoothProfile {
    *;
}
-keep class android.bluetooth.BluetoothDevice {
    *;
}

# Keep the app's core models that are reflected over by DataStore / Compose tooling.
-keep class com.podswitch.core.** { *; }
