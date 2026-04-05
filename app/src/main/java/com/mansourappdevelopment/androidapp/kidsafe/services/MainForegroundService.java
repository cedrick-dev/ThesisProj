package com.mansourappdevelopment.androidapp.kidsafe.services;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.ContactsContract;
import android.telephony.TelephonyManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;
import com.mansourappdevelopment.androidapp.kidsafe.R;
import com.mansourappdevelopment.androidapp.kidsafe.activities.BlockedAppActivity;
import com.mansourappdevelopment.androidapp.kidsafe.activities.ChildSignedInActivity;
import com.mansourappdevelopment.androidapp.kidsafe.broadcasts.AppInstalledReceiver;
import com.mansourappdevelopment.androidapp.kidsafe.broadcasts.AppRemovedReceiver;
import com.mansourappdevelopment.androidapp.kidsafe.broadcasts.PhoneStateReceiver;
import com.mansourappdevelopment.androidapp.kidsafe.broadcasts.ScreenTimeReceiver;
import com.mansourappdevelopment.androidapp.kidsafe.broadcasts.SmsReceiver;
import com.mansourappdevelopment.androidapp.kidsafe.models.App;
import com.mansourappdevelopment.androidapp.kidsafe.models.Child;
import com.mansourappdevelopment.androidapp.kidsafe.models.Contact;
import com.mansourappdevelopment.androidapp.kidsafe.models.ScreenLock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.mansourappdevelopment.androidapp.kidsafe.NotificationChannelCreator.CHANNEL_ID;
import static com.mansourappdevelopment.androidapp.kidsafe.NotificationChannelCreator.HIGH_PRIORITY_CHANNEL_ID;
import com.kidsafe.secure.nsfw.NsfwProtectionHelper;
import android.app.NotificationManager;

public class MainForegroundService extends Service {
	public static final int NOTIFICATION_ID = 27;
	public static final String TAG = "MainServiceTAG";
	public static final String BLOCKED_APP_NAME_EXTRA = "com.mansourappdevelopment.androidapp.kidsafe.services.BLOCKED_APP_NAME_EXTRA";
	private ExecutorService executorService;
	private ArrayList<App> apps;
	private PhoneStateReceiver phoneStateReceiver;
	private SmsReceiver smsReceiver;
	private AppInstalledReceiver appInstalledReceiver;
	private AppRemovedReceiver appRemovedReceiver;
	private ScreenTimeReceiver screenTimeReceiver;
	private String uid;
	private String childEmail;
	private FirebaseDatabase firebaseDatabase = FirebaseDatabase.getInstance();
	private DatabaseReference databaseReference = firebaseDatabase.getReference("users");

	@Override
	public void onCreate() {
		super.onCreate();
		executorService = Executors.newSingleThreadExecutor();
		ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
		LockerThread thread = new LockerThread();
		executorService.submit(thread);
		new Thread(new Runnable() {
			@Override
			public void run() {
				getInstalledApplications();
			}
		}).start();
		Log.i(TAG, "onCreate: executed");
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		// String childEmail = intent.getStringExtra(CHILD_EMAIL);
		// String notificationContent = "Monitoring device";

		FirebaseAuth auth = FirebaseAuth.getInstance();
		FirebaseUser user = auth.getCurrentUser();
		if (user != null) {
			childEmail = user.getEmail();
			uid = user.getUid();
		} else {
			if (intent != null) {
				childEmail = intent.getStringExtra("CHILD_EMAIL");
				uid = intent.getStringExtra("CHILD_UID");
			}
			if (childEmail == null || uid == null) {
				Log.e(TAG, "User not authenticated and no intent extras provided. Stopping service.");
				// Must call startForeground before stopping to avoid ForegroundServiceDidNotStartInTimeException
				Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
						.setSmallIcon(R.drawable.ic_kidsafe)
						.setContentTitle("AegistNet Service Stopping")
						.setContentText("User not authenticated")
						.setPriority(NotificationCompat.PRIORITY_LOW)
						.build();
				startForeground(NOTIFICATION_ID, notification);
				stopSelf();
				return START_NOT_STICKY;
			}
		}

		Intent notificationIntent = new Intent(this, ChildSignedInActivity.class);
		PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
				PendingIntent.FLAG_IMMUTABLE);

		Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
				// .setContentTitle(notificationContent)
				.setSmallIcon(R.drawable.ic_kidsafe).setContentIntent(pendingIntent).build();

		startForeground(NOTIFICATION_ID, notification);

