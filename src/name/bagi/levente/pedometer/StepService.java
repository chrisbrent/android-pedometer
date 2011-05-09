/*
 *  Pedometer - Android App
 *  Copyright (C) 2009 Levente Bagi
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package name.bagi.levente.pedometer;

import java.net.HttpURLConnection;
import java.util.LinkedList;
import java.util.List;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import com.rdio.android.api.Rdio;
import com.rdio.android.api.RdioListener;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;



/**
 * This is an example of implementing an application service that runs locally
 * in the same process as the application.  The {@link StepServiceController}
 * and {@link StepServiceBinding} classes show how to interact with the
 * service.
 *
 * <p>Notice the use of the {@link NotificationManager} when interesting things
 * happen in the service.  This is generally how background services should
 * interact with the user, rather than doing something more disruptive such as
 * calling startActivity().
 */
public class StepService extends Service implements RdioListener {
	private static final String TAG = "name.bagi.levente.pedometer.StepService";
    private SharedPreferences mSettings;
    private PedometerSettings mPedometerSettings;
    private SharedPreferences mState;
    private SharedPreferences.Editor mStateEditor;
    private Utils mUtils;
    private SensorManager mSensorManager;
    private Sensor mSensor;
    private StepDetector mStepDetector;
    // private StepBuzzer mStepBuzzer; // used for debugging
    private StepDisplayer mStepDisplayer;
    private PaceNotifier mPaceNotifier;
    private DistanceNotifier mDistanceNotifier;
    private SpeedNotifier mSpeedNotifier;
    private CaloriesNotifier mCaloriesNotifier;
    private SpeakingTimer mSpeakingTimer;
    
    private PowerManager.WakeLock wakeLock;
    private NotificationManager mNM;

    private int mSteps;
    private int mPace;
    private float mDistance;
    private float mSpeed;
    private float mCalories;
    
    private final String mEchoNestKey = "HIXUT5PXOCGW36CKB";
    private final String mRdioAppKey = "a3dwpuqqh4xccnpbx6zy37wk";
    private final String mRdioAppSecret = "f9sCTPhKxM";
    private static Rdio mRdio;
    private MediaPlayer mPlayer;
    private boolean mQueryBusy;
    private List<Track> mTracks;
    private Track mCurrentTrack;
    
	// Our model for the metadata for a track that we care about
	public class Track {
		public String key;
		public String trackName;
		public String artistName;
		public int tempo;

		public Track(String key, String trackName, String artistName, int tempo) {
			this.key = key;
			this.trackName = trackName;
			this.artistName = artistName;
			this.tempo = tempo;
		}
	}

    public interface PlayerListener {
        public void trackChanged(StepService.Track track);
    }
    private PlayerListener mPlayerListener;
    public void addPlayerListener(PlayerListener listener) {
    	mPlayerListener = listener;
    }
    public void notifyPlayerListener(Track track) {
        mPlayerListener.trackChanged(track);
    }


    /**
     * Class for clients to access.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with
     * IPC.
     */
    public class StepBinder extends Binder {
        StepService getService() {
            return StepService.this;
        }
    }
    
    @Override
    public void onCreate() {
        Log.i(TAG, "[SERVICE] onCreate");
        super.onCreate();
        
        mNM = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        showNotification();
        
        // Load settings
        mSettings = PreferenceManager.getDefaultSharedPreferences(this);
        mPedometerSettings = new PedometerSettings(mSettings);
        mState = getSharedPreferences("state", 0);

        mUtils = Utils.getInstance();
        mUtils.setService(this);
        mUtils.initTTS();

        acquireWakeLock();
        
        // Start detecting
        mStepDetector = new StepDetector();
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        registerDetector();

        // Register our receiver for the ACTION_SCREEN_OFF action. This will make our receiver
        // code be called whenever the phone enters standby mode.
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mReceiver, filter);

