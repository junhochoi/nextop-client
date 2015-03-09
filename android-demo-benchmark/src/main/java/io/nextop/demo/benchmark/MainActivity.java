package io.nextop.demo.benchmark;

import android.app.Activity;
import android.os.Bundle;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;


public class MainActivity extends Activity {
	static final String LOG_TAG = MainActivity.class.getSimpleName();
	static final String NEXTOP_REPORTING_URL = "http://10.1.10.10:9091/result/nextop";
	static final String VOLLEY_REPORTING_URL = "http://10.1.10.10:9091/result/volley";
	static final int MAX_RUNS = 50;
	static final int RUN_INTERVAL = 10000; // ms;

	String deviceId = "Unknown";
	String wifiStrength = "Unknown";
	String cellStrength = "Unknown";

	List<String> benchmarkUrls = Collections.EMPTY_LIST;

	ListView timingsList;
	BenchmarkMeasurementAdapter timingsAdapter;


	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.menu_main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();

		//noinspection SimplifiableIfStatement
		if (id == R.id.action_settings) {
			return true;
		}

		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		timingsList = (ListView) findViewById(R.id.timings);
		timingsAdapter = new BenchmarkMeasurementAdapter(this);
		timingsList.setAdapter(timingsAdapter);

		loadBenchmarkUrls();

		// TODO get device id
		deviceId = "TODO";

		// get cell strength
		TelephonyManager telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
		telephonyManager.listen(
						new PhoneStateListener() {

							public void onSignalStrengthsChanged(SignalStrength strength) {
								super.onSignalStrengthsChanged(strength);
								cellStrength = strength.toString();
							}

						}, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);

		// TODO get wifi strength
		wifiStrength = "TODO";
	}

	public void volley(final View view) {
		final Timer timer = new Timer();
		final AtomicInteger count = new AtomicInteger(0);

		timer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				String runId = String.format("volley-%d", count.get());

				Map<String, String> runContext = ImmutableMap.of(
          "runId", runId,
          "deviceId", deviceId,
          "cellStrength", cellStrength,
          "wifiStrength", wifiStrength
				);

				Benchmark<URL> benchmark = VolleyJsonBenchmark.withStringUrls(view.getContext(), benchmarkUrls);

				benchmark.addListener(BenchmarkReporter.withStringUrl(VOLLEY_REPORTING_URL));
				benchmark.addListener(new Benchmark.ResultListener() {
					@Override
					public void onResult(Benchmark.Result result) {
						TextView methodText = (TextView) findViewById(R.id.context_method);
						methodText.setText(result.getValue("method"));

						TextView runIdText = (TextView) findViewById(R.id.context_runId);
						runIdText.setText(result.getValue("runId"));

						timingsAdapter.clear();
						timingsAdapter.addAll(result.getMeasurements());
						timingsAdapter.notifyDataSetChanged();
					}
				});

				benchmark.run(runContext);

				if (count.incrementAndGet() >= MAX_RUNS) {
					timer.cancel();
				}
			}
		}, 0, RUN_INTERVAL);


	}

	public void nextop(final View view) {
		final Timer timer = new Timer();
		final AtomicInteger count = new AtomicInteger(0);

		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				String runId = String.format("nextop-%d", count.get());

				Map<String, String> runContext = ImmutableMap.of(
          "runId", runId,
          "deviceId", deviceId,
          "cellStrength", cellStrength,
          "wifiStrength", wifiStrength
				);

				Benchmark<URL> benchmark = NextopJsonBenchmark.withStringUrls(view.getContext(), benchmarkUrls);

				benchmark.addListener(BenchmarkReporter.withStringUrl(NEXTOP_REPORTING_URL));
				benchmark.addListener(new Benchmark.ResultListener() {
					@Override
					public void onResult(Benchmark.Result result) {
						TextView methodText = (TextView) findViewById(R.id.context_method);
						methodText.setText(result.getValue("method"));

						TextView runIdText = (TextView) findViewById(R.id.context_runId);
						runIdText.setText(result.getValue("runId"));

						timingsAdapter.clear();
						timingsAdapter.addAll(result.getMeasurements());
						timingsAdapter.notifyDataSetChanged();
					}
				});

				benchmark.run(runContext);

				if (count.incrementAndGet() >= MAX_RUNS) {
					timer.cancel();
				}
			}
		}, 0, RUN_INTERVAL);
	}

	private void loadBenchmarkUrls() {
		try {
			InputStream in = getResources().openRawResource(R.raw.benchmark);
			String str = CharStreams.toString(new InputStreamReader(in, Charsets.UTF_8));

			benchmarkUrls = Lists.newArrayList();
			JSONArray jsonArr = new JSONArray(str);
			for (int i=0; i<jsonArr.length(); i++) {
				benchmarkUrls.add(jsonArr.getString(i));
			}

		} catch(IOException ie) {
			Log.d(LOG_TAG, "Could not find benchmark.json");
			finish();
		} catch(JSONException je) {
			Log.d(LOG_TAG, "Could not parse benchmark.json");
			finish();
		}
	}

}
