// Pressure Altimeter - a pressure altimeter for Android devices
// written by Tom Stepleton <stepleton@gmail.com> in early 2012
// released into the public domain

package com.chamberdyne.PressureAltimeter;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

public class PressureAltimeterActivity extends Activity implements SensorEventListener {
	// A reference to this activity, for use in anonymous classes.
	private PressureAltimeterActivity this_activity_;

	// Handle for the debug text view.
	private TextView debugTextView_;
	
	// Pressure sensor and sensor manager.
	private Sensor sensor_pressure_;
	private SensorManager sensor_manager_;
	
	// Sea level pressure in inches of mercury.
	private double slp_inHg_;
	// Current measured pressure in millibars. It's aviation; you just have to
	// get used to ugly unit combos like this.
	private double pressure_hPa_;
	
	// Kalman filter for smoothing the measured pressure.
	private KalmanFilter pressure_hPa_filter_;
	// Time of the last measurement in seconds since boot; used to compute time
	// since last update so that the Kalman filter can estimate rate of change.
	private double last_measurement_time_;
	
	// Whether we've admonished the user not to use this app for flying planes.
	private boolean admonished_;
	// Whether we've told the user that they need a pressure sensor.
	private boolean pressured_;
	
	// Constants for the altitude calculation.
	// See http://psas.pdx.edu/RocketScience/PressureAltitude_Derived.pdf
	private static final double SLT_K = 288.15;  // Sea level temperature.
	private static final double TLAPSE_K_PER_M = -0.0065;  // Linear temperature atmospheric lapse rate.
	private static final double G_M_PER_S_PER_S = 9.80665;  // Acceleration from gravity.
	private static final double R_J_PER_KG_PER_K = 287.052;  // Specific gas constant for air, US Standard Atmosphere edition.
	
	// Constants for unit conversion.
	private static final double PA_PER_INHG = 3386;  // Pascals per inch of mercury.
	private static final double FT_PER_M = 3.2808399;  // Feet per meter.
	
	// Constants for the Kalman filter's noise models. These values are bigger
	// than actual noise recovered from data, but that's because that noise
	// looks more Laplacian than anything.
	private static final double KF_VAR_ACCEL = 0.0075;  // Variance of pressure acceleration noise input.
	private static final double KF_VAR_MEASUREMENT = 0.05;  // Variance of pressure measurement noise.
	
	// Constants for identifying dialogs.
	private static final int DIALOG_ADMONITION = 0;  // Don't use this to fly!
	private static final int DIALOG_PRESSURE = 1;  // Need a pressure sensor.
	
    /** Create activity, init objects, and get handles to various Droidly bits. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Create our Kalman filter.
        pressure_hPa_filter_ = new KalmanFilter(KF_VAR_ACCEL);
        
        // Set the main layout as the content view.
        setContentView(R.layout.main);

        // Obtain handle for the debug text view.
        debugTextView_ = (TextView) findViewById(R.id.debugText);

        // Obtain sensor manager and pressure sensor.
        sensor_manager_ = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensor_pressure_ = sensor_manager_.getDefaultSensor(Sensor.TYPE_PRESSURE);

    }
    

    /** On resume, set up the display and start listening to the pressure sensor. */
    @Override
    public void onResume() {
    	super.onResume();
    	
    	// Update our self-reference.
    	this_activity_ = this;
    	
    	// If we have started for the first time or have restored from corrupt
    	// state information, the altimeter setting will be bogus. Fall back
    	// on the standard day.
        if (slp_inHg_ < 28.1 || slp_inHg_ > 31.0) slp_inHg_ = 29.92;
        
        // Likewise, we set the pressure reading to standard day sea level. It
        // should get overwritten by the sensor right away, but if we don't
        // have a pressure sensor, a nice zero indication will show up instead.
        pressure_hPa_ = 1013.0912;
    	
        // We reset the Kalman filter with that same pressure value, then mark
        // now as the time of the last measurement.
        pressure_hPa_filter_.reset(pressure_hPa_);
        last_measurement_time_ = SystemClock.elapsedRealtime() / 1000.;
        
    	// Immediately update the hands and the pressure dial to reflect
    	// initial or saved state.
        updatePressureDial();
        updateNeedles();
        
        // If we haven't admonished the user not to use this app for flying
        // actual airplanes, do so.
        if (!admonished_) {
        	showDialog(DIALOG_ADMONITION);
        	admonished_ = true;
        }
    	
    	// Start listening to the pressure sensor.
    	if (sensor_pressure_ != null) {
    		sensor_manager_.registerListener(this, sensor_pressure_, SensorManager.SENSOR_DELAY_GAME);
    	} else if (!pressured_) {
    		showDialog(DIALOG_PRESSURE);
    		pressured_ = true;
    	}    	
    }

    /** On pause, stop listening to sensors. */
    @Override
    public void onPause() {
    	super.onPause();
    	sensor_manager_.unregisterListener(this);
    }
    
    /** Save the current altimeter setting, etc. during interruptions. */
    protected void onSaveInstanceState(Bundle bundle) {
    	super.onSaveInstanceState(bundle);
    	bundle.putDouble("slp_inHg", slp_inHg_);
    	bundle.putBoolean("admonished", admonished_);
    	bundle.putBoolean("pressured", pressured_);
    }
    
    /** Retrieve the current altimeter setting, etc. after interruptions. */
    protected void onRestoreInstanceState(Bundle bundle) {
    	super.onRestoreInstanceState(bundle);
    	slp_inHg_ = bundle.getDouble("slp_inHg");
    	admonished_ = bundle.getBoolean("admonished");
    	pressured_ = bundle.getBoolean("pressured");
    }

