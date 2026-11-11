# Samsung's SDK uses Parcelable creators across its IPC boundary.
-keepclassmembers class com.samsung.android.sdk.health.data.** implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Room finds WorkDatabase_Impl by name and calls its no-arg constructor reflectively.
# Its consumer rule keeps the class name, but R8 full mode can still remove the ctor.
# Without this, WorkManagerInitializer crashes before MainActivity can open.
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public <init>();
}

# The explorer enumerates the SDK's documented public data-type catalog.
-keep interface com.samsung.android.sdk.health.data.request.DataTypes {
    public static <fields>;
}
-keepclassmembers class androidx.health.connect.client.records.ExerciseSessionRecord {
    public static final int EXERCISE_TYPE_*;
}
