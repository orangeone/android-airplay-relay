package jp.orangeone.airplay.relay;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.jmdns.JmDNS;
import javax.jmdns.NetworkTopologyDiscovery;
import javax.jmdns.ServiceInfo;

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
import panda.bind.json.Jsons;
import panda.io.Streams;
import panda.io.stream.ByteArrayOutputStream;
import panda.lang.Exceptions;
import panda.lang.Numbers;
import panda.lang.Strings;
import panda.log.Log;
import panda.log.Logs;
import panda.net.Sockets;

public class MainActivity extends Activity {
	private static final Log log = Logs.getLog(MainActivity.class);
	private static final String AIRPLAY = "_airplay._tcp.local.";

	private Relayer relayer;
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
		
		init();
	}

	private InetAddress localAddress;
	private InetAddress globalAddress;
	
	private void init() {
		AsyncTask<Void, Void, Void> at = new AsyncTask<Void, Void, Void>() {
			@Override
			protected Void doInBackground(Void... params) {
				try {
					for (InetAddress ina : NetworkTopologyDiscovery.Factory.getInstance().getInetAddresses()) {
						log.debug("Find IP address: " + ina + " - " + ina.isAnyLocalAddress() + " " + ina.isSiteLocalAddress());
						infos.add(ina + " - " + ina.isAnyLocalAddress() + " " + ina.isSiteLocalAddress());
						if (ina.isLoopbackAddress()) {
							continue;
						}
						if (ina.isSiteLocalAddress()) {
							localAddress = ina;
						}
						else {
							globalAddress = ina;
						}
					}

					if (localAddress != null) {
						log.debug("Use address: " + localAddress);
						jmdns = JmDNS.create(localAddress);
					}
					
					refreshList();
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

	private void refreshList() {
		runOnUiThread(new NotifyListRunnable());
	}

	private class NotifyListRunnable implements Runnable {
		@Override
		public void run() {
			lstAdapter.notifyDataSetChanged();
		}
	}

	private void onButtonStart() {
		AsyncTask<Void, Void, Void> at = new AsyncTask<Void, Void, Void>() {
			@Override
			protected Void doInBackground(Void... params) {
				if (relayer == null) {
					relayer = new Relayer();
					relayer.start();
				}
				return null;
			}
		};
		at.execute();
	}

	private void onButtonStop() {
		if (relayer != null) {
			relayer.quit();
			relayer = null;
		}
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
				catch (Exception e) {
					log.error(e);
				}

				onButtonStop();
				
				return null;
			}
		};
		at.execute();

		super.onBackPressed();
		this.finish();
	}
	
	public static class ServInfo {
		public String type;
		public String name;
		public int port;
		public int weight;
		public int priority;
		public Map<String, String> props;
	}
	
	public class Acceptor extends Thread {
		protected InetSocketAddress isa;
		protected boolean relaying = true;
		protected Socket socket;
		ServerSocket listener = null;

		public Acceptor(InetAddress ia, int port) {
			isa = new InetSocketAddress(ia, port);
		}

		public void quit() {
			relaying = false;
			try {
				join();
			}
			catch (InterruptedException e) {
			}
		}
		
		public void init() throws IOException {
			log.info("Listening on " + isa);
			infos.add("Listening on " + isa);
			refreshList();

			listener = new ServerSocket();
			listener.bind(isa, 50);
		}

		public void run() {
			try {
				while (relaying) {
					Socket client = listener.accept();
					if (socket != null) {
						log.debug("Already connected, close socket: " + client);
						Sockets.safeClose(client);
					}
					else {
						log.info("Accept socket: " + client);
						socket = client;
					}
				}
			}
			catch (IOException e) {
				log.error(e);
			}
			finally {
				Sockets.safeClose(socket);
				Sockets.safeClose(listener);
			}
		}
	}

	protected class Transportor {
		InetSocketAddress addr;
		Socket socket;
		
		public Transportor(InetSocketAddress addr) throws IOException {
			this.addr = addr;
			socket = new Socket();
			
			String msg = "Connect to " + addr;
			log.info(msg);
			socket.connect(addr);

			infos.add(msg);
			refreshList();
		}
		
		public OutputStream getOutputStream() throws IOException {
			return socket.getOutputStream();
		}

		public InputStream getInputStream() throws IOException {
			return socket.getInputStream();
		}

		public void close() {
			if (socket != null) {
				Sockets.safeClose(socket);
			}
		}
	}

	protected class Relayer extends Thread {
		protected boolean relaying;
		protected ByteArrayOutputStream buffer;
		protected Packet packet = new Packet();
		List<Acceptor> acceptors = new ArrayList<Acceptor>();
		Transportor transportor;

		public class Packet {
			protected int port;
			protected int size;
			public void reset() {
				port = 0;
				size = 0;
			}
		}

		public void quit() {
			this.relaying = false;
			try {
				join();
			}
			catch (InterruptedException e) {
			}
		}
		
		private void addJmdnsService(String s) throws IOException {
			ServInfo rsi = Jsons.fromJson(s, ServInfo.class);
			rsi.props.put("relay", "true");
			ServiceInfo lsi = ServiceInfo.create(rsi.type, rsi.name, rsi.port, rsi.weight,
				rsi.priority, rsi.props);
			jmdns.registerService(lsi);

			for (Acceptor a : acceptors) {
				if (a.isa.getPort() == rsi.port) {
					log.warn("Already listen on " + a.isa);
					return;
				}
			}

			Acceptor a = new Acceptor(localAddress, rsi.port);
			a.init();
			a.start();
			acceptors.add(a);

			log.debug("Add Service: " + s);
			infos.add("Add Service: " + s);
			refreshList();
		}
		
		/**
		 * Services this thread's client by first sending the client a welcome message then
		 * repeatedly reading strings and sending back the capitalized version of the string.
		 */
		public void run() {
			try {
				transportor = new Transportor(new InetSocketAddress("pdemo.foolite.com", 8888));
				transportor.getOutputStream().write("HELO 1\n".getBytes());
				transportor.getOutputStream().flush();

				relaying = true;
				buffer = new ByteArrayOutputStream();

				while (relaying) {
					transportRemote(transportor);
					for (int i = 0; i < acceptors.size(); i++) {
						transportLocal(acceptors.get(i));
					}
				}
			}
			catch (EOFException e) {
				log.debug(e.getMessage());
			}
			catch (IOException e) {
				log.warn(e);
			}
			catch (Throwable e) {
				log.error(e);
			}
			finally {
				for (Acceptor a : acceptors) {
					a.quit();
				}
				acceptors.clear();
			}
		}

		private void transportLocal(Acceptor la) throws IOException {
			if (la.socket == null) {
				return;
			}
			
			InputStream is = la.socket.getInputStream();
			int a = is.available();
			if (a <= 0) {
				return;
			}

			OutputStream os = transportor.getOutputStream();

			String msg = "SEND " + la.socket.getLocalPort() + ' ' + a + Strings.LF;
			log.debug("> " + msg);

			os.write(msg.getBytes());
			long n = Streams.copyLarge(is, os, 0, a);
			
			if (a != n) {
				throw new IOException("Failed to relay: " + n + " / " + a);
			}
			os.flush();
		}

		private void transportRemote(Transportor t) throws IOException {
			InputStream is = t.getInputStream();
			transportRemote(is, is.available());
		}
		
		private void transportRemote(InputStream is, int a) throws IOException {
			if (a <= 0) {
				return;
			}
			log.debug("available: " + a);
			if (packet.size > 0) {
				int l = packet.size > a ? a : packet.size;
				int n = transferData(is, l);
				packet.size -= n;
				if (packet.size <= 0) {
					packet.reset();
				}
				transportRemote(is, a - n);
			}
			else {
				buffer.write(is, a);
				InputStream bis = buffer.toInputStream();
				int i = (int)Streams.skipTo(bis, 0x0a);
				if (i > 0) {
					InputStream bis2 = buffer.toInputStream(0, i);
					String s = Streams.toString(bis2);
					log.debug("<< " + s);
					
					String cmd = Strings.substringBefore(s, ' ');
					if ("HELO".equals(cmd)) {
						addJmdnsService(Strings.substringAfter(s, ' '));
					}
					else if ("SEND".equals(cmd)) {
						String[] ss = Strings.split(s);
						if (ss.length != 3) {
							log.warn("Invalid command: " + s);
							return;
						}
						
						packet.port = Numbers.toInt(ss[1], 0);
						packet.size = Numbers.toInt(ss[2], 0);
						if (packet.port <= 0 || packet.size <= 0) {
							log.warn("Invalid command: " + s);
							packet.reset();
							return;
						}
					}
					else {
						log.warn("Invalid packet: " + Strings.newStringUtf8(buffer.toByteArray()));
						return;
					}

					if (i < buffer.size()) {
						while (bis.available() > 0) {
							transportRemote(bis, bis.available());
						}
					}
					buffer.reset();
				}
			}
		}
		
		protected int transferData(InputStream is, int len) throws IOException {
			Socket s = null;
			for (Acceptor a : acceptors) {
				if (packet.port == a.socket.getLocalPort()) {
					s = a.socket;
					break;
				}
			}

			int n;
			if (s != null) {
				OutputStream os = s.getOutputStream();
				n = (int)Streams.copyLarge(is, os, 0, len);
				os.flush();
				return n;
			}
			else {
				log.warn("Skip unknown packet: " + packet.port + "/" + packet.size);
				n = (int)Streams.skip(is, len);
			}
			return n;
		}
	}

	protected class Echoer extends Thread {
		protected boolean relaying;
		protected ByteArrayOutputStream buffer;
		Transportor transportor;

		public void quit() {
			this.relaying = false;
			try {
				join();
			}
			catch (InterruptedException e) {
			}
		}
		
		/**
		 * Services this thread's client by first sending the client a welcome message then
		 * repeatedly reading strings and sending back the capitalized version of the string.
		 */
		public void run() {
			try {
				transportor = new Transportor(new InetSocketAddress("pdemo.foolite.com", 8888));
				transportor.getOutputStream().write("HELO 1\n".getBytes());
				transportor.getOutputStream().flush();

				relaying = true;
				buffer = new ByteArrayOutputStream();

				while (relaying) {
					int a = transportor.getInputStream().available();
					if (a > 0) {
						buffer.reset();
						buffer.write(transportor.getInputStream(), a);
						log.debug("> " + new String(buffer.toByteArray()));
					}
				}
			}
			catch (IOException e) {
				log.warn(e);
			}
			catch (Throwable e) {
				log.error(e);
			}
			finally {
				transportor.close();
			}
		}
	}
}