    /** Use the volume buttons to change the altimeter setting. */
    public boolean onKeyDown(int key_code, KeyEvent key_event) {
    	// Only want volume keys.
    	if (key_code != KeyEvent.KEYCODE_VOLUME_UP &&
    		key_code != KeyEvent.KEYCODE_VOLUME_DOWN) return false;
    	
    	// We raise or lower the altimeter setting with the volume keys.
    	// We do all arithmetic in longs since multiples of 0.01 do not
    	// have a finite binary representation.
    	long slp_inHg_long = Math.round(100.0 * slp_inHg_);
    	if (key_code == KeyEvent.KEYCODE_VOLUME_UP) {
    		if (slp_inHg_long < 3100) ++slp_inHg_long;
    	} else if (key_code == KeyEvent.KEYCODE_VOLUME_DOWN) {
    		if (slp_inHg_long > 2810) --slp_inHg_long;
    	}
    	slp_inHg_ = slp_inHg_long / 100.0;
    	
    	// We update the altitude needles along with the pressure dial so
    	// that the altimeter setting change has an immediate effect.
    	updatePressureDial();
    	updateNeedles();
    	
    	return true;
    }

    /** Don't let anyone else use the volume buttons for anything! */
    public boolean onKeyUp(int key_code, KeyEvent key_event) {
    	// We really don't want any events involving the volume keys to
    	// leak out, so if it's volume, we say we handled it.
    	return key_code == KeyEvent.KEYCODE_VOLUME_UP ||
       	       key_code == KeyEvent.KEYCODE_VOLUME_DOWN;
    }
    
    
	public void onAccuracyChanged(Sensor arg0, int arg1) {
		// Does nothing, bummer.
	}

	public void onSensorChanged(SensorEvent event) {
		// Update current measured pressure.
		if (event.sensor.getType() != Sensor.TYPE_PRESSURE) return;  // Should not occur.
		pressure_hPa_ = event.values[0];
		// Update the Kalman filter.
		final double curr_measurement_time = SystemClock.elapsedRealtime() / 1000.;
		final double dt = curr_measurement_time - last_measurement_time_;
        pressure_hPa_filter_.update(pressure_hPa_, KF_VAR_MEASUREMENT, dt);
		last_measurement_time_ = curr_measurement_time;
		// Update the needles.
		updateNeedles();
	}

	// Convert atmospheric pressure to feet.
	// See http://psas.pdx.edu/RocketScience/PressureAltitude_Derived.pdf
	// Takes the current sea level pressure in inches of mercury and the current
	// measured pressure in millibars. Like I said, this is normal in aviation.
	// Just look at an American METAR sometime.
	private static double hPaToFeet(double slp_inHg, double pressure_hPa) {
    	// Algebraically unoptimized computations---let the compiler sort it out.
    	double factor_m = SLT_K / TLAPSE_K_PER_M;
    	double exponent = -TLAPSE_K_PER_M * R_J_PER_KG_PER_K / G_M_PER_S_PER_S;    	
    	double current_sea_level_pressure_Pa = slp_inHg * PA_PER_INHG;
    	double altitude_m =
    			factor_m *
    			(Math.pow(100.0 * pressure_hPa / current_sea_level_pressure_Pa, exponent) - 1.0);
    	return FT_PER_M * altitude_m;
    }
	
	// Retrieve the fractional part of a double.
	private static double getFractional(double value) {
		return value - ((long) value);
	}
	
	/** Update the positions of the altimeter's three indicator needles to
	 *  the current altitude in feet. */
	private void updateNeedles() {
		// Compute current altitude in feet given Kalman-filtered pressure.
		double altitude_ft = hPaToFeet(slp_inHg_, pressure_hPa_filter_.getXAbs());

		// Determine angular orientation of the needles.
		double angle_needle_100 = 360.0 * getFractional(altitude_ft / 1000.0);
		double angle_needle_1000 = 360.0 * getFractional(altitude_ft / 10000.0);
		double angle_needle_10000 = 360.0 * getFractional(altitude_ft / 100000.0);

		// Update debugging text.
		debugTextView_.setText(String.format(
				"Debug info\n" +
				"Raw baro: %4.3f\n" +
				"Filtered: %4.3f\n" +
				" Δ (est): %4.3f",
				pressure_hPa_,
				pressure_hPa_filter_.getXAbs(),
				pressure_hPa_filter_.getXVel()));

	}

	/** Rotate the pressure setting dial to reflect the actual value of slp_inHg_. */
	private void updatePressureDial() {
	    double degrees = 100.0 * (31.0 - slp_inHg_);
	}
	
	/** Create dialogs, mainly to admonish and pressure the user. */
	protected Dialog onCreateDialog(int id) {
		Dialog dialog;
		switch(id) {
		case DIALOG_ADMONITION: {
			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setMessage(R.string.alert_admonition)
			       .setCancelable(false)
			       .setPositiveButton(R.string.alert_admonition_ack, new DialogInterface.OnClickListener() {
			    	   public void onClick(DialogInterface dialog, int id) {
			    		   dialog.dismiss();
			    	   }
			       });
			dialog = builder.create();
			break;
		}
		case DIALOG_PRESSURE: {
			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setMessage(R.string.alert_pressure)
			       .setCancelable(false)
			       .setPositiveButton(R.string.alert_pressure_ack, new DialogInterface.OnClickListener() {
			    	   public void onClick(DialogInterface dialog, int id) {
			    		   dialog.dismiss();
			    	   }
			       })
				   .setNegativeButton(R.string.alert_pressure_abort, new DialogInterface.OnClickListener() {
				       public void onClick(DialogInterface dialog, int id) {
				    	   this_activity_.finish();
				       }
			       });
			dialog = builder.create();
			break;
		}
	    default:
		    dialog = null;
		}
		return dialog;
	}
}