        mStepDisplayer = new StepDisplayer(mPedometerSettings, mUtils);
        mStepDisplayer.setSteps(mSteps = mState.getInt("steps", 0));
        mStepDisplayer.addListener(mStepListener);
        mStepDetector.addStepListener(mStepDisplayer);

        mPaceNotifier     = new PaceNotifier(mPedometerSettings, mUtils);
        mPaceNotifier.setPace(mPace = mState.getInt("pace", 0));
        mPaceNotifier.addListener(mPaceListener);
        mStepDetector.addStepListener(mPaceNotifier);

        mDistanceNotifier = new DistanceNotifier(mDistanceListener, mPedometerSettings, mUtils);
        mDistanceNotifier.setDistance(mDistance = mState.getFloat("distance", 0));
        mStepDetector.addStepListener(mDistanceNotifier);
        
        mSpeedNotifier    = new SpeedNotifier(mSpeedListener,    mPedometerSettings, mUtils);
        mSpeedNotifier.setSpeed(mSpeed = mState.getFloat("speed", 0));
        mPaceNotifier.addListener(mSpeedNotifier);
        
        mCaloriesNotifier = new CaloriesNotifier(mCaloriesListener, mPedometerSettings, mUtils);
        mCaloriesNotifier.setCalories(mCalories = mState.getFloat("calories", 0));
        mStepDetector.addStepListener(mCaloriesNotifier);
        
        mSpeakingTimer = new SpeakingTimer(mPedometerSettings, mUtils);
        mSpeakingTimer.addListener(mStepDisplayer);
        mSpeakingTimer.addListener(mPaceNotifier);
        mSpeakingTimer.addListener(mDistanceNotifier);
        mSpeakingTimer.addListener(mSpeedNotifier);
        mSpeakingTimer.addListener(mCaloriesNotifier);
        mStepDetector.addStepListener(mSpeakingTimer);
        
        if (mRdio == null) {
        	mRdio = new Rdio(mRdioAppKey, mRdioAppSecret, null, null, this, this);
        }
        // Used when debugging:
        // mStepBuzzer = new StepBuzzer(this);
        // mStepDetector.addStepListener(mStepBuzzer);

        // Start voice
        reloadSettings();

