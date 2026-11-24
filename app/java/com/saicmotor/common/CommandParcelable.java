package com.saicmotor.common;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Copy of the "switch source" command of the original SWI69 R45 apps (same class name and same format,
 * so the original Media app reads it with its own class). The MG home sends it to com.saicmotor.media
 * (MediaService) with startService, extra "command".
 * Order in the Parcel: event, package, source type, state, Bundle.
 */
public class CommandParcelable implements Parcelable {
    /** MODE_EVENT_SWITCH_SOURCE_ONLY, ..._PLAY, ..._PAUSE (ModeConstants of the original home). */
    public static final int SWITCH_SOURCE = 32769, SWITCH_SOURCE_PLAY = 32773, SWITCH_SOURCE_PAUSE = 32774;
    /** MEDIA_TYPE_BT_MUSIC. */
    public static final int TYPE_BT_MUSIC = 2;

    public int event = -1;
    public String targetPackage = "";
    public int targetType = -1;
    public int targetState = -1;
    public Bundle extras = new Bundle();

    public CommandParcelable() {
    }

    public CommandParcelable(int event, int targetType, String targetPackage) {
        this.event = event;
        this.targetType = targetType;
        this.targetPackage = targetPackage;
    }

    protected CommandParcelable(Parcel p) {
        event = p.readInt();
        targetPackage = p.readString();
        targetType = p.readInt();
        targetState = p.readInt();
        extras = p.readBundle(Bundle.class.getClassLoader());
    }

    @Override
    public void writeToParcel(Parcel p, int flags) {
        p.writeInt(event);
        p.writeString(targetPackage);
        p.writeInt(targetType);
        p.writeInt(targetState);
        p.writeBundle(extras);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<CommandParcelable> CREATOR = new Creator<CommandParcelable>() {
        @Override
        public CommandParcelable createFromParcel(Parcel p) {
            return new CommandParcelable(p);
        }

        @Override
        public CommandParcelable[] newArray(int n) {
            return new CommandParcelable[n];
        }
    };

    @Override
    public String toString() {
        return "CommandParcelable{event=" + event + ", package=" + targetPackage + ", type=" + targetType + "}";
    }
}
