package com.mg4.launcher.apps;

/**
 * MG apps of the SWI69 software (EH32 Color Radio): packages and main screens,
 * taken from the manifests of firmware SWI69-29958-1100R45.
 */
public final class MgApps {
    private MgApps() {}

    public static final String HOME_PKG = "com.saicmotor.launcher";
    public static final String HOME_CLS = "com.saicmotor.launcher.view.MainActivity";

    public static final String RADIO_PKG = "com.saicmotor.radio";
    public static final String RADIO_CLS = "com.saicmotor.radio.view.RadioMainActivity";

    public static final String MEDIA_PKG = "com.saicmotor.media";
    public static final String MEDIA_CLS = "com.saicmotor.media.ui.activity.MainActivity";

    public static final String HVAC_PKG = "com.saicmotor.hvac";
    public static final String HVAC_CLS = "com.saicmotor.hvac.ui.activity.MainActivity";

    public static final String PHONE_PKG = "com.saicmotor.btphone";
    public static final String PHONE_CLS = "com.saicmotor.btphone.view.activity.BTPhoneMainActivity";

    public static final String SETTINGS_PKG = "com.saicmotor.settings";
    public static final String SETTINGS_CLS = "com.saicmotor.settings.ui.MainActivity";

    public static final String VEHICLE_PKG = "com.saicmotor.vehiclesetting";
    public static final String VEHICLE_CLS = "com.saicmotor.vehiclesetting.ui.VehicleSettingActivity";

    /** Energy app (charging, V2L discharge, consumption): has no direct links to its tabs. */
    public static final String ENERGY_PKG = "com.saicmotor.saicev";
    public static final String ENERGY_CLS = "com.saicmotor.saicev.activity.MainActivity";

    public static final String FILES_PKG = "com.saicmotor.folder";
    public static final String FILES_CLS = "com.saicmotor.folder.ui.activity.MainActivity";

    public static final String SOS_PKG = "com.saicmotor.ecall";
    public static final String SOS_CLS = "com.saicmotor.ecall.view.activity.MainActivity";
}