        // Tell the user we started.
        Toast.makeText(this, getText(R.string.started), Toast.LENGTH_SHORT).show();
    }
    
    @Override
    public void onStart(Intent intent, int startId) {
        Log.i(TAG, "[SERVICE] onStart");
        super.onStart(intent, startId);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "[SERVICE] onDestroy");
        mUtils.shutdownTTS();
        
        // Cleanup after the Rdio object
        if (mRdio != null) {
        	mRdio.cleanup();
        	mRdio = null;
        }
        // Cleanup any player we have lingering around
        if (mPlayer != null) {
        	mPlayer.reset();
        	mPlayer.release();
        	mPlayer = null;
        }

        // Unregister our receiver.
        unregisterReceiver(mReceiver);
        unregisterDetector();
        
        mStateEditor = mState.edit();
        mStateEditor.putInt("steps", mSteps);
        mStateEditor.putInt("pace", mPace);
        mStateEditor.putFloat("distance", mDistance);
        mStateEditor.putFloat("speed", mSpeed);
        mStateEditor.putFloat("calories", mCalories);
        mStateEditor.commit();
        
        mNM.cancel(R.string.app_name);

        wakeLock.release();
        
        super.onDestroy();
        
        // Stop detecting
        mSensorManager.unregisterListener(mStepDetector);

        // Tell the user we stopped.
        Toast.makeText(this, getText(R.string.stopped), Toast.LENGTH_SHORT).show();
    }

    private void registerDetector() {
        mSensor = mSensorManager.getDefaultSensor(
            Sensor.TYPE_ACCELEROMETER /*| 
            Sensor.TYPE_MAGNETIC_FIELD | 
            Sensor.TYPE_ORIENTATION*/);
        mSensorManager.registerListener(mStepDetector,
            mSensor,
            SensorManager.SENSOR_DELAY_FASTEST);
    }

    private void unregisterDetector() {
        mSensorManager.unregisterListener(mStepDetector);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "[SERVICE] onBind");
        return mBinder;
    }

    /**
     * Receives messages from activity.
     */
    private final IBinder mBinder = new StepBinder();

    public interface ICallback {
        public void stepsChanged(int value);
        public void paceChanged(int value);
        public void distanceChanged(float value);
        public void speedChanged(float value);
        public void caloriesChanged(float value);
    }
    
    private ICallback mCallback;

    public void registerCallback(ICallback cb) {
        mCallback = cb;
        //mStepDisplayer.passValue();
        //mPaceListener.passValue();
    }
    
    private int mDesiredPace;
    private float mDesiredSpeed;
    
    /**
     * Called by activity to pass the desired pace value, 
     * whenever it is modified by the user.
     * @param desiredPace
     */
    public void setDesiredPace(int desiredPace) {
        mDesiredPace = desiredPace;
        if (mPaceNotifier != null) {
            mPaceNotifier.setDesiredPace(mDesiredPace);
        }
    }
    /**
     * Called by activity to pass the desired speed value, 
     * whenever it is modified by the user.
     * @param desiredSpeed
     */
    public void setDesiredSpeed(float desiredSpeed) {
        mDesiredSpeed = desiredSpeed;
        if (mSpeedNotifier != null) {
            mSpeedNotifier.setDesiredSpeed(mDesiredSpeed);
        }
    }
    
    public void reloadSettings() {
        mSettings = PreferenceManager.getDefaultSharedPreferences(this);
        
        if (mStepDetector != null) { 
            mStepDetector.setSensitivity(
                    Float.valueOf(mSettings.getString("sensitivity", "10"))
            );
        }
        
        if (mStepDisplayer    != null) mStepDisplayer.reloadSettings();
        if (mPaceNotifier     != null) mPaceNotifier.reloadSettings();
        if (mDistanceNotifier != null) mDistanceNotifier.reloadSettings();
        if (mSpeedNotifier    != null) mSpeedNotifier.reloadSettings();
        if (mCaloriesNotifier != null) mCaloriesNotifier.reloadSettings();
        if (mSpeakingTimer    != null) mSpeakingTimer.reloadSettings();
    }
    
    public void resetValues() {
        mStepDisplayer.setSteps(0);
        mPaceNotifier.setPace(0);
        mDistanceNotifier.setDistance(0);
        mSpeedNotifier.setSpeed(0);
        mCaloriesNotifier.setCalories(0);
    }
    
    /**
     * Forwards pace values from PaceNotifier to the activity. 
     */
    private StepDisplayer.Listener mStepListener = new StepDisplayer.Listener() {
        public void stepsChanged(int value) {
            mSteps = value;
            passValue();
        }
        public void passValue() {
            if (mCallback != null) {
                mCallback.stepsChanged(mSteps);
            }
        }
    };
    /**
     * Forwards pace values from PaceNotifier to the activity. 
     */
    private PaceNotifier.Listener mPaceListener = new PaceNotifier.Listener() {
        public void paceChanged(int value) {
            mPace = value;
            passValue();
        }
        public void passValue() {
            if (mCallback != null) {
                mCallback.paceChanged(mPace);
            }
        }
    };
    /**
     * Forwards distance values from DistanceNotifier to the activity. 
     */
    private DistanceNotifier.Listener mDistanceListener = new DistanceNotifier.Listener() {
        public void valueChanged(float value) {
            mDistance = value;
            passValue();
        }
        public void passValue() {
            if (mCallback != null) {
                mCallback.distanceChanged(mDistance);
            }
        }
    };
    /**
     * Forwards speed values from SpeedNotifier to the activity. 
     */
    private SpeedNotifier.Listener mSpeedListener = new SpeedNotifier.Listener() {
        public void valueChanged(float value) {
            mSpeed = value;
            passValue();
        }
        public void passValue() {
            if (mCallback != null) {
                mCallback.speedChanged(mSpeed);
            }
        }
    };
    /**
     * Forwards calories values from CaloriesNotifier to the activity. 
     */
    private CaloriesNotifier.Listener mCaloriesListener = new CaloriesNotifier.Listener() {
        public void valueChanged(float value) {
            mCalories = value;
            passValue();
        }
        public void passValue() {
            if (mCallback != null) {
                mCallback.caloriesChanged(mCalories);
            }
        }
    };
    
    /**
     * Show a notification while this service is running.
     */
    private void showNotification() {
        CharSequence text = getText(R.string.app_name);
        Notification notification = new Notification(R.drawable.ic_notification, null,
                System.currentTimeMillis());
        notification.flags = Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
        Intent pedometerIntent = new Intent();
        pedometerIntent.setComponent(new ComponentName(this, Pedometer.class));
        pedometerIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                pedometerIntent, 0);
        notification.setLatestEventInfo(this, text,
                getText(R.string.notification_subtitle), contentIntent);

        mNM.notify(R.string.app_name, notification);
    }


    // BroadcastReceiver for handling ACTION_SCREEN_OFF.
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Check action just to be on the safe side.
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                // Unregisters the listener and registers it again.
                StepService.this.unregisterDetector();
                StepService.this.registerDetector();
                if (mPedometerSettings.wakeAggressively()) {
                    wakeLock.release();
                    acquireWakeLock();
                }
            }
        }
    };

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        int wakeFlags;
        if (mPedometerSettings.wakeAggressively()) {
            wakeFlags = PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP;
        }
        else if (mPedometerSettings.keepScreenOn()) {
            wakeFlags = PowerManager.SCREEN_DIM_WAKE_LOCK;
        }
        else {
            wakeFlags = PowerManager.PARTIAL_WAKE_LOCK;
        }
        wakeLock = pm.newWakeLock(wakeFlags, TAG);
        wakeLock.acquire();
    }

    /**
     * RdioListener interface
     */
	@Override
	public void onRdioReady() {
		mQueryBusy = false;
		mTracks = new LinkedList<Track>();
        mPaceNotifier.addListener(mRdioPaceListener);
	}

	@Override
	public void onRdioUserPlayingElsewhere() {
		// TODO Implement this		
	}

	@Override
	public void onRdioUserAppApprovalNeeded(Intent authorisationIntent) {
		// Not making API calls which require authentication
	}

	@Override
	public void onRdioAuthorised(String accessToken, String accessTokenSecret) {
		// Not making API calls which require authentication
	}

	/**
	 * Rdio API bits
	 */
	PaceNotifier.Listener mRdioPaceListener = new PaceNotifier.Listener() {
        public void paceChanged(int value) {
        	if (value > 0 && mCurrentTrack != null && Math.abs(mPace - mCurrentTrack.tempo) > 50 && mTracks.size() > 0)
        		next(true);
        	
        	if (value > 0 && !mQueryBusy) {
        		mQueryBusy = true;
        		
        		// Query EchoNest to find songs that match this pace
        		// http://developer.echonest.com/api/v4/song/search?api_key=N6E4NIOVYMTHNDM8J&format=json&results=1&artist=radiohead&title=karma%20police
        		int maxTempo = value + 10;
        		int minTempo = value - 10;
        		
        		final String url = "http://developer.echonest.com/api/v4/song/search?api_key=" + mEchoNestKey + "&format=json&results=5&max_tempo=" +
        							(maxTempo > 500 ? 500 : maxTempo) + "&min_tempo=" + (minTempo < 0 ? 0 : minTempo) +
        							"&bucket=id:rdio-us-streaming&bucket=audio_summary&limit=true&sort=danceability-desc";
        		Log.i(TAG, "Searching for songs via EchoNest: " + url);
        		AsyncTask<Void, Void, JSONObject> searchTask = new AsyncTask<Void, Void, JSONObject>() {
					@Override
					protected JSONObject doInBackground(Void... params) {
						HttpClient client = new DefaultHttpClient();
						HttpGet get = new HttpGet(url);

						try {
							HttpResponse response = client.execute(get);
							if (response.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_OK) {
								String error = "Request failed with status " + response.getStatusLine();
								Log.e(TAG, error);
								throw new Exception(error);
							}
							HttpEntity entity = response.getEntity();
							if (entity == null) {
								String error = "Null entity in response";
								Log.e(TAG, error);
								throw new Exception(error);
							}
							String jsonString = EntityUtils.toString(entity);
							JSONObject json = new JSONObject(jsonString);
							return json;
						} catch (Exception e) {
							Log.e(TAG, "Error searching EchoNest: ", e);
							return null;
						}
					}
					
					@Override
					protected void onPostExecute(JSONObject json) {
						mQueryBusy = false;
						if (json == null)
							return;
						
						try {
							Log.i(TAG, "JSON returned from EchoNest: " + json.toString(2));
							json = json.getJSONObject("response");
							int statusCode = json.getJSONObject("status").getInt("code");
							if (statusCode != 0) {
								Log.i(TAG, "Unsuccessful search.");
								return;
							}
							
							JSONArray songs = json.getJSONArray("songs");
							if (songs.length() <= 0) {
								Log.i(TAG, "No results returned for search.");
								return;
							}
							
							List<Track> newTracks = new LinkedList<Track>();
							for (int i=0; i<songs.length(); i++) {
								JSONObject song = songs.getJSONObject(i);
								String rdioKey = song.getJSONArray("foreign_ids").getJSONObject(0).getString("foreign_id");
								if (!rdioKey.startsWith("rdio-us-streaming:song:")) {
									Log.i(TAG, "Skipping song " + i + ", not an Rdio key");
									continue;
								}
								
								rdioKey = rdioKey.replace("rdio-us-streaming:song:", "");
								Log.i(TAG, "Adding " + rdioKey + " to play queue");
								
								String name = song.getString("title");
								String artist = song.getString("artist_name");
								int tempo = song.getJSONObject("audio_summary").getInt("tempo");
								newTracks.add(new Track(rdioKey, name, artist, tempo));
							}
							
							mTracks.addAll(0, newTracks);
							if (mTracks.size() > 20)
								mTracks = mTracks.subList(0, 20);
							Log.i(TAG, "Track queue: ");
							for (int i=0; i < mTracks.size(); i++) {
								Log.i(TAG, "\t" + mTracks.get(i));
							}
							
							if (mPlayer == null || Math.abs(mPace - mCurrentTrack.tempo) > 50)
								next(true);
						} catch (Exception e) {
							Log.e(TAG, "Error parsing JSON: ", e);
						}
					}
        		};
        		searchTask.execute();
        	}
            passValue();
        }
        
        public void passValue() {
            if (mCallback != null) {
                mCallback.paceChanged(mPace);
            }
        }
	};
	
	public void next(final boolean manualPlay) {
		if (mPlayer != null) {
			mPlayer.stop();
			mPlayer.release();
			mPlayer = null;
		}

		if (mTracks.size() == 0) {
			Log.e(TAG, "No tracks on queue :(");
			return;
		}

		mCurrentTrack = mTracks.remove(0);
		// Load the next track in the background and prep the player (to start buffering)
		// Do this in a bkg thread so it doesn't block the main thread in .prepare()
		AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
			@Override
			protected Void doInBackground(Void... params) {
				try {
					Log.i(TAG, "Playing track...");
					mPlayer = mRdio.getPlayerForTrack(mCurrentTrack.key, null, manualPlay);
					mPlayer.prepare();
					mPlayer.setOnCompletionListener(new OnCompletionListener() {
						@Override
						public void onCompletion(MediaPlayer mp) {
							next(false);
						}
					});
					mPlayer.start();
					
				} catch (Exception e) {
					Log.e("Test", "Exception " + e);
				}
				return null;
			}
			
			@Override
			protected void onPostExecute(Void r) {
				notifyPlayerListener(mCurrentTrack);
			}
		};
		task.execute();
	}
}