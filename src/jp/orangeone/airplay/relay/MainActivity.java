package jp.orangeone.airplay.relay;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import javax.jmdns.JmDNS;
import javax.jmdns.NetworkTopologyDiscovery;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;

import panda.android.Androids;
import panda.lang.Exceptions;
import panda.log.Log;
import panda.log.Logs;

public class MainActivity extends Activity {
	private static final Log log = Logs.getLog(MainActivity.class);
	private static final String AIRPLAY = "_airplay._tcp.local.";

	private JmDNS jmdns;
	private List<String> infos = new ArrayList<String>();
	private ArrayAdapter<String> lstAdapter;
	private int selected = -1;

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		Androids.init(this);
		log.debug("onCreate()");

		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		Intent in = this.getIntent();
		Uri uri = in.getData();
		if (uri != null) {
			this.setTitle(this.getTitle() + " " + uri);
		}

		lstAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, infos);
		ListView lstInfo = (ListView)findViewById(R.id.lstInfo);
		lstInfo.setAdapter(lstAdapter);
		lstInfo.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				selected = position;
				for (int i = 0; i < parent.getChildCount(); i++) {
					parent.getChildAt(i).setBackgroundColor(Color.TRANSPARENT);
				}
				view.setBackgroundColor(Color.GREEN);
			}
		});

		((Button)findViewById(R.id.btnStart)).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				onButtonStart();
			}
		});
		((Button)findViewById(R.id.btnStop)).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				onButtonStop();
			}
		});
		((Button)findViewById(R.id.btnFind)).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				onButtonFind();
			}
		});
		
		initJmDNS();
	}

	private void initJmDNS() {
		AsyncTask<Void, Void, Void> at = new AsyncTask<Void, Void, Void>() {
			@Override
			protected Void doInBackground(Void... params) {
				try {
					InetAddress address = null;
					for (InetAddress ina : NetworkTopologyDiscovery.Factory.getInstance().getInetAddresses()) {
						if (ina.isLoopbackAddress()) {
							continue;
						}
						address = ina;
						if (address instanceof Inet4Address) {
							break;
						}
					}

					log.debug("Use address: " + address);
					jmdns = JmDNS.create(address);
					jmdns.addServiceListener(AIRPLAY, new JmDNSServiceListener());
				}
				catch (IOException e) {
					log.error("Failed to create JmDNS", e);
					throw Exceptions.wrapThrow(e);
				}
				return null;
			}
		};
		at.execute();
	}

	private class NotifyListRunnable implements Runnable {
		@Override
		public void run() {
			lstAdapter.notifyDataSetChanged();
		}
	}

	private class JmDNSServiceListener implements ServiceListener {
		@Override
		public void serviceAdded(ServiceEvent se) {
			log.debug("ServiceAdded: " + se);
			ServiceInfo si = se.getInfo();
			String ss = si.toString();
			if (!infos.contains(ss)) {
				infos.add(ss);
				runOnUiThread(new NotifyListRunnable());
			}
		}

		@Override
		public void serviceRemoved(ServiceEvent se) {
			log.debug("ServiceRemoved: " + se);
			ServiceInfo si = se.getInfo();
			String ss = si.toString();
			if (infos.contains(ss)) {
				infos.remove(ss);
				runOnUiThread(new NotifyListRunnable());
			}
		}

		@Override
		public void serviceResolved(ServiceEvent se) {
			log.debug("ServiceResolved: " + se);
		}
	}

	private void onButtonStart() {
	}

	private void onButtonStop() {
	}

	private void onButtonFind() {
		infos.clear();
		for (ServiceInfo si : jmdns.list(AIRPLAY)) {
			infos.add(si.toString());
		}
		lstAdapter.notifyDataSetChanged();
	}

	public void onStart() {
		log.debug("onStart()");
		super.onStart();
	}

	public void onResume() {
		log.debug("onResume()");
		super.onResume();
	}

	public void onDestory() {
		log.debug("onDestory()");
		super.onDestroy();
	}

	public void onStop() {
		log.debug("onStop()");
		super.onStop();
	}

	public void onPause() {
		log.debug("onPause()");
		super.onPause();
	}

	@Override
	public void onBackPressed() {
		log.debug("onBackPressed()");
		AsyncTask<Void, Void, Void> at = new AsyncTask<Void, Void, Void>() {
			@Override
			protected Void doInBackground(Void... params) {
				try {
					jmdns.close();
				}
				catch (IOException e) {
					log.error(e);
				}
				return null;
			}
		};
		at.execute();

		super.onBackPressed();
		this.finish();
	}
}

