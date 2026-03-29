package com.mansourappdevelopment.androidapp.kidsafe;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import androidx.multidex.MultiDex;

public class NotificationChannelCreator extends Application {
	public static final String CHANNEL_ID = "com.mansourappdevelopment.androidapp.kidsafe.utils.CHANNEL_ID";
	public static final String HIGH_PRIORITY_CHANNEL_ID = "com.mansourappdevelopment.androidapp.kidsafe.utils.HIGH_PRIORITY_CHANNEL_ID";

	@Override
	protected void attachBaseContext(android.content.Context base) {
		super.attachBaseContext(base);
		MultiDex.install(this);
	}

	@Override
	public void onCreate() {
		super.onCreate();
		createNotificationChannel();
	}

	private void createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID, "Service Channel",
					NotificationManager.IMPORTANCE_LOW);

			NotificationChannel highPriorityChannel = new NotificationChannel(HIGH_PRIORITY_CHANNEL_ID, "Alerts Channel",
					NotificationManager.IMPORTANCE_HIGH);

			NotificationManager notificationManager = getSystemService(NotificationManager.class);
			notificationManager.createNotificationChannel(serviceChannel);
			notificationManager.createNotificationChannel(highPriorityChannel);
		}
	}
}
