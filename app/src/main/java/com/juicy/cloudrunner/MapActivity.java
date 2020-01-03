package com.juicy.cloudrunner;

import androidx.annotation.*;
import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.amap.api.interfaces.MapCameraMessage;
import com.amap.api.maps2d.AMap;
import com.amap.api.maps2d.AMapUtils;
import com.amap.api.maps2d.CameraUpdate;
import com.amap.api.maps2d.CameraUpdateFactory;
import com.amap.api.maps2d.MapView;
import com.amap.api.maps2d.model.*;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.route.*;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.*;

public class MapActivity extends AppCompatActivity{

    MapView mMapView;
    AMap aMap;
    LatLng center;
    Marker selectedMarker = null;
    FloatingActionButton btnMove,btnDel,btnCopy;
    TextView distance;
    LinkedList<Marker> markers;
    LinkedList<LatLng> runningRoute,calculatedRoute;
    Polyline oldRouteLine,newRouteLine,segLine;
    RouteSearch routeSearch;
    ProgressBar bar;
    ContentResolver resolver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);

        if(!checkModStatus()) {
            Toast.makeText(this, R.string.module_not_started, Toast.LENGTH_LONG).show();
        }

        btnMove = findViewById(R.id.btnMove);
        btnDel = findViewById(R.id.btnDel);
        btnCopy = findViewById(R.id.btnCopy);
        distance = findViewById(R.id.distance);
        mMapView = findViewById(R.id.map);
        bar = findViewById(R.id.progressBar);
        resolver = getContentResolver();
        mMapView.onCreate(savedInstanceState);
        aMap = mMapView.getMap();
        markers = new LinkedList<>();
        calculatedRoute = new LinkedList<>();
        runningRoute = new LinkedList<>();
        routeSearch = new RouteSearch(this);
        routeSearch.setRouteSearchListener(new RouteSearch.OnRouteSearchListener() {
            @Override
            public void onBusRouteSearched(BusRouteResult busRouteResult, int i) {}

            @Override
            public void onDriveRouteSearched(DriveRouteResult driveRouteResult, int i) {
                float totDist = 0;
                bar.setVisibility(View.GONE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                calculatedRoute.clear();
                if(i == 1000) {
                    List<DrivePath> paths = driveRouteResult.getPaths();
                    for(DrivePath path : paths) {
                        List<DriveStep> steps = path.getSteps();
                        for(DriveStep step : steps) {
                            for(LatLonPoint point : step.getPolyline()) {
                                calculatedRoute.add(convert(point));
                            }
                        }
                        totDist += path.getDistance();
                    }
                    Toast.makeText(getApplicationContext(), R.string.calculation_succeed, Toast.LENGTH_SHORT).show();
                } else {
                    Log.e("FA","Error: " + i);
                    Toast.makeText(getApplicationContext(), R.string.calculation_failed, Toast.LENGTH_SHORT).show();
                    for(Marker marker : markers) {
                        if(!calculatedRoute.isEmpty()) {
                            totDist += AMapUtils.calculateLineDistance(marker.getPosition(), calculatedRoute.getLast());
                        }
                        calculatedRoute.addLast(marker.getPosition());
                    }
                }
                distance.setText(totDist + " m");
                redrawNewRouteLine();
            }

            @Override
            public void onWalkRouteSearched(WalkRouteResult walkRouteResult, int i) {}

            @Override
            public void onRideRouteSearched(RideRouteResult rideRouteResult, int i) {}
        });

        center = aMap.getCameraPosition().target;

        oldRouteLine = aMap.addPolyline(new PolylineOptions().color(0xff66ccff).zIndex(0).width(15));
        newRouteLine = aMap.addPolyline(new PolylineOptions().color(0xff666633).zIndex(1).width(6));
        segLine = aMap.addPolyline(new PolylineOptions().color(0xff000000).setDottedLine(true));
        aMap.setOnCameraChangeListener(new AMap.OnCameraChangeListener() {
            @Override
            public void onCameraChange(CameraPosition cameraPosition) {
                center = cameraPosition.target;
            }

            @Override
            public void onCameraChangeFinish(CameraPosition cameraPosition) {
                //nothing to do here
            }
        });

        aMap.setOnMarkerClickListener(new AMap.OnMarkerClickListener() {
            @Override
            public boolean onMarkerClick(Marker marker) {
                unselectMarker();
                selectMarker(marker);
                return true;
            }
        });

        aMap.setOnMapClickListener(new AMap.OnMapClickListener() {
            @Override
            public void onMapClick(LatLng latLng) {
                unselectMarker();
            }
        });

        aMap.setOnMarkerDragListener(new AMap.OnMarkerDragListener() {
            @Override
            public void onMarkerDragStart(Marker marker) {
                selectMarker(marker);
            }

            @Override
            public void onMarkerDrag(Marker marker) {
                setTitle(markers.indexOf(marker) + ": " + marker.getPosition().latitude + "," + marker.getPosition().longitude);
            }

            @Override
            public void onMarkerDragEnd(Marker marker) {
                putMarkerList();
                redrawSegLine();
            }
        });

        getLocList();
        getMarkerList();
        redrawSegLine();
        redrawOldRouteLine();
        redrawNewRouteLine();
        if(markers.size() > 0) {
            aMap.moveCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition(markers.getFirst().getPosition(), 17, 0, 0)));
        } else if(calculatedRoute.size() > 0) {
            aMap.moveCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition(calculatedRoute.getFirst(), 17, 0, 0)));
        } else {
            aMap.moveCamera(CameraUpdateFactory.zoomTo(12));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        //在activity执行onDestroy时执行mMapView.onDestroy()，销毁地图
        putMarkerList();
        mMapView.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        //在activity执行onResume时执行mMapView.onResume ()，重新绘制加载地图
        mMapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        //在activity执行onPause时执行mMapView.onPause ()，暂停地图的绘制
        mMapView.onPause();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        //在activity执行onSaveInstanceState时执行mMapView.onSaveInstanceState (outState)，保存地图当前的状态
        mMapView.onSaveInstanceState(outState);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
                Intent intent = new Intent(this,SettingsActivity.class);
                startActivity(intent);
        }
        return true;
    }

    public void onAddClick(View view) {
        //Log.d("FA",center.latitude + " " + center.longitude);
        Marker newMarker = aMap.addMarker(new MarkerOptions().position(center));
        markers.addLast(newMarker);
        if(markers.size() == 1) {
            newMarker.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN));
        }
        selectMarker(newMarker);
        putMarkerList();
        redrawSegLine();
    }

    @SuppressLint("RestrictedApi")
    public void onDelClick(View view) {
        if(selectedMarker != null) {
            markers.remove(selectedMarker);
            selectedMarker.destroy();
            selectedMarker = null;
            if(markers.size() > 0) {
                markers.getFirst().setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN));
            }
            setTitle(R.string.title_activity_main);
            btnDel.setVisibility(View.INVISIBLE);
            btnCopy.setVisibility(View.INVISIBLE);
            btnMove.setVisibility(View.INVISIBLE);
            putMarkerList();
            redrawSegLine();
        }
    }

    public void onCopyClick(View view) {
        if(selectedMarker != null) {
            Marker newMarker = aMap.addMarker(new MarkerOptions().position(center));
            markers.add(markers.indexOf(selectedMarker),newMarker);
            selectMarker(newMarker);
            putMarkerList();
            redrawSegLine();
        }
    }

    public void onMoveClick(View view) {
        if(selectedMarker != null) {
            selectedMarker.setPosition(center);
            setTitle(markers.indexOf(selectedMarker) + ": " + selectedMarker.getPosition().latitude + "," + selectedMarker.getPosition().longitude);
            putMarkerList();
            redrawSegLine();
        }
    }

    public void onCalcClick(View view) {
        if(markers.size() < 2) {
            return;
        }

        Toast.makeText(this, R.string.calculating_route, Toast.LENGTH_SHORT).show();
        bar.setVisibility(View.VISIBLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);

        calculatedRoute = new LinkedList<>();
        LinkedList<LatLonPoint> passBy = new LinkedList<>();
        for(Marker marker : markers) {
            passBy.addLast(convert(marker.getPosition()));
        }
        passBy.removeLast();
        passBy.removeFirst();
        RouteSearch.DriveRouteQuery query = new RouteSearch.DriveRouteQuery(new RouteSearch.FromAndTo(convert(markers.getFirst().getPosition()),
                convert(markers.getLast().getPosition())),
                RouteSearch.DRIVING_SINGLE_DEFAULT,passBy,null,null);
        routeSearch.calculateDriveRouteAsyn(query);
    }

    public void onSetClick(View view) {
        runningRoute.clear();
        if(calculatedRoute.isEmpty()) {
            if(!markers.isEmpty()) {
                runningRoute.addLast(markers.getFirst().getPosition());
                Toast.makeText(this, R.string.route_set_default, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.route_cleared, Toast.LENGTH_SHORT).show();
            }
        } else {
            runningRoute.addAll(calculatedRoute);
            Toast.makeText(this, R.string.route_set, Toast.LENGTH_SHORT).show();
        }
        putLocList();
        redrawOldRouteLine();
    }

    public void onClearClick(View view) {
        runningRoute.clear();
        if(!markers.isEmpty()) {
            runningRoute.addLast(markers.getFirst().getPosition());
            Toast.makeText(this, R.string.route_cleared_default, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.route_cleared, Toast.LENGTH_SHORT).show();
        }
        putLocList();
        redrawOldRouteLine();
    }

    @SuppressLint("RestrictedApi")
    private void selectMarker(Marker marker) {
        unselectMarker();
        selectedMarker = marker;
        marker.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED));
        setTitle(markers.indexOf(marker) + ": " + marker.getPosition().latitude + "," + marker.getPosition().longitude);
        btnDel.setVisibility(View.VISIBLE);
        btnCopy.setVisibility(View.VISIBLE);
        btnMove.setVisibility(View.VISIBLE);
    }

    @SuppressLint("RestrictedApi")
    private void unselectMarker() {
        if(selectedMarker != null) {
            if(markers.indexOf(selectedMarker) == 0) {
                selectedMarker.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN));
            } else {
                selectedMarker.setIcon(BitmapDescriptorFactory.defaultMarker());
            }
            selectedMarker = null;
            setTitle(R.string.title_activity_main);
            btnDel.setVisibility(View.INVISIBLE);
            btnCopy.setVisibility(View.INVISIBLE);
            btnMove.setVisibility(View.INVISIBLE);
        }
    }

    private void redrawSegLine() {
        List<LatLng> list = new LinkedList<>();
        for(Marker m : markers) {
            list.add(m.getPosition());
        }
        segLine.setPoints(list);
    }

    private void redrawOldRouteLine() {
        if(runningRoute.isEmpty()) {
            oldRouteLine.setVisible(false);
        } else {
            oldRouteLine.setPoints(runningRoute);
            oldRouteLine.setVisible(true);
        }
    }

    private void redrawNewRouteLine() {
        if(calculatedRoute.isEmpty()) {
            newRouteLine.setVisible(false);
        } else {
            newRouteLine.setPoints(calculatedRoute);
            newRouteLine.setVisible(true);
        }
    }

    private static LatLonPoint convert(LatLng l) {
        return new LatLonPoint(l.latitude,l.longitude);
    }

    private static LatLng convert(LatLonPoint l) {
        return new LatLng(l.getLatitude(),l.getLongitude());
    }

    public void putLocList() {
        resolver.delete(Uri.withAppendedPath(Provider.CONTENT_URI,"location"),null,null);
        int id = 0;
        for(LatLng point : runningRoute) {
            ContentValues values = new ContentValues();
            values.put("_id", ++id);
            values.put("latitude", point.latitude);
            values.put("longitude", point.longitude);
            resolver.insert(Uri.withAppendedPath(Provider.CONTENT_URI, "location"), values);
        }
        PrefAlter.putString(resolver,"last_update", "" + System.nanoTime());
    }

    public void getLocList() {
        Cursor cursor = resolver.query(Uri.withAppendedPath(Provider.CONTENT_URI, "location"), new String[]{"latitude","longitude"}, null,null, null);
        while (cursor.moveToNext()) {
            runningRoute.add(new LatLng(cursor.getDouble(cursor.getColumnIndex("latitude")),
                    cursor.getDouble(cursor.getColumnIndex("longitude"))));
        }
        cursor.close();
    }

    private void putMarkerList() {
        resolver.delete(Uri.withAppendedPath(Provider.CONTENT_URI,"marker"),null,null);
        if (markers != null) {
            int id = 0;
            for(Marker point : markers) {
                ContentValues values = new ContentValues();
                values.put("_id", ++id);
                values.put("latitude", point.getPosition().latitude);
                values.put("longitude", point.getPosition().longitude);
                resolver.insert(Uri.withAppendedPath(Provider.CONTENT_URI,"marker"),values);
            }
        }
    }

    private void getMarkerList() {
        Cursor cursor = resolver.query(Uri.withAppendedPath(Provider.CONTENT_URI, "marker"), new String[]{"latitude","longitude"}, null,null, null);
        while (cursor.moveToNext()) {
            Marker m = aMap.addMarker(new MarkerOptions().position(new LatLng(cursor.getDouble(cursor.getColumnIndex("latitude")),
                    cursor.getDouble(cursor.getColumnIndex("longitude")))));
            markers.addLast(m);
        }
        cursor.close();
        if(markers.size() > 0) {
            markers.getFirst().setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN));
        }
        redrawSegLine();
    }

    public boolean checkModStatus() {
        return false;
    }
}
