package com.juicy.cloudrunner;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.*;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.telephony.PhoneStateListener;
import android.util.*;
import com.amap.api.maps2d.model.LatLng;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.*;
import java.text.SimpleDateFormat;
import java.util.*;

import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "CloudRunnerHook";
    private final double METER_PER_STEP = 0.9;
    //private static final int lac = 0,cid = 0;

    private Handler tmrHandler;
    private Map<SensorEventListener,List<Sensor>> stepListener;
    private ContentResolver resolver;
    private ArrayList<CurveGenerator> gen;
    private Timer prefUpdateTimer;
    private CurveGenerator speedGen;

    private long curStep,startTime,prevTime,updateTime;
    private double curDist,lastBearing;

    //不带星号的可变设置，每秒检测，如更新则重启模拟
    private List<LatLng> route;
    private boolean dropLocData,loop,stepAutoStop;//TODO:循环模式
    private double speedCent,speedDelta,cycleMin,cycleMax;

    //带星号的固定设置，只在加载时读取
    private boolean enabled, location_enabled, step_enabled;
    private String target;

    private class AcceloProvider implements CurveGenerator.ValueProvider {
        CurveGenerator speed;
        AcceloProvider(CurveGenerator _speed) {
            speed = _speed;
        }
        public double val(double x) {
            double s = speed.get() + rand(-0.3,0.3);
            return (METER_PER_STEP / s) * 1e9;
        }
    }

    public static void log(String s){
        //Log.d(TAG, s);
        //XposedBridge.log(s);
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss");
        Date date = new Date(System.currentTimeMillis());
        s = simpleDateFormat.format(date) + " " + s;

        File logFile = new File(Environment.getExternalStorageDirectory() + "/mylog.txt");
        if (!logFile.exists())
        {
            try
            {
                logFile.createNewFile();
            }
            catch (IOException e)
            {
                e.printStackTrace();
                System.exit(1);
            }
        }
        try
        {
            //BufferedWriter for performance, true to set append to file flag
            BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, true));
            buf.append(s);
            buf.newLine();
            buf.close();
        }
        catch (IOException e)
        {
            e.printStackTrace();
            System.exit(1);
        }

    }

    private void updatePref()
    {
        log("Pref Updated");
        loop = PrefAlter.getBoolean(resolver,"loop",false);
        dropLocData = PrefAlter.getBoolean(resolver,"drop_loc_data",false);
        stepAutoStop = PrefAlter.getBoolean(resolver,"step_auto_stop",false);
        speedCent = Double.valueOf(PrefAlter.getString(resolver,"speed_cent", "1"));
        speedDelta = Double.valueOf(PrefAlter.getString(resolver,"speed_delta", "1"));
        cycleMin = Double.valueOf(PrefAlter.getString(resolver,"cycle_min", "20"));
        cycleMax = Double.valueOf(PrefAlter.getString(resolver,"cycle_max", "20"));
        startTime = prevTime = System.nanoTime();
        curStep = 0;
        curDist = 0;
        lastBearing = 0;
        log("starttime = " + startTime);

        speedGen = new CurveGenerator(new CurveGenerator.Range(Math.max(0, speedCent - speedDelta),speedCent),
                new CurveGenerator.Range(speedCent,speedCent + speedDelta),
                new CurveGenerator.Range(cycleMin * 1e9,cycleMax * 1e9), new CurveGenerator.Range(speedDelta * 0.1,speedDelta * 0.3),startTime);

        route = new LinkedList<>();
        Cursor cursor = resolver.query(Uri.withAppendedPath(Provider.CONTENT_URI, "location"),
                new String[]{"latitude", "longitude"}, null, null, null);
        while (cursor.moveToNext()) {
            double[] pos = GPSUtil.gcj02_To_Gps84(cursor.getDouble(cursor.getColumnIndex("latitude")),
                    cursor.getDouble(cursor.getColumnIndex("longitude")));
            route.add(new LatLng(pos[0],pos[1]));
        }
        cursor.close();
        log("New route size = " + route.size());

        ArrayList<CurveGenerator> newGen = new ArrayList<>();
        AcceloProvider acc = new AcceloProvider(speedGen);
        newGen.add(new CurveGenerator(new CurveGenerator.Range(-3, -1) ,new CurveGenerator.Range(1,3), acc,new CurveGenerator.Static(0.2),startTime));
        newGen.add(new CurveGenerator(new CurveGenerator.Range(-7 + SensorManager.STANDARD_GRAVITY, -1 + SensorManager.STANDARD_GRAVITY) ,
                new CurveGenerator.Range(1 + SensorManager.STANDARD_GRAVITY,7 + SensorManager.STANDARD_GRAVITY),acc,new CurveGenerator.Static(0.8),startTime));
        newGen.add(new CurveGenerator(new CurveGenerator.Range(-3, -1) ,new CurveGenerator.Range(1,3), acc,new CurveGenerator.Static(0.2),startTime));
        gen = newGen;

        if(route.size() == 0) {
            dropLocData = true; //若没有设定路径则不提供位置信息
        }
        updateTime = System.nanoTime();
    }

    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        //log("Loaded app: " + lpparam.packageName);
        try {
            Context context = (Context) XposedHelpers.callMethod(XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader), "currentActivityThread"), "getSystemContext");
            resolver = context.getContentResolver();
            target = PrefAlter.getString(resolver, "target", "");
            enabled = PrefAlter.getBoolean(resolver, "enable", false);
            location_enabled = PrefAlter.getBoolean(resolver, "enable_location", false);
            step_enabled = PrefAlter.getBoolean(resolver, "enable_step", false);

            if (lpparam.packageName.equals("com.juicy.cloudrunner")) {
                XposedHelpers.findAndHookMethod("com.juicy.cloudrunner.MapActivity", lpparam.classLoader,
                        "checkModStatus", new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                                return true;
                            }
                        });
            }
            if (!(enabled && lpparam.packageName.equals(target))) {
                return;
            }

            log("Emulating for " + target);

            tmrHandler = new Handler();
            stepListener = new HashMap<>();
            prefUpdateTimer = new Timer();

            prefUpdateTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    long lastUpdateTime = Long.valueOf(PrefAlter.getString(resolver, "last_update", "0"));
                    if (lastUpdateTime > updateTime) {
                        updatePref();
                    }
                }
            }, 1000, 1000);
            updatePref();

            if (step_enabled) {
                tmrHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        invokeListener();
                        if (!stopped()) {
                            ++curStep;
                        }
                        tmrHandler.postDelayed(this, (long) (METER_PER_STEP / speedGen.get() * 1e3));
                    }
                }, 2500);
            }

            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", lpparam.classLoader,
                    "getCellLocation", new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            log("getCellLocation called");
                            //GsmCellLocation gsmCellLocation = new GsmCellLocation();
                            //gsmCellLocation.setLacAndCid(lac, cid);
                            //param.setResult(gsmCellLocation);
                            return null;
                        }
                    });

            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", lpparam.classLoader,
                    "getNetworkOperator", new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            log("getNetworkOperator called");
                            return "";
                        }
                    });

            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", lpparam.classLoader,
                    "listen", PhoneStateListener.class, int.class, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            log("listen called");
                            return null;
                        }
                    });

            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1) {
                XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", lpparam.classLoader,
                        "getPhoneCount", new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                                return 1;
                            }
                        });
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", lpparam.classLoader,
                        "getNeighboringCellInfo", new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                                log("getNeighboringCellInfo called");
                                return new ArrayList<>();
                            }
                        });
            }

            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN) {
                XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", lpparam.classLoader,
                        "getAllCellInfo", new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                                //param.setResult(getCell(460, 0, lac, cid, 0, 0));
                                log("getAllCellInfo called");
                                return null;
                            }
                        });
            }

            XposedHelpers.findAndHookMethod("android.net.wifi.WifiManager", lpparam.classLoader, "getScanResults", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(new ArrayList<>());
                }
            });

            XposedHelpers.findAndHookMethod("android.net.wifi.WifiManager", lpparam.classLoader, "getWifiState", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(1);
                }
            });

            XposedHelpers.findAndHookMethod("android.net.wifi.WifiManager", lpparam.classLoader, "isWifiEnabled", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(true);
                }
            });

            XposedHelpers.findAndHookMethod("android.net.wifi.WifiInfo", lpparam.classLoader, "getMacAddress", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult("00-00-00-00-00-00-00-00");
                }
            });

            XposedHelpers.findAndHookMethod("android.net.wifi.WifiInfo", lpparam.classLoader, "getSSID", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult("null");
                }
            });

            XposedHelpers.findAndHookMethod("android.net.wifi.WifiInfo", lpparam.classLoader, "getBSSID", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult("00-00-00-00-00-00-00-00");
                }
            });

            XposedHelpers.findAndHookMethod("android.net.NetworkInfo", lpparam.classLoader,
                    "getTypeName", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult("WIFI");
                        }
                    });

            XposedHelpers.findAndHookMethod("android.net.NetworkInfo", lpparam.classLoader,
                    "isConnectedOrConnecting", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(true);
                        }
                    });

            XposedHelpers.findAndHookMethod("android.net.NetworkInfo", lpparam.classLoader,
                    "isConnected", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(true);
                        }
                    });

            XposedHelpers.findAndHookMethod("android.net.NetworkInfo", lpparam.classLoader,
                    "isAvailable", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(true);
                        }
                    });

            XposedHelpers.findAndHookMethod("android.telephony.CellInfo", lpparam.classLoader,
                    "isRegistered", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(true);
                        }
                    });

            for (Method method : SensorManager.class.getDeclaredMethods()) {
                if (method.getName().equals("registerListener")
                        && !Modifier.isAbstract(method.getModifiers())
                        && Modifier.isPublic(method.getModifiers())) {
                    //log("hooked registerListener");
                    XposedBridge.hookMethod(method, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.args[0] instanceof SensorEventListener && param.args[1] instanceof Sensor) {
                                SensorEventListener listener = (SensorEventListener) param.args[0];
                                Sensor sensor = (Sensor) param.args[1];
                                int type = sensor.getType();
                                log("Registered sensor " + type);
                                switch (type) {
                                    case Sensor.TYPE_ACCELEROMETER:
                                        if (step_enabled) {
                                            hookInstanceMethod(listener, "onSensorChanged", new XC_MethodHook() {
                                                @Override
                                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                                    SensorEvent event = (SensorEvent) param.args[0];
                                                    if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                                                        fakeAccel(event);
                                                        XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                                                    } else {
                                                        XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                                                    }
                                                }
                                            });
                                        }
                                        break;
                                    case Sensor.TYPE_STEP_COUNTER:
                                    case Sensor.TYPE_STEP_DETECTOR:
                                        if (step_enabled) {
                                            List<Sensor> lst = stepListener.get(listener);
                                            if (lst == null) {
                                                lst = new LinkedList<>();
                                                lst.add(sensor);
                                                stepListener.put(listener, lst);
                                            } else {
                                                lst.add(sensor);
                                            }
                                            XposedHelpers.callMethod(listener, "onAccuracyChanged", sensor, 3);
                                            return null;
                                        } else {
                                            break;
                                        }
                                        //                                    case Sensor.TYPE_GYROSCOPE:
                                        //                                    case Sensor.TYPE_MAGNETIC_FIELD:
                                        //                                        hookInstanceMethod(listener, "onSensorChanged", new XC_MethodHook() {
                                        //                                            @Override
                                        //                                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                        //                                                randomNoise((SensorEvent) param.args[0],3);
                                        //                                            }
                                        //                                        });
                                        //                                        break;
                                        //                                    case Sensor.TYPE_ORIENTATION:
                                        //                                        hookInstanceMethod(listener, "onSensorChanged", new XC_MethodHook() {
                                        //                                            @Override
                                        //                                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                        //                                                SensorEvent event = (SensorEvent) param.args[0];
                                        //                                                randomNoise(event,15);
                                        //                                                event.values[0] = (float) circularMap(event.values[0],0,359);
                                        //                                                event.values[1] = (float) circularMap(event.values[1],-180,180);
                                        //                                                event.values[2] = (float) circularMap(event.values[2],-90,90);
                                        //                                            }
                                        //                                        });
                                        //                                        break;
                                }
                            } else {
                                log("###STUB:Deprecated Function Called###");
                            }
                            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                            return null;
                        }
                    });
                } else if (method.getName().equals("unregisterListener")
                        && !Modifier.isAbstract(method.getModifiers())
                        && Modifier.isPublic(method.getModifiers())) {
                    XposedBridge.hookMethod(method, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            SensorEventListener listener = (SensorEventListener) param.args[0];
                            if (param.args.length == 1) {
                                log("Unregistered all sensors");
                                if (step_enabled) {
                                    stepListener.remove(listener);
                                }
                            } else if (param.args.length == 2) {
                                Sensor sensor = (Sensor) param.args[1];
                                log("Unregistered sensor " + sensor.getType());
                                if (step_enabled && sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
                                    List<Sensor> lst = stepListener.get(listener);
                                    lst.remove(sensor);
                                    return null;
                                }
                            }
                            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                            return null;
                        }
                    });
                }
            }

            if (location_enabled) {
                for (Method method : LocationManager.class.getDeclaredMethods()) {
                    if (method.getName().equals("requestLocationUpdates")
                            && !Modifier.isAbstract(method.getModifiers())
                            && Modifier.isPublic(method.getModifiers())) {
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                log("requestLocationUpdates called");
                                if (param.args.length >= 4 && (param.args[3] instanceof LocationListener)) {
                                    //参数是实现了LocationListener接口的匿名内部类，因此不能直接用LocationListener.class？
                                    replaceInstanceMethod(param.args[3], "onLocationChanged", new XC_MethodReplacement() {
                                        @Override
                                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                                            log("onLocationChanged called");
                                            if (!dropLocData) {
                                                fakeLocation((Location) param.args[0]);
                                                XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                                            }
                                            return null;
                                        }
                                    });
                                }
                            }
                        });
                    } else if (method.getName().equals("requestSingleUpdate")
                            && !Modifier.isAbstract(method.getModifiers())
                            && Modifier.isPublic(method.getModifiers())) {
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                log("requestSingleUpdate called");
                                if (param.args.length >= 3 && (param.args[1] instanceof LocationListener)) {
                                    replaceInstanceMethod(param.args[1], "onLocationChanged", new XC_MethodReplacement() {
                                        @Override
                                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                                            log("onLocationChanged called");
                                            if (!dropLocData) {
                                                fakeLocation((Location) param.args[0]);
                                                XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                                            }
                                            return null;
                                        }
                                    });
                                }
                            }
                        });
                    } else if (method.getName().equals("getLastKnownLocation")
                            && !Modifier.isAbstract(method.getModifiers())
                            && Modifier.isPublic(method.getModifiers())) {
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                log("getLastKnownLocation called");
                                Location loc = dropLocData ? null : (Location) param.getResult();
                                if (loc != null) {
                                    fakeLocation(loc);
                                }
                                param.setResult(loc);
                            }
                        });
                    }
                }
            }
        } catch (Exception e) {
            XposedBridge.log(e);
        }
    }

    private static float[] computeDistanceAndBearing(double lat1, double lon1,
                                                  double lat2, double lon2) {
        // Modified from Android API Location.java
        // Based on http://www.ngs.noaa.gov/PUBS_LIB/inverse.pdf
        // using the "Inverse Formula" (section 4)

        int MAXITERS = 20;
        // Convert lat/long to radians
        lat1 *= Math.PI / 180.0;
        lat2 *= Math.PI / 180.0;
        lon1 *= Math.PI / 180.0;
        lon2 *= Math.PI / 180.0;

        double a = 6378137.0; // WGS84 major axis
        double b = 6356752.3142; // WGS84 semi-major axis
        double f = (a - b) / a;
        double aSqMinusBSqOverBSq = (a * a - b * b) / (b * b);

        double L = lon2 - lon1;
        double A = 0.0;
        double U1 = Math.atan((1.0 - f) * Math.tan(lat1));
        double U2 = Math.atan((1.0 - f) * Math.tan(lat2));

        double cosU1 = Math.cos(U1);
        double cosU2 = Math.cos(U2);
        double sinU1 = Math.sin(U1);
        double sinU2 = Math.sin(U2);
        double cosU1cosU2 = cosU1 * cosU2;
        double sinU1sinU2 = sinU1 * sinU2;

        double sigma = 0.0;
        double deltaSigma = 0.0;
        double cosSqAlpha = 0.0;
        double cos2SM = 0.0;
        double cosSigma = 0.0;
        double sinSigma = 0.0;
        double cosLambda = 0.0;
        double sinLambda = 0.0;

        double lambda = L; // initial guess
        for (int iter = 0; iter < MAXITERS; iter++) {
            double lambdaOrig = lambda;
            cosLambda = Math.cos(lambda);
            sinLambda = Math.sin(lambda);
            double t1 = cosU2 * sinLambda;
            double t2 = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda;
            double sinSqSigma = t1 * t1 + t2 * t2; // (14)
            sinSigma = Math.sqrt(sinSqSigma);
            cosSigma = sinU1sinU2 + cosU1cosU2 * cosLambda; // (15)
            sigma = Math.atan2(sinSigma, cosSigma); // (16)
            double sinAlpha = (sinSigma == 0) ? 0.0 :
                    cosU1cosU2 * sinLambda / sinSigma; // (17)
            cosSqAlpha = 1.0 - sinAlpha * sinAlpha;
            cos2SM = (cosSqAlpha == 0) ? 0.0 :
                    cosSigma - 2.0 * sinU1sinU2 / cosSqAlpha; // (18)

            double uSquared = cosSqAlpha * aSqMinusBSqOverBSq; // defn
            A = 1 + (uSquared / 16384.0) * // (3)
                    (4096.0 + uSquared *
                            (-768 + uSquared * (320.0 - 175.0 * uSquared)));
            double B = (uSquared / 1024.0) * // (4)
                    (256.0 + uSquared *
                            (-128.0 + uSquared * (74.0 - 47.0 * uSquared)));
            double C = (f / 16.0) *
                    cosSqAlpha *
                    (4.0 + f * (4.0 - 3.0 * cosSqAlpha)); // (10)
            double cos2SMSq = cos2SM * cos2SM;
            deltaSigma = B * sinSigma * // (6)
                    (cos2SM + (B / 4.0) *
                            (cosSigma * (-1.0 + 2.0 * cos2SMSq) -
                                    (B / 6.0) * cos2SM *
                                            (-3.0 + 4.0 * sinSigma * sinSigma) *
                                            (-3.0 + 4.0 * cos2SMSq)));

            lambda = L +
                    (1.0 - C) * f * sinAlpha *
                            (sigma + C * sinSigma *
                                    (cos2SM + C * cosSigma *
                                            (-1.0 + 2.0 * cos2SM * cos2SM))); // (11)

            double delta = (lambda - lambdaOrig) / lambda;
            if (Math.abs(delta) < 1.0e-12) {
                break;
            }
        }

        float distance = (float) (b * A * (sigma - deltaSigma));
        float initialBearing = (float) Math.atan2(cosU2 * sinLambda,
                cosU1 * sinU2 - sinU1 * cosU2 * cosLambda);
        initialBearing *= 180.0 / Math.PI;
        float finalBearing = (float) Math.atan2(cosU1 * sinLambda,
                -sinU1 * cosU2 + cosU1 * sinU2 * cosLambda);
        finalBearing *= 180.0 / Math.PI;
        
        return new float[]{distance,initialBearing,finalBearing};
    }
    
    private synchronized void fakeLocation(Location location) {
        long time = System.nanoTime();
        double timeElapsed = (time - prevTime) / 1e9,speed = speedGen.get() + rand(-0.3,0.3);
        curDist += timeElapsed * speed;
        log("Time = " + time + ",Prevtime = " + prevTime);
        
        float[] res = null;
        while (route.size() > 1) {
            LatLng p0 = route.get(0),p1 = route.get(1);
            res = computeDistanceAndBearing(p0.latitude,p0.longitude,p1.latitude,p1.longitude);
            if(Double.isNaN(res[0]) || res[0] < 1e-5) {
                route.remove(0);
                continue;
            }
            if(res[0] > curDist) {
                break;
            }
            route.remove(0);
            curDist -= res[0];
        }
        
        double lat,lot;
        log("Route size = " + route.size());
        if(route.size() > 1) {
            LatLng p0 = route.get(0),p1 = route.get(1);
            lat = p0.latitude + (p1.latitude - p0.latitude) * (curDist / res[0]);
            lot = p0.longitude + (p1.longitude - p0.longitude) * (curDist / res[0]);
            lastBearing = res[1] + (res[2] - res[1]) * (curDist / res[0]);
        } else {
            lat = route.get(0).latitude;
            lot = route.get(0).longitude;
        }

        double accuracy = 5.0 + rand(-0.01, 0.01);
        location.setLatitude(lat + rand(-0.000001,0.000001));
        location.setLongitude(lot + rand(-0.000001,0.000001));
        location.setAccuracy((float) accuracy);
        location.setSpeed((float) speed);
        location.setBearing((float) (lastBearing + rand(-0.01,0.01)));

        prevTime = time;
        log("Fake latitude " + location.getLatitude() + " longitude " + location.getLongitude() + " speed " + location.getSpeed());
    }

    private void fakeAccel(SensorEvent event) {
        event.timestamp = System.nanoTime();
        for(int i = 0;i < 3;++i) {
            if(!stopped()) {
                event.values[i] = (float) (gen.get(i).get());
            } else {
                event.values[i] = (float) (rand(-0.1,0.1) + (i == 1 ? SensorManager.STANDARD_GRAVITY : 0));
            }
        }
    }
