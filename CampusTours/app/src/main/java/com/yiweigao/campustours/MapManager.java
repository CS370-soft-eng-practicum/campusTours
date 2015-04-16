package com.yiweigao.campustours;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.PolylineOptions;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by yiweigao on 3/31/15.
 */

// manages all map activity (location, gps connection, map drawings, etc)
public class MapManager implements
        OnMapReadyCallback,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener,
        ResultCallback<Status>,
        LocationListener {

    // some constants
    private static final LatLng TOUR_START = new LatLng(33.789591, -84.326506);
    private static final String BASE_URL = "http://dutch.mathcs.emory.edu:8009/";
    private static final int GEOFENCE_LIFETIME = 100000;
    private static final int LOCATION_REQUEST_INTERVAL = 10000;
    private static final int LOCATION_REQUEST_FASTEST_INTERVAL = 5000;
    
    // context for making toasts and generating intents
    private Context mContext;

    // map related
    private MapFragment mMapFragment;
    private GoogleMap mGoogleMap;
    
    // location related
    private GoogleApiClient mGoogleApiClient;
    private PendingIntent mGeofencePendingIntent = null;
    
    // list of geofences we are monitoring
    List<Geofence> listOfGeofences = new ArrayList<>();

    // our toast shown while data is being retrieved
    private Toast loadingToast;

    public MapManager(Context context, MapFragment mapFragment) {
        mContext = context;
        mMapFragment = mapFragment;
        getMap();
        buildGoogleApiClient();
    }

    public void getMap() {
        mMapFragment.getMapAsync(this);
    }

    /**
     * When map is ready, set initial view, then load route and geofences 
     * @param googleMap Reference to the map on screen
     */
    @Override
    public void onMapReady(GoogleMap googleMap) {
        mGoogleMap = googleMap;
        setInitialView();
        getRoute();
        getGeofences();
    }

    protected void buildGoogleApiClient() {
        mGoogleApiClient = new GoogleApiClient.Builder(mContext)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();
    }

    private void setInitialView() {
        mGoogleMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
        mGoogleMap.setBuildingsEnabled(true);
        mGoogleMap.getUiSettings().setZoomControlsEnabled(true);
        mGoogleMap.setMyLocationEnabled(true);
        mGoogleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(TOUR_START, 18.0f));

        CameraPosition cameraPosition = new CameraPosition.Builder()
                .target(TOUR_START)
                .zoom(18.0f)    // 18.0f seems to show buildings...anything higher will not
                .bearing(55)    // 55 degrees makes us face the b jones center directly
                .tilt(15)       // 15 degrees seems ideal
                .build();

        mGoogleMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
    }

    private void getRoute() {
        new DownloadRouteTask().execute(BASE_URL + "points");
    }

    private void drawRoute(List<LatLng> listOfRouteCoordinates) {
        mGoogleMap.addPolyline(new PolylineOptions()
                .color(Color.argb(255, 0, 40, 120))     // emory blue, 100% opacity
//                .color(Color.argb(255, 210, 176, 0))    // emory "web light gold", 100% opacity
//                .color(Color.argb(255, 210, 142, 0))    // emory "web dark gold", 100% opacity
                .geodesic(true)
                .addAll(listOfRouteCoordinates));
    }

    private void getGeofences() {
        new DownloadGeofencesTask().execute(BASE_URL + "geofences");
    }

    /**
     * Once the GoogleAPIClient is connected, we add geofences, prepare the pending intent,
     * and start updating our location
     * @param bundle No clue what this is actually...not using it here, 
     *               but I would like to know what this is
     */
    @Override
    public void onConnected(Bundle bundle) {
        LocationServices.GeofencingApi.addGeofences(
                mGoogleApiClient,
                getGeofencingRequest(),
                getGeofencePendingIntent()
        ).setResultCallback(this);

        startLocationUpdates();
    }

    /**
     * Builds and returns a GeofencingRequest. Specifies the list of geofences to be monitored.
     * Also specifies how the geofence notifications are initially triggered.
     */
    private GeofencingRequest getGeofencingRequest() {
        GeofencingRequest.Builder builder = new GeofencingRequest.Builder();

        // The INITIAL_TRIGGER_ENTER flag indicates that geofencing service should trigger a
        // GEOFENCE_TRANSITION_ENTER notification when the geofence is added and if the device
        // is already inside that geofence.
        builder.setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER);

        // Add the geofences to be monitored by geofencing service.
        builder.addGeofences(listOfGeofences);

        // Return a GeofencingRequest.
        return builder.build();
    }

    /**
     * Gets a PendingIntent to send with the request to add or remove Geofences. Location Services
     * issues the Intent inside this PendingIntent whenever a geofence transition occurs for the
     * current list of geofences.
     *
     * @return A PendingIntent for the IntentService that handles geofence transitions.
     */
    private PendingIntent getGeofencePendingIntent() {
        // Reuse the PendingIntent if we already have it.
        if (mGeofencePendingIntent != null) {
            return mGeofencePendingIntent;
        }
        Intent intent = new Intent(mContext, GeofenceTransitionsIntentService.class);
        // We use FLAG_UPDATE_CURRENT so that we get the same pending intent back when calling
        // addGeofences() and removeGeofences().
        return PendingIntent.getService(mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void startLocationUpdates() {
        LocationServices.FusedLocationApi.requestLocationUpdates(
                mGoogleApiClient, getLocationRequest(), this);
    }

    /**
     * Creates and returns a location request
     * @return LocationRequest
     */
    private LocationRequest getLocationRequest() {
        return new LocationRequest()
                .setInterval(LOCATION_REQUEST_INTERVAL)
                .setFastestInterval(LOCATION_REQUEST_FASTEST_INTERVAL)
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    }

    @Override
    public void onLocationChanged(Location location) {
        updateCamera(location);
    }

    public void updateCamera(Location newLocation) {
        mGoogleMap.animateCamera(CameraUpdateFactory.newLatLng(
                new LatLng(newLocation.getLatitude(), newLocation.getLongitude())));
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {

    }

    @Override
    public void onResult(Status status) {

    }

    /**
     * AsyncTask that fetches latitude and longitude from our REST API,
     * converts them into LatLng objects, stores them in a list, and
     * passes this list to drawRoute() for drawing.
     */
    private class DownloadRouteTask extends AsyncTask<String, Void, JSONObject> {

        /**
         * shows a "loading" toast immediately prior to fetching data
         */
        @Override
        protected void onPreExecute() {
            loadingToast = Toast.makeText(mContext, "Loading", Toast.LENGTH_LONG);
            loadingToast.show();
        }

        @Override
        protected JSONObject doInBackground(String... urls) {
            InputStream is = null;
            String result = "";
            JSONObject jsonObject = null;

            // Download JSON data from URL
            try {
                HttpClient httpclient = new DefaultHttpClient();
                HttpGet httpGet = new HttpGet(urls[0]);
                String user = "";
                String pwd = "secret";
                httpGet.addHeader("Authorization", "Basic " + Base64.encodeToString((user + ":" + pwd).getBytes(), Base64.NO_WRAP));

                HttpResponse response = httpclient.execute(httpGet);
                HttpEntity entity = response.getEntity();
                is = entity.getContent();

            } catch (Exception e) {
                Log.e("log_tag", "Error in http connection " + e.toString());
            }

            // Convert response to string
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        is, "iso-8859-1"), 8);
                StringBuilder sb = new StringBuilder();
                String line = null;
                while ((line = reader.readLine()) != null) {
                    sb.append(line + "\n");
                }
                is.close();
                result = sb.toString();
            } catch (Exception e) {
                Log.e("log_tag", "Error converting result " + e.toString());
            }

            try {

                jsonObject = new JSONObject(result);
            } catch (JSONException e) {
                Log.e("log_tag", "Error parsing data " + e.toString());
            }

            return jsonObject;
        }

        /**
         * Converts jsonObject to LatLnt object, adds it to listOfRouteCoordinates,
         * then draws the route
         * @param jsonObject The jsonObject that is returned from the REST API
         */
        @Override
        protected void onPostExecute(JSONObject jsonObject) {
            JSONArray resources = null;
            try {
                resources = jsonObject.getJSONArray("resources");
            } catch (JSONException e) {
                e.printStackTrace();
            }

            List<LatLng> listOfRouteCoordinates = new ArrayList<>();
            for (int i = 0; i < resources.length(); i++) {
                try {
                    JSONObject point = resources.getJSONObject(i);
                    String lat = point.getString("lat");
                    String lng = point.getString("lng");

                    LatLng latLng = new LatLng(Double.parseDouble(lat), Double.parseDouble(lng));
                    listOfRouteCoordinates.add(latLng);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }

            drawRoute(listOfRouteCoordinates);
        }
    }

    /**
     * AsyncTask that fetches latitudes, longitudes, and radii from our REST API.
     * Then GoogleAPIClient.connect() is called
     */
    private class DownloadGeofencesTask extends AsyncTask<String, Void, JSONObject> {

        @Override
        protected JSONObject doInBackground(String... urls) {
            InputStream is = null;
            String result = "";
            JSONObject jsonObject = null;

            // Download JSON data from URL
            try {
                HttpClient httpclient = new DefaultHttpClient();
                HttpGet httpGet = new HttpGet(urls[0]);
                String user = "";
                String pwd = "secret";
                httpGet.addHeader("Authorization", "Basic " + Base64.encodeToString((user + ":" + pwd).getBytes(), Base64.NO_WRAP));

                HttpResponse response = httpclient.execute(httpGet);
                HttpEntity entity = response.getEntity();
                is = entity.getContent();

            } catch (Exception e) {
                Log.e("log_tag", "Error in http connection " + e.toString());
            }

            // Convert response to string
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        is, "iso-8859-1"), 8);
                StringBuilder sb = new StringBuilder();
                String line = null;
                while ((line = reader.readLine()) != null) {
                    sb.append(line + "\n");
                }
                is.close();
                result = sb.toString();
            } catch (Exception e) {
                Log.e("log_tag", "Error converting result " + e.toString());
            }

            try {

                jsonObject = new JSONObject(result);
            } catch (JSONException e) {
                Log.e("log_tag", "Error parsing data " + e.toString());
            }

            return jsonObject;
        }

        /**
         * Converts jsonObject to Geofence, adds it to listOfGeofences, 
         * cancels the "loading" toast, and then tells the Google API client to connect
         * @param jsonObject The jsonObject that is returned from the REST API
         */
        @Override
        protected void onPostExecute(JSONObject jsonObject) {
            JSONArray resources = null;
            try {
                resources = jsonObject.getJSONArray("resources");
            } catch (JSONException e) {
                e.printStackTrace();
            }

            for (int i = 0; i < resources.length(); i++) {
                try {
                    JSONObject point = resources.getJSONObject(i);
                    String id = point.getString("id");
                    String lat = point.getString("lat");
                    String lng = point.getString("lng");
                    String rad = point.getString("rad");

                    listOfGeofences.add(new Geofence.Builder()
                            .setRequestId(id)
                            .setCircularRegion(
                                    Double.parseDouble(lat),
                                    Double.parseDouble(lng),
                                    Float.parseFloat(rad))
                            .setExpirationDuration(GEOFENCE_LIFETIME)
                            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                            .build());


                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }

            loadingToast.cancel();
            mGoogleApiClient.connect();
        }
    }
}