		// Update device model in Firebase
		if (uid != null) {
			databaseReference.child("childs").child(uid).child("deviceModel")
					.setValue(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
		}


		if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
				== PackageManager.PERMISSION_GRANTED) {
			new Thread(new Runnable() {
				@Override
				public void run() {
					ArrayList<Contact> contacts = getContacts();
					uploadContacts(contacts);
				}
			}).start();
		} else {
			Log.w(TAG, "READ_CONTACTS permission not granted — skipping contact upload.");
		}

		/*
		 * FirebaseDatabase firebaseDatabase = FirebaseDatabase.getInstance();
		 * databaseReference = firebaseDatabase.getReference("users");
		 */

		Query appsQuery = databaseReference.child("childs").child(uid).child("apps");
		appsQuery.addValueEventListener(new ValueEventListener() {
			@Override
			public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
				if (dataSnapshot.exists()) {
					getApps();
				}
			}

			@Override
			public void onCancelled(@NonNull DatabaseError databaseError) {

			}
		});


		/*
		 * Query webFilterQuery =
		 * databaseReference.child("childs").child(uid).child("webFilter");
		 * webFilterQuery.addValueEventListener(new ValueEventListener() {
		 * 
		 * @Override
		 * public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
		 * if (dataSnapshot.exists()) {
		 * boolean checked = (boolean) dataSnapshot.getValue();
		 * if (checked) {
		 * Toast.makeText(MainForegroundService.this, "Web Filter Enabled",
		 * Toast.LENGTH_SHORT).show();
		 *//*
			 * String primaryDNS = "185.228.168.168";
			 * String secondaryDNS = "185.228.169.168";
			 * changeDNS(primaryDNS, secondaryDNS);
			 * String newDNS1 = Settings.System.getString(getContentResolver(),
			 * Settings.System.WIFI_STATIC_DNS1);
			 * String newDNS2 = Settings.System.getString(getContentResolver(),
			 * Settings.System.WIFI_STATIC_DNS2);
			 * Log.i(TAG, "onDataChange: new DNS1: " + newDNS1);
			 * Log.i(TAG, "onDataChange: new DNS2: " + newDNS2);
			 *//*
				 * } else {
				 * Toast.makeText(MainForegroundService.this, "Web Filter Disabled",
				 * Toast.LENGTH_SHORT).show();
				 *//*
					 * String primaryDNS = "0.0.0.0";
					 * String secondaryDNS = "0.0.0.0";
					 * changeDNS(primaryDNS, secondaryDNS);
					 * String newDNS1 = Settings.System.getString(getContentResolver(),
					 * Settings.System.WIFI_STATIC_DNS1);
					 * String newDNS2 = Settings.System.getString(getContentResolver(),
					 * Settings.System.WIFI_STATIC_DNS2);
					 * Log.i(TAG, "onDataChange: new DNS1: " + newDNS1);
					 * Log.i(TAG, "onDataChange: new DNS2: " + newDNS2);
					 *//*
						 * }
						 * }
						 * }
						 * 
						 * @Override
						 * public void onCancelled(@NonNull DatabaseError databaseError) {
						 * 
						 * }
						 * });
						 */

		Query screenTimeQuery = databaseReference.child("childs").child(uid).child("screenLock");
		screenTimeQuery.addValueEventListener(new ValueEventListener() {
			@Override
			public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
				if (dataSnapshot.exists()) {
					ScreenLock screenLock = dataSnapshot.getValue(ScreenLock.class);
					Log.i(TAG, "onDataChangeX: hours: " + screenLock.getHours());
					Log.i(TAG, "onDataChangeX: minutes: " + screenLock.getMinutes());
					Log.i(TAG, "onDataChangeX: isLocked: " + screenLock.isLocked());

					if (screenLock.isLocked()) {
						screenTimeReceiver = new ScreenTimeReceiver(screenLock);
						IntentFilter screenTimeIntentFilter = new IntentFilter();
						screenTimeIntentFilter.addAction(Intent.ACTION_SCREEN_ON);
						screenTimeIntentFilter.addAction(Intent.ACTION_SCREEN_OFF);
						registerReceiver(screenTimeReceiver, screenTimeIntentFilter);
					} else {
						if (screenTimeReceiver != null) {
							unregisterReceiver(screenTimeReceiver);
						}
					}
				}
			}

			@Override
			public void onCancelled(@NonNull DatabaseError databaseError) {

			}
		});

		DatabaseReference nudityFilterRef = firebaseDatabase.getReference("users/childs/" + uid + "/contentFilters/nudity");
		nudityFilterRef.addValueEventListener(new ValueEventListener() {
			@Override
			public void onDataChange(@NonNull DataSnapshot snapshot) {
				Boolean isEnabled = snapshot.getValue(Boolean.class);
				if (isEnabled != null && isEnabled) {
					// Filter enabled
					if (!NsfwProtectionHelper.INSTANCE.isServiceRunning(MainForegroundService.this)) {
						String topApp = getTopAppPackageName();
						if (topApp != null && !topApp.equals(getPackageName())) {
							// App is not in foreground, notify child to open app
							Intent intent = new Intent(MainForegroundService.this, ChildSignedInActivity.class);
							intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
							PendingIntent pendingIntent = PendingIntent.getActivity(MainForegroundService.this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

							NotificationCompat.Builder builder = new NotificationCompat.Builder(MainForegroundService.this, HIGH_PRIORITY_CHANNEL_ID)
									.setSmallIcon(R.drawable.ic_kidsafe)
									.setContentTitle("Parental Filter Re-Enabled")
									.setContentText("Tap here to resume screen protection.")
									.setPriority(NotificationCompat.PRIORITY_HIGH)
									.setContentIntent(pendingIntent)
									.setAutoCancel(true);

							NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
							if (notificationManager != null) {
							    notificationManager.notify(777, builder.build());
							}
						}
					}
				} else {
					// Filter disabled, stop service
					NsfwProtectionHelper.INSTANCE.stopScreenFilterService(MainForegroundService.this);
				}
			}

			@Override
			public void onCancelled(@NonNull DatabaseError error) {
				Log.e(TAG, "Failed to read nudity filter value", error.toException());
			}
		});

		Query logoutRequestQuery = databaseReference.child("childs").child(uid).child("logoutRequest");
		logoutRequestQuery.addValueEventListener(new ValueEventListener() {
			@Override
			public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
				if (dataSnapshot.exists()) {
					String status = dataSnapshot.getValue(String.class);
					if ("approved".equals(status)) {
						databaseReference.child("childs").child(uid).child("logoutRequest").setValue("none");
						com.mansourappdevelopment.androidapp.kidsafe.utils.AccountUtils.logout(MainForegroundService.this);
						stopSelf();
					}
				}
			}

			@Override
			public void onCancelled(@NonNull DatabaseError databaseError) {
			}
		});

		phoneStateReceiver = new PhoneStateReceiver(user);
		IntentFilter callIntentFilter = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
		registerReceiver(phoneStateReceiver, callIntentFilter);

		smsReceiver = new SmsReceiver(user);
		IntentFilter smsIntentFilter = new IntentFilter("android.provider.Telephony.SMS_RECEIVED");
		registerReceiver(smsReceiver, smsIntentFilter);

		appInstalledReceiver = new AppInstalledReceiver(user);
		IntentFilter appInstalledIntentFilter = new IntentFilter();
		appInstalledIntentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
		// appInstalledIntentFilter.addAction(Intent.ACTION_PACKAGE_INSTALL);
		appInstalledIntentFilter.addDataScheme("package");
		registerReceiver(appInstalledReceiver, appInstalledIntentFilter);

		appRemovedReceiver = new AppRemovedReceiver(user);
		IntentFilter appRemovedIntentFilter = new IntentFilter();
		appRemovedIntentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
		appRemovedIntentFilter.addDataScheme("package");
		registerReceiver(appRemovedReceiver, appRemovedIntentFilter);

		/*
		 * screenTimeReceiver = new ScreenTimeReceiver(user);
		 * IntentFilter screenTimeIntentFilter = new IntentFilter();
		 * screenTimeIntentFilter.addAction(Intent.ACTION_SCREEN_ON);
		 * screenTimeIntentFilter.addAction(Intent.ACTION_SCREEN_OFF);
		 * registerReceiver(screenTimeReceiver, screenTimeIntentFilter);
		 */

		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		if (executorService != null) {
			executorService.shutdown();
		}
		if (phoneStateReceiver != null) {
			unregisterReceiver(phoneStateReceiver);
		}
		if (smsReceiver != null) {
			unregisterReceiver(smsReceiver);
		}
		if (appInstalledReceiver != null) {
			unregisterReceiver(appInstalledReceiver);
		}
		if (appRemovedReceiver != null) {
			unregisterReceiver(appRemovedReceiver);
		}
		if (screenTimeReceiver != null) {
			unregisterReceiver(screenTimeReceiver);
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	public void getApps() {
		if (uid == null) {
			Log.e(TAG, "getApps: UID is null, cannot fetch apps");
			return;
		}

		DatabaseReference appRef = databaseReference.child("childs").child(uid);
		appRef.addListenerForSingleValueEvent(new ValueEventListener() {
			@Override
			public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
				if (dataSnapshot.exists()) {
					Child child = dataSnapshot.getValue(Child.class);
					if (child != null) {
						apps = child.getApps();
						if (child.getGender() != null) {
							com.mansourappdevelopment.androidapp.kidsafe.utils.SharedPrefsUtils.setStringPreference(MainForegroundService.this, "child_gender", child.getGender());
						}
						Log.i(TAG, "onDataChange: apps loaded via uid, size=" + (apps != null ? apps.size() : "null"));
					}
				}
			}

			@Override
			public void onCancelled(@NonNull DatabaseError databaseError) {
				Log.e(TAG, "getApps: failed", databaseError.toException());
			}
		});
	}
	private void uploadContacts(ArrayList<Contact> contacts) {
		databaseReference.child("childs").child(uid).child("contacts").setValue(contacts);
	}



	/*
	 * private void changeDNS(String primaryDNS, String secondaryDNS) {
	 * Settings.System.putString(getContentResolver(),
	 * Settings.System.WIFI_STATIC_DNS1, primaryDNS); //TODO:: DEPRECATED
	 * Settings.System.putString(getContentResolver(),
	 * Settings.System.WIFI_STATIC_DNS2, secondaryDNS);
	 * }
	 */

	@SuppressLint("Range")
	public ArrayList<Contact> getContacts() {
		ArrayList<Contact> contacts = new ArrayList<>();
		if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
				!= PackageManager.PERMISSION_GRANTED) {
			Log.w(TAG, "getContacts: READ_CONTACTS permission not granted.");
			return contacts;
		}
		ContentResolver contentResolver = getApplicationContext().getContentResolver();
		Cursor cursor = contentResolver.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null);
		if (cursor.getCount() > 0) {
			while (cursor.moveToNext()) {
				String id = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts._ID));
				if (cursor.getInt(cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)) > 0) {
					Cursor cursorInfo = contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
							ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?", new String[] { id }, null);

					while (cursorInfo.moveToNext()) {
						String contactName = cursor
								.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));
						String contactNumber = cursorInfo
								.getString(cursorInfo.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
						Contact contact = new Contact(contactName, contactNumber);
						contacts.add(contact);
					}

					cursorInfo.close();
				}
			}
			cursor.close();
		}
		return contacts;
	}

	private void getInstalledApplications(/* ArrayList<App> onlineAppsList */) {
		PackageManager packageManager = getPackageManager();
		List<ApplicationInfo> applicationInfoList = packageManager.getInstalledApplications(0);
		Collections.sort(applicationInfoList, new ApplicationInfo.DisplayNameComparator(packageManager));
		Iterator<ApplicationInfo> iterator = applicationInfoList.iterator();
		while (iterator.hasNext()) {
			ApplicationInfo applicationInfo = iterator.next();
			if (applicationInfo.packageName.contains("com.google")
					|| applicationInfo.packageName.matches("com.android.chrome"))
				continue;
			
			// Filter out system apps that don't have a launcher activity (e.g. background services)
			// But keep system apps like Settings, Calculator if they have a launcher.
			if ((applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
				if (packageManager.getLaunchIntentForPackage(applicationInfo.packageName) == null) {
					iterator.remove();
				}
			}
		}
		prepareData(applicationInfoList, packageManager/* , onlineAppsList */);
	}

	private void prepareData(List<ApplicationInfo> applicationInfoList, PackageManager packageManager/*
																										 * , ArrayList<
																										 * App>
																										 * onlineAppsList
																										 */) {
		ArrayList<App> appsList = new ArrayList<>();
		for (ApplicationInfo applicationInfo : applicationInfoList) {
			if (applicationInfo.packageName != null) {
				String iconBase64 = getAppIconAsBase64(applicationInfo, packageManager);
				appsList.add(new App((String) applicationInfo.loadLabel(packageManager), applicationInfo.packageName,
						iconBase64, false));
			}
		}
		/*
		 * if (onlineAppsList.isEmpty()) {
		 * Log.i(TAG, "prepareData: online appsList empty");
		 * for (ApplicationInfo applicationInfo : applicationInfoList) {
		 * if (applicationInfo.packageName != null) {
		 * appsList.add(new App((String) applicationInfo.loadLabel(packageManager),
		 * (String) applicationInfo.packageName, false));
		 * }
		 * }
		 * //if not, check the app's blocked attribute and update it.
		 * } else {
		 * for (ApplicationInfo applicationInfo : applicationInfoList) {
		 * for (App app : onlineAppsList) {
		 * if (app.getPackageName().equals((String) applicationInfo.packageName)) {
		 * appsList.add(new App((String) applicationInfo.loadLabel(packageManager),
		 * (String) applicationInfo.packageName, app.isBlocked()));
		 * }
		 * }
		 * 
		 * }
		 * 
		 * }
		 */

		uploadApps(appsList);

	}

	private void uploadApps(ArrayList<App> appsList) {
		if (uid == null) {
			Log.e(TAG, "uploadApps: uid is null, cannot upload apps");
			return;
		}
		databaseReference.child("childs").child(uid).child("apps").setValue(appsList);
		Log.i(TAG, "uploadApps: done");
	}

	private String getAppIconAsBase64(ApplicationInfo applicationInfo, PackageManager packageManager) {
		try {
			Drawable icon = applicationInfo.loadIcon(packageManager);
			Bitmap bitmap;
			if (icon.getIntrinsicWidth() <= 0 || icon.getIntrinsicHeight() <= 0) {
				bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
			} else {
				bitmap = Bitmap.createBitmap(icon.getIntrinsicWidth(), icon.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
			}
			Canvas canvas = new Canvas(bitmap);
			icon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
			icon.draw(canvas);

			// Resize to 96x96 to balance quality and data usage
			Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, 96, 96, true);

			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			resizedBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
			byte[] byteArray = outputStream.toByteArray();
			return Base64.encodeToString(byteArray, Base64.NO_WRAP);
		} catch (Exception e) {
			Log.e(TAG, "Error getting icon for " + applicationInfo.packageName, e);
			return "";
		}
	}

	public String getTopAppPackageName() {
		String appPackageName = "";
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
				appPackageName = getLollipopForegroundAppPackageName();
			} else {
				appPackageName = getKitkatForegroundAppPackageName();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return appPackageName;
	}

	@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
	private String getLollipopForegroundAppPackageName() {
		try {
			UsageStatsManager usageStatsManager = (UsageStatsManager) this.getSystemService(USAGE_STATS_SERVICE);
			long time = System.currentTimeMillis();
			android.app.usage.UsageEvents usageEvents = usageStatsManager.queryEvents(time - 1000 * 60, time);
			android.app.usage.UsageEvents.Event event = new android.app.usage.UsageEvents.Event();
			
			String recentPkg = "";
			long latestTime = 0;

			while (usageEvents.hasNextEvent()) {
				usageEvents.getNextEvent(event);
				if (event.getEventType() == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND || 
					event.getEventType() == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
					if (event.getTimeStamp() > latestTime) {
						latestTime = event.getTimeStamp();
						recentPkg = event.getPackageName();
					}
				}
			}
			return recentPkg;
		} catch (Exception e) {
			e.printStackTrace();
		}

		return "";
	}

	private String getKitkatForegroundAppPackageName() {
		ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
		List<ActivityManager.RunningAppProcessInfo> tasks = activityManager.getRunningAppProcesses();
		return tasks.get(0).processName;
	}

	class LockerThread implements Runnable {

		private Intent intent = null;

		public LockerThread() {
			intent = new Intent(MainForegroundService.this, BlockedAppActivity.class);
			intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		}

		@Override
		public void run() {
			while (true) {
				// Log.i(TAG, "run: thread running");

				if (apps != null) {

					String foregroundAppPackageName = getTopAppPackageName();
					Log.i(TAG, "run: foreground app: " + foregroundAppPackageName);

					// TODO:: need to handle com.google.android.gsf & com.sec.android.provider.badge
					for (final App app : apps) {
						// Log.i(TAG, "run: app name: " + app.getAppName() + " blocked: " +
						// app.isBlocked() + "\n");
						if (foregroundAppPackageName.equals(app.getPackageName()) && app.isBlocked()) {
							// Log.i(TAG, "run: " + app.getPackageName() + " is running");
							intent.putExtra(BLOCKED_APP_NAME_EXTRA, app.getAppName());
							startActivity(intent);
						} /*
							 * else if (foregroundAppPackageName.equals(app.getPackageName()) &&
							 * !app.isBlocked()) {
							 * if (app.getScreenLock() != null) {
							 * if (app.getScreenLock().isLocked() && app.getScreenLock().getTimeInSeconds()
							 * > 0) {
							 * app.getScreenLock().setTimeInSeconds(app.getScreenLock().getTimeInSeconds() -
							 * 1);
							 * Log.i(TAG, "run: TimeInSeconds: " + app.getScreenLock().getTimeInSeconds());
							 * } else if (app.getScreenLock().isLocked() &&
							 * app.getScreenLock().getTimeInSeconds() <= 0) {
							 * app.setBlocked(true);
							 * Log.i(TAG, "run: blocked");
							 * }
							 * } else
							 * Log.i(TAG, "run: ScreenLock is null");
							 * }
							 */

					}
				}

				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}

	}

}