/*
    private void randomNoise(SensorEvent event,double strength) {
        for(int i = 0;i < event.values.length;i++) {
            event.values[i] += rand(-strength, strength);
        }
    }

    private static double circularMap(double val,double lower,double upper) {
        double delta = upper - lower;
        while (val < lower) {
            val += delta;
        }
        while (val > upper) {
            val -= delta;
        }
        return val;
    }
*/
    private void invokeListener() {
        SensorEvent event;
        try {
            Constructor<?> construct = SensorEvent.class.getDeclaredConstructor(int.class); //强行获取package可见构造函数
            construct.setAccessible(true);
            event = (SensorEvent) construct.newInstance(1);
        } catch (Exception e) {
            XposedBridge.log(e);
            return;
        }
        for (Map.Entry<SensorEventListener,List<Sensor>> entry : stepListener.entrySet()) {
            SensorEventListener listener = entry.getKey();
            List<Sensor> sensorList = entry.getValue();
            for(Sensor sensor : sensorList) {
                event.timestamp = System.nanoTime();
                event.values[0] = curStep;
                event.accuracy = 3;
                event.sensor = sensor;
                XposedHelpers.callMethod(listener, "onSensorChanged", event);
            }
        }
    }

    private void replaceInstanceMethod(Object obj,String name,XC_MethodReplacement hook) {
        Class<?> clazz = obj.getClass();
        Method m = null;
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                m = method;
                break;
            }
        }
        if(m == null) {
            return;
        }
        m.setAccessible(true);

        XposedBridge.hookMethod(m, hook);
    }

    private void hookInstanceMethod(Object obj,String name,XC_MethodHook hook) {
        Class<?> clazz = obj.getClass();
        Method m = null;
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                m = method;
                break;
            }
        }
        if(m == null) {
            return;
        }
        m.setAccessible(true);

        XposedBridge.hookMethod(m, hook);
    }

    private double rand(double lower,double upper) {
        return Math.random() * (upper - lower) + lower;
    }

    private boolean stopped() {
        return location_enabled && stepAutoStop && route.size() < 2;
    }
}
